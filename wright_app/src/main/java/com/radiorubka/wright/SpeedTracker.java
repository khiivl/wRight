package com.radiorubka.wright;

import android.location.Location;

import java.util.HashMap;
import java.util.Map;

/**
 * Speed from the location fixes wRight already receives, for wFabia's speed row (sent over the vehicle pipe). The head unit's
 * providers differ in what they carry, so the provider's own speed is used when the fix has one, and otherwise the distance to the
 * previous fix of the same provider (never the network provider, whose positions jump).
 */
final class SpeedTracker {
    /** one result per fix; hasSpeed false = this fix gives no speed */
    static final class Sample {
        final boolean hasSpeed;
        final float speedMs;
        final float accuracy;
        final String provider;

        Sample(boolean hasSpeed, float speedMs, float accuracy, String provider) {
            this.hasSpeed = hasSpeed;
            this.speedMs = speedMs;
            this.accuracy = accuracy;
            this.provider = provider;
        }
    }

    private final Map<String, Location> previous = new HashMap<>();

    synchronized Sample onLocation(Location l) {
        String prov = l.getProvider() == null ? "?" : l.getProvider();
        float accuracy = l.hasAccuracy() ? l.getAccuracy() : -1f;
        if (l.hasSpeed()) {
            previous.put(prov, l);
            return new Sample(true, l.getSpeed(), accuracy, prov);
        }
        Sample result = new Sample(false, 0f, accuracy, prov);
        if (!"network".equals(prov)) {
            Location prev = previous.get(prov);
            if (prev != null) {
                double dt = (l.getElapsedRealtimeNanos() - prev.getElapsedRealtimeNanos()) / 1e9;
                if (dt > 0.3 && dt < 10) result = new Sample(true, (float) (prev.distanceTo(l) / dt), accuracy, prov + " (from positions)");
            }
        }
        previous.put(prov, l);
        return result;
    }

    synchronized void clear() {
        previous.clear();
    }
}
