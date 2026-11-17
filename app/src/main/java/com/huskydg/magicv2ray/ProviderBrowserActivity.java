package com.huskydg.magicv2ray;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Mini browser for a "provider website" category.
 *
 * Deliberately separate from the WebUI's WebView: the page here is somebody else's website, so it gets
 * no {@code ksu} bridge and no file access. The only thing it can do to the app is the sync link
 * ({@code v2rayng://install-config?url=...}): that navigation is caught here, the subscription URL is
 * handed to {@link MainActivity#pendingProviderSync}, and this activity closes so the WebUI (which sits
 * right below in the task) imports the nodes into the category.
 */
public class ProviderBrowserActivity extends Activity {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_CATEGORY = "category";

    /** Nodes already imported into the category, shown by the three-dot menu. Set by {@link #start}. */
    private static volatile String nodesJson = "[]";
    private static volatile String nodesCategory;

    /** The engine's SOCKS inbound (socks-test-in). Non-root mode moves it by NONROOT_PORT_OFFSET (see NonRootEnv). */
    private static final String PROXY_HOST = "127.17.1.3";
    private static final int PROXY_PORT = 808;
    private static final int NONROOT_PORT_OFFSET = 50000;

    private WebView web;
    private boolean proxyApplied;
    private ProgressBar bar;
    private TextView titleView;
    private TextView hostView;
    private String category;
    private String startUrl;
    private boolean light;

    static void start(Activity from, String url, String category, String nodes) {
        nodesJson = nodes == null ? "[]" : nodes;
        nodesCategory = category;
        // Kept on disk too, so a home-screen shortcut (no WebUI around) can still show the list.
        from.getSharedPreferences("provider_nodes", MODE_PRIVATE).edit().putString(category, nodesJson).apply();
        Intent i = new Intent(from, ProviderBrowserActivity.class)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_CATEGORY, category);
        from.startActivity(i);
    }

    /** Saves the node list to disk (and the in-memory copy) without opening anything. */
    static void storeNodes(Context c, String category, String nodes) {
        c.getSharedPreferences("provider_nodes", MODE_PRIVATE).edit().putString(category, nodes).apply();
        if (category.equals(nodesCategory)) nodesJson = nodes;
    }

    /** Pins a launcher shortcut that opens this category's website directly. */
    static boolean pinShortcut(Activity a, String url, String category, String iconDataUri) {
        ShortcutManager sm = a.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
        Intent i = new Intent(a, ProviderBrowserActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_CATEGORY, category);
        ShortcutInfo info = new ShortcutInfo.Builder(a, "provider:" + category)
                .setShortLabel(category.length() > 12 ? category.substring(0, 12) : category)
                .setLongLabel(category)
                .setIcon(siteIcon(a, iconDataUri))
                .setIntent(i)
                .build();
        return sm.requestPinShortcut(info, null);
    }

    /** The site's favicon (a stored data: URI) as the shortcut icon; the app icon if there is none. */
    private static Icon siteIcon(Activity a, String dataUri) {
        try {
            int c = dataUri == null ? -1 : dataUri.indexOf(";base64,");
            if (c > 0) {
                byte[] raw = android.util.Base64.decode(dataUri.substring(c + 8), android.util.Base64.DEFAULT);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (bmp != null) {
                    // Centre the favicon on a white tile so small icons do not look cropped or blurry.
                    int size = 192;
                    android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(out);
                    cv.drawColor(0xFFFFFFFF);
                    int box = 144;
                    float sc = Math.min((float) box / bmp.getWidth(), (float) box / bmp.getHeight());
                    int w = Math.round(bmp.getWidth() * sc), h = Math.round(bmp.getHeight() * sc);
                    android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                    cv.drawBitmap(bmp, null, new android.graphics.Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), paint);
                    return Icon.createWithAdaptiveBitmap(out);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return Icon.createWithResource(a, R.mipmap.ic_launcher);
    }

    private String currentNodes() {
        SharedPreferences sp = getSharedPreferences("provider_nodes", MODE_PRIVATE);
        return sp.getString(category, "[]");
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppPrefs.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startUrl = getIntent().getStringExtra(EXTRA_URL);
        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        if (startUrl == null || !startUrl.matches("(?i)^https://\\S+$") || category == null) {
            finish();
            return;
        }
        light = AppPrefs.hasChrome(this) ? AppPrefs.chromeLight(this) : !AppPrefs.isNight(this);
        int bg = AppPrefs.hasChrome(this) ? AppPrefs.chromeBg(this, getColor(R.color.mv_bg)) : getColor(R.color.mv_bg);
        int fg = light ? 0xFF1B1B1F : 0xFFE6E1E5;
        int sub = light ? 0xFF5F5F66 : 0xFFA9A4AB;

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        //noinspection deprecation
        getWindow().getDecorView().setSystemUiVisibility(light
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        // Toolbar: back, title / host, three dots.
        LinearLayout bar0 = new LinearLayout(this);
        bar0.setOrientation(LinearLayout.HORIZONTAL);
        bar0.setGravity(Gravity.CENTER_VERTICAL);
        bar0.addView(iconButton("←", fg, v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextColor(fg);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setText(category);
        hostView = new TextView(this);
        hostView.setTextColor(sub);
        hostView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hostView.setSingleLine(true);
        hostView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hostView.setText(Uri.parse(startUrl).getHost());
        titles.addView(titleView);
        titles.addView(hostView);
        bar0.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = iconButton("⋮", fg, this::showMenu);
        more.setContentDescription(getString(R.string.provider_menu));
        bar0.addView(more, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(bar0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.INVISIBLE);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        configure(web);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        startLoading(b);
    }

    /**
     * While the core runs, the page is loaded through its SOCKS5 inbound (socks5h: the proxy resolves the
     * names). The app itself can be kept out of the tunnel (Exclude apps), so this is what lets the site
     * open through the proxy anyway; without a running core the page loads directly, as before.
     */
    private void startLoading(Bundle saved) {
        final int port = PROXY_PORT + (RunMode.isNonRoot(this) ? NONROOT_PORT_OFFSET : 0);
        new Thread(() -> {
            boolean up = proxyReachable(port);
            runOnUiThread(() -> {
                if (isDestroyed() || web == null) return;
                Runnable go = () -> {
                    if (web == null) return;
                    if (saved != null) web.restoreState(saved); else web.loadUrl(startUrl);
                };
                if (up && applyProxy(port, go)) return;
                go.run();
            });
        }, "provider-proxy-probe").start();
    }

    private static boolean proxyReachable(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(PROXY_HOST, port), 500);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** @return true when the override was requested; {@code then} runs once it is in effect. */
    private boolean applyProxy(int port, Runnable then) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false;
            ProxyConfig cfg = new ProxyConfig.Builder().addProxyRule("socks5://" + PROXY_HOST + ":" + port).build();
            proxyApplied = true;
            ProxyController.getInstance().setProxyOverride(cfg, Runnable::run, then);
            return true;
        } catch (RuntimeException e) {          // old WebView / bad rule: just load directly
            proxyApplied = false;
            return false;
        }
    }

    private void clearProxy() {
        if (!proxyApplied) return;
        proxyApplied = false;
        try {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> { });
        } catch (RuntimeException ignored) {
        }
    }

    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);          // seller sites are SPAs; there is no bridge to abuse
        s.setDomStorageEnabled(true);          // keeps the seller login between visits
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(light ? 0xFFFFFFFF : 0xFF000000);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl(), r.hasGesture());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(5);
                hostView.setText(Uri.parse(url).getHost());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.INVISIBLE);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                if (p >= 100) bar.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onReceivedIcon(WebView v, Bitmap icon) {
                // The favicon the real page ended up with (covers SPAs, SVG icons, sites that block curl).
                if (icon == null || !sameSite(Uri.parse(String.valueOf(v.getUrl())).getHost())) return;
                try {
                    int max = 128;
                    float sc = Math.min(1f, (float) max / Math.max(icon.getWidth(), icon.getHeight()));
                    Bitmap sm = sc < 1f ? Bitmap.createScaledBitmap(icon,
                            Math.max(1, Math.round(icon.getWidth() * sc)), Math.max(1, Math.round(icon.getHeight() * sc)), true) : icon;
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    sm.compress(Bitmap.CompressFormat.PNG, 100, bo);
                    MainActivity.pendingProviderInfo = new JSONObject().put("category", category)
                            .put("icon", "data:image/png;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP))
                            .toString();
                } catch (JSONException | RuntimeException ignored) {
                }
            }

            @Override
            public void onReceivedTitle(WebView v, String t) {
                if (t != null && !t.isEmpty()) titleView.setText(t);
            }
        });
    }

    /** @return true when the navigation was consumed (never loaded in the page). */
    private boolean handleUrl(Uri u, boolean gesture) {
        String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if ("https".equals(sch)) {
            if (sameSite(u.getHost())) return false;
            openExternal(u);      // Telegram login, payment pages, social links ...: leave the mini browser
            return true;
        }
        if (gesture && ("tg".equals(sch) || "mailto".equals(sch) || "tel".equals(sch))) {
            openExternal(u);
            return true;
        }
        if ("intent".equals(sch)) {                         // some sites wrap the deep link in an intent:// URL
            try {
                Intent in = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                if (in.getData() != null) return handleUrl(in.getData(), gesture);
            } catch (java.net.URISyntaxException ignored) {
            }
            return true;
        }
        if ("v2rayng".equals(sch) || "magicv2ray".equals(sch)) {
            String url = u.getQueryParameter("url");          // already url-decoded
            url = url == null ? "" : url.trim();
            if (!url.matches("(?i)^https?://\\S+$")) {
                Toast.makeText(this, R.string.provider_bad_sync, Toast.LENGTH_SHORT).show();
                return true;
            }
            try {
                MainActivity.pendingProviderSync = new JSONObject()
                        .put("category", category).put("url", url).toString();
            } catch (JSONException e) {
                return true;
            }
            // Bring the app forward (also when this page came from a home-screen shortcut and the app is
            // not running); its WebUI picks the job up in onResume / at start.
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return true;
        }
        return true;   // plain http, tel:, other apps' schemes ...: never leave the sandboxed page
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(new ContextThemeWrapper(this, light
                ? android.R.style.Theme_DeviceDefault_Light : android.R.style.Theme_DeviceDefault), anchor);
        int count = nodeCount();
        m.getMenu().add(0, 1, 0, getString(R.string.provider_nodes, count));
        m.getMenu().add(0, 2, 1, R.string.provider_reload);
        m.getMenu().add(0, 3, 2, R.string.provider_copy_link);
        m.getMenu().add(0, 4, 3, R.string.provider_open_external);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: showNodes(); return true;
                case 2: web.reload(); return true;
                case 3: copyLink(); return true;
                case 4: openExternal(); return true;
                default: return false;
            }
        });
        m.show();
    }

    private int nodeCount() {
        try {
            return new JSONArray(currentNodes()).length();
        } catch (JSONException e) {
            return 0;
        }
    }

    private void showNodes() {
        JSONArray arr;
        try {
            arr = new JSONArray(currentNodes());
        } catch (JSONException e) {
            arr = new JSONArray();
        }
        Context ctx = new ContextThemeWrapper(this, light
                ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert);
        AlertDialog.Builder d = new AlertDialog.Builder(ctx).setTitle(category)
                .setPositiveButton(R.string.provider_close, null);
        if (arr.length() == 0) {
            d.setMessage(R.string.provider_no_nodes);
        } else {
            String[] rows = new String[arr.length()];
            for (int i = 0; i < rows.length; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) { rows[i] = ""; continue; }
                String proto = o.optString("p", "").toUpperCase();
                String addr = o.optString("a", "");
                String info = proto.isEmpty() ? addr : (addr.isEmpty() ? proto : proto + " · " + addr);
                rows[i] = o.optString("n", "") + (info.isEmpty() ? "" : "\n" + info);
            }
            final JSONArray nodes = arr;
            d.setItems(rows, (dlg, which) -> {
                JSONObject o = nodes.optJSONObject(which);
                String id = o == null ? "" : o.optString("i", "");
                if (id.isEmpty()) return;
                try {
                    MainActivity.pendingProviderConnect = new JSONObject()
                            .put("category", category).put("id", id).toString();
                } catch (JSONException e) {
                    return;
                }
                // Back to the app, which asks to confirm and connects.
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            });
        }
        d.show();
    }

    private void copyLink() {
        String url = web.getUrl() != null ? web.getUrl() : startUrl;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("url", url));
            Toast.makeText(this, R.string.provider_link_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** The site the user added, with or without "www." and including its subdomains. */
    private boolean sameSite(String host) {
        if (host == null) return false;
        String h0 = bare(Uri.parse(startUrl).getHost());
        String h = bare(host);
        return h.equals(h0) || h.endsWith("." + h0);
    }

    private static String bare(String h) {
        h = h == null ? "" : h.toLowerCase(java.util.Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private void openExternal() {
        String url = web.getUrl() != null ? web.getUrl() : startUrl;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private TextView iconButton(String glyph, int color, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        clearProxy();            // the override is process-wide: do not leave it on the WebUI's WebView
        if (web != null) {
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
