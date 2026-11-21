package com.radiorubka.wright;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * Starts WRightService strictly after a full OS boot, on a quickboot wake, or after an app update.
 * Intentionally NOT direct-boot aware and not listening for LOCKED_BOOT_COMPLETED / USER_UNLOCKED:
 * starting that early runs this app's I/O and MCU access while storage and the OEM services are
 * still coming up. Sleep/wake (ACC_OFF/ACC_ON) is handled by the running service itself; ACC_ON is
 * also caught here only to revive the service if the system killed it while the unit slept.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "wRight_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Received broadcast action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
                // An app update kills the process, and nothing else restarts the service - the only
                // other starter is MainActivity, which might not get opened for months.
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                // Safety net, not in the spec's list: the head unit aggressively kills processes, so
                // the service may be dead when the unit wakes. A no-op if it's already running (the
                // running service handles ACC_ON itself, via its own dynamic receiver).
                "com.qf.action.ACC_ON".equals(action)) {
            startService(context, action);
        }
    }

    private static void startService(Context context, String why) {
        Log.d(TAG, "Starting WRightService as foreground service... (" + why + ")");
        try {
            ContextCompat.startForegroundService(context, new Intent(context, WRightService.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WRightService: " + e.getMessage(), e);
        }
    }
}
