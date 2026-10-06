package com.radiorubka.wright;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;

/**
 * Single source of truth for "what time is it right now" - nothing else in the app should call
 * System.currentTimeMillis() directly.
 *
 * This exists because of a platform quirk: after the head unit wakes from its ACC_OFF suspend,
 * the system clock can read stale (pre-sleep) time for a while before it self-corrects, and
 * there's no network/NTP available offline to shortcut that. Since all our scheduling hinges on
 * knowing "is it currently before or after sunset", trusting a stale clock right at the moment
 * we resync (ACC_ON) could apply the wrong theme/brightness until the clock catches up.
 *
 * Three sources, preferred in order:
 *   0. A debug time override, set only via DebugTimeReceiver (adb, for testing) - lets a tester
 *      jump the clock the app itself reasons about to any point in the day/night cycle without
 *      physically waiting for it or relocating the unit, overriding even the GPS fix below. Like
 *      the sleep anchor, it's a {wallClock, elapsedRealtime} pair so the simulated time keeps
 *      advancing in real time from wherever it's set, instead of freezing - fades/curves still
 *      play out normally. Never set in normal operation; absent, this step is skipped entirely.
 *   1. The GPS fix's own timestamp (Location.getTime()) - authoritative UTC from the satellites,
 *      completely independent of the device's RTC. We need a fix for coordinates anyway.
 *   2. A sleep-anchor projection: {wallClock, elapsedRealtime} saved right before ACC_OFF.
 *      elapsedRealtime is defined to keep advancing through suspend, so projecting
 *      anchorWall + (elapsedNow - anchorElapsed) stays correct regardless of whether the RTC
 *      has caught up yet. If the raw system clock disagrees with this projection by more than
 *      STALE_TOLERANCE_MS, the raw clock is treated as not-yet-corrected and the projection wins.
 */
public class TimeSource {
    private static final long GPS_FIX_MAX_AGE_MS = 6L * 60 * 60 * 1000; // 6h
    private static final long STALE_TOLERANCE_MS = 5_000;

    private static volatile long lastGpsFixWallMillis = 0;
    private static volatile long lastGpsFixElapsedMillis = 0;

    public static void onLocation(Location location) {
        long t = location.getTime();
        if (t <= 0) return;
        lastGpsFixWallMillis = t;
        lastGpsFixElapsedMillis = SystemClock.elapsedRealtime();
    }

    /** Call right before going to sleep (on com.qf.action.ACC_OFF) to anchor the fallback. */
    public static void onAccOff(Context context) {
        Prefs.putLong(context, Prefs.KEY_SLEEP_ANCHOR_WALL, System.currentTimeMillis());
        Prefs.putLong(context, Prefs.KEY_SLEEP_ANCHOR_ELAPSED, SystemClock.elapsedRealtime());
    }

    /** See DebugTimeReceiver for the adb commands that drive these. */
    public static void setDebugOverride(Context context, long targetWallMillis) {
        Prefs.putLong(context, Prefs.KEY_DEBUG_TIME_WALL, targetWallMillis);
        Prefs.putLong(context, Prefs.KEY_DEBUG_TIME_ELAPSED, SystemClock.elapsedRealtime());
    }

    public static void clearDebugOverride(Context context) {
        Prefs.get(context).edit()
                .remove(Prefs.KEY_DEBUG_TIME_WALL)
                .remove(Prefs.KEY_DEBUG_TIME_ELAPSED)
                .apply();
    }

    public static long nowMillis(Context context) {
        long nowElapsed = SystemClock.elapsedRealtime();

        long debugWall = Prefs.getLong(context, Prefs.KEY_DEBUG_TIME_WALL, 0);
        if (debugWall > 0) {
            long debugElapsed = Prefs.getLong(context, Prefs.KEY_DEBUG_TIME_ELAPSED, 0);
            return debugWall + (nowElapsed - debugElapsed);
        }

        if (lastGpsFixWallMillis > 0) {
            long age = nowElapsed - lastGpsFixElapsedMillis;
            if (age >= 0 && age < GPS_FIX_MAX_AGE_MS) {
                return lastGpsFixWallMillis + age;
            }
        }

        long rawWall = System.currentTimeMillis();
        long anchorWall = Prefs.getLong(context, Prefs.KEY_SLEEP_ANCHOR_WALL, 0);
        long anchorElapsed = Prefs.getLong(context, Prefs.KEY_SLEEP_ANCHOR_ELAPSED, 0);
        if (anchorWall > 0) {
            long expectedWall = anchorWall + (nowElapsed - anchorElapsed);
            if (Math.abs(rawWall - expectedWall) > STALE_TOLERANCE_MS) {
                return expectedWall;
            }
        }
        return rawWall;
    }
}
