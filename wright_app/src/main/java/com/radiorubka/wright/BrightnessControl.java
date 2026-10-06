package com.radiorubka.wright;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.Settings;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Writes screen_brightness directly for immediate effect, and keeps CarSettingService's own
 * 4-profile table (screen_brightness_{day,night}_{headlight,no_headlight}) primed so its resolver
 * - which re-fires synchronously whenever ui_night_mode changes, reading straight from that table
 * - never reads a stale value and causes a visible flicker. See WRightService's priming/restore/
 * capture logic for when each key actually gets touched; this class is just the low-level writer.
 *
 * The 4 profile keys genuinely live in Settings.System (confirmed from CarSettingService's own
 * smali: it reads them via Settings$System;->getIntForUser), but writing them is blocked for our
 * app no matter which API is used - the public Settings.System.putInt() AND a raw ContentResolver
 * update/insert straight to content://settings/system both threw the identical
 * "IllegalArgumentException: You cannot keep your settings in the secure settings." Writing the
 * same key via Settings.Secure succeeds with no error, but into a table CarSettingService never
 * reads, so it has no effect - a dead end confirmed on real hardware (the System value never
 * moved no matter what we wrote to Secure).
 *
 * Confirmed on real hardware: `adb shell settings put system screen_brightness_night_headlight
 * 200` succeeds and the value actually changes. The `settings` CLI runs as the shell UID (2000),
 * which sits below Android's FIRST_APPLICATION_UID and is specially privileged in this vendor's
 * check - so this is a caller-UID gate, not a permission our app can ever be granted (WRITE_
 * SETTINGS/WRITE_SECURE_SETTINGS don't matter to it either way). The only way for our app's
 * process to reach that same privileged UID is to go through `su` - these writes require the
 * device to actually grant root to this app.
 */
public class BrightnessControl {
    private static final String TAG = "wRight_Brightness";
    private static final String KEY_DAY_HEADLIGHT = "screen_brightness_day_headlight";
    private static final String KEY_DAY_NO_HEADLIGHT = "screen_brightness_day_no_headlight";
    private static final String KEY_NIGHT_HEADLIGHT = "screen_brightness_night_headlight";
    private static final String KEY_NIGHT_NO_HEADLIGHT = "screen_brightness_night_no_headlight";

    private static boolean rootUnavailableLogged = false;
    private static Process rootProcess;
    private static DataOutputStream rootOutput;

    // Last value actually written by apply()/primeNightProfiles() - both are called every tick
    // while it's daytime, but the computed curve value barely moves between consecutive ticks, so
    // skipping the write (live Settings write + root settings-put shell) when it hasn't changed
    // cuts out almost all of that traffic instead of re-asserting the same number constantly.
    private static Integer lastAppliedValue255 = null;
    private static Integer lastPrimedNightValue255 = null;

    /** Covers the plain screen_brightness live write. */
    public static boolean hasPermission(Context context) {
        return Settings.System.canWrite(context);
    }

    /** value255 in [0,255]. Writes the live brightness plus both DAY profile keys - called only
     *  while it's actually daytime by the clock, so the live write never fights CarSettingService's
     *  own night-profile resolution. The live write and the root-shelled profile writes are
     *  isolated from each other so one failing can never take down the other, or the service. */
    public static void apply(Context context, int value255) {
        int clamped = Math.max(1, Math.min(255, value255));
        if (lastAppliedValue255 != null && lastAppliedValue255 == clamped) return;
        if (hasPermission(context)) {
            putSystemIntSafely(context, Settings.System.SCREEN_BRIGHTNESS, clamped);
        } else {
            Log.w(TAG, "apply: WRITE_SETTINGS not granted, skipping live screen_brightness=" + clamped);
        }
        putSystemIntsRoot(context, KEY_DAY_HEADLIGHT, clamped, KEY_DAY_NO_HEADLIGHT, clamped);
        lastAppliedValue255 = clamped;
    }

    /** Primes the NIGHT profile keys with the current day-curve value too, without touching the
     *  live screen_brightness - called every tick while it's daytime. This is what kills the
     *  Lights Override flicker: if headlights force dark mode mid-day, CarSettingService reads an
     *  already-correct bright value for screen_brightness_night_headlight instead of whatever was
     *  last stored there (which could be hours stale, or the user's actual night preference,
     *  neither of which is right for "it's still daytime, just themed dark"). */
    public static void primeNightProfiles(Context context, int value255) {
        int clamped = Math.max(1, Math.min(255, value255));
        if (lastPrimedNightValue255 != null && lastPrimedNightValue255 == clamped) return;
        putSystemIntsRoot(context, KEY_NIGHT_HEADLIGHT, clamped, KEY_NIGHT_NO_HEADLIGHT, clamped);
        lastPrimedNightValue255 = clamped;
    }

    /** Writes the NIGHT profile keys directly - used to restore the user's saved real night
     *  preference right before the genuine (clock-based) transition into night, so
     *  CarSettingService's resolver reads the correct value the instant ui_night_mode flips,
     *  instead of whatever we'd been priming all day. */
    public static void setNightProfiles(Context context, int headlightValue255, int noHeadlightValue255) {
        putSystemIntsRoot(context,
                KEY_NIGHT_HEADLIGHT, Math.max(1, Math.min(255, headlightValue255)),
                KEY_NIGHT_NO_HEADLIGHT, Math.max(1, Math.min(255, noHeadlightValue255)));
        // This writes the same keys primeNightProfiles() tracks, outside of its cache - drop the
        // cache so the next priming call doesn't wrongly skip a write, thinking the value it
        // computed matches what's stored, when we just overwrote it with something else here.
        lastPrimedNightValue255 = null;
    }

    /** Reads back the NIGHT profile keys - used right before we resume day-priming (i.e. right
     *  at the real night-to-day transition) to capture whatever the user's real night brightness
     *  currently is, before we start overwriting it with daytime values. Returns -1 for a key
     *  that isn't set/readable, which callers should treat as "nothing to capture". Reading is not
     *  blocked the way writing is, so this needs no root - a plain ContentResolver query suffices. */
    public static int readNightHeadlight(Context context) {
        return querySystemIntRaw(context, KEY_NIGHT_HEADLIGHT, -1);
    }

    public static int readNightNoHeadlight(Context context) {
        return querySystemIntRaw(context, KEY_NIGHT_NO_HEADLIGHT, -1);
    }

    private static void putSystemIntSafely(Context context, String key, int value) {
        try {
            Settings.System.putInt(context.getContentResolver(), key, value);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "putSystemIntSafely: rejected writing " + key + "=" + value + ": " + e);
        }
    }

    /** Writes any number of System settings key/value pairs (key1, value1, key2, value2, ...)
     *  through the shared persistent `su` session - see class doc for why this needs root instead
     *  of any ContentResolver path, and ensureRootSession() for why it's kept open rather than
     *  spawned fresh every call (every root manager we've seen pops a "granted superuser" toast
     *  per NEW su process, and this runs every tick while it's daytime). */
    private static synchronized void putSystemIntsRoot(Context context, Object... keyValuePairs) {
        if (!ensureRootSession()) {
            if (!rootUnavailableLogged) {
                Log.w(TAG, "putSystemIntsRoot: no root session available, skipping");
                rootUnavailableLogged = true;
            }
            return;
        }
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            script.append("settings put system ").append(keyValuePairs[i])
                    .append(' ').append(keyValuePairs[i + 1]).append('\n');
        }
        try {
            rootOutput.writeBytes(script.toString());
            rootOutput.flush();
            rootUnavailableLogged = false;
        } catch (IOException e) {
            // The shared session died (su process killed, root revoked, etc.) - drop it so the
            // next call starts a fresh one instead of writing to a dead pipe forever.
            Log.w(TAG, "putSystemIntsRoot: root session write failed, will reconnect: " + e);
            closeRootSession();
            return;
        }
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String key = (String) keyValuePairs[i];
            int value = (Integer) keyValuePairs[i + 1];
            int readBack = querySystemIntRaw(context, key, Integer.MIN_VALUE);
            if (readBack != value) {
                Log.w(TAG, "putSystemIntsRoot: wrote " + key + "=" + value + " but readback is " + readBack);
            }
        }
    }

    /** Starts the shared `su` process once and keeps it open for the service's lifetime, reusing
     *  it for every subsequent write instead of spawning a new root process per call. */
    private static boolean ensureRootSession() {
        if (rootProcess != null) {
            try {
                rootProcess.exitValue();
                // exitValue() returning (instead of throwing) means the process already died -
                // fall through and start a new one.
                closeRootSession();
            } catch (IllegalThreadStateException stillRunning) {
                return true;
            }
        }
        try {
            rootProcess = Runtime.getRuntime().exec("su");
            rootOutput = new DataOutputStream(rootProcess.getOutputStream());
            return true;
        } catch (IOException e) {
            rootProcess = null;
            rootOutput = null;
            return false;
        }
    }

    private static void closeRootSession() {
        if (rootOutput != null) {
            try {
                rootOutput.close();
            } catch (IOException ignored) {
            }
        }
        if (rootProcess != null) {
            rootProcess.destroy();
        }
        rootProcess = null;
        rootOutput = null;
    }

    /** Queries content://settings/system directly - works for reads even though the write side
     *  is blocked for our app, so no root needed here. */
    private static int querySystemIntRaw(Context context, String key, int def) {
        try (Cursor c = context.getContentResolver().query(Settings.System.CONTENT_URI, null,
                Settings.NameValueTable.NAME + "=?", new String[]{key}, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(Settings.NameValueTable.VALUE);
                if (idx >= 0) {
                    String val = c.getString(idx);
                    if (val != null) return Integer.parseInt(val);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "querySystemIntRaw: failed reading " + key + ": " + e);
        }
        return def;
    }
}
