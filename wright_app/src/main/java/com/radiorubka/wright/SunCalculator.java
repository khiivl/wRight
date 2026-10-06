package com.radiorubka.wright;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Pure offline sunrise/sunset calculator (NOAA's solar position algorithm, accurate to within
 * about a minute - plenty for driving a theme/brightness schedule). No network, no external
 * services: lat/lon/time in, two UTC instants out.
 *
 * Longitude follows Android's own convention (Location.getLongitude()): positive = East.
 */
public class SunCalculator {

    public static class SunTimes {
        public final long sunriseUtcMillis;
        public final long sunsetUtcMillis;

        SunTimes(long sunrise, long sunset) {
            this.sunriseUtcMillis = sunrise;
            this.sunsetUtcMillis = sunset;
        }
    }

    /** nowUtcMillis just selects which UTC calendar day to compute for. */
    public static SunTimes calculate(double latitude, double longitude, long nowUtcMillis) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(nowUtcMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnightUtcMillis = cal.getTimeInMillis();

        double jd = julianDay(cal);
        double t = (jd - 2451545.0) / 36525.0;

        double eqTime = equationOfTimeMinutes(t);
        double solarDec = sunDeclinationDegrees(t);
        double haDegrees = hourAngleDegrees(latitude, solarDec);

        double solarNoonUtcMinutes = 720.0 - 4.0 * longitude - eqTime;
        double sunriseUtcMinutes = solarNoonUtcMinutes - 4.0 * haDegrees;
        double sunsetUtcMinutes = solarNoonUtcMinutes + 4.0 * haDegrees;

        long sunrise = midnightUtcMillis + Math.round(sunriseUtcMinutes * 60_000.0);
        long sunset = midnightUtcMillis + Math.round(sunsetUtcMinutes * 60_000.0);
        return new SunTimes(sunrise, sunset);
    }

    private static double julianDay(Calendar cal) {
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4.0);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    private static double geomMeanLongSunDeg(double t) {
        double l = 280.46646 + t * (36000.76983 + t * 0.0003032);
        l = l % 360.0;
        return l < 0 ? l + 360.0 : l;
    }

    private static double geomMeanAnomalySunDeg(double t) {
        return 357.52911 + t * (35999.05029 - 0.0001537 * t);
    }

    private static double eccentricEarthOrbit(double t) {
        return 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
    }

    private static double sunEqOfCenterDeg(double t) {
        double m = Math.toRadians(geomMeanAnomalySunDeg(t));
        return Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * m) * 0.000289;
    }

    private static double sunApparentLongDeg(double t) {
        double trueLong = geomMeanLongSunDeg(t) + sunEqOfCenterDeg(t);
        double omega = 125.04 - 1934.136 * t;
        return trueLong - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega));
    }

    private static double meanObliquityOfEclipticDeg(double t) {
        return 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0;
    }

    private static double obliquityCorrectionDeg(double t) {
        double e0 = meanObliquityOfEclipticDeg(t);
        double omega = 125.04 - 1934.136 * t;
        return e0 + 0.00256 * Math.cos(Math.toRadians(omega));
    }

    private static double sunDeclinationDegrees(double t) {
        double e = Math.toRadians(obliquityCorrectionDeg(t));
        double lambda = Math.toRadians(sunApparentLongDeg(t));
        double sint = Math.sin(e) * Math.sin(lambda);
        return Math.toDegrees(Math.asin(sint));
    }

    private static double equationOfTimeMinutes(double t) {
        double epsilon = Math.toRadians(obliquityCorrectionDeg(t));
        double l0 = Math.toRadians(geomMeanLongSunDeg(t));
        double e = eccentricEarthOrbit(t);
        double m = Math.toRadians(geomMeanAnomalySunDeg(t));

        double y = Math.tan(epsilon / 2.0);
        y *= y;

        double sin2l0 = Math.sin(2 * l0);
        double sinm = Math.sin(m);
        double cos2l0 = Math.cos(2 * l0);
        double sin4l0 = Math.sin(4 * l0);
        double sin2m = Math.sin(2 * m);

        double etMinutesRad = y * sin2l0 - 2 * e * sinm + 4 * e * y * sinm * cos2l0
                - 0.5 * y * y * sin4l0 - 1.25 * e * e * sin2m;
        return Math.toDegrees(etMinutesRad) * 4.0;
    }

    /** Degrees of hour angle between solar noon and sunrise/sunset (symmetric). */
    private static double hourAngleDegrees(double latitude, double solarDecDegrees) {
        double latRad = Math.toRadians(latitude);
        double decRad = Math.toRadians(solarDecDegrees);
        // 90.833deg accounts for atmospheric refraction (~0.567deg) plus the sun's apparent
        // radius (~0.267deg) - the standard correction for the geometric horizon.
        double cosH = Math.cos(Math.toRadians(90.833)) / (Math.cos(latRad) * Math.cos(decRad))
                - Math.tan(latRad) * Math.tan(decRad);
        double clamped = Math.max(-1.0, Math.min(1.0, cosH));
        return Math.toDegrees(Math.acos(clamped));
    }
}
