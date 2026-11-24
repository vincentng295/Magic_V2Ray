package com.huskydg.magicv2ray;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * Read-only provider of the external API: lets any app ask what is running here and in what state, and be told
 * when that changes, without needing the UI or a broadcast reply.
 *
 * <pre>
 * Bundle b = getContentResolver().call(Uri.parse("content://com.huskydg.magicv2ray.api"), "getInfo", null, null);
 * b.getString("json");  b.getString("versionName");  b.getInt("apiLevel");  b.getBoolean("connected");
 * getContentResolver().registerContentObserver(Uri.parse("content://com.huskydg.magicv2ray.api/state"), false, observer);
 * </pre>
 *
 * Android 11+: the calling app needs {@code <queries><package android:name="com.huskydg.magicv2ray"/></queries>}.
 * Nothing here changes any state; the answers hold no server address and no settings.
 */
public class ExternalApiProvider extends ContentProvider {

    static final String AUTHORITY = "com.huskydg.magicv2ray.api";
    static final Uri INFO_URI = Uri.parse("content://" + AUTHORITY + "/info");
    static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");
    static final String METHOD_INFO = "getInfo";
    static final String METHOD_STATE = "getState";
    /** API level 2, apps only, needs the user's permission: see {@link ExternalNodes}. */
    static final String METHOD_NODES = "getNodes";
    static final String METHOD_ACTIVE_NODE = "getActiveNode";
    static final String METHOD_NODE_ACCESS = "getNodeAccess";

    private static final String[] COLUMNS = {"json", "versionName", "versionCode", "apiLevel", "connected", "mode"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null || method == null) return null;
        if (METHOD_NODES.equals(method) || METHOD_ACTIVE_NODE.equals(method) || METHOD_NODE_ACCESS.equals(method)) {
            return nodeCall(method);
        }
        if (METHOD_INFO.equals(method)) return bundle(ExternalApi.info(getContext()), true);
        if (METHOD_STATE.equals(method)) {
            JSONObject st = ExternalApi.state(getContext());
            Bundle b = bundle(new JSONObject(), false);
            b.putString("json", st.toString());
            b.putBoolean("connected", st.optBoolean("connected"));
            b.putBoolean("known", st.optBoolean("known"));
            b.putString("mode", st.optString("mode"));
            return b;
        }
        return null;
    }

    /**
     * Nodes (level 2). The caller is the package Android reports for this binder call, so only an app can ask and it
     * can not pretend to be another. {@code status}: ok | permission_required | unknown_caller | unavailable.
     * Without permission nothing but the status comes back; the app then starts
     * {@link ExternalNodes#ACTION_REQUEST_ACCESS} with startActivityForResult and asks again.
     */
    private Bundle nodeCall(String method) {
        Context c = getContext();
        Bundle b = new Bundle();
        b.putInt("apiLevel", ExternalApi.API_LEVEL);
        String caller = getCallingPackage();
        if (caller == null || caller.isEmpty() || caller.equals(c.getPackageName())) {
            b.putString("status", "unknown_caller");
            return b;
        }
        String level = ExternalNodes.accessLevel(c, caller);
        b.putString("level", level);
        if (METHOD_NODE_ACCESS.equals(method)) {
            b.putString("status", "ok");
            b.putBoolean("granted", !ExternalNodes.LEVEL_NONE.equals(level));
            b.putBoolean("persistent", ExternalNodes.isPermanent(c, caller));
            b.putString("requestAction", ExternalNodes.ACTION_REQUEST_ACCESS);
            return b;
        }
        if (ExternalNodes.LEVEL_NONE.equals(level)) {
            b.putString("status", "permission_required");
            b.putString("requestAction", ExternalNodes.ACTION_REQUEST_ACCESS);
            return b;
        }
        JSONObject j = METHOD_NODES.equals(method) ? ExternalNodes.nodesJson(c, level) : ExternalNodes.activeJson(c, level);
        if (j == null) {
            b.putString("status", "unavailable");
            return b;
        }
        b.putString("status", "ok");
        b.putString("json", j.toString());
        if (METHOD_ACTIVE_NODE.equals(method)) {
            b.putBoolean("connected", j.optBoolean("connected"));
            b.putString("source", j.optString("source"));
        } else {
            org.json.JSONArray arr = j.optJSONArray("nodes");
            b.putInt("count", arr == null ? 0 : arr.length());
            b.putString("activeId", j.optString("activeId"));
        }
        return b;
    }

    private Bundle bundle(JSONObject info, boolean full) {
        Bundle b = new Bundle();
        b.putString("json", info.toString());
        if (!full) return b;
        JSONObject st = info.optJSONObject("state");
        b.putString("versionName", info.optString("versionName"));
        b.putLong("versionCode", info.optLong("versionCode"));
        b.putInt("apiLevel", ExternalApi.API_LEVEL);
        b.putString("mode", info.optString("mode"));
        b.putBoolean("connected", st != null && st.optBoolean("connected"));
        b.putBoolean("known", st != null && st.optBoolean("known"));
        return b;
    }

    /** {@code .../info} or {@code .../state}: one row, the same fields as the {@code call} Bundle. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (getContext() == null) return null;
        String p = uri.getLastPathSegment();
        if (!"info".equals(p) && !"state".equals(p)) return null;
        JSONObject info = ExternalApi.info(getContext());
        JSONObject st = info.optJSONObject("state");
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[]{
                "state".equals(p) && st != null ? st.toString() : info.toString(),
                info.optString("versionName"), info.optLong("versionCode"), ExternalApi.API_LEVEL,
                st != null && st.optBoolean("connected") ? 1 : 0, info.optString("mode")});
        c.setNotificationUri(getContext().getContentResolver(), STATE_URI);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.huskydg.magicv2ray.api";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
