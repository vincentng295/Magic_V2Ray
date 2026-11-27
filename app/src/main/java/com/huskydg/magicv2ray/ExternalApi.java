package com.huskydg.magicv2ray;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only half of the external API (the control half is {@link ExternalSession}):
 *
 * <ul>
 *   <li><b>Info</b>: what is running here (version, API level, supported actions / events / override sections,
 *       current state). Available as an ordered broadcast ({@link #ACTION_GET_INFO}) and through
 *       {@link ExternalApiProvider} ({@code call("getInfo")}, {@code query(.../info)}).</li>
 *   <li><b>State events</b>: {@link #ACTION_STATE_CHANGED} is broadcast whenever the proxy connects or disconnects,
 *       whoever caused it (the app that asked, the tile, the notification button, a crash, the root service).
 *       It goes to the app that owns the external session (explicit, so a manifest receiver works too) and as a normal
 *       broadcast to receivers registered at run time. The same change is announced to
 *       {@link ExternalApiProvider#STATE_URI} observers. Events carry no server address and no settings.</li>
 * </ul>
 *
 * A short debounce hides the stop / start pair of a restart or a reload: a disconnect is only announced when the
 * engine is still down {@link #DISCONNECT_DEBOUNCE_MS} later. Ending an external session is the exception, it is
 * announced at once ({@code reason = session_ended}).
 */
final class ExternalApi {

    /**
     * Bump when the API gains something an app may need to test for. Level 1: info + state events.
     * Level 2: nodes (permission-gated, apps only) and freedom:// / chain:// / nodeid:// as the node of a connect.
     * Level 3: no limit on the number of routing rules / hosts in {@code overrides}, the overrides can travel in a file
     * ({@code overridesUri}), and custom .dat databases ({@code assets}) replace geoip.dat / geosite.dat / ext: files for
     * the length of the session. See {@link ExternalAssets}.
     */
    static final int API_LEVEL = 3;

    static final String ACTION_GET_INFO = "com.huskydg.magicv2ray.action.GET_INFO";
    static final String ACTION_STATE_CHANGED = "com.huskydg.magicv2ray.event.STATE_CHANGED";
    /**
     * Sent by the root service (native) when xray started or stopped. Nothing in it is trusted: it only makes the app
     * look at the real status again ({@link #refreshRoot}).
     */
    static final String ACTION_ENGINE_CHANGED = "com.huskydg.magicv2ray.action.ENGINE_CHANGED";

    static final String EXTRA_STATE = "state";                  // "connected" | "disconnected"
    static final String EXTRA_CONNECTED = "connected";          // boolean
    static final String EXTRA_REASON = "reason";                // see REASON_*
    static final String EXTRA_SESSION = "session";              // boolean: an external session is involved
    static final String EXTRA_SESSION_NAME = "sessionName";
    static final String EXTRA_CALLER = "caller";                // package that started the session ("" if unknown)
    static final String EXTRA_MODE = "mode";                    // "root" | "nonroot"
    static final String EXTRA_TIME = "time";                    // System.currentTimeMillis()
    static final String EXTRA_SEQ = "seq";                      // increases with every event of this process

    static final String REASON_STARTED = "started";
    static final String REASON_SESSION_STARTED = "session_started";
    /** The external session ended (by the app, the user, the tile ...). */
    static final String REASON_SESSION_ENDED = "session_ended";
    /** Stopped by the user / an app / the service, nothing went wrong. */
    static final String REASON_STOPPED = "stopped";
    /** The engine died or could not start (non-root: also "VPN permission revoked"). */
    static final String REASON_ERROR = "error";
    static final String REASON_CRASHED = "crashed";

    static final long DISCONNECT_DEBOUNCE_MS = 2_000L;
    private static final long ROOT_REFRESH_DELAY_MS = 700L;
    private static final long ROOT_STATE_MAX_AGE_MS = 5_000L;

    private static final Object LOCK = new Object();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mv-ext-api");
        t.setDaemon(true);
        return t;
    });

    /** Last state that was announced; null until known (root mode learns it by looking). */
    private static Boolean announced;
    /** Newest state seen, announced or not. */
    private static Boolean current;
    private static long currentAt;
    private static ScheduledFuture<?> pendingDisconnect;
    private static ScheduledFuture<?> pendingRefresh;
    private static long announcedSessionAt;

    private ExternalApi() {
    }

    // =============================================================== info

    static String versionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    static long versionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static JSONArray arr(String... v) {
        JSONArray a = new JSONArray();
        for (String s : v) a.put(s);
        return a;
    }

    /** What this build of the app supports. Static, safe to show to anybody. */
    static JSONObject capabilities() throws JSONException {
        return new JSONObject()
                .put("level", API_LEVEL)
                .put("actions", arr(ExternalSession.ACTION_CONNECT, ExternalSession.ACTION_DISCONNECT, ACTION_GET_INFO))
                .put("schemes", arr("magicv2ray://connect", "magicv2ray://disconnect"))
                .put("events", arr(ACTION_STATE_CHANGED))
                .put("extras", arr(ExternalSession.EXTRA_URI, ExternalSession.EXTRA_NAME, ExternalSession.EXTRA_EXCLUDE,
                        ExternalSession.EXTRA_OVERRIDES, ExternalSession.EXTRA_OVERRIDES_URI, ExternalSession.EXTRA_ASSETS))
                .put("overrides", arr("traffic", "network", "exclude", "routing", "hosts", "assets"))
                .put("limits", ExternalAssets.limits())
                .put("provider", new JSONObject()
                        .put("authority", ExternalApiProvider.AUTHORITY)
                        .put("methods", arr(ExternalApiProvider.METHOD_INFO, ExternalApiProvider.METHOD_STATE,
                                ExternalApiProvider.METHOD_NODES, ExternalApiProvider.METHOD_ACTIVE_NODE,
                                ExternalApiProvider.METHOD_NODE_ACCESS))
                        .put("uris", arr(ExternalApiProvider.INFO_URI.toString(), ExternalApiProvider.STATE_URI.toString())))
                .put("nodes", new JSONObject()
                        .put("requestAction", ExternalNodes.ACTION_REQUEST_ACCESS)
                        .put("levels", arr(ExternalNodes.LEVEL_ANONYMOUS, ExternalNodes.LEVEL_FULL)))
                .put("connectUris", arr(ExternalNodes.FREEDOM_URI, ExternalNodes.CHAIN_PREFIX + "<urlencode(JSON array)>",
                        ExternalNodes.NODEID_PREFIX + "<id>"))
                .put("features", arr("connect", "disconnect", "overrides", "exclude", "info", "state_events", "state_provider",
                        "nodes", "node_access_request", "connect_freedom", "connect_chain", "connect_nodeid",
                        "overrides_unlimited", "overrides_uri", "custom_assets"));
    }

    /**
     * {@code {app, package, versionName, versionCode, mode, api:{...}, state:{...}}}. Never blocks for long: root mode
     * looks at the engine only when the last look is older than a few seconds.
     */
    static JSONObject info(Context c) {
        Context app = c.getApplicationContext();
        try {
            return new JSONObject()
                    .put("app", "Magic V2Ray")
                    .put("package", app.getPackageName())
                    .put("versionName", versionName(app))
                    .put("versionCode", versionCode(app))
                    .put("mode", mode(app))
                    .put("api", capabilities())
                    .put("state", state(app));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** {@code {connected, known, mode, session:{active, name, caller, startedAt}}}. No server address, no settings. */
    static JSONObject state(Context c) {
        Context app = c.getApplicationContext();
        Boolean running = running(app, true);
        JSONObject session = ExternalSession.state(app);
        try {
            JSONObject s = new JSONObject()
                    .put("active", session.optBoolean("active"))
                    .put("name", session.optString("name", ""))
                    .put("caller", session.optString("caller", ""))
                    .put("startedAt", session.optLong("startedAt", 0));
            return new JSONObject()
                    .put("connected", running != null && running)
                    .put("known", running != null)
                    .put("mode", mode(app))
                    .put("session", s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static String mode(Context c) {
        return RunMode.isNonRoot(c) ? RunMode.NONROOT : RunMode.ROOT;
    }

    /** Whether the proxy is up: exact in non-root mode, root mode asks the service (cached for a few seconds). Null = unknown. */
    private static Boolean running(Context app, boolean allowRootCall) {
        if (RunMode.isNonRoot(app)) return VpnController.isRunning();
        synchronized (LOCK) {
            if (current != null && System.currentTimeMillis() - currentAt < ROOT_STATE_MAX_AGE_MS) return current;
        }
        if (allowRootCall) {
            Boolean r = queryRoot(app);
            if (r != null) {
                observe(app, r, null);
                return r;
            }
        }
        synchronized (LOCK) {
            return current;
        }
    }

    // =============================================================== state changes

    /** Non-root engine (authoritative, called by {@link VpnController}): connected / stopped / failed. */
    static void engine(Context c, boolean running, String reason) {
        if (c == null) return;
        change(c.getApplicationContext(), running, reason, true);
    }

    /** Root mode: the status was read from the service. The first reading is only remembered, never announced. */
    static void observe(Context c, boolean running, String reason) {
        if (c == null || RunMode.isNonRoot(c)) return;
        change(c.getApplicationContext(), running, reason, false);
    }

    private static void change(final Context app, final boolean running, final String reason, boolean authoritative) {
        synchronized (LOCK) {
            current = running;
            currentAt = System.currentTimeMillis();
            if (announced == null && !authoritative) {      // first look in root mode: nothing to compare with
                announced = running;
                if (running) announcedSessionAt = sessionStart(app);
                return;
            }
            boolean was = announced != null && announced;
            if (running) {
                cancel(pendingDisconnect);
                pendingDisconnect = null;
                if (!was) {
                    announce(app, true, REASON_STARTED);
                } else if (sessionStart(app) != announcedSessionAt) {
                    announce(app, true, REASON_SESSION_STARTED);    // a session replaced the engine inside the debounce
                }
                return;
            }
            if (!was) {
                announced = false;
                return;
            }
            if (REASON_ERROR.equals(reason) || REASON_CRASHED.equals(reason)) {
                cancel(pendingDisconnect);
                pendingDisconnect = null;
                announce(app, false, reason);               // an error is final, nothing to wait for
                return;
            }
            if (pendingDisconnect != null) return;
            final String why = reason == null ? REASON_STOPPED : reason;
            pendingDisconnect = EXEC.schedule(() -> {
                synchronized (LOCK) {
                    pendingDisconnect = null;
                    if (current != null && !current && announced != null && announced) announce(app, false, why);
                }
            }, DISCONNECT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** The external session just ended: announced at once, the engine's own stop that follows is then a no-op. */
    static void sessionEnded(Context c, JSONObject session) {
        final Context app = c.getApplicationContext();
        synchronized (LOCK) {
            cancel(pendingDisconnect);
            pendingDisconnect = null;
            current = false;
            currentAt = System.currentTimeMillis();
            announce(app, false, REASON_SESSION_ENDED, session);
        }
    }

    private static long sessionStart(Context c) {
        return ExternalSession.state(c).optLong("startedAt", 0);
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    private static void announce(Context app, boolean connected, String reason) {
        announce(app, connected, reason, ExternalSession.state(app));
    }

    /** Must hold LOCK. {@code session} is the {@link ExternalSession#state} the event belongs to. */
    private static void announce(Context app, boolean connected, String reason, JSONObject session) {
        announced = connected;
        boolean inSession = session != null && session.optBoolean("active", false);
        announcedSessionAt = connected && inSession ? session.optLong("startedAt", 0) : 0;
        String caller = inSession ? session.optString("caller", "") : "";
        try {
            Intent i = new Intent(ACTION_STATE_CHANGED)
                    .putExtra(EXTRA_STATE, connected ? "connected" : "disconnected")
                    .putExtra(EXTRA_CONNECTED, connected)
                    .putExtra(EXTRA_REASON, reason == null ? REASON_STOPPED : reason)
                    .putExtra(EXTRA_SESSION, inSession)
                    .putExtra(EXTRA_SESSION_NAME, inSession ? session.optString("name", "") : "")
                    .putExtra(EXTRA_CALLER, caller)
                    .putExtra(EXTRA_MODE, mode(app))
                    .putExtra(EXTRA_TIME, System.currentTimeMillis())
                    .putExtra(EXTRA_SEQ, SEQ.incrementAndGet());
            if (Build.VERSION.SDK_INT >= 26) i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            // Receivers registered at run time (any app). An implicit broadcast never reaches manifest receivers.
            app.sendBroadcast(new Intent(i));
            // The app that owns the session is told explicitly, so a receiver in its manifest works as well.
            if (!caller.isEmpty() && !caller.equals(app.getPackageName())) app.sendBroadcast(new Intent(i).setPackage(caller));
            app.getContentResolver().notifyChange(ExternalApiProvider.STATE_URI, null);
        } catch (RuntimeException ignored) {        // never let a listener problem touch the engine
        }
    }

    // =============================================================== root mode

    /** Root mode: reads the real status (one word from `proxy_control status`). Null when the service cannot be asked. */
    static Boolean queryRoot(Context c) {
        Context app = c.getApplicationContext();
        if (RunMode.isNonRoot(app)) return null;
        try {
            RootShell.init(app);
            RootShell.Result r = RootShell.run(
                    "[ -p " + ModuleInstaller.STUB_DIR + "/run/control.pipe ] || { echo none; exit 0; }\n"
                            + RootShell.sq(ModuleInstaller.HELPER) + " proxy_control status 2>/dev/null; exit 0", 8_000);
            String o = r.out.trim();
            if (o.endsWith("running")) return Boolean.TRUE;
            if (o.endsWith("stopped") || o.endsWith("crashed")) return Boolean.FALSE;
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * Root mode: something changed in the service. Looks again after a moment (changes come in bursts) and announces
     * what it finds. Safe to call from any thread; does the work on its own thread.
     */
    static void refreshRoot(final Context c) {
        final Context app = c.getApplicationContext();
        if (RunMode.isNonRoot(app)) return;
        synchronized (LOCK) {
            if (pendingRefresh != null && !pendingRefresh.isDone()) return;
            pendingRefresh = EXEC.schedule(() -> {
                Boolean r = queryRoot(app);
                if (r != null) observe(app, r, r ? null : REASON_STOPPED);
            }, ROOT_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }
}
