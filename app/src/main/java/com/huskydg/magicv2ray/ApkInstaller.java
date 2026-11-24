package com.huskydg.magicv2ray;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * In-app update: downloads the new APK into the app cache (cacheDir/updates/update.apk) and installs it with
 * {@link PackageInstaller}, without opening a browser.
 *
 * The address is NOT taken from the WebUI: it is read from the last good update check (see
 * {@link UpdateChecker#apkUrls}), so the page cannot make the app download an arbitrary file.
 *
 * Flow (every step is reported to the WebUI through {@link MainActivity#apkEvent} as JSON
 * {"state": ..., ...}, handled by window.mvApkInstall in updatecheck.js):
 *   need_permission -> (user allows "install unknown apps", the app resumes by itself in onResume)
 *   downloading {percent, done, total} -> verifying -> installing -> confirm | done | error
 *
 * Before installing, the file is checked: it must be an APK of this package, not older than the installed
 * version, and signed by the same key (otherwise Android would refuse it with "signatures do not match").
 *
 * Android always asks the user to confirm an install unless the device is rooted / uses Shizuku. On Android 12+
 * the confirmation is skipped for updates when this app installed itself before (USER_ACTION_NOT_REQUIRED).
 */
final class ApkInstaller {

    static final String ACTION_RESULT = "com.huskydg.magicv2ray.action.APK_INSTALL_RESULT";

    private static final String DIR = "updates";
    private static final String FILE = "update.apk";
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long PROGRESS_EVERY_MS = 250;

    private static final Object LOCK = new Object();
    private static boolean busy;
    private static volatile boolean cancelled;
    /** The user was sent to the "install unknown apps" screen: continue when the app comes back. */
    private static volatile boolean awaitingPermission;

    private ApkInstaller() {
    }

    // ------------------------------------------------------------------ entry points

    /** Starts the download + install on a worker thread (one at a time). */
    static void start(final Context c) {
        final Context app = appContext(c);
        synchronized (LOCK) {
            if (busy) return;
            busy = true;
            cancelled = false;
        }
        Thread t = new Thread(() -> {
            try {
                run(app);
            } catch (Exception e) {
                emitError(cancelled ? "aborted" : "failed", message(e));
            } finally {
                synchronized (LOCK) {
                    busy = false;
                }
            }
        }, "mv-apk-update");
        t.setDaemon(true);
        t.start();
    }

    static void cancel() {
        cancelled = true;
        awaitingPermission = false;
    }

    /** MainActivity.onResume: the user came back from the permission screen, go on with the update. */
    static void resumeAfterPermission(Context c) {
        if (!awaitingPermission) return;
        Context app = appContext(c);
        if (!canInstall(app)) return;
        awaitingPermission = false;
        start(app);
    }

    /** App start: drop a leftover download once that version is installed (or the file is junk). */
    static void cleanupAsync(final Context c) {
        final Context app = appContext(c);
        Thread t = new Thread(() -> {
            try {
                cleanup(app);
            } catch (RuntimeException ignored) {
            }
        }, "mv-apk-cleanup");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ the flow

    private static void run(Context app) throws Exception {
        if (!canInstall(app)) {
            awaitingPermission = true;
            emit("need_permission", 0, 0, 0, null, null);
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return;
        }

        final String[] urls = UpdateChecker.apkUrls(app);
        if (urls.length == 0) throw new IOException("no download address, check for updates first");
        final long expectedCode = UpdateChecker.lastVersionCode(app);

        File dir = new File(app.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create cache folder");
        File apk = new File(dir, FILE);
        File part = new File(dir, FILE + ".part");

        // A file from an earlier try (e.g. the permission screen in between) is reused when it is the right one.
        if (apk.isFile() && verify(app, apk, expectedCode) == null) {
            emit("verifying", 100, apk.length(), apk.length(), null, null);
        } else {
            apk.delete();
            String lastError = "download failed";
            boolean ok = false;
            for (String u : mirrorsOf(urls)) {
                if (cancelled) throw new IOException("cancelled");
                try {
                    download(u, part, apk);
                    emit("verifying", 100, apk.length(), apk.length(), null, null);
                    String bad = verify(app, apk, expectedCode);
                    if (bad == null) {
                        ok = true;
                        break;
                    }
                    lastError = bad;
                    apk.delete();
                } catch (IOException e) {
                    if (cancelled) throw e;
                    lastError = hostOf(u) + ": " + message(e);
                    part.delete();
                    apk.delete();
                }
            }
            if (!ok) throw new IOException(lastError);
        }
        if (cancelled) throw new IOException("cancelled");

        emit("installing", 100, 0, 0, null, null);
        install(app, apk);
        // The rest arrives in ApkInstallReceiver (confirm dialog / success / failure).
    }

    // ------------------------------------------------------------------ download

    private static void download(String address, File part, File dest) throws IOException {
        part.delete();
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MagicV2Ray-updater");
            conn.setRequestProperty("Accept", "application/octet-stream, */*");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            final long total = conn.getContentLengthLong();
            if (total > MAX_BYTES) throw new IOException("file too large");

            long done = 0;
            long lastEmit = 0;
            int lastPercent = -1;
            emit("downloading", 0, 0, total, null, null);
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new IOException("cancelled");
                    done += n;
                    if (done > MAX_BYTES) throw new IOException("file too large");
                    out.write(buf, 0, n);
                    long now = System.currentTimeMillis();
                    int percent = total > 0 ? (int) (done * 100 / total) : -1;
                    if (now - lastEmit >= PROGRESS_EVERY_MS && percent != lastPercent) {
                        lastEmit = now;
                        lastPercent = percent;
                        emit("downloading", Math.max(percent, 0), done, total, null, null);
                    }
                }
                out.flush();
            }
            if (total > 0 && done != total) throw new IOException("incomplete download");
            if (done == 0) throw new IOException("empty download");
            if (!part.renameTo(dest)) throw new IOException("cannot store the download");
        } finally {
            conn.disconnect();
        }
    }

    /** Each address, then the same file through the proxy mirror when it is hosted on GitHub (blocked in some countries). */
    private static List<String> mirrorsOf(String[] urls) {
        List<String> out = new ArrayList<>();
        for (String u : urls) {
            if (!out.contains(u)) out.add(u);
        }
        for (String u : urls) {
            String host = hostOf(u);
            if (host.equals("github.com") || host.endsWith(".githubusercontent.com")) {
                String m = "https://gh-proxy.com/" + u;
                if (!out.contains(m)) out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ verify

    /** Returns null when the file may be installed, else the reason. */
    @SuppressWarnings("deprecation")
    private static String verify(Context c, File f, long expectedCode) {
        try {
            PackageManager pm = c.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo ai = pm.getPackageArchiveInfo(f.getAbsolutePath(), flags);
            if (ai == null) return "the downloaded file is not a valid APK";
            if (!c.getPackageName().equals(ai.packageName)) return "the APK is for another app (" + ai.packageName + ")";
            PackageInfo me = pm.getPackageInfo(c.getPackageName(), flags);
            long code = codeOf(ai);
            if (code < codeOf(me)) return "the APK is older than the installed version";
            if (expectedCode > 0 && code != expectedCode) return "the APK version (" + code + ") is not the announced one (" + expectedCode + ")";
            if (!sameSigner(ai, me)) return "the APK is signed with a different key than the installed app";
            return null;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "cannot verify the APK: " + message(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static long codeOf(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static boolean sameSigner(PackageInfo apk, PackageInfo installed) {
        Set<String> a = new HashSet<>();
        Set<String> b = new HashSet<>();
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo sa = apk.signingInfo;
            SigningInfo sb = installed.signingInfo;
            if (sa == null || sb == null) return false;
            // the new APK's key history (key rotation) against the key the installed app is signed with now
            add(a, sa.getSigningCertificateHistory());
            add(a, sa.getApkContentsSigners());
            add(b, sb.getApkContentsSigners());
            for (String s : b) {
                if (a.contains(s)) return true;
            }
            return false;
        }
        add(a, apk.signatures);
        add(b, installed.signatures);
        return !a.isEmpty() && a.equals(b);
    }

    private static void add(Set<String> into, Signature[] sigs) {
        if (sigs == null) return;
        for (Signature s : sigs) into.add(s.toCharsString());
    }

    // ------------------------------------------------------------------ install

    private static void install(Context app, File apk) throws IOException {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(app.getPackageName());
        p.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        PackageInstaller.Session s = null;
        try {
            s = pi.openSession(id);
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(app, ApkInstallReceiver.class).setAction(ACTION_RESULT);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pe = PendingIntent.getBroadcast(app, id, i, fl);
            s.commit(pe.getIntentSender());
        } catch (IOException | RuntimeException e) {
            if (s != null) {
                try {
                    s.abandon();
                } catch (RuntimeException ignored) {
                }
            }
            throw e;
        } finally {
            if (s != null) s.close();
        }
    }

    // ------------------------------------------------------------------ cleanup

    private static void cleanup(Context app) {
        File dir = new File(app.getCacheDir(), DIR);
        File[] files = dir.listFiles();
        if (files == null) return;
        synchronized (LOCK) {
            if (busy) return;
        }
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (f.getName().endsWith(".part")) {
                if (now - f.lastModified() > 24L * 3600 * 1000) f.delete();
                continue;
            }
            PackageManager pm = app.getPackageManager();
            PackageInfo ai = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            try {
                PackageInfo me = pm.getPackageInfo(app.getPackageName(), 0);
                boolean done = ai == null
                        || codeOf(ai) < codeOf(me)
                        || (codeOf(ai) == codeOf(me) && String.valueOf(ai.versionName).equals(String.valueOf(me.versionName)));
                if (done) f.delete();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    private static Context appContext(Context c) {
        Context app = c.getApplicationContext();
        return app != null ? app : c;
    }

    private static String hostOf(String a) {
        try {
            return new URL(a).getHost();
        } catch (IOException e) {
            return a;
        }
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    static void emitError(String code, String error) {
        emit("error", 0, 0, 0, code, error);
    }

    static void emit(String state, int percent, long done, long total, String code, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("percent", percent);
            o.put("done", done);
            o.put("total", total);
            if (code != null) o.put("code", code);
            if (error != null) o.put("error", error);
            MainActivity.apkEvent(o.toString());
        } catch (JSONException ignored) {
        }
    }
}
