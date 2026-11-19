package com.huskydg.magicv2ray;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * User-managed geo databases (geoip.dat, geosite.dat and any extra *.dat that routing rules reach through
 * "ext:file.dat:tag"). They live in {@code <DATADIR>/location_assets}, NOT in the module's bin dir: the app
 * hash-checks the module dir against its APK and would put the bundled files back.
 *
 * xray reads the directory through XRAY_LOCATION_ASSET:
 *   root:     the service opens the directory as root and passes /proc/self/fd/N (see native xray.c)
 *   non-root: the plain path (see NonRootEnv#prepareAssets)
 *
 * geoip.dat / geosite.dat that the user has no file for are filled with a copy of the bundled ones, so
 * removing a user file brings a bundled copy back.
 */
final class GeoAssets {

    /** A bare file name, ending in .dat; also what the WebUI may pass as the target name. */
    static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.dat$");
    /** The real geoip.dat is ~20 MB; anything beyond this is not a routing database. */
    static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final long MIN_BYTES = 16;

    private GeoAssets() {
    }

    static String dir(Context c) {
        return RunMode.isNonRoot(c) ? NonRootEnv.assetsDir(c).getPath()
                : ModuleInstaller.DATADIR + "/location_assets";
    }

    /** Last path segment of a display name, reduced to the characters NAME_RE allows. */
    static String sanitize(String display) {
        if (display == null) return "";
        int slash = Math.max(display.lastIndexOf('/'), display.lastIndexOf('\\'));
        if (slash >= 0) display = display.substring(slash + 1);
        return display.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Streams {@code in} into the cache, checks it, and installs it as {@code name}. Returns an error text
     * (shown to the user) or null on success. The engine has to be restarted to load a new file.
     */
    static String install(Context c, InputStream in, String name) {
        if (name == null || !NAME_RE.matcher(name).matches()) {
            return "invalid file name (letters, digits, . _ - only, must end in .dat)";
        }
        File stage = new File(c.getCacheDir(), "geo_stage.dat");
        //noinspection ResultOfMethodCallIgnored
        stage.delete();
        try {
            long total = 0;
            int first = -1;
            try (FileOutputStream out = new FileOutputStream(stage)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) {
                    if (first < 0) first = b[0] & 0xFF;
                    total += n;
                    if (total > MAX_BYTES) return "file is too large (limit " + (MAX_BYTES >> 20) + " MB)";
                    out.write(b, 0, n);
                }
            }
            if (total < MIN_BYTES) return "file is empty or too small";
            // geoip.dat / geosite.dat are protobuf lists: field 1, length-delimited => first byte 0x0A.
            // An HTML error page, a zip or a text file fails this; a corrupt database would stop xray from starting.
            if (first != 0x0A) return "not a geoip/geosite database (unexpected file header)";
            return place(c, stage, name);
        } catch (IOException e) {
            return "cannot read the file: " + e.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            stage.delete();
        }
    }

    private static String place(Context c, File stage, String name) {
        if (RunMode.isNonRoot(c)) {
            File dir = NonRootEnv.assetsDir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) return "cannot create " + dir;
            File dst = new File(dir, name);
            File tmp = new File(dir, name + ".new");
            try (InputStream in = new java.io.FileInputStream(stage); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return "cannot write " + tmp + ": " + e.getMessage();
            }
            // rename(2) replaces a link to the bundled file instead of writing through it
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return "cannot replace " + dst;
            }
            return null;
        }
        String d = RootShell.sq(dir(c));
        String script =
                "umask 022\n"
                        + "D=" + d + "\n"
                        + "N=" + RootShell.sq(name) + "\n"
                        + "mkdir -p \"$D\" && chmod 755 \"$D\" && chown 0:0 \"$D\" || { echo 'cannot create location_assets' >&2; exit 1; }\n"
                        + "cp " + RootShell.sq(stage.getAbsolutePath()) + " \"$D/$N.new\" || { echo 'copy failed' >&2; exit 1; }\n"
                        + "chmod 644 \"$D/$N.new\" && chown 0:0 \"$D/$N.new\" || { rm -f \"$D/$N.new\"; echo 'chmod failed' >&2; exit 1; }\n"
                        + "chcon u:object_r:system_file:s0 \"$D/$N.new\" 2>/dev/null\n"
                        + "mv -f \"$D/$N.new\" \"$D/$N\" || { rm -f \"$D/$N.new\"; echo 'replace failed' >&2; exit 1; }\n"
                        + "exit 0\n";
        RootShell.Result r = RootShell.run(script, 120_000);
        if (r.ok()) return null;
        String e = r.err.trim();
        return e.isEmpty() ? "installer exited with code " + r.code : e;
    }
}
