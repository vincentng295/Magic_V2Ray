package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.system.OsConstants;
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
            RootShell.Result r = null;
            if (RunMode.isNonRoot(activity)) {          // no proxy_control.sh without root: drive the VpnService
                r = VpnController.intercept(activity.getApplicationContext(), activity, command);
            }
            if (r == null) r = RootShell.run(script, 0);
            final String js = "window['" + callback + "']&&window['" + callback + "']("
                    + r.code + "," + JSONObject.quote(r.out) + "," + JSONObject.quote(r.err) + ")";
            web.post(() -> web.evaluateJavascript(js, null));
        });
    }

    private String buildScript(String command, String options) {
        StringBuilder sb = new StringBuilder();
        // busybox applets (<moddir>/bin, root or non-root) come after the system tools in PATH
        sb.append("export PATH=\"$PATH:").append(NonRootEnv.activeModdir(activity)).append("/bin\"\n");
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

    /** "root" or "nonroot": decides which base paths and engine the WebUI uses. */
    @JavascriptInterface
    public String mode() {
        return RunMode.isNonRoot(activity) ? RunMode.NONROOT : RunMode.ROOT;
    }

    @JavascriptInterface
    public String moddir() {
        return NonRootEnv.moddir(activity).getPath();
    }

    @JavascriptInterface
    public String datadir() {
        return NonRootEnv.datadir(activity).getPath();
    }

    @JavascriptInterface
    public String stubdir() {
        return NonRootEnv.stubdir(activity).getPath();
    }

    /**
     * Underlying (non-VPN) network as an `ip addr show` style dump, which is what the WebUI's
     * parseAddrInfo() reads. This app is excluded from its own VPN, so it sees the physical network.
     */
    @JavascriptInterface
    public String networkInfo() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            Network pick = null;
            Network act = cm.getActiveNetwork();
            if (act != null && isPhysical(cm, act)) pick = act;
            if (pick == null) {
                for (Network n : cm.getAllNetworks()) {
                    if (isPhysical(cm, n)) {
                        pick = n;
                        break;
                    }
                }
            }
            if (pick == null) return "";
            LinkProperties lp = cm.getLinkProperties(pick);
            if (lp == null || lp.getInterfaceName() == null) return "";
            StringBuilder sb = new StringBuilder("1: ").append(lp.getInterfaceName()).append(": <UP>\n");
            for (LinkAddress la : lp.getLinkAddresses()) {
                java.net.InetAddress a = la.getAddress();
                if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                String host = a.getHostAddress();
                int pct = host.indexOf('%');
                if (pct >= 0) host = host.substring(0, pct);
                if (a instanceof java.net.Inet4Address) {
                    sb.append("    inet ").append(host).append('/').append(la.getPrefixLength()).append('\n');
                } else if (la.getScope() == OsConstants.RT_SCOPE_UNIVERSE) {
                    sb.append("    inet6 ").append(host).append('/').append(la.getPrefixLength()).append(" scope global\n");
                }
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean isPhysical(ConnectivityManager cm, Network n) {
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @JavascriptInterface
    public int sdk() {
        return android.os.Build.VERSION.SDK_INT;
    }

    /** Switches between root and non-root mode; the old engine is stopped first. */
    @JavascriptInterface
    public void setMode(final String mode) {
        if (!RunMode.ROOT.equals(mode) && !RunMode.NONROOT.equals(mode)) return;
        if (mode.equals(mode())) return;
        POOL.execute(() -> {
            if (RunMode.isNonRoot(activity)) {
                VpnController.stop();
            } else {
                RootShell.run("sh " + RootShell.sq(ModuleInstaller.MODDIR + "/proxy_control.sh") + " stop", 30_000);
            }
            RunMode.set(activity, mode);
            activity.runOnUiThread(activity::recreate);
        });
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
