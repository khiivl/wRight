package com.radiorubka.wright;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.flexbox.AlignItems;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_LOCATION = 100;
    private static final int REQ_EXPORT = 200;
    private static final int REQ_IMPORT = 201;

    private FlexboxLayout cardContainer;
    private LinearLayout bannerContainer;

    private TextView statusLocationValue;
    private TextView statusGpsTimeValue;
    private TextView statusHeadlightValue;
    private TextView statusSunriseValue;
    private TextView statusSunsetValue;
    private TextView statusBrightnessValue;
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (prefs, key) -> runOnUiThread(this::refreshStatusPanel);

    // Backlight color sends a binder RPC to the MCU on every change. Firing that synchronously
    // on the UI thread for every single tick of a slider drag was flooding the MCU's serial link
    // (visibly delayed reactivity) and risked janking/ANR-ing the UI on a slow binder round trip.
    // This throttles to a fixed cadence and always does the actual call off the main thread,
    // while UI feedback (hex text, preview swatch) stays instant and unthrottled.
    private static final long COLOR_APPLY_INTERVAL_MS = 80;
    private android.os.HandlerThread colorApplyThread;
    private android.os.Handler colorApplyHandler;
    private long lastColorApplyAtMs = 0;
    private Integer pendingColor = null;
    private final Runnable flushPendingColor = this::flushPendingColor;

    private void scheduleColorApply(int color) {
        pendingColor = color;
        long now = android.os.SystemClock.uptimeMillis();
        long elapsed = now - lastColorApplyAtMs;
        colorApplyHandler.removeCallbacks(flushPendingColor);
        if (elapsed >= COLOR_APPLY_INTERVAL_MS) {
            colorApplyHandler.post(flushPendingColor);
        } else {
            colorApplyHandler.postDelayed(flushPendingColor, COLOR_APPLY_INTERVAL_MS - elapsed);
        }
    }

    private void flushPendingColor() {
        Integer color = pendingColor;
        if (color == null) return;
        pendingColor = null;
        lastColorApplyAtMs = android.os.SystemClock.uptimeMillis();
        BacklightColorControl.apply(color);
    }

    private interface Check {
        boolean isSatisfied();
        int textRes();
        String actionLabel();
        Runnable action();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        colorApplyThread = new android.os.HandlerThread("wRightColorApply");
        colorApplyThread.start();
        colorApplyHandler = new android.os.Handler(colorApplyThread.getLooper());

        inflateAndBindContentView();

        ContextCompat.startForegroundService(this, new Intent(this, WRightService.class));
        BacklightColorControl.apply(Prefs.getInt(this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889));
    }

    /** Everything that depends on the current theme's resources (colors, the app_background
     *  drawable) - called from onCreate, and again in full from onConfigurationChanged on a
     *  uiMode flip. MainActivity declares android:configChanges="uiMode|..." so Android won't
     *  recreate the Activity itself when our own Dark Mode toggle flips the system theme (that
     *  was interrupting the switch mid-tap, making it look like it "reset" until spammed) - but
     *  that puts us on the hook for re-resolving every themed resource ourselves instead of
     *  getting a free fresh inflate. Re-running setContentView() here does that correctly for
     *  everything in the XML tree (it's a real re-inflate, so every @color/@drawable reference
     *  resolves fresh against the new configuration) in one place, rather than hunting down and
     *  patching individual stale views one at a time - window-level attributes (background,
     *  status bar icon contrast) live outside that view tree and still need their own explicit
     *  re-apply below, which setContentView alone can't reach. */
    private void inflateAndBindContentView() {
        setContentView(R.layout.activity_main);
        getWindow().setBackgroundDrawableResource(R.drawable.app_background);
        applyStatusBarIconContrast();

        cardContainer = findViewById(R.id.card_container);
        bannerContainer = findViewById(R.id.banner_container);

        setupStatusPanel();
        refreshStatusPanel();
        buildCards();
        refreshBanners();

        findViewById(R.id.btn_export).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "wright_settings.json");
            startActivityForResult(intent, REQ_EXPORT);
        });
        findViewById(R.id.btn_import).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent, REQ_IMPORT);
        });
    }

    /** android:windowLightStatusBar is a window-level theme attribute resolved once when the
     *  window is created - like windowBackground, a uiMode-only config change doesn't touch it
     *  on its own. */
    private void applyStatusBarIconContrast() {
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (isNight) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; // light icons on a dark background
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; // dark icons on a light background
        }
        decor.setSystemUiVisibility(flags);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            exportSettings(uri);
        } else if (requestCode == REQ_IMPORT) {
            importSettings(uri);
        }
    }

    private void exportSettings(Uri uri) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            org.json.JSONObject json = Prefs.exportToJson(this);
            out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void importSettings(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
            String text = buffer.toString("UTF-8");
            org.json.JSONObject json = new org.json.JSONObject(text);
            Prefs.importFromJson(this, json);
            Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show();
            buildCards();
            BacklightColorControl.apply(Prefs.getInt(this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889));
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBanners();
        Status.prefs(this).registerOnSharedPreferenceChangeListener(statusListener);
        refreshStatusPanel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Status.prefs(this).unregisterOnSharedPreferenceChangeListener(statusListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        colorApplyThread.quitSafely();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        inflateAndBindContentView();
    }

    // --- status panel ---------------------------------------------------------------------

    private void setupStatusPanel() {
        LinearLayout faderRow = findViewById(R.id.status_fader_row);
        addFader(faderRow, getString(R.string.fader_sunrise), -30, 30, 1,
                Prefs.getInt(this, Prefs.KEY_SUNRISE_OFFSET_MIN, 0), "m",
                R.color.card_dark_mode,
                v -> Prefs.putInt(this, Prefs.KEY_SUNRISE_OFFSET_MIN, v));
        addFader(faderRow, getString(R.string.fader_sunset), -30, 30, 1,
                Prefs.getInt(this, Prefs.KEY_SUNSET_OFFSET_MIN, 0), "m",
                R.color.card_dark_mode,
                v -> Prefs.putInt(this, Prefs.KEY_SUNSET_OFFSET_MIN, v));

        statusLocationValue = bindStatusRow(R.id.status_row_location, R.string.status_location);
        statusGpsTimeValue = bindStatusRow(R.id.status_row_gps_time, R.string.status_gps_time);
        statusHeadlightValue = bindStatusRow(R.id.status_row_headlight, R.string.status_headlight);
        statusSunriseValue = bindStatusRow(R.id.status_row_sunrise, R.string.status_sunrise);
        statusSunsetValue = bindStatusRow(R.id.status_row_sunset, R.string.status_sunset);
        statusBrightnessValue = bindStatusRow(R.id.status_row_brightness, R.string.status_brightness);
    }

    private TextView bindStatusRow(int rowId, int labelRes) {
        View row = findViewById(rowId);
        ((TextView) row.findViewById(R.id.row_label)).setText(labelRes);
        return row.findViewById(R.id.row_value);
    }

    private void refreshStatusPanel() {
        boolean hasFix = Status.getBool(this, Status.KEY_HAS_FIX, false);
        if (hasFix) {
            double lat = Status.getDouble(this, Status.KEY_LAT, 0);
            double lon = Status.getDouble(this, Status.KEY_LON, 0);
            statusLocationValue.setText(String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon));
        } else {
            statusLocationValue.setText(R.string.status_no_fix);
        }

        long now = Status.getLong(this, Status.KEY_NOW_MILLIS, 0);
        statusGpsTimeValue.setText(now == 0 ? getString(R.string.status_dash)
                : android.text.format.DateFormat.format("MMM d, HH:mm:ss", new java.util.Date(now)));

        statusHeadlightValue.setText(Status.getBool(this, Status.KEY_HEADLIGHTS_ON, false)
                ? R.string.status_on : R.string.status_off);

        long sunrise = Status.getLong(this, Status.KEY_SUNRISE_MILLIS, 0);
        statusSunriseValue.setText(sunrise == 0 ? getString(R.string.status_dash)
                : android.text.format.DateFormat.format("HH:mm", new java.util.Date(sunrise)));

        long sunset = Status.getLong(this, Status.KEY_SUNSET_MILLIS, 0);
        statusSunsetValue.setText(sunset == 0 ? getString(R.string.status_dash)
                : android.text.format.DateFormat.format("HH:mm", new java.util.Date(sunset)));

        int brightnessPct = Status.getInt(this, Status.KEY_BRIGHTNESS_PCT, -1);
        if (brightnessPct == -2) {
            statusBrightnessValue.setText(R.string.status_night_stock);
        } else if (brightnessPct < 0) {
            statusBrightnessValue.setText(R.string.status_disabled);
        } else {
            statusBrightnessValue.setText(brightnessPct + "%");
        }
    }

    // --- cards ------------------------------------------------------------------------------

    private void buildCards() {
        cardContainer.removeAllViews();

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float basis = landscape ? 50f : 100f;

        addCard(R.string.card_dark_mode_title, R.string.card_dark_mode_subtitle,
                Prefs.KEY_DARK_MODE_ENABLED, true, R.color.card_dark_mode, basis, null);

        addCard(R.string.card_lights_override_title, R.string.card_lights_override_subtitle,
                Prefs.KEY_LIGHTS_OVERRIDE_ENABLED, false, R.color.card_lights_override, basis, null);

        addCard(R.string.card_night_shift_title, R.string.card_night_shift_subtitle,
                Prefs.KEY_NIGHT_SHIFT_ENABLED, false, R.color.card_night_shift, basis, faderRow -> {
                    addFader(faderRow, getString(R.string.fader_intensity), 0, 100, 5,
                            Prefs.getInt(this, Prefs.KEY_NIGHT_SHIFT_INTENSITY, 70), "%",
                            R.color.card_night_shift,
                            v -> Prefs.putInt(this, Prefs.KEY_NIGHT_SHIFT_INTENSITY, v));
                    addFader(faderRow, getString(R.string.fader_fade), 5, 60, 5,
                            Prefs.getInt(this, Prefs.KEY_NIGHT_SHIFT_FADE_MIN, 20), "m",
                            R.color.card_night_shift,
                            v -> Prefs.putInt(this, Prefs.KEY_NIGHT_SHIFT_FADE_MIN, v));
                    addSystemSettingsButton(faderRow, Settings.ACTION_NIGHT_DISPLAY_SETTINGS, R.color.card_night_shift);
                });

        addCard(R.string.card_brightness_title, R.string.card_brightness_subtitle,
                Prefs.KEY_BRIGHTNESS_ENABLED, false, R.color.card_brightness, basis, faderRow -> {
                    addFader(faderRow, getString(R.string.fader_min), 0, 100, 5,
                            Prefs.getInt(this, Prefs.KEY_BRIGHTNESS_MIN, 15), "%",
                            R.color.card_brightness,
                            v -> Prefs.putInt(this, Prefs.KEY_BRIGHTNESS_MIN, v));
                    addFader(faderRow, getString(R.string.fader_max), 0, 100, 5,
                            Prefs.getInt(this, Prefs.KEY_BRIGHTNESS_MAX, 100), "%",
                            R.color.card_brightness,
                            v -> Prefs.putInt(this, Prefs.KEY_BRIGHTNESS_MAX, v));
                });

        addCard(R.string.card_backlight_title, R.string.card_backlight_subtitle,
                null, true, R.color.card_backlight, 100f, this::buildBacklightCard);
    }

    private interface FaderBuilder {
        void build(LinearLayout faderRow);
    }

    private void addCard(int titleRes, int subtitleRes, String prefKey, boolean defaultOn,
                          int accentColorRes, float flexBasisPercent, FaderBuilder faders) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_feature_card, cardContainer, false);
        MaterialCardView card = cardView.findViewById(R.id.card_root);
        TextView title = cardView.findViewById(R.id.card_title);
        TextView subtitle = cardView.findViewById(R.id.card_subtitle);
        MaterialSwitch switchView = cardView.findViewById(R.id.card_switch);
        LinearLayout faderRow = cardView.findViewById(R.id.fader_row);

        int accent = ContextCompat.getColor(this, accentColorRes);
        title.setText(titleRes);
        subtitle.setText(subtitleRes);
        card.setStrokeColor(accent);

        if (prefKey == null) {
            // No on/off concept for this card (e.g. a color picker is just whatever it's set
            // to) - drop the switch entirely instead of giving it a meaning it doesn't have.
            switchView.setVisibility(View.GONE);
        } else {
            applySwitchColors(switchView, accent);
            switchView.setChecked(Prefs.getBool(this, prefKey, defaultOn));
            switchView.setOnCheckedChangeListener((btn, checked) -> {
                Prefs.putBool(this, prefKey, checked);
                faderRow.setAlpha(checked ? 1f : 0.35f);
            });
            faderRow.setAlpha(switchView.isChecked() ? 1f : 0.35f);
        }

        if (faders != null) {
            faderRow.setVisibility(View.VISIBLE);
            faders.build(faderRow);
        }

        FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.MATCH_PARENT, FlexboxLayout.LayoutParams.WRAP_CONTENT);
        lp.setFlexBasisPercent(flexBasisPercent / 100f);
        lp.setAlignSelf(AlignItems.FLEX_START);
        cardView.setLayoutParams(lp);
        cardContainer.addView(cardView);
    }

    /** Accent color only shows up on the switch when it's ON; OFF always reads as a neutral
     *  gray, so a row of unrelated-colored dots doesn't show up while everything's switched off. */
    private void applySwitchColors(MaterialSwitch switchView, int accent) {
        int neutral = ContextCompat.getColor(this, R.color.text_theme_aware_2);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        switchView.setThumbTintList(new ColorStateList(states, new int[]{accent, neutral}));
        switchView.setTrackTintList(new ColorStateList(states, new int[]{
                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 140),
                androidx.core.graphics.ColorUtils.setAlphaComponent(neutral, 60)
        }));
    }

    private interface IntConsumer { void accept(int value); }

    /** Builds one horizontal fader: a name/value label row above a full-width Slider with step
     *  ticks. parentRow lays its faders out side by side, each getting equal width. */
    private void addFader(LinearLayout parentRow, String name, int min, int max, int step,
                           int initial, String suffix, int accentColorRes, IntConsumer onChange) {
        int accent = ContextCompat.getColor(this, accentColorRes);
        float dp = getResources().getDisplayMetrics().density;
        int thumbHeightPx = (int) getResources().getDimension(R.dimen.thumb_height);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        columnLp.setMargins((int) (8 * dp), 0, (int) (8 * dp), 0);
        column.setLayoutParams(columnLp);

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView nameLabel = new TextView(this);
        nameLabel.setText(name);
        nameLabel.setTextColor(ContextCompat.getColor(this, R.color.text_theme_aware_2));
        nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        nameLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueLabel = new TextView(this);
        valueLabel.setText(initial + suffix);
        valueLabel.setTextColor(accent);
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        valueLabel.setTypeface(valueLabel.getTypeface(), android.graphics.Typeface.BOLD);

        labelRow.addView(nameLabel);
        labelRow.addView(valueLabel);

        Slider slider = new Slider(this, null);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);
        slider.setValue(clampToStep(initial, min, max, step));
        slider.setThumbHeight(thumbHeightPx);
        slider.setHaloRadius((int) (24 * dp));
        slider.setHaloTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setTrackActiveTintList(ColorStateList.valueOf(accent));
        slider.setTrackInactiveTintList(ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 70)));
        slider.setTrackHeight((int) (4 * dp));
        slider.setTrackStopIndicatorSize(0);
        slider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        slider.setTickActiveTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.tick_color_active)));
        slider.setTickInactiveTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.tick_color_inactive)));
        slider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        slider.addOnChangeListener((s, value, fromUser) -> {
            int v = Math.round(value);
            valueLabel.setText(v + suffix);
            if (fromUser) onChange.accept(v);
        });

        column.addView(labelRow);
        column.addView(slider);
        parentRow.addView(column);
    }

    private static int clampToStep(int value, int min, int max, int step) {
        int clamped = Math.max(min, Math.min(max, value));
        int steps = Math.round((clamped - min) / (float) step);
        return min + steps * step;
    }

    /** A compact link to the matching Android system settings screen, for testers who want to
     *  cross-check what wRight is actually driving against the OS's own UI for it. */
    private void addSystemSettingsButton(LinearLayout parentRow, String settingsAction, int accentColorRes) {
        int accent = ContextCompat.getColor(this, accentColorRes);
        float dp = getResources().getDisplayMetrics().density;

        MaterialButton button = new MaterialButton(this);
        button.setText(R.string.system_settings);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        button.setBackgroundTintList(ColorStateList.valueOf(accent));
        button.setTextColor(ContextCompat.getColor(this, R.color.colored_button_text));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMarginStart((int) (4 * dp));
        button.setLayoutParams(lp);

        button.setOnClickListener(v -> {
            try {
                startActivity(new Intent(settingsAction));
            } catch (Exception e) {
                Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_SHORT).show();
            }
        });

        parentRow.addView(button);
    }

    // --- backlight color card -----------------------------------------------------------------

    /** Three RGB sliders, a hex field, and a preview swatch, all kept in sync with each other -
     *  whichever one the user just touched updates the other two plus the preview, then pushes
     *  the new color live via BacklightColorControl. isUpdatingUi guards against the programmatic
     *  updates that sync triggers from re-triggering each other (same pattern wDSP itself uses
     *  for its own two-way-bound controls). */
    private void buildBacklightCard(LinearLayout faderRow) {
        float dp = getResources().getDisplayMetrics().density;
        int initial = Prefs.getInt(this, Prefs.KEY_BACKLIGHT_COLOR, 0x028889);
        boolean[] isUpdatingUi = {false};

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View preview = new View(this);
        int previewSizePx = (int) (40 * dp);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(previewSizePx, previewSizePx);
        previewLp.setMarginEnd((int) (12 * dp));
        previewLp.gravity = Gravity.CENTER_VERTICAL;
        preview.setLayoutParams(previewLp);
        android.graphics.drawable.GradientDrawable previewBg = new android.graphics.drawable.GradientDrawable();
        previewBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        previewBg.setCornerRadius(8 * dp);
        previewBg.setStroke((int) dp, ContextCompat.getColor(this, R.color.stroke_color));
        preview.setBackground(previewBg);

        android.widget.EditText hexInput = new android.widget.EditText(this);
        hexInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hexInput.setHint(R.string.hex_hint);
        hexInput.setSingleLine(true);
        hexInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        hexInput.setTextColor(ContextCompat.getColor(this, R.color.text_theme_aware));
        hexInput.setGravity(Gravity.CENTER_VERTICAL);

        Slider[] sliders = new Slider[3];
        int[] names = {R.string.fader_red, R.string.fader_green, R.string.fader_blue};
        int[] channelColors = {R.color.pastel_red, R.color.pastel_green, R.color.pastel_blue};

        Runnable[] applyColor = new Runnable[1];

        for (int i = 0; i < 3; i++) {
            int channelValue = (initial >> (16 - 8 * i)) & 0xFF;
            sliders[i] = buildRgbSlider(sliderRow, getString(names[i]), channelValue, channelColors[i], v -> {
                if (isUpdatingUi[0]) return;
                applyColor[0].run();
            });
        }

        applyColor[0] = () -> {
            int color = (Math.round(sliders[0].getValue()) << 16)
                    | (Math.round(sliders[1].getValue()) << 8)
                    | Math.round(sliders[2].getValue());
            isUpdatingUi[0] = true;
            hexInput.setText(String.format("#%06X", color));
            isUpdatingUi[0] = false;
            previewBg.setColor(0xFF000000 | color);
            Prefs.putInt(this, Prefs.KEY_BACKLIGHT_COLOR, color);
            scheduleColorApply(color);
        };

        hexInput.setText(String.format("#%06X", initial));
        hexInput.setOnEditorActionListener((v, actionId, event) -> {
            applyHexInput(hexInput, sliders, isUpdatingUi, previewBg);
            return false;
        });
        hexInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) applyHexInput(hexInput, sliders, isUpdatingUi, previewBg);
        });

        previewBg.setColor(0xFF000000 | initial);

        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hexRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexRowLp.topMargin = (int) (12 * dp);
        hexRow.setLayoutParams(hexRowLp);
        hexRow.addView(preview);
        hexRow.addView(hexInput);

        block.addView(sliderRow);
        block.addView(hexRow);
        faderRow.addView(block);
    }

    private Slider buildRgbSlider(LinearLayout parentRow, String name, int initial, int accentColorRes, IntConsumer onChange) {
        int accent = ContextCompat.getColor(this, accentColorRes);
        float dp = getResources().getDisplayMetrics().density;
        int thumbHeightPx = (int) getResources().getDimension(R.dimen.thumb_height);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        columnLp.setMargins((int) (8 * dp), 0, (int) (8 * dp), 0);
        column.setLayoutParams(columnLp);

        TextView nameLabel = new TextView(this);
        nameLabel.setText(name);
        nameLabel.setTextColor(ContextCompat.getColor(this, R.color.text_theme_aware_2));
        nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        Slider slider = new Slider(this, null);
        slider.setValueFrom(0);
        slider.setValueTo(255);
        slider.setStepSize(1);
        slider.setValue(initial);
        slider.setThumbHeight(thumbHeightPx);
        slider.setHaloRadius((int) (24 * dp));
        slider.setHaloTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setTrackActiveTintList(ColorStateList.valueOf(accent));
        slider.setTrackInactiveTintList(ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 70)));
        slider.setTrackHeight((int) (4 * dp));
        slider.setTrackStopIndicatorSize(0);
        slider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        slider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        slider.addOnChangeListener((s, value, fromUser) -> { if (fromUser) onChange.accept(Math.round(value)); });

        column.addView(nameLabel);
        column.addView(slider);
        parentRow.addView(column);
        return slider;
    }

    private void applyHexInput(android.widget.EditText hexInput, Slider[] sliders, boolean[] isUpdatingUi,
                                android.graphics.drawable.GradientDrawable previewBg) {
        String text = hexInput.getText().toString().trim();
        if (text.startsWith("#")) text = text.substring(1);
        if (!text.matches("[0-9A-Fa-f]{6}")) return;
        int color = Integer.parseInt(text, 16);

        isUpdatingUi[0] = true;
        sliders[0].setValue((color >> 16) & 0xFF);
        sliders[1].setValue((color >> 8) & 0xFF);
        sliders[2].setValue(color & 0xFF);
        isUpdatingUi[0] = false;

        previewBg.setColor(0xFF000000 | color);
        Prefs.putInt(this, Prefs.KEY_BACKLIGHT_COLOR, color);
        scheduleColorApply(color);
    }

    // --- setup banners ------------------------------------------------------------------------

    private void refreshBanners() {
        bannerContainer.removeAllViews();
        List<Check> checks = new ArrayList<>();

        checks.add(new Check() {
            public boolean isSatisfied() {
                return ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            }
            public int textRes() { return R.string.setup_banner_location; }
            public String actionLabel() { return "Grant"; }
            public Runnable action() {
                return () -> ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }, REQ_LOCATION);
            }
        });

        checks.add(new Check() {
            public boolean isSatisfied() { return Settings.System.canWrite(MainActivity.this); }
            public int textRes() { return R.string.setup_banner_write_settings; }
            public String actionLabel() { return "Grant"; }
            public Runnable action() {
                return () -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });

        checks.add(new Check() {
            public boolean isSatisfied() { return NightShiftControl.hasPermission(MainActivity.this); }
            public int textRes() { return R.string.setup_banner_secure_settings; }
            public String actionLabel() { return getString(R.string.copy); }
            public Runnable action() {
                return () -> {
                    String cmd = "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("adb command", cmd));
                    Toast.makeText(MainActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
                };
            }
        });

        checks.add(new Check() {
            public boolean isSatisfied() {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
            public int textRes() { return R.string.setup_banner_battery; }
            public String actionLabel() { return "Grant"; }
            public Runnable action() {
                return () -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        });

        for (Check check : checks) {
            if (check.isSatisfied()) continue;
            View banner = LayoutInflater.from(this).inflate(R.layout.view_setup_banner, bannerContainer, false);
            TextView text = banner.findViewById(R.id.banner_text);
            MaterialButton action = banner.findViewById(R.id.banner_action);
            if (check.textRes() == R.string.setup_banner_secure_settings) {
                text.setText(getString(check.textRes(), getPackageName()));
            } else {
                text.setText(check.textRes());
            }
            action.setText(check.actionLabel());
            action.setOnClickListener(v -> check.action().run());
            bannerContainer.addView(banner);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshBanners();
    }
}
