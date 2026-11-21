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
import android.os.SystemClock;
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
 * polling down while asleep - confirmed this does NOT match wDSP's McuService (which does the
 * same thing), despite an earlier attempt here to instead stop/restart the whole service on
 * every ACC cycle; BootReceiver's own ACC_ON handling remains as the safety net for the case
 * where this service's process got killed outright while the unit was asleep, same as before.
 */
public class WRightService extends Service implements LocationListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "wRight_Service";
    private static final String CHANNEL_ID = "wright_background";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_ACC_ON = "com.qf.action.ACC_ON";
    private static final String ACTION_ACC_OFF = "com.qf.action.ACC_OFF";
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF";
    private static final String ACTION_QUICKBOOT_POWEROFF_HTC = "com.htc.intent.action.QUICKBOOT_POWEROFF";

    // Wake sequence (boot, ACC_ON, quickboot). The MCU and power rails need a moment to settle, so
    // GPS starts 500ms after the wake, the non-time-dependent config (backlight color, stock
    // auto-dark-mode off) goes out at 1000ms, and everything time-dependent (dark mode, night
    // shift, brightness) waits until a fresh GPS fix arrives - or, failing that, until GPS has had
    // GPS_FALLBACK_AFTER_MS to show up, after which the last known coordinates plus the clock
    // (TimeSource's own fallback) are used until a fix does arrive.
    private static final long WAKE_GPS_DELAY_MS = 500;
    private static final long WAKE_CONFIG_DELAY_MS = 1_000;
    private static final long GPS_FALLBACK_AFTER_MS = 5_000;

    private static final long TICK_INTERVAL_MS = 1_500; // headlight responsiveness
    private static final long FULL_RECOMPUTE_INTERVAL_MS = 60_000; // brightness/night-shift smoothness
    private static final long LOCATION_MIN_TIME_MS = 0;       // every fix the receiver produces, as fast as it produces them
    private static final long LOCATION_RETRY_MS = 15_000, POSITION_EVERY_MS = 60_000;
    private boolean fixSinceWake;
    private static final long GPS_LIVE_MS = 10_000;
    private long lastPositionMs, lastSentFixNanos, fixCountSince, lastFreshFixMs;
    private int fixesSent, fixesDropped;
    private volatile SpeedTracker.Sample lastSample;
    private final java.util.Map<String, Integer> fixCounts = new java.util.HashMap<>();
    private final SpeedTracker speedTracker = new SpeedTracker();
    private static final long BRIGHTNESS_REASSERT_DELAY_MS = 500;
    // Grace period after sunset before Night Shift's warmth starts ramping, so dark mode visibly
    // switches first instead of both changes landing in the same instant.
    private static final long NIGHT_SHIFT_START_DELAY_MS = 5_000;

    // Status.KEY_BRIGHTNESS_PCT sentinels distinguishing *why* nothing's being applied, since
    // "disabled by the user" and "nighttime, deferred to CarSettings' own profile" look
    // identical to a tester watching the Status panel otherwise.
    private static final int STATUS_BRIGHTNESS_DISABLED = -1;
    private static final int STATUS_BRIGHTNESS_NIGHT = -2;
    private static final int STATUS_BRIGHTNESS_MANUAL = -3;

    private HandlerThread workerThread;
    private Handler bgHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private double lastLat;
    private double lastLon;
    private boolean hasFix = false;

    // Written from the main thread (broadcasts) and read on the worker thread.
    private volatile boolean applyGateOpen = false;   // nothing time-dependent is applied until true (see openApplyGate)

    // Named Runnables (not this::method each time) so removeCallbacks() can actually match them.
    private final Runnable startGpsRunnable = this::startGpsStage;
    private final Runnable gpsFallbackRunnable = this::gpsFallbackStage;
    private final Runnable wakeConfigRunnable = this::wakeConfigStage;
    private final Runnable reassertBrightnessRunnable = this::applyBrightness;

    private boolean headlightsOn = false;
    // Elapsed-realtime based (SystemClock): the wall clock of this unit jumps (GPS/NTP corrections, suspend), and a jump
    // backwards used to keep the "60 s since the last full recompute" test false for as long as the jump was big.
    private long lastFullRecompute = -FULL_RECOMPUTE_INTERVAL_MS;
    private boolean accOn = true;

    // --- headlights and dimmer without the ILL wire -------------------------------------------------------------
    // wFabia (the only owner of the CAN adapter) hands the car's lights and dimmer wheel level over a binder pipe.
    private VehiclePipeClient pipe;
    private volatile boolean canValid, canLightsOn;
    private volatile int canLevel;
    private boolean lastEffectiveDark;
    private int mcuSentState = -1;              // the lights state last passed to the MCU / the property: -1 none, 0 off, 1 on
    private long lastMcuAssertMs;
    private boolean dimmerLoopRunning, dimmerWasActive, buttonsWasActive;   // dimmerWasActive = display, buttonsWasActive = strip
    private long lastDimmerStepMs, lastButtonsWriteMs;
    private int lastDisplayWritten = -1, lastButtonsRgb = -1;
    /** the last dimmed button colour the dimmer wrote (-1 none). Survives the dimmer's release and an ACC cycle, so the wake
     *  puts the strip back at that colour instead of at the user's full one. Also kept in Prefs for a service restart. */
    private int lastDimmedRgb = -1;
    private static final String PREF_LAST_DIMMED_RGB = "last_dimmed_rgb";
    // The display has about 15 integer steps, smoothing only adds delay there. The button colour has 256 steps per channel
    // and gets a very light interpolation, so the filtered level reaches both within a frame or two.
    private final DimmerMap.Smoother displaySmoother = new DimmerMap.Smoother(1f);
    private final DimmerMap.Smoother buttonsSmoother = new DimmerMap.Smoother(50f);
    private static final long DIMMER_LOOP_MS = 40, BUTTONS_MIN_GAP_MS = 60;
    private static final String PROP_HEADLIGHT_STATE = "sys.qf.vehicle.headlight_state";
    private static final long MCU_REASSERT_MS = 30_000;

    // Edge-detection for the real (clock-based) day<->night transition, independent of
    // Lights-Override-forced uiMode flips - null means "unknown yet" so the very first tick after
    // a fresh start never misfires as a transition. See primeOrTransitionNightProfiles().
    private Boolean lastNightWindow = null;

    // applyBrightness() (the day curve) runs once per full recompute, not on every 1.5 s tick: the curve moves by one 0..255 step
    // every minute or more. It also runs at once when the headlight state changes.
    private boolean lastBrightnessHeadlights;

    // Manual brightness changes at night: a change of the live screen_brightness that is not ours and not a reaction of
    // CarSettingService to an event is the user's, and is saved as their night brightness (see watchManualBrightness()).
    private static final long MANUAL_SETTLE_MS = 5_000;
    private long manualSettleUntilMs;
    private int lastSeenLive = -1;
    /** after ACC_ON the pipe needs a moment (wFabia opens the adapter again): the last lights state holds until it is fresh */
    private static final long WAKE_HOLD_MS = 10_000;
    private long wakeHoldUntilMs;
    /** the user set the brightness by hand in the daytime: the day curve stays off until the next wake (ACC_ON) or sunset */
    private volatile boolean manualDayOverride;

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
                case ACTION_QUICKBOOT_POWERON:
                case ACTION_QUICKBOOT_POWERON_HTC:
                    beginWakeSequence(action);
                    break;
                case ACTION_ACC_OFF:
                case ACTION_QUICKBOOT_POWEROFF:
                case ACTION_QUICKBOOT_POWEROFF_HTC:
                    beginSleep(action);
                    break;
                case Intent.ACTION_TIME_CHANGED:
                case Intent.ACTION_TIMEZONE_CHANGED:
                    if (accOn) recomputeAndApply(true);
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
                boolean fullTick = SystemClock.elapsedRealtime() - lastFullRecompute >= FULL_RECOMPUTE_INTERVAL_MS;
                // "has fix" on the status panel means a live fix: a real fix in the last 10 s, not the position kept from before the sleep
                Status.putBool(WRightService.this, Status.KEY_HAS_FIX,
                        fixSinceWake && SystemClock.elapsedRealtime() - lastFreshFixMs <= GPS_LIVE_MS);
                pollHeadlight();                // also repeats the MCU state every 30 s
                watchManualBrightness();
                recomputeAndApply(fullTick);
                if (fullTick) lastFullRecompute = SystemClock.elapsedRealtime();
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
        filter.addAction(ACTION_QUICKBOOT_POWERON);
        filter.addAction(ACTION_QUICKBOOT_POWERON_HTC);
        filter.addAction(ACTION_QUICKBOOT_POWEROFF);
        filter.addAction(ACTION_QUICKBOOT_POWEROFF_HTC);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(receiver, filter);

        // before anything can write the night profile keys: what is there now is the user's own
        captureNightBrightnessIfMissing();
        pipe = new VehiclePipeClient(this, bgHandler, (valid, lights, level) -> {
            canValid = valid;
            canLightsOn = valid && lights;
            canLevel = level;
            pollHeadlight();                 // react at once, not on the next 1.5 s tick
            if (dimmerLoopRunning) dimmerStep();   // and drive the outputs now, not on the next loop tick
        });

        // A service start (boot, update, app restart) is a wake like any other.
        beginWakeSequence("service start");
    }

    // --- wake / sleep sequence ----------------------------------------------------------------

    /** Safe to call repeatedly (duplicate ACC_ON + QUICKBOOT_POWERON broadcasts): every call
     *  cancels whatever the previous one had pending and starts over. */
    private void beginWakeSequence(String why) {
        Log.i(TAG, "wake sequence (" + why + "): GPS in " + WAKE_GPS_DELAY_MS + "ms, apply gate closed");
        accOn = true;
        cancelPendingWork();
        applyGateOpen = false;
        wakeHoldUntilMs = SystemClock.elapsedRealtime() + WAKE_HOLD_MS;
        manualSettleUntilMs = SystemClock.elapsedRealtime() + MANUAL_SETTLE_MS;
        lastSeenLive = -1;
        manualDayOverride = false;
        captureNightBrightnessIfMissing();
        NightShiftControl.invalidate();     // the colour service may have lost its state across the sleep
        dimmerWasActive = false;
        buttonsWasActive = false;
        lastButtonsRgb = -1;
        // The tick loop starts right away so headlight state keeps being tracked, but every recompute is a no-op until
        // the gate opens.
        startTickLoop();
        // the vehicle pipe and the dimmer start again; until wFabia's state is fresh the last known lights state holds
        if (pipe != null) pipe.start();
        updateDimmerLoop();
        bgHandler.postDelayed(startGpsRunnable, WAKE_GPS_DELAY_MS);
        bgHandler.postDelayed(wakeConfigRunnable, WAKE_CONFIG_DELAY_MS);
    }

    private void beginSleep(String why) {
        Log.i(TAG, "sleep (" + why + "): cancelling pending wake work, stopping GPS and polling");
        accOn = false;
        cancelPendingWork();
        applyGateOpen = false;
        // The dimmed colour goes into the persistent property too (the one the head unit restores from on its next
        // start), so a restore from it comes back dim, and releaseDimmer leaves the strip alone while the ignition is off.
        if (headlightsOn && lastDimmedRgb != -1 && Prefs.getBool(this, Prefs.KEY_DIMMER_ENABLED, false)) {
            Prefs.putInt(this, PREF_LAST_DIMMED_RGB, lastDimmedRgb);
            BacklightColorControl.apply(lastDimmedRgb, true);
        }
        // The button backlight goes out with the ignition, and what the head unit restores at its next start is "out" as well:
        // it sends its stored headlight_enable again at every start, and a stored "lights on" lit the buttons for the moments
        // before wRight had corrected the state. Only when wRight is the one deciding (the CAN lights state): with the ILL wire
        // in charge the MCU keeps its own say.
        if (Prefs.getBool(this, Prefs.KEY_LIGHTS_FROM_CAN, true) && pipe != null) {
            McuControl.setBacklightLights(false);
            BrightnessControl.runRoot("setprop persist.sys.headlight_enable false");
            mcuSentState = 0;
        }
        TimeSource.onAccOff(this);
        stopTickLoop();
        // ACC_OFF is the signal to shut everything down: the head unit goes into deep sleep. The dimmer and the
        // vehicle pipe stop (the last dimmed colour and the lights state are kept for the wake), pending delayed work
        // is dropped and the root shell is closed. Nothing runs until ACC_ON.
        dimmerLoopRunning = false;
        bgHandler.removeCallbacks(dimmerRunnable);
        mainHandler.removeCallbacksAndMessages(null);
        if (pipe != null) pipe.stop();
        canValid = false;
        BrightnessControl.shutdown();
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {}
    }

    private void cancelPendingWork() {
        bgHandler.removeCallbacks(startGpsRunnable);
        bgHandler.removeCallbacks(gpsFallbackRunnable);
        bgHandler.removeCallbacks(wakeConfigRunnable);
        bgHandler.removeCallbacks(locationRetry);
        mainHandler.removeCallbacks(reassertBrightnessRunnable);
    }

    private void startGpsStage() {
        restartLocationUpdates();           // registers, and again every 15 s until a fix arrives
        bgHandler.postDelayed(gpsFallbackRunnable, GPS_FALLBACK_AFTER_MS);
    }

    private void gpsFallbackStage() {
        if (applyGateOpen) return;
        Log.w(TAG, "no fresh GPS fix " + GPS_FALLBACK_AFTER_MS + "ms after starting it - applying with the last known coordinates and the clock until one arrives");
        openApplyGate("GPS fallback");
    }

    /** The settings that do not depend on the time of day, one second after the wake: the MCU and its power rails need that
     *  moment to settle. */
    private void wakeConfigStage() {
        // The ambient strip's own memory of its color doesn't necessarily survive the MCU's side of a sleep cycle - reassert
        // ours so it's not left stale. If the lights were on when the ignition went off, the dimmer was driving the strip: bring
        // it back at the last dimmed colour. The user's full colour here is the flash of full brightness on wake; the dimmer
        // takes over as soon as wFabia's state is fresh again (and releaseDimmer restores the full colour if it never does).
        int dimmedOnWake = wakeDimmedColour();
        if (dimmedOnWake != -1) {
            BacklightColorControl.apply(dimmedOnWake, false);
        } else {
            BacklightColorControl.apply(Prefs.getInt(this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889));
        }
        // The MCU forgets what we told it across its own sleep cycle, and CarSettings sends its stored
        // headlight_enable again at every start: say it again.
        mcuSentState = -1;
        syncMcu(true);
        // Belt and suspenders: if the user poked around in CarSettings and re-enabled its own auto-dark-mode logic, put it
        // back to sleep on every wake, not only at service startup.
        ConfigServiceControl.disableStockAutoDarkMode(this);
    }

    /** Opens the way for everything that depends on the time of day (dark mode, Night Shift, brightness): the first fresh GPS fix
     *  of the wake, or the fallback if none arrives in time. Until then nothing time-dependent is written, so the wake does not
     *  apply a schedule computed from a clock that is still wrong. */
    private void openApplyGate(String why) {
        if (applyGateOpen) return;
        applyGateOpen = true;
        Log.i(TAG, "apply gate open (" + why + ")");
        recomputeAndApply(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        restoreSavedNightBrightness();
        super.onDestroy();
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this);
        cancelPendingWork();
        stopTickLoop();
        dimmerLoopRunning = false;
        if (pipe != null) pipe.stop();
        workerThread.quitSafely();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- location -----------------------------------------------------------------------

    /** wRight is the only app that asks the head unit's location service for fixes: the position feeds the sun times, the speed
     *  goes to wFabia over the vehicle pipe for the cluster's speed row. It asks every second, always (the speed needs the rate),
     *  and after a wake it registers again every 15 s until a fix arrives: a registration made while the receiver was still waking
     *  up could otherwise stay silent for many minutes or forever. */
    private void restartLocationUpdates() {
        fixSinceWake = false;
        bgHandler.removeCallbacks(locationRetry);
        registerLocation();
        bgHandler.postDelayed(locationRetry, LOCATION_RETRY_MS);
    }

    /** every provider the head unit offers (they differ in which one carries the GPS), as wFabia used to do itself */
    private void registerLocation() {
        try {
            locationManager.removeUpdates(this);
            speedTracker.clear();
            lastSentFixNanos = 0;
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last, false);
            for (String name : locationManager.getAllProviders()) {
                if ("network".equals(name)) continue;
                try {
                    locationManager.requestLocationUpdates(name, LOCATION_MIN_TIME_MS, 0f, this, bgHandler.getLooper());
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "location provider " + name + " unavailable: " + e);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No location permission yet", e);
        }
    }

    /** one log line every 10 s with how many fixes each provider delivered: what rate the receiver really gives */
    private void countFix(String provider, long now) {
        String p = provider == null ? "?" : provider;
        Integer n = fixCounts.get(p);
        fixCounts.put(p, n == null ? 1 : n + 1);
        if (now - fixCountSince >= 10_000) {
            if (fixCountSince != 0) {
                SpeedTracker.Sample s = lastSample;
                Log.i(TAG, "location fixes in " + (now - fixCountSince) / 1000 + " s: " + fixCounts
                        + ", speed to wFabia: sent " + fixesSent + ", dropped " + fixesDropped + " (pipe " + (pipe == null ? "-" : pipe.status()) + ")"
                        + (s == null ? "" : ", last " + (s.hasSpeed ? String.format(java.util.Locale.US, "%.1f km/h", s.speedMs * 3.6) : "no speed")
                                + " from " + s.provider + ", accuracy " + s.accuracy + " m"));
            }
            fixesSent = fixesDropped = 0;
            fixCounts.clear();
            fixCountSince = now;
        }
    }

    private final Runnable locationRetry = new Runnable() {
        @Override
        public void run() {
            if (!accOn || fixSinceWake) return;
            Log.i(TAG, "no GPS fix since the wake yet, registering again");
            registerLocation();
            bgHandler.postDelayed(this, LOCATION_RETRY_MS);
        }
    };

    @Override
    public void onLocationChanged(Location location) {
        onLocationChanged(location, true);
    }

    /** fresh = a real fix from the receiver (not the cached last known location) */
    private void onLocationChanged(Location location, boolean fresh) {
        if ("network".equals(location.getProvider())) return;
        long now = SystemClock.elapsedRealtime();
        if (fresh) {
            lastFreshFixMs = now;
            if (!fixSinceWake) {
                fixSinceWake = true;
                lastPositionMs = 0;          // the first fix after a wake is always processed (position and the GPS time)
                bgHandler.removeCallbacks(locationRetry);
                Log.i(TAG, "first GPS fix since the wake (" + location.getProvider() + ")");
            }
            // the speed row of wFabia's cluster display. Sent the moment a fix arrives; passive and gps deliver the same fix
            // (same timestamp), and a slower provider's older fix must never overwrite a newer speed, so only a fix newer than the
            // last one sent goes out.
            long fixNanos = location.getElapsedRealtimeNanos();
            countFix(location.getProvider(), now);
            if (fixNanos > lastSentFixNanos) {
                lastSentFixNanos = fixNanos;
                SpeedTracker.Sample sample = speedTracker.onLocation(location);
                boolean sent = pipe != null && pipe.sendLocation(sample.hasSpeed, sample.speedMs, sample.accuracy, sample.provider);
                if (sent) fixesSent++; else fixesDropped++;
                lastSample = sample;
            }
        }
        // position, time and the schedule do not need every second: the first fix, or once a minute
        if (hasFix && now - lastPositionMs < POSITION_EVERY_MS) return;
        lastPositionMs = now;
        // a cached fix is stamped with the time it was really taken (TimeSource), so it cannot pass for a fresh clock reading
        TimeSource.onLocation(location, !fresh);
        lastLat = location.getLatitude();
        lastLon = location.getLongitude();
        hasFix = true;
        Prefs.putLong(this, Prefs.KEY_LAST_LAT, Double.doubleToLongBits(lastLat));
        Prefs.putLong(this, Prefs.KEY_LAST_LON, Double.doubleToLongBits(lastLon));
        Status.putDouble(this, Status.KEY_LAT, lastLat);
        Status.putDouble(this, Status.KEY_LON, lastLon);
        // Coordinates are kept either way, but only a live fix opens the apply gate: a cached one does not count as the GPS
        // having arrived.
        if (fresh && !applyGateOpen) {
            openApplyGate("fresh GPS fix");
            return;
        }
        recomputeAndApply(true);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {
        // the receiver came back (wake, the user switched location on): register again
        if (accOn && LocationManager.GPS_PROVIDER.equals(provider)) bgHandler.post(this::restartLocationUpdates);
    }
    @Override public void onProviderDisabled(String provider) {}

    // --- tick loop ------------------------------------------------------------------------

    private void startTickLoop() {
        bgHandler.removeCallbacks(tickRunnable);
        bgHandler.post(tickRunnable);
    }

    private void stopTickLoop() {
        bgHandler.removeCallbacks(tickRunnable);
    }

    /**
     * "Headlights on" is decided here, from up to two sources: the MCU's headlight state (the ILL wire, through the vendor
     * property) and the car's CAN bus (through wFabia). With the ILL wire disconnected, or the MCU told to stop reporting
     * (mcu_headlight_off), only the CAN source counts and wRight alone decides. The CAN source is also faster: the MCU holds
     * its state for several seconds after the switch goes off.
     */
    private void pollHeadlight() {
        boolean canSource = canIsSource();
        boolean canOn = canSource && canLightsOn;
        // while the CAN state is fresh it alone decides; otherwise the MCU's own state (the ILL wire) is the fallback
        boolean holdForWake = !canSource && SystemClock.elapsedRealtime() < wakeHoldUntilMs
                && Prefs.getBool(this, Prefs.KEY_LIGHTS_FROM_CAN, true) && pipe != null;
        boolean on = canSource ? canOn : holdForWake ? headlightsOn : SysProp.getBoolean(PROP_HEADLIGHT_STATE, false);
        Status.putBool(this, Status.KEY_HEADLIGHTS_ON, on);
        if (on != headlightsOn) {
            Log.i(TAG, "headlights " + (on ? "ON" : "OFF") + " (source=" + (canSource ? "can" : "mcu") + " pipe=" + (pipe == null ? "-" : pipe.status()) + ")");
            headlightsOn = on;
            manualSettleUntilMs = SystemClock.elapsedRealtime() + MANUAL_SETTLE_MS;     // CarSettingService reacts to the edge
            // Order matters for latency and flashes: the dimmer's values go out first (the strip keeps its last dimmed
            // colour while the lights are off, so it comes on at that colour), then the MCU is told, and only then the
            // slow schedule logic runs.
            if (dimmerLoopRunning) dimmerStep();
            syncMcu(false);
            if (on && dimmerActive()) {
                // CarSettingService reacts to the headlight state with its own brightness: write ours again right after
                for (long delay : new long[]{80, 200, 400, 800}) {
                    bgHandler.postDelayed(() -> { lastDisplayWritten = -1; if (dimmerLoopRunning) dimmerStep(); }, delay);
                }
            }
            recomputeAndApply(false);
            onHeadlightEdge(on);
        } else {
            syncMcu(false);
        }
    }

    /** The MCU used to tell CarSettings about every headlight edge, and CarSettings then picked the matching night profile.
     *  With the MCU out of the picture wRight does that one step itself: at night, and while the dimmer is not driving
     *  the brightness, put the user's own profile value (headlight / no headlight) on the screen. The four profile keys
     *  themselves are only read here, never written. */
    private void onHeadlightEdge(boolean on) {
        if (dimmerActive() || !lastEffectiveDark) return;       // the dimmer owns it / daytime is applyBrightness()'s job
        final int value = on ? BrightnessControl.readNightHeadlight(this) : BrightnessControl.readNightNoHeadlight(this);
        if (value > 0) {
            // CarSettingService may also react to this edge: give it time, then put the value on top
            mainHandler.postDelayed(() -> BrightnessControl.applyLive(this, value), BRIGHTNESS_REASSERT_DELAY_MS);
        }
    }

    /** Passes the lights state on to the head unit, as measured on it: RPC_SendHeadLightState(false) is what puts the button
     *  backlight on, (true) puts it out; persist.sys.headlight_enable is what makes the system think the headlights are on
     *  (true) or off (false). So lights on = RPC(false) + property true, lights off = RPC(true) + property false. Only done
     *  while the CAN state is the source; the RPC is repeated every 30 s because the MCU forgets it across its sleep. */
    private void syncMcu(boolean force) {
        if (!canIsSource()) return;
        int state = headlightsOn ? 1 : 0;
        long now = SystemClock.elapsedRealtime();
        boolean changed = state != mcuSentState;
        if (!changed && !force && now - lastMcuAssertMs < MCU_REASSERT_MS) return;
        McuControl.setBacklightLights(headlightsOn);
        lastMcuAssertMs = now;
        if (changed || force) BrightnessControl.runRoot("setprop persist.sys.headlight_enable " + headlightsOn);
        mcuSentState = state;
    }

    /** the CAN lights state is in charge: switched on, wFabia's state is fresh and valid */
    private boolean canIsSource() {
        return Prefs.getBool(this, Prefs.KEY_LIGHTS_FROM_CAN, true) && pipe != null && pipe.isFresh() && canValid;
    }

    // --- dimmer: display brightness and button backlight follow the car's dimmer wheel ---------------------------

    /** the dimmer drives the outputs: enabled, wFabia's state is fresh, the lights are on and (by default) it is night */
    private boolean dimmerActive() {
        return buttonsActive() && (!Prefs.getBool(this, Prefs.KEY_DIMMER_NIGHT_ONLY, true) || lastEffectiveDark);
    }

    /** the wheel drives the button backlight: enabled, wFabia's state is fresh and the lights are on, day or night
     *  (the "dimmer only at night" option applies to the display only) */
    private boolean buttonsActive() {
        if (!Prefs.getBool(this, Prefs.KEY_DIMMER_ENABLED, false)) return false;
        return pipe != null && pipe.isFresh() && canValid && canLightsOn;
    }

    private final Runnable dimmerRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                dimmerStep();
            } catch (Exception e) {
                Log.e(TAG, "dimmer step failed, will retry", e);
            } finally {
                if (dimmerLoopRunning) bgHandler.postDelayed(this, DIMMER_LOOP_MS);
            }
        }
    };

    private void updateDimmerLoop() {
        boolean want = Prefs.getBool(this, Prefs.KEY_DIMMER_ENABLED, false) && accOn;
        if (want && !dimmerLoopRunning) {
            dimmerLoopRunning = true;
            bgHandler.post(dimmerRunnable);
        } else if (!want && dimmerLoopRunning) {
            dimmerLoopRunning = false;
            bgHandler.removeCallbacks(dimmerRunnable);
            if (dimmerWasActive) {
                dimmerWasActive = false;
                releaseDisplay();
            }
            if (buttonsWasActive) {
                buttonsWasActive = false;
                releaseButtons();
            }
        }
    }

    private void dimmerStep() {
        long now = SystemClock.elapsedRealtime();
        float dtMs = lastDimmerStepMs == 0 ? 100f : Math.min(1000f, now - lastDimmerStepMs);
        lastDimmerStepMs = now;
        boolean buttons = buttonsActive();
        boolean display = buttons && dimmerActive();
        int level = canLevel;
        int shownDisplay = -1, shownRgb = -1;

        if (display) {
            float displayMin = Prefs.getInt(this, Prefs.KEY_DIMMER_DISPLAY_MIN, 1) / 255f;
            float displayMax = Prefs.getInt(this, Prefs.KEY_DIMMER_DISPLAY_MAX, 15) / 255f;
            // interpolated, rounded at the very end so the steps stay one unit wide
            float shown = displaySmoother.step(DimmerMap.displayBrightness(level, displayMin, displayMax, 1f), dtMs);
            shownDisplay = Math.max(1, Math.round(shown));
            // checked against the live value on every step (a cheap read): whoever else writes screen_brightness
            // (CarSettingService on its events, the day curve) is corrected within one step instead of pulsing the screen
            if (BrightnessControl.applyLive(this, shownDisplay) > 0) lastDisplayWritten = shownDisplay;
            if (!dimmerWasActive) Log.i(TAG, "dimmer ACTIVE: level " + level);
            dimmerWasActive = true;
        } else if (dimmerWasActive) {
            dimmerWasActive = false;
            releaseDisplay();
        }

        if (buttons) {
            float buttonsMin = Prefs.getInt(this, Prefs.KEY_DIMMER_BUTTONS_MIN, 10) / 100f;
            float buttonsMax = Prefs.getInt(this, Prefs.KEY_DIMMER_BUTTONS_MAX, 100) / 100f;
            // the user's colour scaled by the dimmer, hue kept, written only when the colour really changed
            int base = Prefs.getInt(this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889);
            float fraction = buttonsSmoother.step(DimmerMap.fraction(level, buttonsMin, buttonsMax, 1f), dtMs);
            shownRgb = DimmerMap.scaleColor(base, fraction);
            if (shownRgb != lastButtonsRgb && now - lastButtonsWriteMs >= BUTTONS_MIN_GAP_MS) {
                BacklightColorControl.apply(shownRgb, false);
                lastButtonsRgb = shownRgb;
                lastDimmedRgb = shownRgb;
                lastButtonsWriteMs = now;
            }
            buttonsWasActive = true;
        } else if (buttonsWasActive) {
            buttonsWasActive = false;
            releaseButtons();
        }

        if (pipe != null && buttons) pipe.reportApplied(display ? shownDisplay : BrightnessControl.readLive(this, 0), shownRgb, display);
    }

    /** the colour for the strip on ACC_ON: the last dimmed one if the dimmer is on and the lights were on at ACC_OFF, else -1 */
    private int wakeDimmedColour() {
        if (!headlightsOn || !Prefs.getBool(this, Prefs.KEY_DIMMER_ENABLED, false)) return -1;
        if (lastDimmedRgb == -1) lastDimmedRgb = Prefs.getInt(this, PREF_LAST_DIMMED_RGB, -1);
        return lastDimmedRgb;
    }

    /** the dimmer stops driving: the user's own colour back on the buttons, and the display back to what wRight's own
     *  logic (the day curve) or the user's night profile says */
    private void releaseButtons() {
        Log.i(TAG, "dimmer released (buttons)");
        // Lights off with the CAN state in charge: the MCU switches the strip off, and its colour stays the last dimmed one
        // so it comes back at that colour (not at full) when the lights go on. Otherwise the user's colour goes back.
        // Also kept while the ignition is off: the pipe going stale then is not the lights going off, and the user's full colour
        // written now is what the head unit would come back with on wake.
        boolean keepDimmedColour = (!headlightsOn && canIsSource()) || !accOn;
        if (!keepDimmedColour) {
            BacklightColorControl.apply(Prefs.getInt(this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889));
            lastButtonsRgb = -1;
        }
        buttonsSmoother.clear();
    }

    /** the display stops following the wheel: back to wRight's own logic (the day curve) or the user's night profile */
    private void releaseDisplay() {
        Log.i(TAG, "dimmer released (display)");
        manualSettleUntilMs = SystemClock.elapsedRealtime() + MANUAL_SETTLE_MS;
        lastDisplayWritten = -1;
        displaySmoother.clear();
        recomputeAndApply(true);
        if (lastEffectiveDark) {
            int value = headlightsOn ? BrightnessControl.readNightHeadlight(this) : BrightnessControl.readNightNoHeadlight(this);
            if (value > 0) BrightnessControl.applyLive(this, value);
        }
        if (pipe != null) pipe.reportApplied(BrightnessControl.readLive(this, 0),
                Prefs.getInt(this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889), false);
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
        if (!accOn) return;     // asleep: nothing is computed or written until ACC_ON
        if (!applyGateOpen) {
            // Wake sequence in progress: nothing time-dependent is applied until a fresh GPS fix arrives or the fallback
            // fires (see openApplyGate()).
            Log.i(TAG, "doRecomputeAndApply: waiting for GPS or the fallback, not applying yet");
            return;
        }
        if (!hasFix) {
            // No coordinates at all yet (first run ever, nothing cached): can't compute a
            // schedule. Logged so that's distinguishable from an actually-dead loop.
            Log.i(TAG, "doRecomputeAndApply: no coordinates yet, skipping");
            updateNotification(null, null);
            return;
        }

        long now = TimeSource.nowMillis(this);

        SunCalculator.SunTimes sun = computeEffectiveSunTimes(now);

        // Is true if it's night, false if it's day
        boolean nightWindow = now < sun.sunriseUtcMillis || now >= sun.sunsetUtcMillis;

        // Time-based logic
        Status.putLong(this, Status.KEY_NOW_MILLIS, now);
        Status.putLong(this, Status.KEY_SUNRISE_MILLIS, sun.sunriseUtcMillis);
        Status.putLong(this, Status.KEY_SUNSET_MILLIS, sun.sunsetUtcMillis);

        // Checking which functions are enabled
        boolean darkModeEnabled = Prefs.getBool(this, Prefs.KEY_DARK_MODE_ENABLED, true);
        boolean lightsOverrideEnabled = Prefs.getBool(this, Prefs.KEY_LIGHTS_OVERRIDE_ENABLED, false);

        // Is true if Dark Mode is on and it's night currently
        boolean scheduleWantsDark = darkModeEnabled && nightWindow;

        // Is true if Lights Override is enabled and the headlights are on
        boolean lightsForceDark = lightsOverrideEnabled && headlightsOn;

        // Is true if it's time to set the dark mode or the lights are on
        boolean effectiveDark = scheduleWantsDark || lightsForceDark;
        lastEffectiveDark = effectiveDark;

        // Get current saved night brightness values
        int headlightSaved = Prefs.getInt(this, Prefs.KEY_SAVED_NIGHT_HEADLIGHT_BRIGHTNESS, -1);
        int noHeadlightSaved = Prefs.getInt(this, Prefs.KEY_SAVED_NIGHT_NO_HEADLIGHT_BRIGHTNESS, -1);

        // If the service has just started, this is the first loop
        if (lastNightWindow == null) {
            // And there's no saved night brightness values yet, we capture the night brightness;
            // Truly the first start after installation.
            if (headlightSaved == -1 || noHeadlightSaved == -1) {
                        captureNightBrightness();
            }
            // And there ARE saved brightness values
            else {
                // And it's currently night
                if (nightWindow) {
                    // We restore the saved brightness values. This prevents the brightness being at the day value
                    // in the night after service restart.
                    restoreSavedNightBrightness();
                }
            }

        }

        // Edge-detect the real (clock-based) day<->night transition - deliberately independent of
        // effectiveDark/Lights Override, which can flip uiMode mid-day and must NOT be mistaken
        // for the genuine transition. Restoring the user's real night brightness has to happen
        // BEFORE setDark() below fires the TEST_UIMODE broadcast, since CarSettingService resolves
        // its brightness profile synchronously off that same broadcast. This MUST run before the
        // "cyclically capture" block below: on the very first tick of a new night, the table still
        // holds whatever daytime value was primed into it moments ago, which almost always differs
        // from last night's saved value - if the cyclic-capture check ran first, it would read
        // that stale daytime value as if it were a deliberate user adjustment and immediately
        // clobber the real saved preference with it, so restoreSavedNightBrightness() right after
        // would just restore the wrong, just-clobbered value instead of the real one.

        // We restore the brightness if the loop has already ran once,
        // it was daytime before and it's night right now.
        if (lastNightWindow != null && !lastNightWindow && nightWindow) {
            manualDayOverride = false;                 // a new night: the next day starts with the curve again
            restoreSavedNightBrightness();
        }
        // We capture the brightness if the loop has already ran once,
        // it was night time before and it's day right now.
        else if (lastNightWindow != null && lastNightWindow && !nightWindow) {
            captureNightBrightness();
        }

        // Save the last night window state for the next loop.
        lastNightWindow = nightWindow;

        // Cyclically capture the brightness values in the night. This will save the values to the
        // preferences so that they can be restored the next day:

        // If it's night
        if (nightWindow) {
            // Get the current brightness values for the night
            int headlight = BrightnessControl.readNightHeadlight(this);
            int noHeadlight = BrightnessControl.readNightNoHeadlight(this);
            // If they have changed - capture and save.
            if (headlight != headlightSaved || noHeadlight != noHeadlightSaved) {
                captureNightBrightness();
            }
        }

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
        boolean brightnessDue = fullTick || headlightsOn != lastBrightnessHeadlights;
        lastBrightnessHeadlights = headlightsOn;
        if (brightnessDue) applyBrightness();

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

        if (currentlyDark != effectiveDark) manualSettleUntilMs = SystemClock.elapsedRealtime() + MANUAL_SETTLE_MS;   // the uiMode flip re-resolves brightness
        ThemeControl.setDark(this, effectiveDark);

        if (enteringNightLightsOn || enteringDayHeadlightsOff) {
            // Give CarSettingService's resolver time to actually stomp screen_brightness off its
            // broadcast first, then correct it - reacting synchronously here would just get
            // overwritten moments later once that resolution actually runs.
            mainHandler.removeCallbacks(reassertBrightnessRunnable);
            mainHandler.postDelayed(reassertBrightnessRunnable, BRIGHTNESS_REASSERT_DELAY_MS);
        }

        // Every tick, not only every 60 s: NightShiftControl compares with what the system really has and writes only a
        // difference, so this is cheap and a reset of the keys (wake from suspend, CarSettings, the user) is corrected
        // within one tick instead of whenever the next full recompute happens to run.
        applyNightShift(now, sun);

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

    /** The night profile keys only need priming when the headlights override can force the dark theme in the daytime. */
    private boolean lightsOverrideOn() {
        return Prefs.getBool(this, Prefs.KEY_LIGHTS_OVERRIDE_ENABLED, false);
    }

    /** First run (or after the saved values were lost): what the night profile keys hold now is the night brightness the user
     *  has, so keep it for the restore at sunset. Only fills in what is missing, never overwrites a saved value. */
    private void captureNightBrightnessIfMissing() {
        if (Prefs.getInt(this, Prefs.KEY_SAVED_NIGHT_HEADLIGHT_BRIGHTNESS, -1) <= 0) {
            int v = BrightnessControl.readNightHeadlight(this);
            if (v > 0) Prefs.putInt(this, Prefs.KEY_SAVED_NIGHT_HEADLIGHT_BRIGHTNESS, v);
        }
        if (Prefs.getInt(this, Prefs.KEY_SAVED_NIGHT_NO_HEADLIGHT_BRIGHTNESS, -1) <= 0) {
            int v = BrightnessControl.readNightNoHeadlight(this);
            if (v > 0) Prefs.putInt(this, Prefs.KEY_SAVED_NIGHT_NO_HEADLIGHT_BRIGHTNESS, v);
        }
    }

    /** With the CAN dimmer not driving the screen, at night: when the live brightness changes and it was not our write and not
     *  a reaction to a headlight edge / uiMode flip / wake (those open a settle window), the user moved it. Save it as their
     *  night brightness for the current headlight state, so the restore at sunset and the headlight-edge handler use it.
     *  With the dimmer driving, the value is the wheel's, never the user's: nothing is saved. */
    private void watchManualBrightness() {
        int live = BrightnessControl.readLive(this, -1);
        long now = SystemClock.elapsedRealtime();
        boolean night = Boolean.TRUE.equals(lastNightWindow);
        boolean day = Boolean.FALSE.equals(lastNightWindow);
        if (live <= 0 || !accOn || !(night || day) || dimmerActive() || dimmerWasActive || now < manualSettleUntilMs
                || lastSeenLive <= 0) {
            lastSeenLive = live;
            return;
        }
        if (live == lastSeenLive) return;
        lastSeenLive = live;
        if (live == BrightnessControl.lastLiveWritten()) return;
        if (day) {
            // by hand in the daytime: the day curve stops until the next wake, and the night keys get this value (when the
            // lights override can need them) instead of the curve's
            if (!manualDayOverride) Log.i(TAG, "manual day brightness " + live + ": day curve paused until the next wake");
            manualDayOverride = true;
            if (lightsOverrideOn()) BrightnessControl.primeNightProfiles(this, live);
            Status.putInt(this, Status.KEY_BRIGHTNESS_PCT, STATUS_BRIGHTNESS_MANUAL);
            return;
        }
        Log.i(TAG, "manual night brightness " + live + " (headlights " + (headlightsOn ? "on" : "off") + ") saved");
        Prefs.putInt(this, headlightsOn ? Prefs.KEY_SAVED_NIGHT_HEADLIGHT_BRIGHTNESS : Prefs.KEY_SAVED_NIGHT_NO_HEADLIGHT_BRIGHTNESS, live);
        BrightnessControl.setNightProfile(this, headlightsOn, live);
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
                if (current > 0 && lightsOverrideOn()) BrightnessControl.primeNightProfiles(this, current);
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

        if (manualDayOverride) {
            // the user set the brightness by hand: leave it alone (and the day profile keys with it) until the next wake
            Status.putInt(this, Status.KEY_BRIGHTNESS_PCT, STATUS_BRIGHTNESS_MANUAL);
            return;
        }

        int minPct = Prefs.getInt(this, Prefs.KEY_BRIGHTNESS_MIN, 15);
        int maxPct = Prefs.getInt(this, Prefs.KEY_BRIGHTNESS_MAX, 100);
        if (maxPct < minPct) maxPct = minPct;

        double dayFraction = (now - sun.sunriseUtcMillis) / (double) (sun.sunsetUtcMillis - sun.sunriseUtcMillis);
        double curve = Math.sin(Math.PI * clamp01(dayFraction)); // 0 at edges, 1 at solar noon
        double pct = minPct + curve * (maxPct - minPct);

        int value255 = Math.round((float) (pct / 100.0 * 255.0));
        // the dimmer owns screen_brightness while it drives the display (lights override in the daytime): writing the curve
        // value here is what made the screen pulse, the dimmer then had to take it back
        if (!dimmerActive()) BrightnessControl.apply(this, value255);
        if (lightsOverrideOn()) BrightnessControl.primeNightProfiles(this, value255);
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
        if (Prefs.KEY_BACKLIGHT_COLOR.equals(key)) {
            lastButtonsRgb = -1;            // the dimmer re-derives its scaled colour from the new base at its next step
            return;
        }
        if (!accOn) return;     // asleep: the setting is picked up at ACC_ON
        bgHandler.post(() -> {
            updateDimmerLoop();
            pollHeadlight();
            recomputeAndApply(true);
        });
    }
}
