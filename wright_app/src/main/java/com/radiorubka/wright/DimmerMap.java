package com.radiorubka.wright;

/**
 * wRight side. Maps the dimmer level that wFabia publishes (0 = lights off, 34..100 = wheel) to the display
 * brightness and to the button-backlight colour, with a smooth interpolation so the 5 Hz updates never show as steps.
 *
 * Pure Java, no Android classes. All ranges are fractions 0..1 of full scale so the same code serves the display
 * (brightness 0..255) and the buttons (colour scale).
 */
public final class DimmerMap {
    public static final int WHEEL_MIN = 34;
    public static final int WHEEL_MAX = 100;

    private DimmerMap() {}

    /** wheel position 0..1 from a level; levels below the darkest position are clamped */
    public static float position(int level) {
        float x = (level - WHEEL_MIN) / (float) (WHEEL_MAX - WHEEL_MIN);
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    /** fraction 0..1 between min and max for the wheel position. gamma > 1 gives more resolution at the dark end. */
    public static float fraction(int level, float min, float max, float gamma) {
        float x = (float) Math.pow(position(level), gamma);
        return min + (max - min) * x;
    }

    /** display brightness on the Android 0..255 scale, as a float so the caller can interpolate before rounding. */
    public static float displayBrightness(int level, float minFraction, float maxFraction, float gamma) {
        return 255f * fraction(level, minFraction, maxFraction, gamma);
    }

    /** the button colour (0xRRGGBB) scaled to the dimmer, hue kept. Channels are rounded from floats. */
    public static int scaleColor(int rgb, int level, float minFraction, float maxFraction, float gamma) {
        float f = fraction(level, minFraction, maxFraction, gamma);
        return scaleColor(rgb, f);
    }

    public static int scaleColor(int rgb, float f) {
        int r = Math.round(((rgb >> 16) & 0xFF) * f);
        int g = Math.round(((rgb >> 8) & 0xFF) * f);
        int b = Math.round((rgb & 0xFF) * f);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /**
     * Time-based exponential smoothing, independent of the update rate: value moves toward the target with time
     * constant tauMs. Call step() from a ~50-100 ms timer while value != target.
     */
    public static final class Smoother {
        private final float tauMs;
        private float value;
        private boolean init;

        public Smoother(float tauMs) { this.tauMs = tauMs; }

        public float step(float target, float dtMs) {
            if (!init) {
                value = target;
                init = true;
                return value;
            }
            float a = 1f - (float) Math.exp(-dtMs / tauMs);
            value += (target - value) * a;
            return value;
        }

        public float value() { return value; }

        public void jumpTo(float v) { value = v; init = true; }

        public void clear() { init = false; }
    }
}
