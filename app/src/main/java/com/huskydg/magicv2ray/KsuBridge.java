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

    /** Save dialog for a text file; callback(0) saved, callback(1) cancelled / failed. */
    @JavascriptInterface
    public void saveTextFile(final String name, final String text, final String callback) {
        if (callback == null || !CB_RE.matcher(callback).matches() || !(activity instanceof MainActivity)) return;
        final String safe = (name == null ? "hosts.txt" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        activity.runOnUiThread(() -> ((MainActivity) activity).saveTextFile(safe, text == null ? "" : text, callback));
    }

    /**
     * "Export app logs": builds the zip (LogExporter) from the files the WebUI composed
     * ({name: text} JSON: settings.txt, config.txt) plus the logs / device info collected here,
     * then saves it to Download/MagicV2Ray. Callback gets (0, "Download/MagicV2Ray/<file>", "") on
     * success, (1, "", reason) on failure, (1, "", "cancelled") when the save dialog was dismissed
     * (Android 9 and older only).
     */
    @JavascriptInterface
    public void exportLogs(final String filesJson, final String callback) {
        if (callback == null || !CB_RE.matcher(callback).matches()) return;
        POOL.execute(() -> {
            java.io.File zip = null;
            try {
                zip = LogExporter.build(activity.getApplicationContext(), filesJson);
                String where = LogExporter.saveToDownloads(activity.getApplicationContext(), zip);
                if (where == null && activity instanceof MainActivity) {   // Android 9-: ask where to save
                    final java.io.File pending = zip;
                    zip = null;                                            // MainActivity deletes it
                    activity.runOnUiThread(() -> ((MainActivity) activity).saveZipFile(pending, callback));
                    return;
                }
                deliver(callback, 0, where == null ? "" : where, "");
            } catch (Exception e) {
                deliver(callback, 1, "", String.valueOf(e.getMessage()));
            } finally {
                if (zip != null) {
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                }
            }
        });
    }

    /** The URL of a pending deep link ("" if none); returns it once. */
    @JavascriptInterface
    public String takePendingLink() {
        String u = MainActivity.pendingLink;
        MainActivity.pendingLink = null;
        return u == null ? "" : u;
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

    /**
     * DNS servers of the underlying (non-VPN) network as a JSON array of IP strings, in the
     * order Android uses them. Unusable entries are dropped here: the wildcard address
     * (0.0.0.0 / ::), loopback and IPv6 link-local servers (they need a scope id that a bare
     * UDP query to "fe80::1" cannot carry). Returns "[]" when nothing usable is known.
     */
    @JavascriptInterface
    public String getSystemDnsServers() {
        JSONArray out = new JSONArray();
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return out.toString();
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
            if (pick == null) return out.toString();
            LinkProperties lp = cm.getLinkProperties(pick);
            if (lp == null) return out.toString();
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (java.net.InetAddress a : lp.getDnsServers()) {
                if (a == null || a.isAnyLocalAddress() || a.isLoopbackAddress()) continue;
                if (a instanceof java.net.Inet6Address && a.isLinkLocalAddress()) continue;
                String host = a.getHostAddress();
                if (host == null) continue;
                int pct = host.indexOf('%');
                if (pct >= 0) host = host.substring(0, pct);
                if (host.isEmpty() || "0.0.0.0".equals(host) || "::".equals(host)) continue;
                seen.add(host);
            }
            for (String h : seen) out.put(h);
        } catch (RuntimeException ignored) {
        }
        return out.toString();
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

    /**
     * The WebUI reports the proxy status and the selected node's name after every status check.
     * The name goes into the notification; in root mode this also posts / removes it.
     */
    @JavascriptInterface
    public void syncStatus(final boolean running, final String nodeName) {
        final Context app = activity.getApplicationContext();
        ProxyNotifier.setNode(app, nodeName);
        if (RunMode.isNonRoot(activity)) {
            MagicVpnService s = VpnController.svc;
            if (s != null) s.updateNode();
        } else {
            ProxyNotifier.showRoot(app, running);
        }
    }

    // ------------------------------------------------ Share proxy via Shizuku (non-root)

    /** JSON state for the WebUI card (see ShizukuHotspot.info). */
    @JavascriptInterface
    public String hotspotInfo() {
        return ShizukuHotspot.info(activity.getApplicationContext());
    }

    /** "requested" | "denied_forever" | "unavailable". */
    @JavascriptInterface
    public String hotspotRequestPermission() {
        return ShizukuHotspot.requestPermission();
    }

    @JavascriptInterface
    public void hotspotOpenShizuku() {
        ShizukuHotspot.openShizuku(activity.getApplicationContext());
    }

    /** options: {"wifi":bool,"usb":bool}. Callback gets (errno, stdout, "code|detail"). */
    @JavascriptInterface
    public void hotspotStart(String options, String callback) {
        if (callback == null || !CB_RE.matcher(callback).matches() || !RunMode.isNonRoot(activity)) return;
        ShizukuHotspot.start(activity, options, (code, out, err) -> deliver(callback, code, out, err));
    }

    @JavascriptInterface
    public void hotspotStop(String callback) {
        if (callback == null || !CB_RE.matcher(callback).matches()) return;
        ShizukuHotspot.stop(activity, (code, out, err) -> deliver(callback, code, out, err));
    }

    private void deliver(String callback, int code, String out, String err) {
        final String js = "window['" + callback + "']&&window['" + callback + "'](" + code + ","
                + JSONObject.quote(out) + "," + JSONObject.quote(err) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    // ------------------------------------------------------------ app appearance

    /** The WebUI's appearance settings as JSON ("" until first saved). */
    @JavascriptInterface
    public String getAppSettings() {
        return AppPrefs.getJson(activity);
    }

    @JavascriptInterface
    public void setAppSettings(String json) {
        AppPrefs.setJson(activity, json);
    }

    /** True when the SYSTEM is in dark mode, whatever theme the app itself is showing. */
    @JavascriptInterface
    public boolean isSystemNight() {
        return AppPrefs.systemIsNight(activity);
    }

    /**
     * Android 13+ dynamic colour: the system accent1-3 / neutral1-2 shades as
     * {"a1":{"0":"#FFFFFF","10":...,"1000":"#000000"},"a2":...,"n1":...,"n2":...}; "" when unavailable.
     */
    @JavascriptInterface
    public String getSystemPalette() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return "";
        final String[][] groups = {{"a1", "accent1"}, {"a2", "accent2"}, {"a3", "accent3"}, {"n1", "neutral1"}, {"n2", "neutral2"}};
        final int[] shades = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
        try {
            JSONObject out = new JSONObject();
            for (String[] g : groups) {
                JSONObject o = new JSONObject();
                for (int sh : shades) {
                    int id = activity.getResources().getIdentifier("system_" + g[1] + "_" + sh, "color", "android");
                    if (id == 0) return "";
                    o.put(String.valueOf(sh), String.format("#%06X", 0xFFFFFF & activity.getResources().getColor(id, activity.getTheme())));
                }
                out.put(g[0], o);
            }
            return out.toString();
        } catch (JSONException | RuntimeException e) {
            return "";
        }
    }

    /** Colours the status / navigation bars (and the WebView backdrop) to match the WebUI's surface. */
    @JavascriptInterface
    public void setChrome(final String bgHex, final boolean lightBars) {
        if (bgHex == null || !bgHex.matches("^#[0-9A-Fa-f]{6}$") || !(activity instanceof MainActivity)) return;
        final int color = android.graphics.Color.parseColor(bgHex);
        activity.runOnUiThread(() -> ((MainActivity) activity).applyChrome(color, lightBars));
    }
}
