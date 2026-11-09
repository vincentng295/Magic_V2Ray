package com.huskydg.magicv2ray;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

/**
 * Checks root and makes sure the Magic V2Ray module is present and current in
 * /data/adb/modules/magic_v2ray.
 *
 * Payload layout inside the APK (zip root, same files a flashed module zip uses):
 *   {service.sh, proxy_control.sh, uninstall.sh, action.sh, module.prop}
 *   bin/{geoip.dat, geosite.dat}
 *   lib/<abi>/{libxray.so, libxhuskydg_helper.so, libcurl.so}   (real executables,
 *       extracted to nativeLibraryDir at install; installed as bin/xray etc.)
 *
 * Besides the payload, the app copies its own APK to $MODDIR/app.apk, so the
 * module's Action button (action.sh) can reinstall the app if it gets removed.
 *
 * The WebUI is not part of the module any more; it is the app itself.
 */
final class ModuleInstaller {

    static final String MODDIR = "/data/adb/modules/magic_v2ray";
    /** Where Magisk / KernelSU / APatch stage a module update that is applied at the next boot. */
    static final String UPDATE_DIR = "/data/adb/modules_update/magic_v2ray";
    static final String DATADIR = "/data/adb/magic_v2ray";

    /**
     * First versionCode whose service understands "stop_svc" (xhuskydg_helper service --stop).
     * From there on an update is applied live: the old service is stopped cleanly and the new
     * one started, no reboot. Older builds can only be replaced through modules_update + reboot.
     */
    static final int DYNAMIC_UPDATE_MIN_VC = 176;

    enum Status {OK, NO_ROOT, FAILED}

    static final class Outcome {
        final Status status;
        final String detail;
        final boolean installed;          // files were (re)written this run
        final boolean rebootRecommended;  // engine was already running an older build

        Outcome(Status s, String detail, boolean installed, boolean reboot) {
            this.status = s;
            this.detail = detail;
            this.installed = installed;
            this.rebootRecommended = reboot;
        }
    }

    interface Progress {
        void step(int stringRes);
    }

    private static final String[] TEXT_ASSETS = {
            "service.sh", "proxy_control.sh", "uninstall.sh", "action.sh", "module.prop"};
    private static final String[] DATA_ASSETS = {"bin/geoip.dat", "bin/geosite.dat"};
    // lib file in nativeLibraryDir -> path inside the module
    private static final String[][] NATIVE_BINS = {
            {"libxray.so", "bin/xray"},
            {"libxhuskydg_helper.so", "bin/xhuskydg_helper"},
            {"libcurl.so", "bin/curl"}};

    private ModuleInstaller() {
    }

    static Outcome ensureInstalled(Context ctx, Progress progress) {
        RootShell.init(ctx);

        progress.step(R.string.setup_checking_root);
        if (!RootShell.hasRoot()) {
            return new Outcome(Status.NO_ROOT, "", false, false);
        }

        progress.step(R.string.setup_checking_module);
        String bundled;
        try {
            bundled = readEntry(ctx, "module.prop");
        } catch (IOException e) {
            return new Outcome(Status.FAILED, "bundled module.prop missing: " + e.getMessage(), false, false);
        }
        int bundledVc = versionCode(bundled);
        String bundledVer = prop(bundled, "version");

        RootShell.Result probe = RootShell.run(
                "M=" + RootShell.sq(MODDIR) + "\n"
                        + "if [ -s \"$M/module.prop\" ]; then\n"
                        + "  echo \"VC=$(sed -n 's/^versionCode=//p' \"$M/module.prop\" | head -n 1 | tr -d '\\r')\"\n"
                        + "  echo \"VER=$(sed -n 's/^version=//p' \"$M/module.prop\" | head -n 1 | tr -d '\\r')\"\n"
                        + "fi\n"
                        + "if [ -s \"$M/module.prop\" ] && [ -x \"$M/bin/xray\" ] && [ -x \"$M/bin/xhuskydg_helper\" ] \\\n"
                        + "   && [ -x \"$M/bin/curl\" ] && [ -s \"$M/bin/geoip.dat\" ] && [ -s \"$M/bin/geosite.dat\" ] \\\n"
                        + "   && [ -x \"$M/service.sh\" ] && [ -x \"$M/proxy_control.sh\" ] && [ -s \"$M/app.apk\" ]; then\n"
                        + "  :\n"
                        + "else echo MISSING; fi\n"
                        + "[ -p /dev/sysctl_stubs/run/control.pipe ] && echo SVC=1 || echo SVC=0\n",
                30_000);
        if (!probe.ok()) {
            return new Outcome(Status.FAILED, probe.err.trim(), false, false);
        }

        boolean missing = probe.out.contains("MISSING");
        boolean serviceUp = probe.out.contains("SVC=1");
        int installedVc = -1;
        String installedVer = "";
        for (String line : probe.out.split("\n")) {
            if (line.startsWith("VC=")) installedVc = parseInt(line.substring(3));
            else if (line.startsWith("VER=")) installedVer = line.substring(4).trim();
        }

        boolean need = missing
                || installedVc < bundledVc
                || (installedVc == bundledVc && !installedVer.equals(bundledVer));

        // An installed module that predates dynamic updates (<= 175) cannot be stopped live,
        // so its replacement is staged for the root manager and applied by a reboot.
        boolean legacy = installedVc >= 0 && installedVc < DYNAMIC_UPDATE_MIN_VC;

        if (need && legacy) {
            progress.step(R.string.setup_installing);
            String err = install(ctx, UPDATE_DIR, true);
            if (err != null) return new Outcome(Status.FAILED, err, false, false);
            return new Outcome(Status.OK, "", true, true);
        }

        boolean installed = false;
        if (need) {
            progress.step(R.string.setup_installing);
            String err = install(ctx, MODDIR, false);
            if (err != null) return new Outcome(Status.FAILED, err, false, false);
            installed = true;
        }

        // busybox applets for the WebUI shell (idempotent; skipped when already current)
        progress.step(R.string.setup_installing);
        NonRootEnv.installBusybox(ctx, new File(ctx.getApplicationInfo().nativeLibraryDir));

        boolean reboot = false;
        if (!serviceUp) {
            progress.step(R.string.setup_starting);
            startService();
        } else if (installed) {
            // Dynamic update: stop the running service (it tears down xray, routing rules and
            // its mounts), then start the one from the freshly installed files.
            progress.step(R.string.setup_starting);
            reboot = !restartService();
        }
        return new Outcome(Status.OK, "", installed, reboot);
    }

    /**
     * Stages the payload in the app cache, then installs it as root into {@code target}
     * (the live module dir, or modules_update when {@code stageForReboot}). In the latter case
     * the live module is flagged with "update" so the root manager swaps it in at boot.
     * Returns an error text or null.
     */
    private static String install(Context ctx, String target, boolean stageForReboot) {
        File stage = new File(ctx.getCacheDir(), "mv_stage");
        deleteRecursive(stage);
        try {
            for (String f : TEXT_ASSETS) copyEntry(ctx, f, new File(stage, f));
            for (String f : DATA_ASSETS) copyEntry(ctx, f, new File(stage, f));
            File libDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
            for (String[] b : NATIVE_BINS) {
                File src = new File(libDir, b[0]);
                if (!src.isFile() || src.length() == 0) {
                    return "missing native binary for this CPU: " + b[0]
                            + " (supported ABIs: arm64-v8a, x86_64)";
                }
                copyFile(src, new File(stage, b[1]));
            }
        } catch (IOException e) {
            deleteRecursive(stage);
            return "cannot stage payload: " + e.getMessage();
        }

        String script =
                "STAGE=" + RootShell.sq(stage.getAbsolutePath()) + "\n"
                        + "MOD=" + RootShell.sq(target) + "\n"
                        + "DATA=" + RootShell.sq(DATADIR) + "\n"
                        + "APK=" + RootShell.sq(ctx.getApplicationInfo().sourceDir) + "\n"
                        + "umask 022\n"
                        + "fail() { echo \"install failed at: $1\" >&2; exit 1; }\n"
                        + "mkdir -p \"$MOD/bin\" || fail mkdir-module\n"
                        + "mkdir -p \"$DATA\" && chmod 700 \"$DATA\" && chown 0:0 \"$DATA\" || fail datadir\n"
                        // Copy to a temp name and rename: replacing a binary that is currently
                        // executing fails with ETXTBSY, a rename over it does not.
                        + "put() {\n"
                        + "  cp \"${3:-$STAGE/$1}\" \"$MOD/$1.new\" || return 1\n"
                        + "  chmod \"$2\" \"$MOD/$1.new\" && chown 0:0 \"$MOD/$1.new\" || return 1\n"
                        + "  chcon u:object_r:system_file:s0 \"$MOD/$1.new\" 2>/dev/null\n"
                        + "  mv -f \"$MOD/$1.new\" \"$MOD/$1\"\n"
                        + "}\n"
                        + "put bin/xray 0755 || fail bin/xray\n"
                        + "put bin/xhuskydg_helper 0755 || fail bin/xhuskydg_helper\n"
                        + "put bin/curl 0755 || fail bin/curl\n"
                        + "put bin/geoip.dat 0644 || fail bin/geoip.dat\n"
                        + "put bin/geosite.dat 0644 || fail bin/geosite.dat\n"
                        + "put service.sh 0755 || fail service.sh\n"
                        + "put proxy_control.sh 0755 || fail proxy_control.sh\n"
                        + "put uninstall.sh 0755 || fail uninstall.sh\n"
                        + "put action.sh 0755 || fail action.sh\n"
                        // The app's own APK, used by action.sh to (re)install the app.
                        + "put app.apk 0644 \"$APK\" || fail app.apk\n"
                        // module.prop last: a half-written install never looks complete.
                        + "put module.prop 0644 || fail module.prop\n"
                        // Old zip installs shipped a webroot; the UI is the app now.
                        + "rm -rf \"$MOD/webroot\" \"$MOD/remove\"\n"
                        + "chown 0:0 \"$MOD\" \"$MOD/bin\"; chmod 0755 \"$MOD\" \"$MOD/bin\"\n"
                        + "chcon u:object_r:system_file:s0 \"$MOD\" \"$MOD/bin\" 2>/dev/null\n"
                        + "for f in profiles.base64 settings.base64 active_config.txt config.json ip_hunt.list; do\n"
                        + "  [ -f \"$DATA/$f\" ] && chmod 600 \"$DATA/$f\"\n"
                        + "done\n"
                        + (stageForReboot
                        ? "mkdir -p " + RootShell.sq(MODDIR) + " && touch " + RootShell.sq(MODDIR + "/update") + " || fail update-flag\n"
                        : "")
                        + "exit 0\n";
        RootShell.Result r = RootShell.run(script, 300_000);
        deleteRecursive(stage);
        if (!r.ok()) {
            String e = r.err.trim();
            return e.isEmpty() ? "installer exited with code " + r.code : e;
        }
        return null;
    }

    /**
     * Magisk/KernelSU/APatch start service.sh at boot. After a fresh install there
     * has been no boot yet, so launch it now (the helper daemonizes itself).
     * Best effort: if it does not come up, the next reboot starts it.
     */
    private static void startService() {
        RootShell.run(
                "M=" + RootShell.sq(MODDIR) + "\n"
                        + "[ -p /dev/sysctl_stubs/run/control.pipe ] && exit 0\n"
                        + "[ -x \"$M/service.sh\" ] || exit 1\n"
                        + "( sh \"$M/service.sh\" </dev/null >/dev/null 2>&1 & )\n"
                        + "sleep 2\n"
                        + "exit 0\n",
                30_000);
    }

    /**
     * Dynamic update: asks the running service to stop through its control pipe
     * ({@code xhuskydg_helper service --stop} writes stop_svc and waits until it is gone), then
     * starts the new one. Returns false when the old service could not be stopped.
     */
    private static boolean restartService() {
        RootShell.Result r = RootShell.run(
                "M=" + RootShell.sq(MODDIR) + "\n"
                        + "\"$M/bin/xhuskydg_helper\" service --stop >/dev/null 2>&1\n"
                        + "[ -p /dev/sysctl_stubs/run/control.pipe ] && { echo STILL_UP; exit 0; }\n"
                        + "( sh \"$M/service.sh\" </dev/null >/dev/null 2>&1 & )\n"
                        + "sleep 2\n"
                        + "[ -p /dev/sysctl_stubs/run/control.pipe ] && echo STARTED || echo NOT_STARTED\n"
                        + "exit 0\n",
                60_000);
        return r.ok() && r.out.contains("STARTED") && !r.out.contains("NOT_STARTED");
    }

    // ------------------------------------------------------------------ helpers

    private static String readEntry(Context ctx, String name) throws IOException {
        try (ZipFile zf = new ZipFile(ctx.getApplicationInfo().sourceDir)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) throw new IOException("not in APK: " + name);
            try (InputStream in = zf.getInputStream(e)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void copyEntry(Context ctx, String name, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (ZipFile zf = new ZipFile(ctx.getApplicationInfo().sourceDir)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) throw new IOException("not in APK: " + name);
            try (InputStream in = zf.getInputStream(e); OutputStream out = new FileOutputStream(dst)) {
                pump(in, out);
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            pump(in, out);
        }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String prop(String text, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(.*)$").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static int versionCode(String text) {
        return parseInt(prop(text, "versionCode"));
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
