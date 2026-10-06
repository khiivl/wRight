package com.radiorubka.wright;

import android.graphics.Color;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Drives the ambient/backlight RGB strip through the vendor MCU binder service - reverse
 * engineered from CarSettings' GeneralSetFragment.onColorPicked(): it decomposes the picked
 * color into R/G/B, scales each channel by R_5_MAX/G_5_MAX/B_5_MAX over REALITY_MAX (a factory
 * calibration hook that defaults to 255/255 - identity - unless a technician recalibrated this
 * unit, so plain 0-255 is correct here), packs [r, g, b, dynamicTransition] into 4 bytes, and
 * sends it via McuManager.RPC_SendSetQicaideng(byte[], int). Same reflection pattern wDSP's
 * McuService already uses successfully for RPC_SetEQData/RPC_SendMcuMsgData - bind "mcu_service",
 * get IMcuManager$Stub.asInterface(binder), reflect the RPC method off that instance.
 */
public class BacklightColorControl {
    private static final String TAG = "wRight_Backlight";

    private static Object mcuManagerInstance;
    private static Method sendQicaidengMethod;
    private static Method setPropMethod;

    private static void ensureMcuManager() throws Exception {
        if (mcuManagerInstance != null) return;
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "mcu_service");
        if (binder == null) return;
        Class<?> stub = Class.forName("android.qf.mcu.IMcuManager$Stub");
        mcuManagerInstance = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (mcuManagerInstance != null) {
            sendQicaidengMethod = mcuManagerInstance.getClass()
                    .getMethod("RPC_SendSetQicaideng", byte[].class, int.class);
        }
    }

    /** rgb as a standard 0xRRGGBB int (alpha ignored). */
    public static void apply(int rgb) {
        int r = Color.red(rgb);
        int g = Color.green(rgb);
        int b = Color.blue(rgb);
        try {
            ensureMcuManager();
            if (sendQicaidengMethod != null && mcuManagerInstance != null) {
                byte[] payload = {(byte) r, (byte) g, (byte) b, 0};
                sendQicaidengMethod.invoke(mcuManagerInstance, payload, payload.length);
            }
        } catch (Exception e) {
            Log.w(TAG, "RPC_SendSetQicaideng failed", e);
        }
        persistLastColor(rgb);
    }

    /** Best-effort only - CarSettings itself writes this for next-boot restore, but an
     *  untrusted app's write to property_service can be blocked by SELinux depending on the
     *  property's policy label. The RPC call above is what actually drives the hardware live,
     *  so a failure here is harmless either way. */
    private static void persistLastColor(int rgb) {
        try {
            if (setPropMethod == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                setPropMethod = sp.getMethod("set", String.class, String.class);
            }
            setPropMethod.invoke(null, "persist.sys.color.light.value", String.valueOf(rgb));
        } catch (Exception ignored) {
        }
    }
}
