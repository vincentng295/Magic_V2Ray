package com.huskydg.magicv2ray;

import android.content.ContentProvider;
import android.content.ContentValues;
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

    private static final String[] COLUMNS = {"json", "versionName", "versionCode", "apiLevel", "connected", "mode"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null || method == null) return null;
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
