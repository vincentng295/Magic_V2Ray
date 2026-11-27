package com.huskydg.magicv2ray;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-app activity log of the external control API ("App permission" > an app's log): what each app asked for and what
 * was decided. One JSON array per package, newest last, {@link #MAX_ENTRIES} at most.
 *
 * <p>An entry is {@code {ts, k, d}}: a time, a kind code the WebUI turns into a sentence (so the log follows the
 * language of the UI) and an optional short detail. Details are never secrets: a node's host, the names of the settings
 * sections an app overrides, an error reason. The node URI (credentials) is never written here.
 */
final class ExternalAppLog {

    private static final String PREFS = "ext_applog";
    static final int MAX_ENTRIES = 200;
    private static final int MAX_DETAIL = 300;
    private static final Object LOCK = new Object();

    // kinds
    static final String K_CONNECT_ONCE = "connect_once";        // user chose "Allow once"
    static final String K_CONNECT_ALWAYS = "connect_always";    // user chose "Always allow"
    static final String K_CONNECT_AUTO = "connect_auto";        // let through because the app is always allowed
    static final String K_CONNECT_DENIED = "connect_denied";
    static final String K_CONNECTED = "connected";              // the WebUI started the session
    static final String K_CONNECT_FAILED = "connect_failed";    // d = reason
    static final String K_DISCONNECT = "disconnect";            // the app asked to end its session
    static final String K_NODES_ONCE = "nodes_once";            // d = level
    static final String K_NODES_ALWAYS = "nodes_always";        // d = level
    static final String K_NODES_DENIED = "nodes_denied";
    static final String K_REVOKED = "revoked";                  // the user revoked everything this app was allowed

    private ExternalAppLog() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void add(Context c, String pkg, String kind, String detail) {
        if (c == null || pkg == null || pkg.isEmpty() || kind == null || kind.isEmpty()) return;
        if (detail == null) detail = "";
        if (detail.length() > MAX_DETAIL) detail = detail.substring(0, MAX_DETAIL);
        synchronized (LOCK) {
            SharedPreferences p = sp(c);
            JSONArray cur;
            try {
                cur = new JSONArray(p.getString(pkg, "[]"));
            } catch (JSONException e) {
                cur = new JSONArray();
            }
            try {
                cur.put(new JSONObject().put("ts", System.currentTimeMillis()).put("k", kind).put("d", detail));
            } catch (JSONException e) {
                return;
            }
            if (cur.length() > MAX_ENTRIES) {
                JSONArray trimmed = new JSONArray();
                for (int i = cur.length() - MAX_ENTRIES; i < cur.length(); i++) trimmed.put(cur.opt(i));
                cur = trimmed;
            }
            p.edit().putString(pkg, cur.toString()).apply();
        }
    }

    /** The log of one app, newest FIRST, as a JSON array. */
    static JSONArray list(Context c, String pkg) {
        JSONArray out = new JSONArray();
        if (pkg == null || pkg.isEmpty()) return out;
        synchronized (LOCK) {
            try {
                JSONArray cur = new JSONArray(sp(c).getString(pkg, "[]"));
                for (int i = cur.length() - 1; i >= 0; i--) out.put(cur.opt(i));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    static void clear(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        synchronized (LOCK) {
            sp(c).edit().remove(pkg).apply();
        }
    }

    /** Packages that have a log, with the time of their first entry, so the "App permission" list can show them. */
    static List<Object[]> packages(Context c) {
        List<Object[]> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
                long first = 0;
                try {
                    JSONArray a = new JSONArray(String.valueOf(e.getValue()));
                    if (a.length() == 0) continue;
                    first = a.getJSONObject(0).optLong("ts");
                } catch (JSONException ignored) {
                }
                out.add(new Object[]{e.getKey(), first});
            }
        }
        return out;
    }
}
