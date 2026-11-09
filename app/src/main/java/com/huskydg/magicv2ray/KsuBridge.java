package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * JavaScript bridge exposed to the WebUI as {@code window.ksu}.
 *
 * It implements the subset of the KernelSU WebUI API the Magic V2Ray UI uses
 * (exec, listPackages, getPackagesInfo) with the same signatures and result
 * formats, so webroot code written for the module's KSU WebUI runs unchanged.
 * Everything here runs as root-capable code: the WebView that hosts it must
 * only ever load the app's own bundled pages (see MainActivity).
 */
final class KsuBridge {

    private static final Pattern CB_RE = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
    private static final Pattern ENV_KEY_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ksu-exec");
        t.setDaemon(true);
        return t;
    });

    private final Activity activity;
    private final WebView web;

    KsuBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    /** ksu.exec(command, optionsJson, callbackName) -> window[callbackName](errno, stdout, stderr). */
    @JavascriptInterface
    public void exec(final String command, final String options, final String callback) {
        if (callback == null || !CB_RE.matcher(callback).matches()) return;
        final String script = buildScript(command == null ? "" : command, options);
        POOL.execute(() -> {
            RootShell.Result r = RootShell.run(script, 0);
            final String js = "window['" + callback + "']&&window['" + callback + "']("
                    + r.code + "," + JSONObject.quote(r.out) + "," + JSONObject.quote(r.err) + ")";
            web.post(() -> web.evaluateJavascript(js, null));
        });
    }

    private static String buildScript(String command, String options) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject o = new JSONObject(options == null || options.isEmpty() ? "{}" : options);
            JSONObject env = o.optJSONObject("env");
            if (env != null) {
                Iterator<String> it = env.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (ENV_KEY_RE.matcher(k).matches()) {
                        sb.append("export ").append(k).append('=').append(RootShell.sq(env.optString(k))).append('\n');
                    }
                }
            }
            String cwd = o.optString("cwd", "");
            if (!cwd.isEmpty()) sb.append("cd ").append(RootShell.sq(cwd)).append(" || exit 1\n");
        } catch (JSONException ignored) {
        }
        return sb.append(command).toString();
    }

    /** ksu.listPackages(type) -> JSON array of package names. type: all | user | system. */
    @JavascriptInterface
    public String listPackages(String type) {
        PackageManager pm = activity.getPackageManager();
        JSONArray arr = new JSONArray();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if ("user".equals(type) && system) continue;
            if ("system".equals(type) && !system) continue;
            arr.put(ai.packageName);
        }
        return arr.toString();
    }

    /** ksu.getPackagesInfo(jsonArrayOfNames) -> JSON array of {packageName, appLabel, isSystem, uid, versionName, versionCode}. */
    @JavascriptInterface
    public String getPackagesInfo(String namesJson) {
        PackageManager pm = activity.getPackageManager();
        JSONArray out = new JSONArray();
        try {
            JSONArray names = new JSONArray(namesJson);
            for (int i = 0; i < names.length(); i++) {
                String pkg = names.optString(i, "");
                if (pkg.isEmpty()) continue;
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("packageName", pkg);
                    o.put("appLabel", String.valueOf(pm.getApplicationLabel(ai)));
                    o.put("versionName", pi.versionName == null ? "" : pi.versionName);
                    o.put("versionCode", pi.versionCode);
                    o.put("isSystem", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    o.put("uid", ai.uid);
                    out.put(o);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    @JavascriptInterface
    public void toast(final String msg) {
        activity.runOnUiThread(() -> Toast.makeText(activity, String.valueOf(msg), Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public String readClipboard() {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return "";
        ClipData d = cm.getPrimaryClip();
        if (d == null || d.getItemCount() == 0) return "";
        CharSequence cs = d.getItemAt(0).coerceToText(activity);
        return cs == null ? "" : cs.toString();
    }

    @JavascriptInterface
    public void writeClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Magic V2Ray", text));
    }

    /** Opens an http(s) URL in the browser. */
    @JavascriptInterface
    public void openUrl(String url) {
        if (url == null || !url.matches("^https?://[^\\s]+$")) return;
        activity.runOnUiThread(() -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        });
    }
}
