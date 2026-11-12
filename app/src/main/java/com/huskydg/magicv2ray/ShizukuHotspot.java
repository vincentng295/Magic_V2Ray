package com.huskydg.magicv2ray;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * "Share proxy via Shizuku" (non-root mode): lets hotspot / USB-tethering clients use the proxy
 * without root. App-side half; the privileged half is {@link HotspotService}.
 *
 * <pre>
 *   hotspot client ──► tethering stack ──► test-network TUN (shell-owned, HotspotService)
 *                                              │ fd, handed over by Binder
 *                                              ▼
 *                       bridge xray: `tun` inbound ──► SOCKS5 to 127.17.1.3:50808
 *                                              │
 *                                              ▼
 *                       main xray (MagicVpnService): socks-test-in ──► routing ──► proxy node
 * </pre>
 *
 * Order of events (all on {@link #EXEC}, one at a time):
 * <ol>
 *   <li>bind the Shizuku UserService, check the contract version and device compatibility;</li>
 *   <li>{@code prepare()}: the service creates the TUN, nothing is routed into it yet;</li>
 *   <li>start the bridge xray on that fd;</li>
 *   <li>{@code activate()}: tethering now prefers the TUN and the downstream is started.</li>
 * </ol>
 * Clients are therefore never pointed at a TUN nobody is reading. If anything fails, or the bridge,
 * the helper or the main engine dies, the whole thing is torn down: hotspot clients lose the
 * internet rather than quietly bypassing the proxy.
 *
 * Failures are reported as {@code "<code>|<detail>"}; the WebUI localises the code.
 */
final class ShizukuHotspot {

    interface Done {
        void done(int code, String out, String err);
    }

    private static final int REQ_PERMISSION = 4001;
    private static final String[] SHIZUKU_PACKAGES = {"moe.shizuku.privileged.api", "moe.shizuku.redirect"};
    private static final String SHIZUKU_RELEASES = "https://github.com/RikkaApps/Shizuku/releases";
    private static final long BIND_TIMEOUT_S = 35;
    private static final long POLL_MS = 3_000;
    private static final long LOG_MAX_BYTES = 512 * 1024;

    private static final String IDLE = "IDLE", STARTING = "STARTING", ACTIVE = "ACTIVE", ERROR = "ERROR";

    /** One start / stop at a time. */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mv-hotspot");
        t.setDaemon(true);
        return t;
    });
    private static final Object OP = new Object();

    private static volatile String state = IDLE;
    private static volatile String detail = "";
    private static volatile String errorCode = "";
    private static volatile JSONObject remote = new JSONObject();

    private static IHotspotService service;
    private static ServiceConnection connection;
    private static Shizuku.UserServiceArgs serviceArgs;
    /** The helper watches this Binder: when this process dies, the helper ends the session by itself. */
    private static final Binder LIFETIME = new Binder();

    private static volatile java.lang.Process bridge;
    private static volatile boolean stopping;
    private static volatile Thread poller;

    private ShizukuHotspot() {
    }

    // ------------------------------------------------------------------- state

    /** Everything the WebUI shows, in one JSON object. */
    static String info(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("supported", Build.VERSION.SDK_INT >= 30);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("shizuku", shizukuState(c));
            o.put("shizukuUid", shizukuUid());
            o.put("engineRunning", VpnController.isRunning());
            o.put("state", state);
            o.put("detail", detail);
            o.put("code", errorCode);
            JSONObject r = remote;
            for (String k : new String[]{"interface", "clientCount", "bytesUp", "bytesDown", "activeSince", "log"}) {
                if (r.has(k)) o.put(k, r.get(k));
            }
            o.put("busy", STARTING.equals(state));
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** not_installed | not_running | permission | ready */
    static String shizukuState(Context c) {
        try {
            if (!Shizuku.pingBinder()) return isShizukuInstalled(c) ? "not_running" : "not_installed";
            if (Shizuku.isPreV11()) return "not_running";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "permission";
            return "ready";
        } catch (Throwable t) {
            return isShizukuInstalled(c) ? "not_running" : "not_installed";
        }
    }

    private static int shizukuUid() {
        try {
            return Shizuku.pingBinder() ? Shizuku.getUid() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean isShizukuInstalled(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : SHIZUKU_PACKAGES) {
            try {
                pm.getPackageInfo(p, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    /** "requested" | "denied_forever" | "unavailable". */
    static String requestPermission() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return "unavailable";
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return "requested";
            if (Shizuku.shouldShowRequestPermissionRationale()) return "denied_forever";
            Shizuku.requestPermission(REQ_PERMISSION);
            return "requested";
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Opens the Shizuku app, or its download page when it is not installed. */
    static void openShizuku(Context c) {
        try {
            for (String p : SHIZUKU_PACKAGES) {
                Intent i = c.getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) {
                    c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASES))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- start / stop

    static void start(final Context ctx, final String optionsJson, final Done done) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> reply(done, doStart(app, optionsJson)));
    }

    static void stop(final Context ctx, final Done done) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            synchronized (OP) {
                teardown(app, IDLE, "stopped", "");
            }
            reply(done, new Object[]{0, "stopped\n", ""});
        });
    }

    /** MagicVpnService: the main engine is up. Starts sharing when the user asked for it to follow the proxy. */
    static void onEngineUp(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            JSONObject st = NonRootEnv.settings(app);
            if (!st.optBoolean("shizukuShareAuto", false) || ACTIVE.equals(state) || STARTING.equals(state)) return;
            if (!"ready".equals(shizukuState(app))) return;
            JSONObject o = new JSONObject();
            try {
                o.put("wifi", st.optBoolean("shizukuShareWifi", true));
                o.put("usb", st.optBoolean("shizukuShareUsb", false));
            } catch (JSONException ignored) {
            }
            doStart(app, o.toString());
        });
    }

    /** MagicVpnService: the main engine stopped, so the bridge would have nowhere to go. */
    static void onEngineDown(final Context ctx) {
        if (IDLE.equals(state) && service == null && bridge == null) return;
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            synchronized (OP) {
                teardown(app, IDLE, "stopped with the proxy", "");
            }
        });
    }

    private static void reply(Done done, Object[] r) {
        if (done != null) done.done((Integer) r[0], (String) r[1], (String) r[2]);
    }

    private static Object[] fail(String code, String why) {
        return new Object[]{1, "", code + "|" + (why == null ? "" : why)};
    }

    private static Object[] doStart(Context app, String optionsJson) {
        synchronized (OP) {
            if (ACTIVE.equals(state)) return new Object[]{0, "active\n", ""};
            state = STARTING;
            detail = "";
            errorCode = "";
            remote = new JSONObject();
            stopping = false;
            try {
                if (Build.VERSION.SDK_INT < 30) throw new Failure("sdk", "Android 11 or newer is required");
                if (!VpnController.isRunning()) throw new Failure("engine_off", "the proxy is not running");
                String sh = shizukuState(app);
                if (!"ready".equals(sh)) throw new Failure("shizuku_" + sh, "Shizuku is " + sh);

                NonRootEnv.ensure(app);
                IHotspotService svc = bind(app);
                int contract = svc.getContractVersion();
                if (contract != HotspotService.CONTRACT_VERSION) {
                    throw new Failure("stale_helper", "app contract " + HotspotService.CONTRACT_VERSION
                            + ", helper reports " + contract);
                }
                JSONObject compat = new JSONObject(svc.checkCompatibility());
                if (!compat.optBoolean("ok")) {
                    throw new Failure("compat_" + compat.optString("code"), compat.optString("reason"));
                }

                JSONObject st = new JSONObject(svc.prepare(optionsJson == null ? "{}" : optionsJson, LIFETIME));
                remote = st;
                if (!"PREPARED".equals(st.optString("state"))) {
                    throw new Failure("failed", st.optString("detail"));
                }

                ParcelFileDescriptor tun = svc.getTunFd();
                try {
                    startBridge(app, tun);
                } finally {
                    try {
                        tun.close();                // the bridge process inherited its own copy
                    } catch (IOException ignored) {
                    }
                }

                st = new JSONObject(svc.activate());
                remote = st;
                if (!"ACTIVE".equals(st.optString("state"))) {
                    throw new Failure("failed", st.optString("detail"));
                }

                state = ACTIVE;
                detail = st.optString("detail");
                startPoller(app);
                return new Object[]{0, "started\n", ""};
            } catch (Failure f) {
                return finishFailed(app, f.code, f.getMessage());
            } catch (Throwable t) {
                return finishFailed(app, "failed", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private static Object[] finishFailed(Context app, String code, String why) {
        String text = why;
        try {
            IHotspotService svc = service;
            if (svc != null) remote = new JSONObject(svc.getStatus());
        } catch (Throwable ignored) {
        }
        JSONObject keep = remote;
        teardown(app, ERROR, text, code);
        remote = keep;                      // the log lines stay visible after the failure
        return fail(code, text);
    }

    private static final class Failure extends Exception {
        final String code;

        Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Undoes everything, in the order that never leaves clients on a dead upstream. Holds {@link #OP}. */
    private static void teardown(Context app, String newState, String why, String code) {
        stopping = true;
        stopPoller();
        IHotspotService svc = service;
        if (svc != null) {
            try {
                svc.stop();
            } catch (Throwable ignored) {
            }
        }
        killBridge();
        unbind();
        state = newState;
        detail = why;
        errorCode = code;
        if (IDLE.equals(newState)) remote = new JSONObject();
        stopping = false;
    }

    // ------------------------------------------------------------ Shizuku binding

    private static IHotspotService bind(Context app) throws Exception {
        IHotspotService existing = service;
        if (existing != null && existing.asBinder().pingBinder()) return existing;
        unbind();

        final CountDownLatch latch = new CountDownLatch(1);
        final IHotspotService[] box = new IHotspotService[1];
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                box[0] = binder == null ? null : IHotspotService.Stub.asInterface(binder);
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                onHelperLost();
            }
        };
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(app.getPackageName(), HotspotService.class.getName()))
                // non-daemon: the helper dies with this process; destroy() then drops the hotspot
                .daemon(false)
                .tag("magic-hotspot")
                .processNameSuffix("hotspot")
                .debuggable(false)
                .version(appVersionCode(app));
        connection = conn;
        serviceArgs = args;
        Shizuku.bindUserService(args, conn);
        if (!latch.await(BIND_TIMEOUT_S, TimeUnit.SECONDS) || box[0] == null) {
            unbind();
            throw new Failure("bind_failed", "Shizuku did not start the helper process within " + BIND_TIMEOUT_S
                    + " s (adb logcat -s ShizukuServiceStarter shows why)");
        }
        service = box[0];
        return box[0];
    }

    @SuppressWarnings("deprecation")
    private static int appVersionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }

    private static void unbind() {
        service = null;
        ServiceConnection conn = connection;
        Shizuku.UserServiceArgs args = serviceArgs;
        connection = null;
        serviceArgs = null;
        if (conn == null || args == null) return;
        try {
            Shizuku.unbindUserService(args, conn, true);        // true: ask the helper to destroy() itself
        } catch (Throwable ignored) {
        }
    }

    /** The helper process went away (Shizuku stopped, helper killed). */
    private static void onHelperLost() {
        service = null;
        if (stopping) return;
        if (!ACTIVE.equals(state) && !STARTING.equals(state)) return;
        EXEC.execute(() -> {
            synchronized (OP) {
                if (!ACTIVE.equals(state)) return;
                teardown(null, ERROR, "the Shizuku helper process went away", "helper_lost");
            }
        });
    }

    // ------------------------------------------------------------------- poller

    private static void startPoller(final Context app) {
        stopPoller();
        Thread t = new Thread(() -> pollLoop(app), "mv-hotspot-poll");
        t.setDaemon(true);
        poller = t;
        t.start();
    }

    private static void stopPoller() {
        Thread t = poller;
        poller = null;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    private static void pollLoop(final Context app) {
        while (poller == Thread.currentThread()) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (poller != Thread.currentThread()) return;
            IHotspotService svc = service;
            String problem = null;
            if (svc == null) {
                problem = "the Shizuku helper process went away";
            } else if (!VpnController.isRunning()) {
                problem = "the proxy stopped";
            } else {
                try {
                    JSONObject st = new JSONObject(svc.getStatus());
                    remote = st;
                    String s = st.optString("state");
                    if (!"ACTIVE".equals(s)) problem = st.optString("detail", "the session ended (" + s + ")");
                } catch (Throwable t) {
                    problem = "lost contact with the Shizuku helper: " + t.getClass().getSimpleName();
                }
            }
            if (problem != null) {
                final String why = problem;
                EXEC.execute(() -> endedUnexpectedly(app, why));
                return;
            }
        }
    }

    private static void endedUnexpectedly(Context app, String why) {
        synchronized (OP) {
            if (!ACTIVE.equals(state)) return;
            JSONObject keep = remote;
            boolean engineGone = "the proxy stopped".equals(why);
            teardown(app, engineGone ? IDLE : ERROR, why, engineGone ? "" : "ended");
            remote = keep;
        }
    }

    // ------------------------------------------------------------------- bridge

    /**
     * Starts the bridge xray on the TUN fd. Same hand-over as MagicVpnService.startXray: a
     * child process cannot inherit an fd through ProcessBuilder, so `xhuskydg_helper vpnrun`
     * receives it over an abstract unix socket (SCM_RIGHTS), exports it as XRAY_TUN_FD and execs xray.
     */
    private static void startBridge(Context app, ParcelFileDescriptor tun) throws IOException {
        boolean v6 = NonRootEnv.settings(app).optBoolean("enableIPv6", false);
        File cfg = NonRootEnv.hotspotConfig(app, !v6);
        final String sock = "mv-hs-" + android.os.Process.myPid() + "-" + System.nanoTime();
        final LocalServerSocket server = new LocalServerSocket(sock);
        final java.lang.Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    NonRootEnv.bin(app, "xhuskydg_helper").getPath(), "vpnrun", sock,
                    NonRootEnv.bin(app, "xray").getPath(), "run", "-c", cfg.getPath());
            pb.environment().put("XRAY_LOCATION_ASSET", new File(NonRootEnv.moddir(app), "bin").getPath());
            pb.directory(NonRootEnv.root(app));
            pb.redirectErrorStream(true);
            p = pb.start();
            p.getOutputStream().close();
        } catch (IOException e) {
            server.close();
            throw e;
        }

        final CountDownLatch sent = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean();
        final FileDescriptor fd = tun.getFileDescriptor();
        new Thread(() -> {
            try (LocalSocket s = server.accept()) {
                s.setFileDescriptorsForSend(new FileDescriptor[]{fd});
                OutputStream o = s.getOutputStream();
                o.write(0);
                o.flush();
                ok.set(true);
            } catch (IOException ignored) {
            } finally {
                sent.countDown();
            }
        }, "mv-hotspot-fd").start();
        boolean done;
        try {
            done = sent.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            done = false;
            Thread.currentThread().interrupt();
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        if (!done || !ok.get()) {
            p.destroyForcibly();
            throw new IOException("could not hand the TUN fd to the bridge xray");
        }

        pump(app, p);
        bridge = p;
        new Thread(() -> {
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            if (bridge == p && !stopping) {
                final int code = p.exitValue();
                EXEC.execute(() -> endedUnexpectedly(null, "the bridge xray exited (code " + code + "), see hotspot.log"));
            }
        }, "mv-hotspot-watch").start();

        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (!p.isAlive()) {
            bridge = null;
            throw new IOException("the bridge xray exited right after start (code " + p.exitValue() + "), see hotspot.log");
        }
    }

    /** Bridge output to stubs/logs/hotspot.log, truncated per session and capped (it only logs warnings). */
    private static void pump(final Context app, final java.lang.Process p) {
        new Thread(() -> {
            File f = NonRootEnv.hotspotLogFile(app);
            try (InputStream in = p.getInputStream(); FileOutputStream out = new FileOutputStream(f, false)) {
                byte[] b = new byte[4096];
                int n;
                long size = 0;
                while ((n = in.read(b)) > 0) {
                    if (size < LOG_MAX_BYTES) {
                        out.write(b, 0, n);
                        size += n;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "mv-hotspot-log").start();
    }

    private static void killBridge() {
        java.lang.Process p = bridge;
        bridge = null;
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** Last lines of the bridge log, for the diagnostics the UI can show. */
    static JSONArray tailLog(Context app, int lines) {
        JSONArray out = new JSONArray();
        File f = NonRootEnv.hotspotLogFile(app);
        if (!f.isFile()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>();
            String line;
            while ((line = r.readLine()) != null) {
                ring.addLast(line);
                if (ring.size() > lines) ring.removeFirst();
            }
            for (String l : ring) out.put(l);
        } catch (IOException ignored) {
        }
        return out;
    }
}
