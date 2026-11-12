package com.huskydg.magicv2ray;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * App-level (non-engine) settings: theme and primary colour. The WebUI owns the schema
 * ({@code {"theme":"system|light|dark","color":"system|default|custom","seed":"#RRGGBB"}}) and keeps it
 * here so it is readable before the page loads. The last system-bar colour the WebUI asked for is cached
 * so the very first frame already matches it.
 */
final class AppPrefs {
    private static final String FILE = "app_prefs";
    private static final String K_SETTINGS = "settings";
    private static final String K_CHROME_BG = "chrome_bg";
    private static final String K_CHROME_LIGHT = "chrome_light";

    private AppPrefs() {}

    private static SharedPreferences sp(Context c) {
        Context app = c.getApplicationContext();
        return (app != null ? app : c).getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String getJson(Context c) {
        return sp(c).getString(K_SETTINGS, "");
    }

    /** Stores the WebUI's settings blob; anything that is not a JSON object is ignored. */
    static void setJson(Context c, String json) {
        try {
            new JSONObject(json);
        } catch (JSONException | NullPointerException e) {
            return;
        }
        sp(c).edit().putString(K_SETTINGS, json).apply();
    }

    /** "system" (default), "light" or "dark". */
    static String theme(Context c) {
        try {
            String t = new JSONObject(getJson(c)).optString("theme", "system");
            return "light".equals(t) || "dark".equals(t) ? t : "system";
        } catch (JSONException e) {
            return "system";
        }
    }

    static boolean systemIsNight(Context c) {
        // The application context is never wrapped, so it always shows the real system setting.
        Context app = c.getApplicationContext();
        int ui = (app != null ? app : c).getResources().getConfiguration().uiMode;
        return (ui & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Whether the app is currently dark: the user's choice, else the system's. */
    static boolean isNight(Context c) {
        String t = theme(c);
        return "dark".equals(t) || ("system".equals(t) && systemIsNight(c));
    }

    /** Forces the day/night resource qualifier to the user's choice (no-op for "system"). */
    static Context wrap(Context base) {
        String t = theme(base);
        if ("system".equals(t)) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | ("dark".equals(t) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(cfg);
    }

    static void saveChrome(Context c, int bg, boolean lightBars) {
        sp(c).edit().putInt(K_CHROME_BG, bg).putBoolean(K_CHROME_LIGHT, lightBars).apply();
    }

    static boolean hasChrome(Context c) {
        return sp(c).contains(K_CHROME_BG);
    }

    static int chromeBg(Context c, int fallback) {
        return sp(c).getInt(K_CHROME_BG, fallback);
    }

    static boolean chromeLight(Context c) {
        return sp(c).getBoolean(K_CHROME_LIGHT, false);
    }
}
