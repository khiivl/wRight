package com.radiorubka.wright;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Calendar;

/**
 * Debug-only time simulation, driven entirely by adb - not part of the app's own UI, this is a
 * testing tool. TimeSource.nowMillis() otherwise anchors to the GPS fix (or a sleep-anchor
 * projection) and deliberately ignores the real system clock, so simply running `adb shell date`
 * would NOT fool wRight once it has a fix - this overrides even that. The simulated clock keeps
 * advancing in real time from whatever point it's set to (it's not frozen), so sunset/sunrise
 * transitions, Night Shift's fade, and the brightness curve can all be watched play out normally
 * instead of looking stuck - jump near a transition and just wait a few seconds/minutes.
 *
 * -p com.radiorubka.wright is not optional: since Android 8.0, a manifest-declared receiver like
 * this one won't be invoked by a plain implicit broadcast (no target package) at all - the
 * broadcast just silently goes nowhere. Explicitly targeting the package is what makes it
 * deliverable.
 *
 * Jump to a specific time of day (today's date, device's local timezone):
 *   adb shell am broadcast -p com.radiorubka.wright -a com.radiorubka.wright.DEBUG_SET_TIME --es hhmm "18:45"
 *
 * Shift by a relative offset in minutes from the real current time (may be negative):
 *   adb shell am broadcast -p com.radiorubka.wright -a com.radiorubka.wright.DEBUG_SET_TIME --ei offsetMinutes 90
 *
 * Jump to an absolute instant (epoch milliseconds):
 *   adb shell am broadcast -p com.radiorubka.wright -a com.radiorubka.wright.DEBUG_SET_TIME --el millis 1733600000000
 *
 * Clear the simulation and go back to real GPS/clock time:
 *   adb shell am broadcast -p com.radiorubka.wright -a com.radiorubka.wright.DEBUG_CLEAR_TIME
 *
 * Any one of hhmm/offsetMinutes/millis is enough; if more than one is present hhmm wins, then
 * offsetMinutes, then millis. Setting or clearing both take effect within ~1.5s (the service's
 * own tick loop) with no need to restart the app or the unit. Watch `adb logcat -s
 * wRight_Service` for the per-tick state dump to confirm it actually landed.
 */
public class DebugTimeReceiver extends BroadcastReceiver {
    private static final String TAG = "wRight_DebugTime";
    static final String ACTION_SET = "com.radiorubka.wright.DEBUG_SET_TIME";
    static final String ACTION_CLEAR = "com.radiorubka.wright.DEBUG_CLEAR_TIME";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "onReceive: " + action); // confirms the broadcast actually reached this
                                             // receiver at all, before anything else can go wrong
        if (ACTION_CLEAR.equals(action)) {
            TimeSource.clearDebugOverride(context);
            Log.i(TAG, "cleared debug time override");
            return;
        }
        if (!ACTION_SET.equals(action)) return;

        Long target = null;
        if (intent.hasExtra("hhmm")) {
            target = parseTodayAt(intent.getStringExtra("hhmm"));
        } else if (intent.hasExtra("offsetMinutes")) {
            target = TimeSource.nowMillis(context) + intent.getIntExtra("offsetMinutes", 0) * 60_000L;
        } else if (intent.hasExtra("millis")) {
            target = intent.getLongExtra("millis", 0);
        }
        if (target != null) {
            TimeSource.setDebugOverride(context, target);
            Log.i(TAG, "set debug time override to " + new java.util.Date(target));
        } else {
            Log.w(TAG, "DEBUG_SET_TIME received but no valid hhmm/offsetMinutes/millis extra found");
        }
    }

    /** Null on a malformed hhmm rather than throwing - a typo'd adb command should no-op, not
     *  crash the receiver. */
    private static Long parseTodayAt(String hhmm) {
        if (hhmm == null) return null;
        String[] parts = hhmm.split(":");
        if (parts.length != 2) return null;
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
