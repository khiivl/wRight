package com.radiorubka.wright;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Drives Android's built-in Night Display (the real ColorDisplayManager feature, not a drawn
 * overlay) directly through its backing Settings.Secure keys. Writing Settings.Secure needs
 * WRITE_SECURE_SETTINGS, which can't be requested through a runtime dialog - it's granted once
 * via `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` and then just works
 * like any other permission from then on.
 *
 * night_display_color_temperature is NOT Kelvin on this ROM. Stock AOSP uses this exact setting
 * name for a Kelvin value (~2500-6500), which is what an earlier version of this class assumed
 * and read from the config_nightDisplayColorTemperatureMin/Max framework resource overlay - but
 * on real K706 hardware the stock Night Light slider was confirmed (by watching what it actually
 * writes) to drive this same setting across a tiny integer range instead: 1 = warmest, 64 =
 * neutral/off. This vendor repurposed the setting's meaning while keeping the AOSP name, so every
 * Kelvin-range value this class used to compute (thousands) was wildly outside the real valid
 * domain and got silently rejected/clamped - the actual cause of the Intensity slider doing
 * nothing. Using the confirmed real scale directly instead of trusting a resource lookup that
 * clearly isn't describing this device's real behavior.
 */
public class NightShiftControl {
    private static final String TAG = "wRight_NightShift";
    private static final String KEY_ACTIVATED = "night_display_activated";
    private static final String KEY_TEMPERATURE = "night_display_color_temperature";

    // Confirmed on real K706 hardware: the stock slider writes 1 at its warmest end and 64 at
    // its neutral/off end.
    private static final int LEVEL_WARMEST = 1;
    private static final int LEVEL_NEUTRAL = 64;

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** featureEnabled is the user's Night Shift toggle - activated only tracks THIS, not the
     *  current fade position, and so only ever flips on the rare, deliberate action of the user
     *  turning the feature on/off. intensity01 in [0,1] (0 = neutral, 1 = warmest) drives only the
     *  temperature, continuously, including dropping to 0 (neutral) for the entire daytime portion
     *  of every cycle - toggling `activated` off and back on every single day was causing
     *  ColorDisplayService to visibly re-init its color transform (a screen artifact) on a cycle
     *  that happens twice a day, every day, for no benefit over just parking the temperature at
     *  neutral, which looks identical and causes no transform re-init. */
    public static void apply(Context context, boolean featureEnabled, float intensity01) {
        if (!hasPermission(context)) return;
        float clamped = Math.max(0f, Math.min(1f, intensity01));
        int level = Math.round(LEVEL_NEUTRAL - clamped * (LEVEL_NEUTRAL - LEVEL_WARMEST));
        level = Math.max(LEVEL_WARMEST, Math.min(LEVEL_NEUTRAL, level)); // defensive: never send outside the confirmed valid 1-64 domain
        Log.i(TAG, "apply: featureEnabled=" + featureEnabled + " intensity=" + clamped + " -> level=" + level);
        putIntSafely(context, KEY_ACTIVATED, featureEnabled ? 1 : 0);
        putIntSafely(context, KEY_TEMPERATURE, level);
    }

    private static void putIntSafely(Context context, String key, int value) {
        try {
            Settings.Secure.putInt(context.getContentResolver(), key, value);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Permission revoked after we checked, or this platform rejects the key outright -
            // either way must never crash the caller (it runs on a HandlerThread with no default
            // exception handler, so an uncaught throw here takes the whole service down).
        }
    }
}
