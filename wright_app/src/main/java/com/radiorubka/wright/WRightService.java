package com.radiorubka.wright;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Date;

/**
 * Foreground service that owns the whole schedule: location -> sun times -> dark mode / night
 * shift / brightness. Stays alive across com.qf.action.ACC_OFF (the unit suspends rather than
 * rebooting - see TimeSource's doc comment for why that matters) and just throttles its own
 * polling down while asleep.
 */
public class WRightService extends Service implements LocationListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "wRight_Service";
    private static final String CHANNEL_ID = "wright_background";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_ACC_ON = "com.qf.action.ACC_ON";
    private static final String ACTION_ACC_OFF = "com.qf.action.ACC_OFF";

    private static final long TICK_INTERVAL_MS = 1_500; // headlight responsiveness
    private static final long FULL_RECOMPUTE_INTERVAL_MS = 60_000; // brightness/night-shift smoothness
    private static final long LOCATION_MIN_TIME_MS = 10 * 60_000;
    private static final long BRIGHTNESS_REASSERT_DELAY_MS = 500;
    // Grace period after sunset before Night Shift's warmth starts ramping, so dark mode visibly
    // switches first instead of both changes landing in the same instant.
    private static final long NIGHT_SHIFT_START_DELAY_MS = 5_000;

    // Status.KEY_BRIGHTNESS_PCT sentinels distinguishing *why* nothing's being applied, since
    // "disabled by the user" and "nighttime, deferred to CarSettings' own profile" look
    // identical to a tester watching the Status panel otherwise.
    private static final int STATUS_BRIGHTNESS_DISABLED = -1;
    private static final int STATUS_BRIGHTNESS_NIGHT = -2;

    private HandlerThread workerThread;
    private Handler bgHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private double lastLat;
    private double lastLon;
    private boolean hasFix = false;

    private boolean headlightsOn = false;
    private long lastFullRecompute = 0;
    private boolean accOn = true;

    // Edge-detection for the real (clock-based) day<->night transition, independent of
    // Lights-Override-forced uiMode flips - null means "unknown yet" so the very first tick after
    // a fresh start never misfires as a transition. See primeOrTransitionNightProfiles().
    private Boolean lastNightWindow = null;

    // Edge-detection for the settled (uiMode, headlight) combination CarSettingService actually
    // resolves brightness off - used to react precisely when Lights Override settles into
    // night+headlights-on or day+headlights-off (the two profiles that matter without root, since
    // we can't keep their table entries primed) rather than on every tick or every toggle. The
    // other two combinations (day+headlights-on, night+headlights-off) are deliberately ignored -
    // see WRightService's day-headlight-override design discussion.
    private boolean lastNightLightsOnSettled = false;
    private boolean lastDayHeadlightsOffSettled = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case ACTION_ACC_ON:
                    accOn = true;
                    restartLocationUpdates();
                    startTickLoop();
                    recomputeAndApply(true);
                    // The ambient strip's own memory of its color doesn't necessarily survive
                    // the MCU's side of a sleep cycle - reassert ours so it's not left stale.
                    BacklightColorControl.apply(Prefs.getInt(WRightService.this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889));
                    // Belt and suspenders: if the user poked around in CarSettings and
                    // re-enabled its own auto-dark-mode logic, put it back to sleep on every wake
                    // rather than only once at service startup.
                    ConfigServiceControl.disableStockAutoDarkMode(WRightService.this);
                    break;
                case ACTION_ACC_OFF:
                    accOn = false;
                    TimeSource.onAccOff(WRightService.this);
                    stopTickLoop();
                    try {
                        locationManager.removeUpdates(WRightService.this);
                    } catch (SecurityException ignored) {}
                    break;
                case Intent.ACTION_TIME_CHANGED:
                case Intent.ACTION_TIMEZONE_CHANGED:
                    recomputeAndApply(true);
                    break;
            }
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            // This loop runs on a HandlerThread with no default uncaught-exception handler, so
            // any exception here - including ones we haven't anticipated - would otherwise kill
            // the whole service process. A bad tick should log and retry next cycle, never take
            // the schedule down with it.
            try {
                boolean fullTick = System.currentTimeMillis() - lastFullRecompute >= FULL_RECOMPUTE_INTERVAL_MS;
                pollHeadlight();
                recomputeAndApply(fullTick);
                if (fullTick) lastFullRecompute = System.currentTimeMillis();
            } catch (Exception e) {
                Log.e(TAG, "Tick failed, will retry next cycle", e);
            } finally {
                bgHandler.postDelayed(this, TICK_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("wRightWorker");
        workerThread.start();
        bgHandler = new Handler(workerThread.getLooper());

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        lastLat = Double.longBitsToDouble(Prefs.getLong(this, Prefs.KEY_LAST_LAT, Double.doubleToLongBits(0)));
        lastLon = Double.longBitsToDouble(Prefs.getLong(this, Prefs.KEY_LAST_LON, Double.doubleToLongBits(0)));
        hasFix = Prefs.getLong(this, Prefs.KEY_LAST_FIX_TIME, 0) > 0;

        Prefs.get(this).registerOnSharedPreferenceChangeListener(this);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ACC_ON);
        filter.addAction(ACTION_ACC_OFF);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(receiver, filter);

        restartLocationUpdates();
        startTickLoop();
        recomputeAndApply(true);
        ConfigServiceControl.disableStockAutoDarkMode(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this);
        stopTickLoop();
        workerThread.quitSafely();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- location -----------------------------------------------------------------------

    private void restartLocationUpdates() {
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_MIN_TIME_MS, 0f, this);
        } catch (SecurityException e) {
            Log.w(TAG, "No location permission yet", e);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "GPS provider unavailable", e);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        TimeSource.onLocation(location);
        lastLat = location.getLatitude();
        lastLon = location.getLongitude();
        hasFix = true;
        Prefs.putLong(this, Prefs.KEY_LAST_LAT, Double.doubleToLongBits(lastLat));
        Prefs.putLong(this, Prefs.KEY_LAST_LON, Double.doubleToLongBits(lastLon));
        Prefs.putLong(this, Prefs.KEY_LAST_FIX_TIME, System.currentTimeMillis());
        Status.putDouble(this, Status.KEY_LAT, lastLat);
        Status.putDouble(this, Status.KEY_LON, lastLon);
        Status.putBool(this, Status.KEY_HAS_FIX, true);
        recomputeAndApply(true);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    // --- tick loop ------------------------------------------------------------------------

    private void startTickLoop() {
        bgHandler.removeCallbacks(tickRunnable);
        bgHandler.post(tickRunnable);
    }

    private void stopTickLoop() {
        bgHandler.removeCallbacks(tickRunnable);
    }

    private void pollHeadlight() {
        boolean on = SysProp.getBoolean("sys.qf.vehicle.headlight_state", false);
        Status.putBool(this, Status.KEY_HEADLIGHTS_ON, on);
        if (on != headlightsOn) {
            headlightsOn = on;
            recomputeAndApply(false);
        }
    }

    // --- core schedule logic ----------------------------------------------------------------

    /** Entry point called from three places (tick loop, location callback, prefs listener) -
     *  catches its own exceptions so a problem on any one of those paths can't take the service
     *  process down with it. */
    private void recomputeAndApply(boolean fullTick) {
        try {
            doRecomputeAndApply(fullTick);
        } catch (Exception e) {
            Log.e(TAG, "recomputeAndApply failed", e);
        }
    }

    private void doRecomputeAndApply(boolean fullTick) {
        if (!hasFix) {
            // The single most likely reason "nothing reacts at all" looks identical to a broken
            // broadcast/tick loop: every recompute bails out right here until the first GPS fix
            // ever lands. Logging it so that's distinguishable from an actually-dead loop.
            Log.i(TAG, "doRecomputeAndApply: no GPS fix yet, skipping");
            updateNotification(null, null);
            return;
        }

        long now = TimeSource.nowMillis(this);
        SunCalculator.SunTimes sun = computeEffectiveSunTimes(now);
        boolean nightWindow = now < sun.sunriseUtcMillis || now >= sun.sunsetUtcMillis;

        Status.putLong(this, Status.KEY_NOW_MILLIS, now);
        Status.putLong(this, Status.KEY_SUNRISE_MILLIS, sun.sunriseUtcMillis);
        Status.putLong(this, Status.KEY_SUNSET_MILLIS, sun.sunsetUtcMillis);

        boolean darkModeEnabled = Prefs.getBool(this, Prefs.KEY_DARK_MODE_ENABLED, true);
        boolean lightsOverrideEnabled = Prefs.getBool(this, Prefs.KEY_LIGHTS_OVERRIDE_ENABLED, false);

        boolean scheduleWantsDark = darkModeEnabled && nightWindow;
        boolean lightsForceDark = lightsOverrideEnabled && headlightsOn;
        boolean effectiveDark = scheduleWantsDark || lightsForceDark;

        // Edge-detect the real (clock-based) day<->night transition - deliberately independent of
        // effectiveDark/Lights Override, which can flip uiMode mid-day and must NOT be mistaken
        // for the genuine transition. Restoring the user's real night brightness has to happen
        // BEFORE setDark() below fires the TEST_UIMODE broadcast, since CarSettingService resolves
        // its brightness profile synchronously off that same broadcast.
        if (lastNightWindow != null && !lastNightWindow && nightWindow) {
            restoreSavedNightBrightness();
        } else if (lastNightWindow != null && lastNightWindow && !nightWindow) {
            captureNightBrightness();
        }
        lastNightWindow = nightWindow;

        boolean currentlyDark = ThemeControl.isDarkActive(this);

        Log.i(TAG, "tick: now=" + new Date(now) + " sunrise=" + new Date(sun.sunriseUtcMillis)
                + " sunset=" + new Date(sun.sunsetUtcMillis) + " nightWindow=" + nightWindow
                + " darkModeEnabled=" + darkModeEnabled + " lightsOverride=" + lightsOverrideEnabled
                + " headlightsOn=" + headlightsOn + " effectiveDark=" + effectiveDark
                + " currentlyDark=" + currentlyDark + " fullTick=" + fullTick);

        // Refresh the brightness profile tables on EVERY tick, before the broadcast below, not
        // just when we can see a toggle coming. CarSettingService appears to react to the raw
        // headlight_state property change by itself (not only to our ui_night_mode broadcast),
        // so by the time our own tick even notices headlightsOn flipped, it may have already
        // re-resolved brightness off whatever was last sitting in the table - priming only right
        // before our own setDark() call was still too late in that case. Keeping the table
        // continuously current removes the timing dependency entirely.
        applyBrightness();

        // Settled-state edge detection for the Lights-Override-during-day case: CarSettingService
        // resolves brightness off the combination of uiMode and headlight state, and without root
        // we can't keep every profile's table entry primed ahead of time - only the live write
        // works unconditionally. Reassert right after landing on either of the two profiles that
        // actually matter here (night+headlights-on, day+headlights-off), not on every toggle and
        // not on the other two combinations (day+headlights-on, night+headlights-off), which are
        // deliberately left alone. At night this is moot anyway: applyBrightness() no-ops once
        // nightWindow is true, so this can't regress the real schedule-based transition.
        boolean nightLightsOnSettled = effectiveDark && headlightsOn;
        boolean dayHeadlightsOffSettled = !effectiveDark && !headlightsOn;
        boolean enteringNightLightsOn = nightLightsOnSettled && !lastNightLightsOnSettled;
        boolean enteringDayHeadlightsOff = dayHeadlightsOffSettled && !lastDayHeadlightsOffSettled;
        lastNightLightsOnSettled = nightLightsOnSettled;
        lastDayHeadlightsOffSettled = dayHeadlightsOffSettled;

        ThemeControl.setDark(this, effectiveDark);
        if (enteringNightLightsOn || enteringDayHeadlightsOff) {
            // Give CarSettingService's resolver time to actually stomp screen_brightness off its
            // broadcast first, then correct it - reacting synchronously here would just get
            // overwritten moments later once that resolution actually runs.
            mainHandler.postDelayed(this::applyBrightness, BRIGHTNESS_REASSERT_DELAY_MS);
        }

        if (fullTick) {
            applyNightShift(now, sun);
        }

        updateNotification(sun, nightWindow);
    }

    /** Restores the user's real night brightness (captured by captureNightBrightness() at the
     *  last night->day transition) into the NIGHT profile keys, right before the genuine
     *  day->night transition - so CarSettingService's resolver reads the correct value the
     *  instant it re-fires, instead of whatever daytime value we'd been priming all day. No-op
     *  if nothing has ever been captured (fresh install, or the user never adjusted brightness
     *  at night). */
    private void restoreSavedNightBrightness() {
        int headlight = Prefs.getInt(this, Prefs.KEY_SAVED_NIGHT_HEADLIGHT_BRIGHTNESS, -1);
        int noHeadlight = Prefs.getInt(this, Prefs.KEY_SAVED_NIGHT_NO_HEADLIGHT_BRIGHTNESS, -1);
        if (headlight > 0 && noHeadlight > 0) {
            BrightnessControl.setNightProfiles(this, headlight, noHeadlight);
        }
    }

    /** Captures whatever the NIGHT profile keys currently hold - i.e. the user's real night
     *  brightness preference - right before the genuine night->day transition, before daytime
     *  priming starts overwriting them. */
    private void captureNightBrightness() {
        int headlight = BrightnessControl.readNightHeadlight(this);
        int noHeadlight = BrightnessControl.readNightNoHeadlight(this);
        if (headlight > 0) Prefs.putInt(this, Prefs.KEY_SAVED_NIGHT_HEADLIGHT_BRIGHTNESS, headlight);
        if (noHeadlight > 0) Prefs.putInt(this, Prefs.KEY_SAVED_NIGHT_NO_HEADLIGHT_BRIGHTNESS, noHeadlight);
    }

    private void applyNightShift(long now, SunCalculator.SunTimes sun) {
        boolean enabled = Prefs.getBool(this, Prefs.KEY_NIGHT_SHIFT_ENABLED, false);
        if (!enabled) {
            NightShiftControl.apply(this, false, 0f);
            return;
        }
        int fadeMin = Prefs.getInt(this, Prefs.KEY_NIGHT_SHIFT_FADE_MIN, 20);
        long fadeMs = Math.max(1, fadeMin) * 60_000L;
        int userIntensityPct = Prefs.getInt(this, Prefs.KEY_NIGHT_SHIFT_INTENSITY, 70);

        double fraction;
        if (now >= sun.sunsetUtcMillis) {
            // Dark mode itself flips the instant nightWindow goes true, same as sunset - delaying
            // the START of the warmth ramp (not the ramp's own speed) means the screen visibly
            // goes dark first, and only a few seconds later does it begin to warm up, instead of
            // both changes appearing to land at once.
            long elapsedSinceSunset = now - sun.sunsetUtcMillis - NIGHT_SHIFT_START_DELAY_MS;
            fraction = clamp01(elapsedSinceSunset / (double) fadeMs);
        } else if (now < sun.sunriseUtcMillis) {
            long untilSunrise = sun.sunriseUtcMillis - now;
            fraction = untilSunrise < fadeMs ? clamp01(untilSunrise / (double) fadeMs) : 1.0;
        } else {
            fraction = 0.0;
        }
        NightShiftControl.apply(this, true, (float) (fraction * (userIntensityPct / 100.0)));
    }

    private void applyBrightness() {
        boolean enabled = Prefs.getBool(this, Prefs.KEY_BRIGHTNESS_ENABLED, false);
        if (!hasFix) {
            Status.putInt(this, Status.KEY_BRIGHTNESS_PCT, STATUS_BRIGHTNESS_DISABLED);
            return;
        }

        long now = TimeSource.nowMillis(this);
        SunCalculator.SunTimes sun = computeEffectiveSunTimes(now);
        boolean isDaytime = now >= sun.sunriseUtcMillis && now < sun.sunsetUtcMillis;
        Log.i(TAG, "applyBrightness: enabled=" + enabled + " isDaytime=" + isDaytime
                + " headlightsOn=" + headlightsOn + " hasWriteSettings=" + BrightnessControl.hasPermission(this));

        if (!enabled) {
            // Day Brightness itself is off, so the user's brightness is whatever they set
            // manually (stock slider/hardware). Still keep the NIGHT profile keys primed with
            // that live value while it's daytime, same reasoning as the curve branch below: if
            // Lights Override forces dark mode mid-day, CarSettingService must read a value that
            // matches what's actually on screen right now, not stale night data.
            if (isDaytime) {
                int current = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
                if (current > 0) BrightnessControl.primeNightProfiles(this, current);
            }
            Status.putInt(this, Status.KEY_BRIGHTNESS_PCT, STATUS_BRIGHTNESS_DISABLED);
            return;
        }

        if (!isDaytime) {
            // Nighttime: stay completely hands-off. CarSettingService's own night-profile
            // resolver (screen_brightness_night_*, triggered by our dark-mode toggle) owns
            // screen_brightness after dark - we were previously still writing minPct here every
            // tick regardless of day/night, which fought that resolver every 60s and was the
            // actual cause of "brightness doesn't update properly on profile change".
            Status.putInt(this, Status.KEY_BRIGHTNESS_PCT, STATUS_BRIGHTNESS_NIGHT);
            return;
        }

        int minPct = Prefs.getInt(this, Prefs.KEY_BRIGHTNESS_MIN, 15);
        int maxPct = Prefs.getInt(this, Prefs.KEY_BRIGHTNESS_MAX, 100);
        if (maxPct < minPct) maxPct = minPct;

        double dayFraction = (now - sun.sunriseUtcMillis) / (double) (sun.sunsetUtcMillis - sun.sunriseUtcMillis);
        double curve = Math.sin(Math.PI * clamp01(dayFraction)); // 0 at edges, 1 at solar noon
        double pct = minPct + curve * (maxPct - minPct);

        int value255 = Math.round((float) (pct / 100.0 * 255.0));
        BrightnessControl.apply(this, value255);
        BrightnessControl.primeNightProfiles(this, value255);
        Status.putInt(this, Status.KEY_BRIGHTNESS_PCT, (int) Math.round(pct));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** The one place SunCalculator.calculate() should be called from - applies the user's
     *  +/-30min sunrise/sunset compensation sliders on top of the raw astronomical times, so
     *  scheduling, alarms, and the Status panel all agree on the same "effective" instants. */
    private SunCalculator.SunTimes computeEffectiveSunTimes(long now) {
        SunCalculator.SunTimes raw = SunCalculator.calculate(lastLat, lastLon, now);
        long sunriseOffsetMs = Prefs.getInt(this, Prefs.KEY_SUNRISE_OFFSET_MIN, 0) * 60_000L;
        long sunsetOffsetMs = Prefs.getInt(this, Prefs.KEY_SUNSET_OFFSET_MIN, 0) * 60_000L;
        return new SunCalculator.SunTimes(raw.sunriseUtcMillis + sunriseOffsetMs, raw.sunsetUtcMillis + sunsetOffsetMs);
    }

    // --- notification -----------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text_waiting))
                .setSmallIcon(android.R.drawable.ic_menu_day)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void updateNotification(@Nullable SunCalculator.SunTimes sun, @Nullable Boolean nightWindow) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        String text;
        if (sun == null) {
            text = getString(R.string.notif_text_waiting);
        } else {
            boolean night = nightWindow != null && nightWindow;
            long next = night ? sun.sunriseUtcMillis : sun.sunsetUtcMillis;
            String label = night ? "sunrise" : "sunset";
            String time = DateFormat.format("HH:mm", new Date(next)).toString();
            text = getString(R.string.notif_text_active, label, time);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_day)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Backlight color is applied live by MainActivity itself on every slider/hex edit (and
        // reasserted by this service only once, on ACC_ON) - routing it through a full
        // recompute+notification-rebuild on every single RGB tick during a drag was spamming
        // NotificationManager badly enough to hit its rate limit for no benefit.
        if (Prefs.KEY_BACKLIGHT_COLOR.equals(key)) return;
        bgHandler.post(() -> recomputeAndApply(true));
    }
}
