package com.radiorubka.wright;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DimmerMapTest {
    @Test public void wheelEndsMapToTheConfiguredRange() {
        assertEquals(1, Math.round(DimmerMap.displayBrightness(34, 1f / 255f, 15f / 255f, 1f)));
        assertEquals(15, Math.round(DimmerMap.displayBrightness(100, 1f / 255f, 15f / 255f, 1f)));
    }

    @Test public void levelsBelowTheDarkestPositionAreClamped() {
        assertEquals(DimmerMap.displayBrightness(34, 0.1f, 0.5f, 1f), DimmerMap.displayBrightness(10, 0.1f, 0.5f, 1f), 0f);
        assertEquals(DimmerMap.displayBrightness(100, 0.1f, 0.5f, 1f), DimmerMap.displayBrightness(130, 0.1f, 0.5f, 1f), 0f);
    }

    @Test public void displayBrightnessIsMonotonic() {
        float prev = -1f;
        for (int level = 34; level <= 100; level++) {
            float b = DimmerMap.displayBrightness(level, 0.004f, 0.06f, 1.6f);
            assertTrue(b >= prev);
            prev = b;
        }
    }

    @Test public void buttonColourIsUnchangedAtFullAndKeepsTheHueBelow() {
        int base = 0xAE2100;
        assertEquals(base, DimmerMap.scaleColor(base, 100, 0.1f, 1f, 1f));
        int mid = DimmerMap.scaleColor(base, 67, 0.1f, 1f, 1f);
        float r = ((mid >> 16) & 0xFF) / 174f, g = ((mid >> 8) & 0xFF) / 33f;
        assertTrue(Math.abs(r - g) < 0.1f);
        assertEquals(0, mid & 0xFF);                       // blue stays 0
    }

    @Test public void scaleColorNeverLeavesTheByteRange() {
        assertEquals(0xFFFFFF, DimmerMap.scaleColor(0xFFFFFF, 5f));
        assertEquals(0x000000, DimmerMap.scaleColor(0xFFFFFF, -1f));
    }

    @Test public void smootherConvergesWithoutOvershootAndMovesAboutSixtyThreePercentPerTimeConstant() {
        DimmerMap.Smoother s = new DimmerMap.Smoother(300f);
        s.step(0f, 50f);
        float v = s.step(100f, 300f);
        assertTrue(v > 62f && v < 64f);
        for (int i = 0; i < 200; i++) {
            v = s.step(100f, 50f);
            assertTrue(v <= 100.0001f);
        }
        assertTrue(v > 99.9f);
    }
}
