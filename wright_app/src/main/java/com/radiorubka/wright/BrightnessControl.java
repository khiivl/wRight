package com.radiorubka.wright;

import android.content.Context;
import android.provider.Settings;

/**
 * Writes screen_brightness directly for immediate effect, and keeps CarSettingService's own
 * 4-profile table (screen_brightness_{day,night}_{headlight,no_headlight}) primed so its resolver
 * - which re-fires synchronously whenever ui_night_mode changes, reading straight from that table
 * - never reads a stale value and causes a visible flicker. See WRightService's priming/restore/
 * capture logic for when each key actually gets touched; this class is just the low-level writer.
 */
public class BrightnessControl {
    private static final String KEY_DAY_HEADLIGHT = "screen_brightness_day_headlight";
    private static final String KEY_DAY_NO_HEADLIGHT = "screen_brightness_day_no_headlight";
    private static final String KEY_NIGHT_HEADLIGHT = "screen_brightness_night_headlight";
    private static final String KEY_NIGHT_NO_HEADLIGHT = "screen_brightness_night_no_headlight";

    public static boolean hasPermission(Context context) {
        return Settings.System.canWrite(context);
    }

    /** value255 in [0,255]. Writes the live brightness plus both DAY profile keys - called only
     *  while it's actually daytime by the clock, so the live write never fights CarSettingService's
     *  own night-profile resolution. Each write is isolated: on some platform versions
     *  SettingsProvider rejects OEM-custom System keys with an IllegalArgumentException (not a
     *  SecurityException) since they aren't on its public allowlist - that must never take down
     *  the real screen_brightness write, or the service, with it. */
    public static void apply(Context context, int value255) {
        if (!hasPermission(context)) return;
        int clamped = Math.max(1, Math.min(255, value255));
        putIntSafely(context, Settings.System.SCREEN_BRIGHTNESS, clamped);
        putIntSafely(context, KEY_DAY_HEADLIGHT, clamped);
        putIntSafely(context, KEY_DAY_NO_HEADLIGHT, clamped);
    }

    /** Primes the NIGHT profile keys with the current day-curve value too, without touching the
     *  live screen_brightness - called every daytime tick alongside apply() above. This is what
     *  kills the Lights Override flicker: if headlights force dark mode mid-day, CarSettingService
     *  reads an already-correct bright value for screen_brightness_night_headlight instead of
     *  whatever was last stored there (which could be hours stale, or the user's actual night
     *  preference, neither of which is right for "it's still daytime, just themed dark"). */
    public static void primeNightProfiles(Context context, int value255) {
        if (!hasPermission(context)) return;
        int clamped = Math.max(1, Math.min(255, value255));
        putIntSafely(context, KEY_NIGHT_HEADLIGHT, clamped);
        putIntSafely(context, KEY_NIGHT_NO_HEADLIGHT, clamped);
    }

    /** Writes the NIGHT profile keys directly - used to restore the user's saved real night
     *  preference right before the genuine (clock-based) transition into night, so
     *  CarSettingService's resolver reads the correct value the instant ui_night_mode flips,
     *  instead of whatever we'd been priming all day. */
    public static void setNightProfiles(Context context, int headlightValue255, int noHeadlightValue255) {
        if (!hasPermission(context)) return;
        putIntSafely(context, KEY_NIGHT_HEADLIGHT, Math.max(1, Math.min(255, headlightValue255)));
        putIntSafely(context, KEY_NIGHT_NO_HEADLIGHT, Math.max(1, Math.min(255, noHeadlightValue255)));
    }

    /** Reads back the NIGHT profile keys - used right before we resume day-priming (i.e. right
     *  at the real night-to-day transition) to capture whatever the user's real night brightness
     *  currently is, before we start overwriting it with daytime values. Returns -1 for a key
     *  that isn't set/readable, which callers should treat as "nothing to capture". */
    public static int readNightHeadlight(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_NIGHT_HEADLIGHT, -1);
    }

    public static int readNightNoHeadlight(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_NIGHT_NO_HEADLIGHT, -1);
    }

    private static void putIntSafely(Context context, String key, int value) {
        try {
            Settings.System.putInt(context.getContentResolver(), key, value);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }
}
