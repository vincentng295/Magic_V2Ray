package com.huskydg.magicv2ray;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Files and settings of non-root mode. Everything lives in the app's private storage, laid
 * out like the root install so the WebUI only needs different base paths:
 *
 *   files/module/bin/{xray,xhuskydg_helper,curl}  symlinks to the native libs
 *   files/module/bin/geoip.dat, geosite.dat       copied from the APK
 *   files/data/                                   DATADIR (profiles, settings, config.v2.json)
 *   files/stubs/{run,logs,proc}                   STUB_DIR (runtime state, log pipes)
 */
final class NonRootEnv {

    /** Same list as LAN_BYPASS_CIDRS in webroot/vars.js. */
    static final String[] LAN_CIDRS = {
            "10.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24",
            "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.51.100.0/24", "203.0.113.0/24",
            "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
            "::ffff:0:0/96", "64:ff9b::/96", "100::/64", "2001::/32", "2001:10::/28", "2001:20::/28",
            "2001:db8::/32", "2002::/16", "fc00::/7", "fe80::/10", "ff00::/8"};

    private NonRootEnv() {
    }

    static File root(Context c) {
        return c.getFilesDir();
    }

    static File moddir(Context c) {
        return new File(root(c), "module");
    }

    static File datadir(Context c) {
        return new File(root(c), "data");
    }

    static File stubdir(Context c) {
        return new File(root(c), "stubs");
    }

    static File bin(Context c, String name) {
        return new File(moddir(c), "bin/" + name);
    }

    /** Xray's stdout/stderr (written by MagicVpnService, tailed by the WebUI's Log tab). */
    static File logFile(Context c) {
        return new File(stubdir(c), "logs/xray.log");
    }

    static File config(Context c) {
        return new File(datadir(c), "config.v2.json");
    }

    static boolean configReady(Context c) {
        File f = config(c);
        return f.isFile() && f.length() > 0;
    }

    /** Creates the directories, the executable symlinks and the geo databases. Idempotent. */
    static void ensure(Context c) throws IOException {
        for (File d : new File[]{datadir(c), new File(moddir(c), "bin"), new File(stubdir(c), "run"),
                new File(stubdir(c), "logs"), new File(stubdir(c), "proc")}) {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        }
        File lib = new File(c.getApplicationInfo().nativeLibraryDir);
        String[][] links = {{"xray", "libxray.so"}, {"xhuskydg_helper", "libxhuskydg_helper.so"}, {"curl", "libcurl.so"}};
        for (String[] l : links) {
            File target = new File(lib, l[1]);
            File link = bin(c, l[0]);
            if (!target.isFile()) throw new IOException("missing native binary: " + l[1]);
            try {
                String cur = null;
                try {
                    cur = Os.readlink(link.getPath());
                } catch (ErrnoException ignored) {
                }
                if (!target.getPath().equals(cur)) {
                    //noinspection ResultOfMethodCallIgnored
                    link.delete();
                    Os.symlink(target.getPath(), link.getPath());
                }
            } catch (ErrnoException e) {
                throw new IOException("symlink " + link + ": " + e.getMessage());
            }
        }
        installBusybox(c, lib);
        try (ZipFile zf = new ZipFile(c.getApplicationInfo().sourceDir)) {
            for (String g : new String[]{"geoip.dat", "geosite.dat"}) {
                ZipEntry e = zf.getEntry("bin/" + g);
                if (e == null) throw new IOException("not in APK: bin/" + g);
                File dst = bin(c, g);
                if (dst.isFile() && dst.length() == e.getSize()) continue;
                File tmp = new File(dst.getPath() + ".new");
                try (InputStream in = zf.getInputStream(e); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                if (!tmp.renameTo(dst)) throw new IOException("cannot replace " + dst);
            }
        }
    }

    /** Module dir the WebUI shell works against: the app's own in non-root mode, the root module otherwise. */
    static String activeModdir(Context c) {
        return RunMode.isNonRoot(c) ? moddir(c).getPath() : ModuleInstaller.MODDIR;
    }

    /**
     * BusyBox ships as libbusybox.so. Its applets are symlinked into the module's bin dir next to
     * xray and curl (files/module/bin in non-root mode, /data/adb/modules/magic_v2ray/bin in root
     * mode); the WebUI appends that directory to PATH (see KsuBridge), so Android's own tools
     * win and busybox only fills the gaps. An applet is invoked through its symlink, which is how
     * busybox picks the applet from argv[0]. Best effort: a failure here never blocks the VPN.
     */
    static void installBusybox(Context c, File lib) {
        if (RunMode.isNonRoot(c)) installBusyboxNonRoot(c, lib);
        else installBusyboxRoot(c, lib);
    }

    /**
     * Root mode: the module dir belongs to root, so the work is done by an su script. busybox is
     * copied into the module (like xray/curl, so it survives the app being removed) and the
     * applets are symlinked to that copy. Idempotent, stamped like the non-root variant.
     */
    private static void installBusyboxRoot(Context c, File lib) {
        File src = new File(lib, "libbusybox.so");
        if (!src.isFile() || src.length() == 0) return;
        String want = src.getPath() + "|" + src.length() + "|" + src.lastModified();
        String script =
                "SRC=" + RootShell.sq(src.getPath()) + "\n"
                        + "WANT=" + RootShell.sq(want) + "\n"
                        + "B=" + RootShell.sq(ModuleInstaller.MODDIR + "/bin") + "\n"
                        + "[ -d \"$B\" ] || exit 0\n"
                        + "umask 022\n"
                        + "if [ -x \"$B/busybox\" ] && [ \"$(cat \"$B/.busybox.stamp\" 2>/dev/null)\" = \"$WANT\" ]; then exit 0; fi\n"
                        // copy + rename: replacing a running binary fails with ETXTBSY, a rename does not
                        + "cp \"$SRC\" \"$B/busybox.new\" || exit 1\n"
                        + "chmod 0755 \"$B/busybox.new\" && chown 0:0 \"$B/busybox.new\" || exit 1\n"
                        + "chcon u:object_r:system_file:s0 \"$B/busybox.new\" 2>/dev/null\n"
                        + "mv -f \"$B/busybox.new\" \"$B/busybox\" || exit 1\n"
                        + "LIST=$(\"$B/busybox\" --list) || exit 1\n"
                        + "for a in $LIST; do\n"
                        // plain names only; never replace our own binaries
                        + "  case \"$a\" in busybox|xray|curl|xhuskydg_helper|'') continue ;; esac\n"
                        + "  case \"$a\" in *[!A-Za-z0-9._-]*) continue ;; esac\n"
                        + "  if [ -L \"$B/$a\" ]; then\n"
                        + "    [ \"$(readlink \"$B/$a\")\" = \"$B/busybox\" ] && continue\n"
                        + "    rm -f \"$B/$a\"\n"
                        + "  elif [ -e \"$B/$a\" ]; then continue; fi\n"      // a real file that is not ours
                        + "  ln -s \"$B/busybox\" \"$B/$a\" 2>/dev/null\n"
                        + "done\n"
                        + "printf '%s' \"$WANT\" > \"$B/.busybox.stamp\"\n"
                        + "exit 0\n";
        RootShell.run(script, 120_000);     // best effort: the result is ignored
    }

    /** Non-root mode: symlinks straight to libbusybox.so in the app's native lib dir. */
    private static void installBusyboxNonRoot(Context c, File lib) {
        File target = new File(lib, "libbusybox.so");
        if (!target.isFile()) return;
        File bin = new File(moddir(c), "bin");
        File stamp = new File(bin, ".busybox.stamp");
        String want = target.getPath() + "|" + target.length() + "|" + target.lastModified();
        try {
            if (stamp.isFile() && want.equals(new String(java.nio.file.Files.readAllBytes(stamp.toPath()),
                    StandardCharsets.UTF_8)) && new File(bin, "busybox").exists()) return;
        } catch (IOException ignored) {
        }
        try {
            Process p = new ProcessBuilder(target.getPath(), "--list").redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            try (InputStream in = p.getInputStream()) {
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            if (p.waitFor() != 0) return;
            List<String> names = new ArrayList<>();
            names.add("busybox");
            for (String a : bo.toString("UTF-8").split("\\s+")) {
                // plain names only; never replace our own binaries or shell metacharacter lookalikes
                if (a.matches("[A-Za-z0-9._-]+") && !a.equals("xray") && !a.equals("curl")
                        && !a.equals("xhuskydg_helper")) names.add(a);
            }
            for (String name : names) {
                File link = new File(bin, name);
                String cur = null;
                try {
                    cur = Os.readlink(link.getPath());
                } catch (ErrnoException ignored) {
                }
                if (target.getPath().equals(cur)) continue;
                if (cur == null && link.exists()) continue;     // a real file that is not ours: leave it
                //noinspection ResultOfMethodCallIgnored
                link.delete();
                try {
                    Os.symlink(target.getPath(), link.getPath());
                } catch (ErrnoException ignored) {
                }
            }
            try (FileOutputStream out = new FileOutputStream(stamp)) {
                out.write(want.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    /** settings.base64 = base64(JSON). Missing or unreadable -> empty object (defaults apply). */
    static JSONObject settings(Context c) {
        File f = new File(datadir(c), "settings.base64");
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            String raw = bo.toString("UTF-8").replaceAll("\\s+", "");
            byte[] dec = Base64.decode(raw, Base64.DEFAULT);
            return new JSONObject(new String(dec, StandardCharsets.UTF_8));
        } catch (IOException | JSONException | IllegalArgumentException e) {
            return new JSONObject();
        }
    }

    /** Packages of the Exclude Apps list, when that feature is switched on. */
    static List<String> excludedApps(Context c, JSONObject settings) {
        List<String> out = new ArrayList<>();
        if (!settings.optBoolean("excludeApps", false)) return out;
        File f = new File(datadir(c), "excludelist.txt");
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            for (String line : bo.toString("UTF-8").split("\n")) {
                String p = line.trim();
                if (p.matches("^[A-Za-z0-9_.]+$")) out.add(p);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** LAN ranges that skip the tunnel: all of them except the ones the user unticked. */
    static List<String> lanBypass(JSONObject settings) {
        Set<String> proxied = new HashSet<>();
        for (String s : settings.optString("lanProxyCidrs", "").split("\\s+")) if (!s.isEmpty()) proxied.add(s);
        List<String> out = new ArrayList<>();
        for (String cidr : LAN_CIDRS) if (!proxied.contains(cidr)) out.add(cidr);
        return out;
    }

    /**
     * The config the WebUI wrote targets the root engine: every outbound carries
     * sockopt.mark = 255 so the iptables rules can tell Xray's own packets apart. An
     * unprivileged process may not set SO_MARK (EPERM), and it is not needed here because the
     * app itself is excluded from the VPN. Writes a copy without those marks.
     */
    static File runtimeConfig(Context c, boolean blockIpv6) throws IOException {
        File src = config(c);
        try (FileInputStream in = new FileInputStream(src)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            Object root = new org.json.JSONTokener(bo.toString("UTF-8")).nextValue();
            stripMarks(root);
            if (blockIpv6 && root instanceof JSONObject) dropIpv6((JSONObject) root);
            File dst = new File(datadir(c), "config.nonroot.json");
            try (FileOutputStream out = new FileOutputStream(dst)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            return dst;
        } catch (JSONException e) {
            throw new IOException("config.v2.json is not valid JSON: " + e.getMessage());
        }
    }

    /**
     * "Enable IPv6" off: put a first routing rule that sends every IPv6 destination coming from
     * the tun to the "block" outbound (the root engine does the same with ip6tables).
     */
    private static void dropIpv6(JSONObject cfg) throws JSONException {
        JSONObject routing = cfg.optJSONObject("routing");
        if (routing == null) {
            routing = new JSONObject();
            cfg.put("routing", routing);
        }
        JSONArray rules = routing.optJSONArray("rules");
        JSONArray out = new JSONArray();
        JSONObject drop = new JSONObject();
        drop.put("type", "field");
        drop.put("inboundTag", new JSONArray().put("tun-in"));
        drop.put("ip", new JSONArray().put("::/0"));
        drop.put("outboundTag", "block");
        out.put(drop);
        if (rules != null) for (int i = 0; i < rules.length(); i++) out.put(rules.get(i));
        routing.put("rules", out);
    }

    private static void stripMarks(Object node) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject so = o.optJSONObject("sockopt");
            if (so != null) so.remove("mark");
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) stripMarks(o.get(names.getString(i)));
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) stripMarks(a.get(i));
        }
    }
}
