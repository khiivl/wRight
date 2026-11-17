package com.radiorubka.wright;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Talks to the vendor MCU binder service, the same way BacklightColorControl does (bind "mcu_service", reflect the RPC off the
 * IMcuManager proxy). One call so far: RPC_SendHeadLightState(boolean), transaction 0x1b of android.qf.mcu.IMcuManager.
 *
 * What it really is (read from CarSettings and confirmed on the unit's UART log): ARM -> MCU  FF FD FE 03 01 16 <arg> <chk> FF,
 * answered by a plain ack. Observed on a unit with the ILL wire disconnected: arg = false puts the button backlight on,
 * arg = true puts it out. wRight sends false as the car's lights go on and true as they go off (setBacklightLights).
 */
public class McuControl {
    private static final String TAG = "wRight_Mcu";

    private static Object mcuManager;
    private static Method sendHeadLightState;

    private static synchronized boolean ensure() {
        if (mcuManager != null && sendHeadLightState != null) return true;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "mcu_service");
            if (binder == null) return false;
            Class<?> stub = Class.forName("android.qf.mcu.IMcuManager$Stub");
            mcuManager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (mcuManager == null) return false;
            sendHeadLightState = mcuManager.getClass().getMethod("RPC_SendHeadLightState", boolean.class);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "mcu_service not reachable", e);
            mcuManager = null;
            sendHeadLightState = null;
            return false;
        }
    }

    /** The lights state as the head unit wants it: RPC_SendHeadLightState(false) = button backlight on, (true) = off. */
    public static boolean setBacklightLights(boolean lightsOn) {
        return setHeadlightReporting(!lightsOn);
    }

    /** the raw RPC argument. Returns false if the call could not be made. */
    public static synchronized boolean setHeadlightReporting(boolean enable) {
        if (!ensure()) return false;
        try {
            sendHeadLightState.invoke(mcuManager, enable);
            Log.i(TAG, "RPC_SendHeadLightState(" + enable + ")");
            return true;
        } catch (InvocationTargetException e) {
            // a dead binder (the MCU service restarted): forget it, the next call looks it up again
            Log.w(TAG, "RPC_SendHeadLightState failed", e.getCause());
            mcuManager = null;
            sendHeadLightState = null;
            return false;
        } catch (Exception e) {
            Log.w(TAG, "RPC_SendHeadLightState failed", e);
            return false;
        }
    }
}
