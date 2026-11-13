package com.huskydg.magicv2ray;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.net.Uri;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Export app logs" (App settings tab).
 *
 * Builds one zip with everything useful for a bug report:
 *
 *   app.log          this app's logcat (also carries the WebUI console output)
 *   xray.log         Xray's output: root = `logservice read` dump of the ring buffer, non-root = tail of the file
 *   service.log, proxy_control.log, hotspot.log   backend logs, when they exist
 *   settings.txt     options as shown in the UI, English labels (built by the WebUI)
 *   config.txt       active node URI payload, UUIDs / passwords hidden (built by the WebUI)
 *   config.json      the generated Xray config, secrets hidden
 *   deviceinfo.txt   device / Android / app / module / root details
 *   network.txt      interfaces, policy routing, iptables, processes
 *
 * Anything that looks like a UUID is replaced with "xxxxx" in every file. The zip is
 * saved to Download/MagicV2Ray (MediaStore, no permission needed on Android 10+); older
 * versions fall back to the system "save as" dialog (see MainActivity.saveZipFile).
 */
final class LogExporter {

    static final String MASK = "xxxxx";
    /** Per log file: only the newest part is kept. */
    private static final int LOG_TAIL_BYTES = 2 * 1024 * 1024;
    private static final int APP_LOG_LINES = 6000;
    /** Upper bound for `logservice read -n`; the daemon keeps at most its own max-lines (default 1000). */
    private static final int XRAY_LOG_LINES = 100000;
    private static final int MAX_TEXT_CHARS = 6 * 1024 * 1024;
    private static final int HOSTS_KEPT = 50;
    private static final String SAVE_DIR = "MagicV2Ray";

    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** JSON keys in config.json whose string values are credentials. Compared lower-case. */
    private static final String[] SECRET_KEYS = {
            "id", "uuid", "password", "pass", "privatekey", "secretkey", "psk", "auth",
            "seed", "shortid", "token", "secret"};

    private LogExporter() {
    }

    // ------------------------------------------------------------------ build

    /** @param webFiles JSON object name -> text for the files the WebUI builds itself. */
    static File build(Context c, String webFiles) throws IOException {
        final boolean nonRoot = RunMode.isNonRoot(c);
        final String datadir = nonRoot ? NonRootEnv.datadir(c).getPath() : ModuleInstaller.DATADIR;
        final String stubdir = nonRoot ? NonRootEnv.stubdir(c).getPath() : "/dev/sysctl_stubs";
        final String moddir = nonRoot ? NonRootEnv.moddir(c).getPath() : ModuleInstaller.MODDIR;

        LinkedHashMap<String, String> entries = new LinkedHashMap<>();

        // 1. files composed by the WebUI (settings.txt, config.txt)
        try {
            JSONObject o = new JSONObject(webFiles == null || webFiles.isEmpty() ? "{}" : webFiles);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String safe = k.replaceAll("[^A-Za-z0-9._-]", "_");
                if (!safe.isEmpty()) entries.put(safe, o.optString(k, ""));
            }
        } catch (JSONException ignored) {
        }

        // 2. everything the app can see on its own
        entries.put("deviceinfo.txt", deviceInfo(c, nonRoot, datadir, moddir));
        entries.put("app.log", appLog());
        // Root mode: Xray's output only lives in the logservice daemon's ring buffer, so it has to be
        // dumped with `logservice read`. Non-root: the VpnService appends it to a plain file.
        entries.put("xray.log", nonRoot ? tailFile(stubdir + "/logs/xray.log") : xrayLogFromService(moddir, stubdir));
        if (!nonRoot) {
            // native service / control script logs (root mode only)
            entries.put("xray-datadir.log", tailFile(datadir + "/xray.log"));
            entries.put("service.log", tailFile(datadir + "/service.log"));
            entries.put("proxy_control.log", tailFile(datadir + "/proxy_control.log"));
        } else {
            entries.put("hotspot.log", tailFile(stubdir + "/logs/hotspot.log"));
        }
        entries.put("config.json", configJson(datadir + "/config.v2.json"));
        entries.put("network.txt", networkInfo(nonRoot, datadir, stubdir));

        // 3. zip it
        File dir = new File(c.getCacheDir(), "export");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File zip = new File(dir, "MagicV2Ray_logs_" + stamp + ".zip");
        long now = System.currentTimeMillis();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                String text = mask(e.getValue() == null ? "" : e.getValue());
                if (text.length() > MAX_TEXT_CHARS) {
                    text = "[... truncated, newest " + MAX_TEXT_CHARS + " chars kept ...]\n"
                            + text.substring(text.length() - MAX_TEXT_CHARS);
                }
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(now);
                z.putNextEntry(ze);
                z.write(text.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }

    static String mask(String s) {
        return UUID_RE.matcher(s).replaceAll(MASK);
    }

    // ------------------------------------------------------------------ pieces

    /** This app's own logcat. An app can only read its own process' lines, which is what we want. */
    private static String appLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("# app.log - logcat of this app (last ").append(APP_LOG_LINES)
                .append(" lines; includes WebUI console output, tag \"chromium\")\n");
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", String.valueOf(APP_LOG_LINES))
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < MAX_TEXT_CHARS) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy();
        } catch (IOException | RuntimeException e) {
            sb.append("# logcat failed: ").append(e).append('\n');
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sb.toString();
    }

    /** Last LOG_TAIL_BYTES of a file, read with the same privileges the engine runs with. */
    private static String tailFile(String path) {
        String out = sh(
                "if [ -f " + RootShell.sq(path) + " ]; then tail -c " + LOG_TAIL_BYTES + " " + RootShell.sq(path)
                        + "; else echo '(file not found: " + path + ")'; fi", 20_000);
        if (out.trim().isEmpty()) return "(empty: " + path + ")\n";
        return out;
    }

    /**
     * Root mode xray.log: `xhuskydg_helper logservice read -c <control pipe> -n N -o <file>` dumps the
     * newest N buffered lines (the same call the Xray Log tab makes). It is written to a temp file
     * first, never to stdout, so the result does not depend on how the shell wires stdin / stdout.
     */
    private static String xrayLogFromService(String moddir, String stubdir) {
        final String ctl = stubdir + "/logs/control.pipe";
        final String tmp = stubdir + "/run/export_xray.$$.log";
        String script = ""
                + "T=\"" + tmp + "\"\n"
                + "rm -f \"$T\"\n"
                + "ERR=$(" + RootShell.sq(moddir + "/bin/xhuskydg_helper") + " logservice read -c " + RootShell.sq(ctl)
                + " -n " + XRAY_LOG_LINES + " -o \"$T\" 2>&1)\n"
                + "RC=$?\n"
                + "if [ -s \"$T\" ]; then\n"
                + "  tail -c " + LOG_TAIL_BYTES + " \"$T\"\n"
                + "else\n"
                + "  echo \"(logservice returned no log lines: exit code $RC)\"\n"
                + "  [ -n \"$ERR\" ] && echo \"$ERR\"\n"
                + "  [ -p " + RootShell.sq(ctl) + " ] && echo '(control pipe exists)' || echo '(control pipe missing: logservice is not running)'\n"
                + "fi\n"
                + "rm -f \"$T\"\n";
        String out = sh(script, 30_000);
        return out.trim().isEmpty() ? "(empty: logservice read gave no output)\n" : out;
    }

    /** config.json with credentials hidden and the (possibly huge) dns.hosts table shortened. */
    private static String configJson(String path) {
        String raw = sh(
                "if [ -f " + RootShell.sq(path) + " ]; then cat " + RootShell.sq(path)
                        + "; else echo '(file not found: " + path + ")'; fi", 20_000);
        try {
            Object root = new org.json.JSONTokener(raw).nextValue();
            if (root instanceof JSONObject) {
                maskJson(root);
                JSONObject dns = ((JSONObject) root).optJSONObject("dns");
                JSONObject hosts = dns == null ? null : dns.optJSONObject("hosts");
                if (hosts != null && hosts.length() > HOSTS_KEPT) {
                    JSONObject kept = new JSONObject();
                    Iterator<String> it = hosts.keys();
                    int n = 0;
                    while (it.hasNext() && n < HOSTS_KEPT) {
                        String k = it.next();
                        kept.put(k, hosts.get(k));
                        n++;
                    }
                    kept.put("__omitted__", (hosts.length() - HOSTS_KEPT) + " more entries not exported");
                    dns.put("hosts", kept);
                }
                return ((JSONObject) root).toString(2);
            }
        } catch (JSONException | RuntimeException ignored) {
            // not JSON (broken / empty file): ship the text as it is, UUIDs are still masked later
        }
        return raw;
    }

    private static void maskJson(Object node) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            // copy the keys first: put() while iterating keys() is not safe
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = o.get(k);
                if (v instanceof String) {
                    String lk = k.toLowerCase(Locale.ROOT);
                    String s = (String) v;
                    if (!s.isEmpty() && Arrays.asList(SECRET_KEYS).contains(lk)) {
                        o.put(k, MASK);
                    } else if (lk.equals("encryption") && s.length() > 16 && s.indexOf('.') > 0) {
                        // VLESS Encryption: "mlkem768x25519plus.native.0rtt.<key material>"
                        String[] p = s.split("\\.");
                        StringBuilder b = new StringBuilder();
                        for (int i = 0; i < p.length && i < 3; i++) b.append(p[i]).append('.');
                        o.put(k, b.append(MASK).toString());
                    }
                } else {
                    maskJson(v);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.get(i);
                if (!(v instanceof String)) maskJson(v);
            }
        }
    }

    private static String deviceInfo(Context c, boolean nonRoot, String datadir, String moddir) {
        StringBuilder sb = new StringBuilder();
        sb.append("Magic V2Ray - device info\n");
        sb.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append(" (").append(TimeZone.getDefault().getID()).append(")\n\n");

        // ---- device
        TreeMap<String, String> props = props();
        String market = firstNonEmpty(props, "ro.product.marketname", "ro.product.vendor.marketname",
                "ro.product.odm.marketname", "ro.product.system.marketname", "ro.vendor.oplus.market.name",
                "ro.oppo.market.name", "ro.config.marketing_name", "ro.vivo.market.name");
        String fullName = (market.isEmpty() ? "" : market + " / ") + Build.MANUFACTURER + " " + Build.MODEL;
        sb.append("== Device ==\n");
        line(sb, "Device (full name)", fullName);
        line(sb, "Manufacturer", Build.MANUFACTURER);
        line(sb, "Brand", Build.BRAND);
        line(sb, "Model", Build.MODEL);
        line(sb, "Device codename", Build.DEVICE);
        line(sb, "Product", Build.PRODUCT);
        line(sb, "Hardware / board", Build.HARDWARE + " / " + Build.BOARD);
        line(sb, "SoC", firstNonEmpty(props, "ro.soc.manufacturer") + " " + firstNonEmpty(props, "ro.soc.model")
                + " (platform " + firstNonEmpty(props, "ro.board.platform") + ")");
        line(sb, "CPU ABIs", Arrays.toString(Build.SUPPORTED_ABIS));
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                line(sb, "RAM", (mi.totalMem >> 20) + " MB total, " + (mi.availMem >> 20) + " MB available"
                        + (mi.lowMemory ? " (LOW MEMORY)" : ""));
            }
        } catch (RuntimeException ignored) {
        }
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        line(sb, "Screen", dm.widthPixels + "x" + dm.heightPixels + " @" + dm.densityDpi + "dpi");
        line(sb, "Locale / time zone", Locale.getDefault().toLanguageTag() + " / " + TimeZone.getDefault().getID());

        // ---- android
        sb.append("\n== Android ==\n");
        line(sb, "Version", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT
                + (Build.VERSION.CODENAME.equals("REL") ? "" : ", " + Build.VERSION.CODENAME) + ")");
        line(sb, "Security patch", Build.VERSION.SECURITY_PATCH);
        line(sb, "Build ID / display", Build.ID + " / " + Build.DISPLAY);
        line(sb, "Incremental", Build.VERSION.INCREMENTAL);
        line(sb, "Fingerprint", Build.FINGERPRINT);
        line(sb, "Build type / tags", Build.TYPE + " / " + Build.TAGS);
        line(sb, "Kernel (java)", System.getProperty("os.version"));
        for (String k : new String[]{"ro.miui.ui.version.name", "ro.mi.os.version.name", "ro.build.version.oplusrom",
                "ro.build.version.emui", "ro.build.version.opporom", "ro.vivo.os.version", "ro.build.version.oneui"}) {
            String v = props.get(k);
            if (v != null && !v.isEmpty()) line(sb, k, v);
        }

        // ---- app
        sb.append("\n== App ==\n");
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            line(sb, "Package", c.getPackageName());
            line(sb, "Version", pi.versionName + " (" + pi.versionCode + ")");
            line(sb, "Installer", String.valueOf(c.getPackageManager().getInstallerPackageName(c.getPackageName())));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            line(sb, "Package", c.getPackageName());
        }
        try {
            PackageInfo wv = WebView.getCurrentWebViewPackage();
            if (wv != null) line(sb, "WebView", wv.packageName + " " + wv.versionName);
        } catch (RuntimeException ignored) {
        }
        line(sb, "Run mode", nonRoot ? "Non-root (VpnService)" : "Root (iptables / policy routing)");

        // ---- system tools (run as root in root mode, as the app otherwise)
        String script = ""
                + "echo '== Runtime =='\n"
                + "echo \"id: $(id 2>&1)\"\n"
                + "echo \"uname: $(uname -a 2>&1)\"\n"
                + "echo \"SELinux: $(getenforce 2>&1)\"\n"
                + (nonRoot ? "" : ""
                + "echo \"magisk: $(magisk -V 2>&1 | head -n 1) ($(magisk -v 2>&1 | head -n 1))\"\n"
                + "echo \"ksud: $(ksud -V 2>&1 | head -n 1)\"\n"
                + "echo \"apd: $(apd -V 2>&1 | head -n 1)\"\n")
                + "echo\n"
                + "echo '== Xray core =='\n"
                + RootShell.sq(moddir + "/bin/xray") + " version 2>&1 | head -n 3\n"
                + "echo\n"
                + "echo '== module.prop =='\n"
                + "cat " + RootShell.sq(moddir + "/module.prop") + " 2>&1\n"
                + "echo\n"
                + "echo '== data dir =='\n"
                + "ls -la " + RootShell.sq(datadir) + " 2>&1 | head -n 60\n";
        sb.append('\n').append(sh(script, 30_000));

        // ---- selected system properties
        sb.append("\n\n== Properties ==\n");
        for (Map.Entry<String, String> e : props.entrySet()) {
            sb.append('[').append(e.getKey()).append("]: [").append(e.getValue()).append("]\n");
        }
        return sb.toString();
    }

    /** Runs a script (root in root mode) and returns its stdout, never null. */
    private static String sh(String script, long timeoutMs) {
        RootShell.Result r = RootShell.run(script, timeoutMs);
        return r == null || r.out == null ? "" : r.out;
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(String.format(Locale.US, "%-22s %s\n", k + ":", v == null ? "" : v.trim()));
    }

    private static String firstNonEmpty(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    /** `getprop`, reduced to the keys that identify the device / ROM. */
    private static TreeMap<String, String> props() {
        TreeMap<String, String> out = new TreeMap<>();
        String[] prefixes = {"ro.product.", "ro.build.version.", "ro.build.id", "ro.build.display.id",
                "ro.build.flavor", "ro.miui.", "ro.mi.os.", "ro.oplus.", "ro.vendor.oplus.", "ro.oppo.",
                "ro.vivo.", "ro.config.marketing_name", "ro.soc.", "ro.board.platform", "ro.hardware",
                "ro.treble.enabled", "ro.boot.verifiedbootstate", "ro.boot.flash.locked"};
        try {
            Process p = new ProcessBuilder("getprop").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // [key]: [value]
                    int a = line.indexOf("]: [");
                    if (!line.startsWith("[") || a < 0 || !line.endsWith("]")) continue;
                    String k = line.substring(1, a);
                    String v = line.substring(a + 4, line.length() - 1);
                    for (String pre : prefixes) {
                        if (k.startsWith(pre)) {
                            out.put(k, v);
                            break;
                        }
                    }
                }
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy();
        } catch (IOException | RuntimeException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /** Interfaces, policy routing, firewall rules and the engine's processes. */
    private static String networkInfo(boolean nonRoot, String datadir, String stubdir) {
        StringBuilder s = new StringBuilder();
        s.append("echo '### ip addr'; ip addr 2>&1\n");
        s.append("echo; echo '### ip rule'; ip rule 2>&1; ip -6 rule 2>&1\n");
        s.append("echo; echo '### ip route (all tables, first 300 lines)'; ip route show table all 2>&1 | head -n 300\n");
        s.append("echo; echo '### ip -6 route (first 200 lines)'; ip -6 route show table all 2>&1 | head -n 200\n");
        if (!nonRoot) {
            for (String t : new String[]{"mangle", "nat", "filter"}) {
                s.append("echo; echo '### iptables -t ").append(t).append(" -S'; iptables -t ").append(t).append(" -S 2>&1 | head -n 400\n");
                s.append("echo; echo '### ip6tables -t ").append(t).append(" -S'; ip6tables -t ").append(t).append(" -S 2>&1 | head -n 400\n");
            }
        }
        s.append("echo; echo '### processes'; ps -A 2>/dev/null | grep -i -E 'xray|magic|logservice|vpnrun|openxtun' | grep -v grep\n");
        s.append("echo; echo '### listening sockets'; (ss -ltnup 2>/dev/null || netstat -ltnup 2>/dev/null) | head -n 80\n");
        s.append("echo; echo '### stub dirs'; ls -laR ").append(RootShell.sq(stubdir + "/run")).append(' ')
                .append(RootShell.sq(stubdir + "/logs")).append(" 2>&1 | head -n 120\n");
        return sh(s.toString(), 40_000);
    }

    // ------------------------------------------------------------------ saving

    /**
     * Saves the zip to Download/MagicV2Ray. Returns the user-visible path, or null on
     * Android 9 and older (no scoped storage: the caller opens the save dialog instead).
     */
    static String saveToDownloads(Context c, File zip) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, zip.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("cannot create a file in Downloads");
        try (OutputStream o = cr.openOutputStream(uri)) {
            if (o == null) throw new IOException("cannot open the new file in Downloads");
            copy(zip, o);
        } catch (IOException | RuntimeException e) {
            cr.delete(uri, null, null);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR + "/" + zip.getName();
    }

    static void copy(File from, OutputStream to) throws IOException {
        try (InputStream in = new FileInputStream(from)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) to.write(buf, 0, n);
        }
    }
}
