package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/** Asks (once) to exempt the app from battery optimisation so Doze does not stop the proxy. */
final class BatteryOpt {

    private static final String PREFS = "proxy_state";

    private BatteryOpt() {
    }

    static boolean isIgnoring(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Shows the system "allow to run in the background" dialog, only the first time. */
    static void askOnce(Activity a) {
        if (isIgnoring(a)) return;
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("asked_battery", false)) return;
        p.edit().putBoolean("asked_battery", true).apply();
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }
}
