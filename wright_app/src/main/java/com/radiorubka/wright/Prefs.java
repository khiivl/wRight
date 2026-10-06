package com.radiorubka.wright;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/** Thin wrapper around the one SharedPreferences file the whole app reads/writes. */
public class Prefs {
    private static final String FILE = "wright_prefs";

    public static final String KEY_DARK_MODE_ENABLED = "dark_mode_enabled";
    public static final String KEY_NIGHT_SHIFT_ENABLED = "night_shift_enabled";
    public static final String KEY_NIGHT_SHIFT_INTENSITY = "night_shift_intensity"; // 0-100
    public static final String KEY_NIGHT_SHIFT_FADE_MIN = "night_shift_fade_min"; // minutes
    public static final String KEY_LIGHTS_OVERRIDE_ENABLED = "lights_override_enabled";
    public static final String KEY_BRIGHTNESS_ENABLED = "brightness_enabled";
    public static final String KEY_BRIGHTNESS_MIN = "brightness_min"; // 1-100 (%)
    public static final String KEY_BRIGHTNESS_MAX = "brightness_max"; // 1-100 (%)
    public static final String KEY_BACKLIGHT_COLOR = "backlight_color"; // 0xRRGGBB
    public static final String KEY_SUNRISE_OFFSET_MIN = "sunrise_offset_min"; // -30..30
    public static final String KEY_SUNSET_OFFSET_MIN = "sunset_offset_min"; // -30..30

    // The user's real night brightness, captured from screen_brightness_night_{headlight,
    // no_headlight} right before we resume daytime priming (i.e. at the real night->day
    // transition) - see WRightService's edge-detection logic. -1 = never captured yet.
    public static final String KEY_SAVED_NIGHT_HEADLIGHT_BRIGHTNESS = "saved_night_headlight_brightness";
    public static final String KEY_SAVED_NIGHT_NO_HEADLIGHT_BRIGHTNESS = "saved_night_no_headlight_brightness";

    public static final String KEY_LAST_LAT = "last_lat";
    public static final String KEY_LAST_LON = "last_lon";
    public static final String KEY_LAST_FIX_TIME = "last_fix_time";

    // TimeSource sleep anchor - see TimeSource.onAccOff()/correctedNowMillis()
    public static final String KEY_SLEEP_ANCHOR_WALL = "sleep_anchor_wall";
    public static final String KEY_SLEEP_ANCHOR_ELAPSED = "sleep_anchor_elapsed";

    // TimeSource debug override (DebugTimeReceiver, adb-only) - the "debug_" prefix keeps these
    // out of Export/Import (see exportToJson below), since a simulated clock has no business in
    // a settings backup.
    public static final String KEY_DEBUG_TIME_WALL = "debug_time_wall";
    public static final String KEY_DEBUG_TIME_ELAPSED = "debug_time_elapsed";

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return get(ctx).getBoolean(key, def);
    }

    public static int getInt(Context ctx, String key, int def) {
        return get(ctx).getInt(key, def);
    }

    public static float getFloat(Context ctx, String key, float def) {
        return get(ctx).getFloat(key, def);
    }

    public static long getLong(Context ctx, String key, long def) {
        return get(ctx).getLong(key, def);
    }

    public static void putBool(Context ctx, String key, boolean value) {
        get(ctx).edit().putBoolean(key, value).apply();
    }

    public static void putInt(Context ctx, String key, int value) {
        get(ctx).edit().putInt(key, value).apply();
    }

    public static void putFloat(Context ctx, String key, float value) {
        get(ctx).edit().putFloat(key, value).apply();
    }

    public static void putLong(Context ctx, String key, long value) {
        get(ctx).edit().putLong(key, value).apply();
    }

    /** Dumps every stored preference as-is - used for the Export button. Generic over the whole
     *  file (rather than listing keys by hand) so new settings are included automatically. */
    public static JSONObject exportToJson(Context ctx) throws JSONException {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, ?> e : get(ctx).getAll().entrySet()) {
            if (e.getKey().startsWith("debug_")) continue;
            obj.put(e.getKey(), e.getValue());
        }
        return obj;
    }

    /** Restores preferences from a previously exported JSON object - used for the Import button.
     *  JSON doesn't distinguish int/long/float itself, so the runtime type org.json already
     *  picked while parsing (Boolean/Long/Integer/Double/String) decides which setter to call. */
    public static void importFromJson(Context ctx, JSONObject obj) throws JSONException {
        SharedPreferences.Editor editor = get(ctx).edit();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = obj.get(key);
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Double) editor.putFloat(key, ((Double) value).floatValue());
            else if (value instanceof String) editor.putString(key, (String) value);
        }
        editor.apply();
    }
}
