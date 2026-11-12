package com.huskydg.magicv2ray;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * Host for the Magic V2Ray WebUI.
 *
 * On every launch: check root, make sure the module is installed and current in
 * /data/adb/modules/magic_v2ray, then show the bundled WebUI. The WebUI talks to
 * the module through {@link KsuBridge}.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.local";
    private static final String START_URL = "https://" + HOST + "/webroot/index.html";
    private static final int REQ_FILE = 1;
    private static final Pattern PKG_RE = Pattern.compile("^[A-Za-z0-9_.]+$");

    private final LruCache<String, byte[]> icons = new LruCache<>(400);

    private FrameLayout root;
    private View welcomeView;
    private View setupView;
    private ProgressBar progress;
    private TextView status;
    private TextView title;
    private View logo;
    private View errorIcon;
    private View actions;
    private Button retryBtn;
    private Button exitBtn;
    private Button nonRootBtn;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private int bg;                  // current system-bar / backdrop colour
    private boolean lightBars;       // dark bar icons (light theme)

    @Override
    protected void attachBaseContext(Context base) {
        // Apply the user's light / dark choice to the day-night resource qualifier.
        super.attachBaseContext(AppPrefs.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Until the WebUI reports its real surface colour, use the cached one (or the resource default).
        boolean light = !AppPrefs.isNight(this);
        bg = AppPrefs.hasChrome(this) ? AppPrefs.chromeBg(this, getColor(R.color.mv_bg)) : getColor(R.color.mv_bg);
        lightBars = AppPrefs.hasChrome(this) ? AppPrefs.chromeLight(this) : light;
        paintChrome();
        buildSetupViews();
        if (RunMode.get(this) == null) {
            showWelcome();          // first launch: let the user pick root / VPN
        } else {
            startSetup();
        }
    }

    // ------------------------------------------------------------------ appearance

    /** Called (on the UI thread) by the WebUI whenever its theme changes. */
    void applyChrome(int color, boolean light) {
        bg = color;
        lightBars = light;
        AppPrefs.saveChrome(this, color, light);
        paintChrome();
        if (root != null) root.setBackgroundColor(color);
        if (web != null) web.setBackgroundColor(color);
    }

    @SuppressWarnings("deprecation")
    private void paintChrome() {
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        int flags = 0;
        if (lightBars) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /** Native dialogs (JS alert / confirm / prompt) follow the app's current light / dark state. */
    private Context dialogContext() {
        return new ContextThemeWrapper(this, lightBars
                ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // System dark mode or dynamic colours changed: let the WebUI re-resolve its theme.
        if (web != null) web.evaluateJavascript("window.mvThemeSystemChanged && mvThemeSystemChanged()", null);
    }

    // ------------------------------------------------------------ setup screens

    private void buildSetupViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        LayoutInflater inflater = LayoutInflater.from(this);

        // Welcome page: two cards, root and VPN (layout after AdAway's welcome page).
        welcomeView = inflater.inflate(R.layout.view_welcome, root, false);
        fillFeatures(welcomeView.findViewById(R.id.features_root), inflater,
                new int[]{R.string.feat_faster, R.string.feat_battery, R.string.feat_needs_root},
                new boolean[]{true, true, false});
        fillFeatures(welcomeView.findViewById(R.id.features_vpn), inflater,
                new int[]{R.string.feat_slower, R.string.feat_background, R.string.feat_compatible},
                new boolean[]{false, false, true});
        welcomeView.findViewById(R.id.card_root).setOnClickListener(v -> {
            // The mode is stored once root is confirmed (see startSetup), so a failed
            // root check never leaves the app locked to a mode that cannot run.
            startSetup();
        });
        welcomeView.findViewById(R.id.card_vpn).setOnClickListener(v -> {
            RunMode.set(this, RunMode.NONROOT);
            startSetup();
        });
        welcomeView.setVisibility(View.GONE);
        root.addView(welcomeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Progress / error page.
        setupView = inflater.inflate(R.layout.view_setup, root, false);
        logo = setupView.findViewById(R.id.setup_logo);
        errorIcon = setupView.findViewById(R.id.setup_error_icon);
        title = setupView.findViewById(R.id.setup_title);
        progress = setupView.findViewById(R.id.setup_progress);
        status = setupView.findViewById(R.id.setup_status);
        actions = setupView.findViewById(R.id.setup_actions);
        retryBtn = setupView.findViewById(R.id.btn_retry);
        nonRootBtn = setupView.findViewById(R.id.btn_nonroot);
        exitBtn = setupView.findViewById(R.id.btn_exit);
        retryBtn.setOnClickListener(v -> startSetup());
        nonRootBtn.setOnClickListener(v -> {
            RunMode.set(this, RunMode.NONROOT);
            startSetup();
        });
        exitBtn.setOnClickListener(v -> finish());
        setupView.setVisibility(View.GONE);
        root.addView(setupView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /** Adds one "+ / -" row per entry to a mode card. */
    private static void fillFeatures(ViewGroup parent, LayoutInflater inflater, int[] texts, boolean[] positive) {
        for (int i = 0; i < texts.length; i++) {
            View row = inflater.inflate(R.layout.item_feature, parent, false);
            ((ImageView) row.findViewById(R.id.feature_icon))
                    .setImageResource(positive[i] ? R.drawable.ic_feat_plus : R.drawable.ic_feat_minus);
            ((TextView) row.findViewById(R.id.feature_text)).setText(texts[i]);
            parent.addView(row);
        }
    }

    private void showWelcome() {
        setupView.setVisibility(View.GONE);
        welcomeView.setVisibility(View.VISIBLE);
    }

    private void showBusy(int msgRes) {
        welcomeView.setVisibility(View.GONE);
        setupView.setVisibility(View.VISIBLE);
        logo.setVisibility(View.VISIBLE);
        errorIcon.setVisibility(View.GONE);
        title.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        actions.setVisibility(View.GONE);
        nonRootBtn.setVisibility(View.GONE);
        status.setText(msgRes);
    }

    private void showError(int titleRes, CharSequence msg) {
        welcomeView.setVisibility(View.GONE);
        setupView.setVisibility(View.VISIBLE);
        logo.setVisibility(View.GONE);
        errorIcon.setVisibility(View.VISIBLE);
        title.setText(titleRes);
        title.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        actions.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private void startSetup() {
        if (RunMode.isNonRoot(this)) {
            startNonRootSetup();
            return;
        }
        RootShell.setNonRoot(false);
        showBusy(R.string.setup_checking_root);
        new Thread(() -> {
            final ModuleInstaller.Outcome o = ModuleInstaller.ensureInstalled(
                    getApplicationContext(), res -> runOnUiThread(() -> status.setText(res)));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                switch (o.status) {
                    case OK:
                        if (RunMode.get(this) == null) RunMode.set(this, RunMode.ROOT);
                        OomGuard.start(getApplicationContext());
                        if (o.rebootRecommended) {
                            Toast.makeText(this, R.string.reboot_hint, Toast.LENGTH_LONG).show();
                        }
                        showWebUi();
                        break;
                    case NO_ROOT:
                        showError(R.string.err_no_root_title, getString(R.string.err_no_root_body));
                        nonRootBtn.setVisibility(View.VISIBLE);     // no root: offer the VpnService mode
                        break;
                    default:
                        showError(R.string.err_install_title, getString(R.string.err_install) + "\n\n" + o.detail);
                        break;
                }
            });
        }, "mv-setup").start();
    }

    /** Non-root mode: no su, no module; the engine is xray driven by MagicVpnService. */
    private void startNonRootSetup() {
        showBusy(R.string.setup_starting);
        RootShell.setNonRoot(true);
        RootShell.init(getApplicationContext());
        new Thread(() -> {
            String err = null;
            try {
                NonRootEnv.ensure(getApplicationContext());
            } catch (java.io.IOException e) {
                err = String.valueOf(e.getMessage());
            }
            final String e = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (e == null) showWebUi();
                else showError(R.string.err_install_title, getString(R.string.err_install) + "\n\n" + e);
            });
        }, "mv-setup-nonroot").start();
    }

    // ------------------------------------------------------------------ WebUI

    private void showWebUi() {
        if (web == null) {
            web = new WebView(this);
            web.setBackgroundColor(bg);
            configureWebView(web);
            root.addView(web, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.loadUrl(START_URL);
        }
        setupView.setVisibility(View.GONE);
        welcomeView.setVisibility(View.GONE);
        // One system dialog at a time: notifications first, battery optimisation after the answer.
        if (!ProxyNotifier.askPermissionOnce(this)) BatteryOpt.askOnce(this);
    }

    private void configureWebView(WebView w) {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Only bundled pages are ever loaded, and they hold a root shell bridge.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setTextZoom(100);

        w.addJavascriptInterface(new KsuBridge(this, w), "ksu");
        w.setWebViewClient(new HostClient());
        w.setWebChromeClient(new HostChromeClient());
    }

    private final class HostClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String scheme = u.getScheme();
            if ("ksu".equals(scheme) && "icon".equals(u.getHost())) {
                return iconResponse(u.getLastPathSegment());
            }
            if ("https".equals(scheme) && HOST.equals(u.getHost())) {
                return assetResponse(u.getPath());
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return blocked();   // the UI never needs the network; the shell does that
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if ("https".equals(u.getScheme()) && HOST.equals(u.getHost())) return false;
            if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
            }
            return true;
        }
    }

    private final class HostChromeClient extends WebChromeClient {
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(dialogContext())
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(dialogContext())
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String def, JsPromptResult result) {
            final EditText input = new EditText(dialogContext());
            if (def != null) input.setText(def);
            new AlertDialog.Builder(dialogContext())
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VpnController.REQ_VPN) {
            VpnController.onPermissionResult(resultCode == RESULT_OK);
            return;
        }
        if (requestCode == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // --------------------------------------------------------------- resources

    private WebResourceResponse assetResponse(String path) {
        if (path == null || path.contains("..") || path.length() < 2) return notFound();
        try {
            InputStream in = getAssets().open(path.substring(1));
            return new WebResourceResponse(mimeFor(path), "utf-8", in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private WebResourceResponse iconResponse(String pkg) {
        if (pkg == null || !PKG_RE.matcher(pkg).matches()) return notFound();
        byte[] png = icons.get(pkg);
        if (png == null) {
            try {
                PackageManager pm = getPackageManager();
                Drawable d = pm.getApplicationIcon(pkg);
                int size = 96;
                Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, size, size);
                d.draw(new Canvas(b));
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, bo);
                b.recycle();
                png = bo.toByteArray();
                icons.put(pkg, png);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                return notFound();
            }
        }
        return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null,
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeFor(String path) {
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        BatteryOpt.askOnce(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
