package com.huskydg.magicv2ray;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Runs an approved external request (magicv2ray://connect, the CONNECT intent, ...) when Magic V2Ray has no
 * screen and no background activity, without ever showing {@link MainActivity}.
 *
 * <p>The config of a session is built by the WebUI (main.js / external.js), so the WebUI has to run. Until now
 * that meant starting {@link MainActivity}, letting it load and then moving it to the back (the "open the app
 * once and it closes again" flash). Here the very same WebUI pages are loaded in a WebView that is never
 * attached to a window, hosted by {@link ExternalHeadlessService} (a short-lived foreground service, so the
 * process is not killed halfway). The page gets the same {@code ksu} bridge, only without an Activity: whatever
 * needs a screen is a no-op there (see KsuBridge).
 *
 * <p>Life cycle: {@link #submit} (request already parked in {@link ExternalSession}) -> service starts ->
 * setup (root module / non-root env) off the main thread -> WebView loads the WebUI, which takes the parked
 * request at start-up -> the WebUI calls {@code ksu.headlessIdle()} once nothing is left -> {@link #onIdle}
 * -> everything is torn down after a short grace period.
 *
 * <p>If it cannot work (no WebView, setup failed, the VPN permission was never granted, ...) the request is
 * parked again, the normal "connection request" notification is posted, and the next approval goes through
 * {@link MainActivity} as before ({@link #failed}, cleared when MainActivity opens).
 */
final class ExternalHeadless {

    private static final String HOST = "appassets.local";
    private static final String START_URL = "https://" + HOST + "/webroot/index.html";
    private static final long IDLE_GRACE_MS = 1500;         // another request may follow right away
    private static final long WATCHDOG_MS = 150_000;        // never stay alive for ever

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- main thread only
    private static WebView web;
    private static ExternalHeadlessService service;
    private static boolean starting;        // the service was asked to start / is setting up
    private static boolean ready;           // the WebUI finished its start-up and reported idle at least once
    private static boolean busy;            // a request is parked or being handled
    private static Runnable shutdownTask;
    private static Runnable watchdogTask;

    /** The request that started this run, to park it again when the headless run cannot work. */
    private static volatile JSONObject lastReq;

    /** After a failure the next request goes through MainActivity (it can show errors / ask for the VPN permission). */
    static volatile boolean failed;

    private ExternalHeadless() {
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Whether an approved request may be run without the screen. Cheap, no root access: called from the dialog.
     * False for a first launch (nothing set up yet) and, in VPN mode, while the system VPN permission has not
     * been granted (only MainActivity can ask for it).
     */
    static boolean usable(Context c) {
        if (failed) return false;
        if (RunMode.get(c) == null) return false;
        if (RunMode.isNonRoot(c)) {
            try {
                if (VpnService.prepare(c) != null) return false;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    /** The request is already parked ({@link ExternalSession#queue}); make sure something is going to run it. */
    static void submit(final Context app, final JSONObject req) {
        if (Looper.myLooper() == Looper.getMainLooper()) submitMain(app, req);
        else MAIN.post(() -> submitMain(app, req));
    }

    private static void submitMain(Context app, JSONObject req) {
        lastReq = req;
        busy = true;
        cancel(shutdownTask);
        shutdownTask = null;
        if (web != null) {
            // running already: take it now if the WebUI is ready, otherwise its start-up takes it
            if (ready) pokeWeb();
            return;
        }
        if (starting) return;
        starting = true;
        try {
            app.startForegroundService(new Intent(app, ExternalHeadlessService.class));
        } catch (RuntimeException e) {
            starting = false;
            giveUp(app, e.getMessage());
        }
    }

    /** MainActivity is opening: the hidden WebUI must not run next to the real one, unless it is mid-request. */
    static void releaseForUi() {
        failed = false;
        MAIN.post(() -> {
            if (web != null && ready && !busy) shutdown();
        });
    }

    // ------------------------------------------------------------------ service side

    /** Called by the service (main thread) once it is in the foreground. */
    static void attach(final ExternalHeadlessService svc) {
        service = svc;
        starting = true;
        cancel(watchdogTask);
        watchdogTask = () -> {
            final Context app = svc.getApplicationContext();
            if (!ready) giveUp(app, "timeout");
            else shutdown();
        };
        MAIN.postDelayed(watchdogTask, WATCHDOG_MS);

        final Context app = svc.getApplicationContext();
        new Thread(() -> {
            final String err = setup(app);
            MAIN.post(() -> {
                if (service != svc) return;             // shut down meanwhile
                if (err != null) giveUp(app, err);
                else openWeb(app);
            });
        }, "mv-ext-headless").start();
    }

    static void onServiceDestroyed(ExternalHeadlessService svc) {
        if (service == svc) {
            service = null;
            shutdown();
        }
    }

    /** Same preparation MainActivity.startSetup() does before it shows the WebUI. @return null when ok, else why not. */
    private static String setup(Context app) {
        try {
            if (RunMode.isNonRoot(app)) {
                RootShell.setNonRoot(true);
                RootShell.init(app);
                NonRootEnv.ensure(app);
            } else {
                RootShell.setNonRoot(false);
                ModuleInstaller.Outcome o = ModuleInstaller.ensureInstalled(app, res -> { });
                if (o.status == ModuleInstaller.Status.NO_ROOT) return app.getString(R.string.err_no_root_title);
                if (o.status != ModuleInstaller.Status.OK) return app.getString(R.string.err_install_title);
                OomGuard.start(app);
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the hidden WebUI

    private static void openWeb(final Context app) {
        if (web != null) return;
        try {
            final WebView w = new WebView(app);
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            // only the bundled pages are ever loaded, and they hold a root shell bridge
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            s.setSupportMultipleWindows(false);
            s.setGeolocationEnabled(false);
            w.addJavascriptInterface(new KsuBridge(app, null, w), "ksu");
            w.setWebViewClient(new HeadlessClient(app));
            w.onResume();               // a detached WebView counts as hidden: keep its timers going
            w.resumeTimers();
            web = w;
            w.loadUrl(START_URL);
        } catch (RuntimeException e) {      // no WebView provider, ...
            giveUp(app, String.valueOf(e.getMessage()));
        }
    }

    /** From the WebUI (via KsuBridge.headlessIdle, on a bridge thread): no request left, or it just started up. */
    static void onIdle() {
        MAIN.post(() -> {
            if (web == null) return;
            ready = true;
            starting = false;
            // a request that arrived while the WebUI was still starting up has not been taken: take it now
            if (ExternalSession.hasPending()) {
                pokeWeb();
                return;
            }
            busy = false;
            cancel(shutdownTask);
            shutdownTask = ExternalHeadless::shutdown;
            MAIN.postDelayed(shutdownTask, IDLE_GRACE_MS);
        });
    }

    private static void pokeWeb() {
        final WebView w = web;
        if (w != null) w.evaluateJavascript("window.checkExternalRequest&&window.checkExternalRequest()", null);
    }

    /** Tears everything down. Main thread. Safe to call twice. */
    private static void shutdown() {
        cancel(shutdownTask);
        cancel(watchdogTask);
        shutdownTask = null;
        watchdogTask = null;
        ready = false;
        busy = false;
        starting = false;
        lastReq = null;
        final WebView w = web;
        web = null;
        if (w != null) {
            try {
                w.stopLoading();
                w.removeJavascriptInterface("ksu");
                w.destroy();
            } catch (RuntimeException ignored) {
            }
        }
        final ExternalHeadlessService s = service;
        service = null;
        if (s != null) {
            s.stopForeground(true);
            s.stopSelf();
        }
    }

    /**
     * The headless run cannot work. The request is parked again and the usual "connection request" notification
     * is posted (tapping it opens the dialog, which now goes through MainActivity).
     */
    private static void giveUp(Context app, String why) {
        final JSONObject req = lastReq;
        final boolean wasReady = ready;
        failed = true;
        shutdown();
        if (wasReady || req == null) return;       // the WebUI already took the request: nothing to park
        try {
            ExternalSession.queue(app, req);
            ExternalSession.notifyPending(app, req);
        } catch (RuntimeException ignored) {
        }
        final String msg = why == null || why.isEmpty() ? "" : ": " + why;
        Toast.makeText(app, app.getString(R.string.err_install_title) + msg, Toast.LENGTH_LONG).show();
    }

    private static void cancel(Runnable r) {
        if (r != null) MAIN.removeCallbacks(r);
    }

    // ------------------------------------------------------------------ assets (same rules as MainActivity.HostClient)

    private static final class HeadlessClient extends WebViewClient {
        private final Context app;

        HeadlessClient(Context app) {
            this.app = app;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String scheme = u.getScheme();
            if ("https".equals(scheme) && HOST.equals(u.getHost())) return asset(u.getPath());
            if ("http".equals(scheme) || "https".equals(scheme)) return reply(403, "Blocked", null);
            if ("ksu".equals(scheme)) return reply(404, "Not Found", null);     // app icons: nobody looks at them here
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            return !("https".equals(u.getScheme()) && HOST.equals(u.getHost()));    // never leave the bundled pages
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                giveUp(app, error == null ? "" : String.valueOf(error.getDescription()));
            }
        }

        private WebResourceResponse asset(String path) {
            if (path == null || path.contains("..") || path.length() < 2) return reply(404, "Not Found", null);
            try {
                InputStream in = app.getAssets().open(path.substring(1));
                return new WebResourceResponse(mime(path), "utf-8", in);
            } catch (IOException e) {
                return reply(404, "Not Found", null);
            }
        }

        private static WebResourceResponse reply(int code, String text, InputStream body) {
            return new WebResourceResponse("text/plain", "utf-8", code, text, null,
                    body == null ? new ByteArrayInputStream(new byte[0]) : body);
        }

        private static String mime(String path) {
            String p = path.toLowerCase();
            if (p.endsWith(".html")) return "text/html";
            if (p.endsWith(".js")) return "text/javascript";
            if (p.endsWith(".css")) return "text/css";
            if (p.endsWith(".json")) return "application/json";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".png")) return "image/png";
            if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
            if (p.endsWith(".woff2")) return "font/woff2";
            if (p.endsWith(".woff")) return "font/woff";
            return "application/octet-stream";
        }
    }
}
