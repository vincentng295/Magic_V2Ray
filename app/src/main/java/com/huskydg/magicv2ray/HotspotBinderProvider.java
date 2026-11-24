package com.huskydg.magicv2ray;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Second way for the app_process helper (HotspotMain) to hand over its Binder: a synchronous
 * ContentProvider call, the same route Shizuku uses. Unlike a broadcast it cannot be silently
 * dropped by OEM background restrictions, and the helper sees the error when it fails.
 * Exported because the caller is the shell user; the one-time nonce authenticates it.
 */
public final class HotspotBinderProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        if (!HotspotMain.METHOD_SEND_BINDER.equals(method) || extras == null) return reply;
        boolean ok = HotspotBinderReceiver.offer(extras.getString(HotspotMain.EXTRA_NONCE),
                extras.getBinder(HotspotMain.KEY_BINDER));
        reply.putBoolean("accepted", ok);
        return reply;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
