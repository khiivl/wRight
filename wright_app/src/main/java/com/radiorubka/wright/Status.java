package com.radiorubka.wright;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Runtime diagnostics WRightService publishes for MainActivity's status panel - deliberately a
 * separate file from Prefs so this live/derived state is never swept into Export/Import (which
 * dumps Prefs.get(ctx).getAll() generically - these aren't settings, they're the service's
 * current readout and get stale the instant they're written anyway).
 */
public class Status {
    private static final String FILE = "wright_status";

    public static final String KEY_LAT = "lat";
    public static final String KEY_LON = "lon";
    public static final String KEY_HAS_FIX = "has_fix";
    public static final String KEY_NOW_MILLIS = "now_millis"; // TimeSource's current estimate
    public static final String KEY_HEADLIGHTS_ON = "headlights_on";
    public static final String KEY_SUNRISE_MILLIS = "sunrise_millis";
    public static final String KEY_SUNSET_MILLIS = "sunset_millis";
    public static final String KEY_BRIGHTNESS_PCT = "brightness_pct"; // -1 = disabled/no data

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static void putDouble(Context ctx, String key, double value) {
        prefs(ctx).edit().putLong(key, Double.doubleToLongBits(value)).apply();
    }

    public static double getDouble(Context ctx, String key, double def) {
        return Double.longBitsToDouble(prefs(ctx).getLong(key, Double.doubleToLongBits(def)));
    }

    public static void putLong(Context ctx, String key, long value) {
        prefs(ctx).edit().putLong(key, value).apply();
    }

    public static long getLong(Context ctx, String key, long def) {
        return prefs(ctx).getLong(key, def);
    }

    public static void putBool(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return prefs(ctx).getBoolean(key, def);
    }

    public static void putInt(Context ctx, String key, int value) {
        prefs(ctx).edit().putInt(key, value).apply();
    }

    public static int getInt(Context ctx, String key, int def) {
        return prefs(ctx).getInt(key, def);
    }
}
