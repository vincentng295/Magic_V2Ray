package com.huskydg.magicv2ray;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Update check against {@link #UPDATE_URL}.
 *
 * The WebView is not allowed to touch the network (see MainActivity), so the request is made here, in the
 * app process, and only the parsed result goes back to the WebUI as JSON:
 *
 * <pre>
 * {"ok":true,"available":true,"current":{"name":"v3.2","code":199},
 *  "version":"v3.3","versionCode":201,"url":"https://...apk","universalUrl":"https://...apk",
 *  "changelog":"https://...","checkedAt":1700000000000}
 * {"ok":false,"error":"..."}
 * </pre>
 *
 * "Newer" is decided by versionCode only (the version string is for display).
 * The download is not done in the app: the WebUI hands the APK link to the browser (KsuBridge.openUrl).
 *
 * Auto check: on app start (at most once per {@link #MIN_INTERVAL_MS}) and by a daily {@link JobScheduler}
 * job ({@link UpdateJobService}) so a new version is announced even when the app is not opened.
 */
final class UpdateChecker {

    static final String UPDATE_URL = "https://vincentng295.github.io/magic-v2ray-site/update.json";

    /** Auto check is on until the user turns it off in App settings. */
    private static final boolean DEFAULT_AUTO = true;
    /** The start-up check is skipped when the last one is newer than this. */
    private static final long MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(12);
    private static final long JOB_PERIOD_MS = TimeUnit.HOURS.toMillis(24);
    private static final int JOB_ID = 0x4d56;           // "MV"
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BYTES = 64 * 1024;     // update.json is a few hundred bytes

    static final String CHANNEL = "mv_update";
    private static final int NOTIF_ID = 4;
    private static final int REQ_OPEN = 4;

    private static final String PREFS = "update_prefs";
    private static final String K_AUTO = "auto";
    private static final String K_LAST_CHECK = "last_check";
    private static final String K_LAST_RESULT = "last_result";
    private static final String K_NOTIFIED = "notified_code";

    private static final Object LOCK = new Object();
    private static boolean autoRunning;

    private UpdateChecker() {
    }

    private static SharedPreferences sp(Context c) {
        Context app = c.getApplicationContext();
        return (app != null ? app : c).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ settings

    static boolean autoEnabled(Context c) {
        return sp(c).getBoolean(K_AUTO, DEFAULT_AUTO);
    }

    static void setAutoEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_AUTO, on).apply();
        syncJob(c);
    }

    // ------------------------------------------------------------------ installed version

    @SuppressWarnings("deprecation")
    private static JSONObject current(Context c) {
        JSONObject o = new JSONObject();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            o.put("name", pi.versionName == null ? "" : pi.versionName);
            o.put("code", code);
        } catch (Exception e) {
            try {
                o.put("name", "").put("code", 0);
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    // ------------------------------------------------------------------ check

    /**
     * Blocking: downloads and evaluates update.json. Never throws; a failure is {"ok":false,"error":..}.
     * A good answer is cached so the WebUI can show it without a network round trip.
     */
    static JSONObject check(Context c) {
        JSONObject cur = current(c);
        JSONObject res = new JSONObject();
        try {
            String body = fetch(UPDATE_URL);
            JSONObject j = new JSONObject(body);
            int code = j.optInt("versionCode", 0);
            String version = j.optString("version", "").trim();
            if (code <= 0 || version.isEmpty()) throw new JSONException("update.json has no version");

            String universal = httpsOrEmpty(j.optString("zipUrl", ""));
            String url = universal;
            JSONObject arch = j.optJSONObject("arch");
            if (arch != null) {
                for (String abi : Build.SUPPORTED_ABIS) {       // most preferred ABI first
                    String u = httpsOrEmpty(arch.optString(abi, ""));
                    if (!u.isEmpty()) {
                        url = u;
                        break;
                    }
                }
            }
            res.put("ok", true);
            res.put("available", code > cur.optLong("code", 0));
            res.put("version", version);
            res.put("versionCode", code);
            res.put("url", url);
            res.put("universalUrl", universal);
            res.put("changelog", httpsOrEmpty(j.optString("changelog", "")));
            res.put("checkedAt", System.currentTimeMillis());
            res.put("current", cur);
            sp(c).edit()
                    .putLong(K_LAST_CHECK, System.currentTimeMillis())
                    .putString(K_LAST_RESULT, res.toString())
                    .apply();
        } catch (Exception e) {
            res = new JSONObject();
            try {
                res.put("ok", false);
                res.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                res.put("current", cur);
            } catch (JSONException ignored) {
            }
        }
        return res;
    }

    /** What the WebUI shows on open: the installed version plus the last good check (if any). */
    static String info(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONObject cur = current(c);
            out.put("current", cur);
            String last = sp(c).getString(K_LAST_RESULT, "");
            if (!last.isEmpty()) {
                JSONObject r = new JSONObject(last);
                // The app may have been updated since: re-evaluate against the installed version.
                r.put("available", r.optInt("versionCode", 0) > cur.optLong("code", 0));
                r.put("current", cur);
                out.put("last", r);
            }
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    private static String httpsOrEmpty(String u) {
        u = u == null ? "" : u.trim();
        return u.matches("(?i)^https://[^\\s'\"]+$") ? u : "";
    }

    private static String fetch(String address) throws IOException {
        URL url = new URL(address);
        // HttpURLConnection only follows same-protocol redirects on its own (and not reliably for 301/308),
        // so the redirects are followed by hand, https only, to a bounded depth.
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "MagicV2Ray-update-check");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.trim().isEmpty()) throw new IOException("HTTP " + code + " without Location");
                    URL next = new URL(url, loc.trim());    // Location may be relative
                    if (!"https".equalsIgnoreCase(next.getProtocol())) {
                        throw new IOException("redirect to non-https url refused");
                    }
                    url = next;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        if (out.size() + n > MAX_BYTES) throw new IOException("response too large");
                        out.write(b, 0, n);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    // ------------------------------------------------------------------ auto check

    /** App start: checks in the background when auto check is on and the last check is old enough. */
    static void maybeAutoCheck(Context c) {
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        syncJob(app);
        if (!autoEnabled(app)) return;
        long last = sp(app).getLong(K_LAST_CHECK, 0);
        long now = System.currentTimeMillis();
        if (last <= now && now - last < MIN_INTERVAL_MS) return;
        runAuto(app, null);
    }

    /** Runs one automatic check on a worker thread (one at a time) and notifies when there is news. */
    static void runAuto(final Context app, final Runnable done) {
        synchronized (LOCK) {
            if (autoRunning) {
                if (done != null) done.run();
                return;
            }
            autoRunning = true;
        }
        Thread t = new Thread(() -> {
            try {
                JSONObject r = check(app);
                if (r.optBoolean("ok") && r.optBoolean("available")) notifyAvailable(app, r);
                MainActivity.updateChanged();
            } finally {
                synchronized (LOCK) {
                    autoRunning = false;
                }
                if (done != null) done.run();
            }
        }, "mv-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Once per version, and only while the app is not on screen (the WebUI shows a banner then). */
    private static void notifyAvailable(Context c, JSONObject r) {
        int code = r.optInt("versionCode", 0);
        SharedPreferences p = sp(c);
        if (p.getInt(K_NOTIFIED, 0) >= code) return;
        if (MainActivity.resumed) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, c.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT));
            }
            PendingIntent open = PendingIntent.getActivity(c, REQ_OPEN,
                    new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL)
                    : new Notification.Builder(c);
            nm.notify(NOTIF_ID, nb.setContentTitle(c.getString(R.string.update_notif_title))
                    .setContentText(c.getString(R.string.update_notif_text, r.optString("version", "")))
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
            p.edit().putInt(K_NOTIFIED, code).apply();      // only after it was actually posted
        } catch (RuntimeException ignored) {    // notifications blocked: the in-app banner still shows it
        }
    }

    static void clearNotification(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    // ------------------------------------------------------------------ daily job

    /** Makes the periodic job match the setting. Safe to call often: an existing job keeps its schedule. */
    static void syncJob(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!autoEnabled(c)) {
                js.cancel(JOB_ID);
                return;
            }
            if (js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(JOB_PERIOD_MS)
                    .setPersisted(true)                 // needs RECEIVE_BOOT_COMPLETED (manifest)
                    .build());
        } catch (RuntimeException ignored) {    // a device that refuses the job: the start-up check still works
        }
    }
}