package com.radiorubka.wright;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Disables CarSettings' own stock "Automatic switching of dark mode" and "Dark mode follows
 * changes in headlights" toggles (DisplaySetFragment.onCheckout(), config keys dark_mode_auto /
 * dark_mode_headlight) - left on, either one would fight wRight's own Dark Mode / Lights Override
 * features for control of ui_night_mode. Written through the same vendor config service
 * CarSettings itself uses: Context.getSystemService("config_service") returns an
 * android.qf.config.ConfigInfoManager, reflected here since that class isn't part of the public
 * SDK. Unlike McuManager this is reached through the ordinary getSystemService(String) path
 * rather than a raw ServiceManager/binder lookup - ordinary apps can call it.
 */
public class ConfigServiceControl {
    private static final String TAG = "wRight_ConfigService";
    private static final String KEY_DARK_MODE_AUTO = "dark_mode_auto";
    private static final String KEY_DARK_MODE_HEADLIGHT = "dark_mode_headlight";

    private static Object configInfoManager;
    private static Method updateMethod;

    private static void ensure(Context context) throws Exception {
        if (configInfoManager != null) return;
        configInfoManager = context.getSystemService("config_service");
        if (configInfoManager != null) {
            updateMethod = configInfoManager.getClass()
                    .getMethod("updateConfigItemInfo", String.class, String.class);
        }
    }

    /** Idempotent - safe to call on every wake, which is how WRightService uses it to make sure
     *  a manual re-enable in CarSettings (or a factory reset) doesn't quietly come back.
     *  Logs distinctly by failure mode (service missing vs. the write itself throwing) - on
     *  hardware where this doesn't seem to take effect, `adb logcat -s wRight_ConfigService` says
     *  whether the write actually succeeded (in which case CarSettings' own checkbox just isn't
     *  live-refreshed, a cosmetic issue in CarSettings' UI, not a wRight bug) or never went
     *  through at all (service unavailable from a third-party app on this ROM, or the write threw). */
    public static void disableStockAutoDarkMode(Context context) {
        try {
            ensure(context);
        } catch (Exception e) {
            Log.w(TAG, "Could not obtain config_service", e);
            return;
        }
        if (configInfoManager == null) {
            Log.w(TAG, "getSystemService(\"config_service\") returned null - not available to this app on this ROM");
            return;
        }
        if (updateMethod == null) {
            Log.w(TAG, "config_service has no updateConfigItemInfo(String,String) method");
            return;
        }
        try {
            updateMethod.invoke(configInfoManager, KEY_DARK_MODE_AUTO, "0");
            updateMethod.invoke(configInfoManager, KEY_DARK_MODE_HEADLIGHT, "0");
            Log.i(TAG, "dark_mode_auto / dark_mode_headlight write succeeded");
        } catch (Exception e) {
            Log.w(TAG, "updateConfigItemInfo() threw", e);
        }
    }
}
