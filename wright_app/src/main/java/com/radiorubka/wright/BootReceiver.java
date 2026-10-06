package com.radiorubka.wright;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the service on boot/quickboot, and as a safety net on ACC_ON in case the service's
 *  own process got killed while the unit was asleep (see WRightService's doc comment - the
 *  common case is the service staying alive through suspend and reacting to its own
 *  dynamically-registered ACC_ON receiver instead). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, WRightService.class);
        context.startForegroundService(serviceIntent);
    }
}
