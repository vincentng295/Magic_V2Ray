package com.huskydg.magicv2ray;

import android.content.Context;
import android.content.SharedPreferences;

/** Which engine the app drives: the root service (iptables / netlink) or a VpnService. */
final class RunMode {
    static final String ROOT = "root";
    static final String NONROOT = "nonroot";
    private static final String PREFS = "run_mode";

    private RunMode() {
    }

    /** Null until the first setup decided (root available -> root, otherwise the user's choice). */
    static String get(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", null);
    }

    static void set(Context c, String mode) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString("mode", mode).apply();
    }

    static boolean isNonRoot(Context c) {
        return NONROOT.equals(get(c));
    }
}
