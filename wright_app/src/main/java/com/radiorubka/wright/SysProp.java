package com.radiorubka.wright;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Reflection into android.os.SystemProperties. This is a hidden framework API, not a public
 * SDK surface - but it's the same technique wDSP's McuService already uses successfully on this
 * exact ROM to read sys.qf.last_audio_src, so it's proven to work here without root/Shizuku.
 */
public class SysProp {
    private static final String TAG = "wRight_SysProp";

    private static Method getMethod;
    private static Method getBooleanMethod;

    static {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            getMethod = sp.getMethod("get", String.class, String.class);
            getBooleanMethod = sp.getMethod("getBoolean", String.class, boolean.class);
        } catch (Exception e) {
            Log.e(TAG, "Reflection init failed", e);
        }
    }

    public static String get(String key, String def) {
        try {
            if (getMethod != null) {
                return (String) getMethod.invoke(null, key, def);
            }
        } catch (Exception ignored) {}
        return def;
    }

    public static boolean getBoolean(String key, boolean def) {
        try {
            if (getBooleanMethod != null) {
                return (boolean) getBooleanMethod.invoke(null, key, def);
            }
        } catch (Exception ignored) {}
        return def;
    }
}
