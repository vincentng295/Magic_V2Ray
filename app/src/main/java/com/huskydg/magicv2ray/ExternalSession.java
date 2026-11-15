package com.huskydg.magicv2ray;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Control from other apps and websites: {@code magicv2ray://connect?uri=..&name=..&exclude=..},
 * {@code magicv2ray://disconnect}, and the same through an explicit intent / broadcast.
 *
 * <p>A connect request is only ever a <em>request</em>: it is parked here, handed to the WebUI
 * (which owns config generation and shows the confirmation) and never acted on by itself. The
 * node of an external session is temporary: the user's profiles and selected node are not touched.
 * Only the generated config.v2.json is swapped (the previous one is kept in config.base.json), so
 * ending the session, from anywhere, puts the previous node back without needing the WebUI.
 *
 * <p>Who called: Android does not tell a website from the browser that opened it, so a caller is
 * only "verified" when the system gave us its package (the activity referrer, never the
 * spoofable EXTRA_REFERRER) and it is not a browser. The name shown for a session is for display
 * only, nothing is trusted because of it.
 */
final class ExternalSession {

    static final String ACTION_CONNECT = "com.huskydg.magicv2ray.action.CONNECT";
    static final String ACTION_DISCONNECT = "com.huskydg.magicv2ray.action.DISCONNECT";
    /** Our own notification: "an app wants to connect, tap to review" (the request is already parked). */
    static final String ACTION_REVIEW = "com.huskydg.magicv2ray.action.REVIEW";

    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_EXCLUDE = "exclude";

    static final String BASE_FILE = "config.base.json";
    static final String SESSION_EXCLUDE_FILE = "excludelist.session.txt";

    private static final String PREFS = "ext_session";
    private static final String TRUST_PREFS = "ext_trust";
    private static final String K_SESSION = "session";
    private static final String K_PENDING = "pending";
    private static final long PENDING_TTL_MS = 10 * 60_000L;
    private static final long MIN_AGE_BEFORE_ABANDON_MS = 15_000L;
    private static final int MAX_URI = 8192;
    private static final int MAX_NAME = 60;
    private static final int MAX_EXCLUDE = 8;
    private static final Pattern PKG_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final String REVIEW_CHANNEL = "mv_ext";
    private static final int REVIEW_NOTIF_ID = 2;

    private static final Object LOCK = new Object();
    /** Caller of the request the WebUI took last, if that caller may be trusted ("always trust this app"). */
    private static volatile String lastTakenTrustable;

    private ExternalSession() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // =============================================================== who is calling

    /**
     * Package of the app that started {@code a} with {@code i}, or null when unknown. The system's
     * referrer is only used when the intent carries no EXTRA_REFERRER(_NAME): those override
     * Activity.getReferrer() and any caller can set them.
     */
    static String callerOf(Activity a, Intent i) {
        if (i == null || i.hasExtra(Intent.EXTRA_REFERRER) || i.hasExtra(Intent.EXTRA_REFERRER_NAME)) return null;
        Uri r = a.getReferrer();
        if (r == null || !"android-app".equals(r.getScheme())) return null;
        String pkg = r.getAuthority();
        if (pkg == null || pkg.isEmpty() || pkg.equals(a.getPackageName())) return null;
        return pkg;
    }

    static String labelOf(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    /** Browsers (and anything else that opens web links) cannot say which page asked. */
    static boolean isBrowser(Context c, String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    // =============================================================== requests

    /**
     * The request carried by {@code i}, as JSON for the WebUI, or null when it is not a connect
     * request (a disconnect is returned as {"action":"disconnect"}).
     *
     * @param caller   verified package of the caller (see {@link #callerOf}), or null
     * @param broadcast the intent came through the broadcast receiver, not an activity start
     */
    static JSONObject request(Context c, Intent i, String caller, boolean broadcast) {
        if (i == null) return null;
        String action = i.getAction();
        String source;
        String uri;
        String name;
        String exclude;
        boolean connect;
        Uri d = i.getData();
        if (Intent.ACTION_VIEW.equals(action) && d != null && "magicv2ray".equals(d.getScheme())
                && ("connect".equals(d.getHost()) || "disconnect".equals(d.getHost()))) {
            source = "deeplink";
            connect = "connect".equals(d.getHost());
            uri = d.getQueryParameter(EXTRA_URI);
            name = d.getQueryParameter(EXTRA_NAME);
            exclude = d.getQueryParameter(EXTRA_EXCLUDE);
        } else if (ACTION_CONNECT.equals(action) || ACTION_DISCONNECT.equals(action)) {
            source = broadcast ? "broadcast" : "intent";
            connect = ACTION_CONNECT.equals(action);
            uri = i.getStringExtra(EXTRA_URI);
            name = i.getStringExtra(EXTRA_NAME);
            exclude = excludeExtra(i);
        } else {
            return null;
        }
        try {
            JSONObject o = new JSONObject();
            if (!connect) return o.put("action", "disconnect").put("source", source);
            uri = uri == null ? "" : uri.trim();
            if (uri.isEmpty() || uri.length() > MAX_URI || !uri.matches("^[A-Za-z][A-Za-z0-9+.-]*://\\S+$")) return null;
            boolean browser = caller != null && isBrowser(c, caller);
            // Trust is only offered for calls that app code made on purpose (an explicit action):
            // a link can come from anywhere (a chat message, a web page), whichever app opened it.
            boolean trustable = !"deeplink".equals(source) && caller != null && !browser;
            o.put("action", "connect")
                    .put("uri", uri)
                    .put("name", clean(name, MAX_NAME))
                    .put("exclude", new JSONArray(parseExclude(exclude)))
                    .put("source", source)
                    .put("caller", caller == null ? "" : caller)
                    .put("callerLabel", caller == null ? "" : labelOf(c, caller))
                    .put("browser", browser)
                    .put("trustable", trustable)
                    .put("trusted", trustable && isTrusted(c, caller))
                    .put("ts", System.currentTimeMillis());
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    private static String excludeExtra(Intent i) {
        String[] arr = i.getStringArrayExtra(EXTRA_EXCLUDE);
        if (arr != null) return String.join(",", arr);
        return i.getStringExtra(EXTRA_EXCLUDE);
    }

    private static List<String> parseExclude(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split("[,\\s;]+")) {
            if (PKG_RE.matcher(s).matches() && s.length() <= 200 && !out.contains(s)) out.add(s);
            if (out.size() >= MAX_EXCLUDE) break;
        }
        return out;
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ---- parking a request until the WebUI takes it

    static void queue(Context c, JSONObject req) {
        sp(c).edit().putString(K_PENDING, req.toString()).apply();
    }

    /** The parked request as JSON ("" if none or expired); returns it once. */
    static String take(Context c) {
        synchronized (LOCK) {
            String s = sp(c).getString(K_PENDING, "");
            sp(c).edit().remove(K_PENDING).apply();
            lastTakenTrustable = null;
            if (s == null || s.isEmpty()) return "";
            try {
                JSONObject o = new JSONObject(s);
                if (System.currentTimeMillis() - o.optLong("ts") > PENDING_TTL_MS) return "";
                if (o.optBoolean("trustable")) lastTakenTrustable = o.optString("caller");
            } catch (JSONException e) {
                return "";
            }
            return s;
        }
    }

    /** Posts "app X wants to connect, tap to review" for requests that arrive while nothing is on screen. */
    static void notifyPending(Context c, JSONObject req) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        REVIEW_CHANNEL, c.getString(R.string.ext_review_channel), NotificationManager.IMPORTANCE_HIGH));
            }
            String who = req.optString("callerLabel", "");
            if (who.isEmpty()) who = c.getString(R.string.ext_unknown_app);
            String name = req.optString("name", "");
            if (name.isEmpty()) name = c.getString(R.string.ext_unnamed);
            PendingIntent open = PendingIntent.getActivity(c, 2,
                    new Intent(c, MainActivity.class).setAction(ACTION_REVIEW)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, REVIEW_CHANNEL)
                    : new Notification.Builder(c);
            nm.notify(REVIEW_NOTIF_ID, nb.setContentTitle(c.getString(R.string.ext_review_title))
                    .setContentText(c.getString(R.string.ext_review_text, who, name))
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build());
        } catch (RuntimeException ignored) {    // notifications blocked: the request still waits for the next app launch
        }
    }

    static void clearPendingNotification(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(REVIEW_NOTIF_ID);
    }

    // =============================================================== trusted apps

    /** SHA-256 of the app's signing certificate(s): trust follows the app, not just the package name. */
    @SuppressWarnings("deprecation")
    static String signature(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pi.signingInfo;
                sigs = si == null ? null : si.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) return "";
            List<String> hashes = new ArrayList<>();
            for (Signature s : sigs) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : h) sb.append(String.format("%02x", b));
                hashes.add(sb.toString());
            }
            Collections.sort(hashes);
            return String.join(",", hashes);
        } catch (Exception e) {
            return "";
        }
    }

    private static SharedPreferences trustSp(Context c) {
        return c.getApplicationContext().getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE);
    }

    static boolean isTrusted(Context c, String pkg) {
        String v = trustSp(c).getString(pkg, null);
        if (v == null) return false;
        try {
            String sig = new JSONObject(v).optString("sig", "");
            return !sig.isEmpty() && sig.equals(signature(c, pkg));     // reinstalled by someone else: not trusted
        } catch (JSONException e) {
            return false;
        }
    }

    /** Only the caller of the request the WebUI took last may be added, and only when it was trustable. */
    static boolean trust(Context c, String pkg) {
        if (pkg == null || !pkg.equals(lastTakenTrustable)) return false;
        String sig = signature(c, pkg);
        if (sig.isEmpty()) return false;
        try {
            trustSp(c).edit().putString(pkg, new JSONObject().put("sig", sig)
                    .put("since", System.currentTimeMillis()).toString()).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    static void untrust(Context c, String pkg) {
        if (pkg != null) trustSp(c).edit().remove(pkg).apply();
    }

    /** [{pkg,label,since,installed}] for the "Access" tab; entries of uninstalled apps are kept until revoked. */
    static JSONArray trustedJson(Context c) {
        JSONArray out = new JSONArray();
        for (java.util.Map.Entry<String, ?> e : trustSp(c).getAll().entrySet()) {
            try {
                JSONObject v = new JSONObject(String.valueOf(e.getValue()));
                boolean installed;
                try {
                    c.getPackageManager().getPackageInfo(e.getKey(), 0);
                    installed = true;
                } catch (PackageManager.NameNotFoundException nf) {
                    installed = false;
                }
                out.put(new JSONObject().put("pkg", e.getKey())
                        .put("label", installed ? labelOf(c, e.getKey()) : e.getKey())
                        .put("since", v.optLong("since"))
                        .put("installed", installed));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    // =============================================================== the session

    private static JSONObject read(Context c) {
        String s = sp(c).getString(K_SESSION, "");
        if (s == null || s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean isActive(Context c) {
        return read(c) != null;
    }

    /** Display name of the running external session, "" when there is none. */
    static String name(Context c) {
        JSONObject s = read(c);
        return s == null ? "" : s.optString("name", "");
    }

    static String callerLabel(Context c) {
        JSONObject s = read(c);
        return s == null ? "" : s.optString("callerLabel", "");
    }

    /** {"active":false} or {"active":true,name,uri,caller,callerLabel,exclude,wasRunning,startedAt}. */
    static JSONObject state(Context c) {
        JSONObject s = read(c);
        try {
            return s == null ? new JSONObject().put("active", false) : new JSONObject(s.toString()).put("active", true);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** The WebUI has written the external config (and the base copy): remember the session. */
    static boolean begin(Context c, String json) {
        try {
            JSONObject in = new JSONObject(json);
            JSONObject prev = read(c);
            JSONObject s = new JSONObject()
                    .put("name", clean(in.optString("name"), MAX_NAME))
                    .put("uri", in.optString("uri"))
                    .put("caller", in.optString("caller"))
                    .put("callerLabel", clean(in.optString("callerLabel"), MAX_NAME))
                    .put("exclude", in.optJSONArray("exclude") == null ? new JSONArray() : in.getJSONArray("exclude"))
                    // a second connect while one is active keeps what the first one remembered
                    .put("wasRunning", prev != null ? prev.optBoolean("wasRunning") : in.optBoolean("wasRunning"))
                    .put("startedAt", System.currentTimeMillis());
            if (s.optString("uri").isEmpty()) return false;
            sp(c).edit().putString(K_SESSION, s.toString()).apply();
            clearPendingNotification(c);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    static String dataDir(Context c) {
        return RunMode.isNonRoot(c) ? NonRootEnv.datadir(c).getPath() : ModuleInstaller.DATADIR;
    }

    /** Previous config back in place, session exclusions gone. Same shell in both modes. */
    private static void restoreFiles(Context c) {
        RootShell.init(c);
        String d = dataDir(c);
        RootShell.run("D=" + RootShell.sq(d) + "\n"
                + "if [ -f \"$D/" + BASE_FILE + "\" ]; then cp -f \"$D/" + BASE_FILE + "\" \"$D/config.v2.json\" && chmod 600 \"$D/config.v2.json\"; fi\n"
                + "rm -f \"$D/" + BASE_FILE + "\" \"$D/" + SESSION_EXCLUDE_FILE + "\"\n", 30_000);
    }

    /**
     * Ends the session and goes back to the node that was selected before it: the engine is
     * restarted on the old config when it was running before, and stopped when it was not.
     * Blocks, call it from a worker thread.
     */
    static void end(Context c) {
        Context app = c.getApplicationContext();
        JSONObject s;
        synchronized (LOCK) {
            s = read(app);
            if (s == null) return;
            sp(app).edit().remove(K_SESSION).apply();
        }
        restoreFiles(app);
        if (s.optBoolean("wasRunning")) {
            if (RunMode.isNonRoot(app)) {
                VpnController.handle(app, null, "restart");
            } else {
                RootShell.run("sh " + RootShell.sq(ModuleInstaller.MODDIR + "/proxy_control.sh") + " restart 2>&1", 60_000);
                ProxyNotifier.showRoot(app, true);
            }
        } else {
            ProxyNotifier.stopEngine(app);
        }
        pingUi(app);
    }

    /**
     * The session outlived its engine (reboot, crash, stopped some other way): drop it and put the
     * old config back without touching the engine. Too young a session is left alone, its engine
     * may still be starting.
     */
    static boolean abandon(Context c) {
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            JSONObject s = read(app);
            if (s == null) return false;
            if (System.currentTimeMillis() - s.optLong("startedAt") < MIN_AGE_BEFORE_ABANDON_MS) return false;
            sp(app).edit().remove(K_SESSION).apply();
        }
        restoreFiles(app);
        pingUi(app);
        return true;
    }

    /** Lets a live WebUI refresh its status chip / banner after the session changed behind its back. */
    static void pingUi(Context c) {
        MainActivity m = MainActivity.current;
        if (m != null) m.pokeWeb("window.onExtSessionChanged&&window.onExtSessionChanged()");
    }
}
