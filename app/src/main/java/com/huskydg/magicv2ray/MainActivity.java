package com.huskydg.magicv2ray;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
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
import android.widget.LinearLayout;
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
    private static final int BG = Color.parseColor("#141218");
    private static final int FG = Color.parseColor("#E6E0E9");
    private static final int REQ_FILE = 1;
    private static final Pattern PKG_RE = Pattern.compile("^[A-Za-z0-9_.]+$");

    private final LruCache<String, byte[]> icons = new LruCache<>(400);

    private FrameLayout root;
    private LinearLayout setupView;
    private ProgressBar progress;
    private TextView status;
    private Button retryBtn;
    private Button exitBtn;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildSetupView();
        startSetup();
    }

    // ------------------------------------------------------------ setup screen

    private void buildSetupView() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        setupView = new LinearLayout(this);
        setupView.setOrientation(LinearLayout.VERTICAL);
        setupView.setGravity(Gravity.CENTER);
        int pad = dp(32);
        setupView.setPadding(pad, pad, pad, pad);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        setupView.addView(progress, new LinearLayout.LayoutParams(dp(48), dp(48)));

        status = new TextView(this);
        status.setTextColor(FG);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(24);
        setupView.addView(status, tp);

        retryBtn = new Button(this);
        retryBtn.setText(R.string.retry);
        retryBtn.setVisibility(View.GONE);
        retryBtn.setOnClickListener(v -> startSetup());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(24);
        setupView.addView(retryBtn, bp);

        exitBtn = new Button(this);
        exitBtn.setText(R.string.exit);
        exitBtn.setVisibility(View.GONE);
        exitBtn.setOnClickListener(v -> finish());
        setupView.addView(exitBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(setupView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void showBusy(int msgRes) {
        progress.setVisibility(View.VISIBLE);
        retryBtn.setVisibility(View.GONE);
        exitBtn.setVisibility(View.GONE);
        status.setText(msgRes);
    }

    private void showError(CharSequence msg) {
        progress.setVisibility(View.GONE);
        retryBtn.setVisibility(View.VISIBLE);
        exitBtn.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private void startSetup() {
        showBusy(R.string.setup_checking_root);
        new Thread(() -> {
            final ModuleInstaller.Outcome o = ModuleInstaller.ensureInstalled(
                    getApplicationContext(), res -> runOnUiThread(() -> status.setText(res)));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                switch (o.status) {
                    case OK:
                        if (o.rebootRecommended) {
                            Toast.makeText(this, R.string.reboot_hint, Toast.LENGTH_LONG).show();
                        }
                        showWebUi();
                        break;
                    case NO_ROOT:
                        showError(getString(R.string.err_no_root));
                        break;
                    default:
                        showError(getString(R.string.err_install) + "\n\n" + o.detail);
                        break;
                }
            });
        }, "mv-setup").start();
    }

    // ------------------------------------------------------------------ WebUI

    private void showWebUi() {
        if (web == null) {
            web = new WebView(this);
            web.setBackgroundColor(BG);
            configureWebView(web);
            root.addView(web, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.loadUrl(START_URL);
        }
        setupView.setVisibility(View.GONE);
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
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String def, JsPromptResult result) {
            final EditText input = new EditText(MainActivity.this);
            if (def != null) input.setText(def);
            new AlertDialog.Builder(MainActivity.this)
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
