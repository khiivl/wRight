package com.radiorubka.wright;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;

/**
 * Switches the system-wide theme by broadcasting com.qf.action.TEST_UIMODE, which
 * CarSettingService (running as system UID, holding MODIFY_DAY_NIGHT_MODE) picks up on an
 * unprotected dynamic receiver and uses to toggle UiModeManager's night mode. Third-party apps
 * can't call UiModeManager.setNightMode() directly - this broadcast is QF's own escape hatch for
 * it, discovered by reverse-engineering CarSettings.apk. Since it's a pure toggle, we always
 * check the current state first so we land on the explicit mode we actually want.
 */
public class ThemeControl {
    private static final String ACTION_TOGGLE = "com.qf.action.TEST_UIMODE";

    public static boolean isDarkActive(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Returns true if a toggle broadcast was actually sent (i.e. the mode needed to change). */
    public static boolean setDark(Context context, boolean wantDark) {
        if (wantDark == isDarkActive(context)) return false;
        context.sendBroadcast(new Intent(ACTION_TOGGLE));
        return true;
    }
}
