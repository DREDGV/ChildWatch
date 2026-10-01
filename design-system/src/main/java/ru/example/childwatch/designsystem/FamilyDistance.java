package ru.example.childwatch.designsystem;

import android.content.Context;
import android.location.Location;
import java.util.Locale;

/** Straight-line distance, only between two recent, sufficiently accurate measurements. */
public final class FamilyDistance {
    public static final class Point {
        public final double latitude, longitude;
        public final long timestamp;
        public final float accuracy;
        public Point(double latitude, double longitude, long timestamp, float accuracy) {
            this.latitude = latitude; this.longitude = longitude;
            this.timestamp = timestamp; this.accuracy = accuracy;
        }
    }
    public static String text(Context context, Point person, Point own, boolean samePerson) {
        if (samePerson) return context.getString(R.string.cw_distance_self);
        if (!valid(person)) return context.getString(R.string.cw_distance_person_missing);
        if (!valid(own)) return context.getString(R.string.cw_distance_own_missing);
        long now = System.currentTimeMillis();
        if (!fresh(person, now) || !fresh(own, now)) return context.getString(R.string.cw_distance_stale);
        if (!accurate(person) || !accurate(own)) return context.getString(R.string.cw_distance_inaccurate);
        float[] meters = new float[1];
        Location.distanceBetween(person.latitude, person.longitude, own.latitude, own.longitude, meters);
        // Round rather than suggesting GPS can resolve an exact metre.
        if (meters[0] < 50f) return context.getString(R.string.cw_distance_nearby);
        if (meters[0] < 1000f) return context.getString(R.string.cw_distance_m, Math.round(meters[0] / 50f) * 50);
        return context.getString(R.string.cw_distance_km, String.format(Locale.getDefault(), "%.1f", meters[0] / 1000f));
    }
    public static String compactText(Context context, Point person, Point own, boolean samePerson) {
        if (samePerson) return context.getString(R.string.cw_map_self);
        if (!canMeasure(person, own)) return context.getString(R.string.cw_map_no_distance);
        return text(context, person, own, false).split(" · ")[0];
    }
    public static boolean canMeasure(Point person, Point own) {
        long now = System.currentTimeMillis();
        return valid(person) && valid(own) && fresh(person, now) && fresh(own, now)
                && accurate(person) && accurate(own);
    }
    private static boolean valid(Point p) {
        return p != null && Double.isFinite(p.latitude) && Double.isFinite(p.longitude)
                && p.latitude >= -90 && p.latitude <= 90 && p.longitude >= -180 && p.longitude <= 180;
    }
    private static boolean fresh(Point p, long now) {
        if (p.timestamp <= 0) return false;
        long millis = p.timestamp < 10_000_000_000L ? p.timestamp * 1000L : p.timestamp;
        long age = now - millis;
        return age >= -60_000L && age <= 300_000L;
    }
    private static boolean accurate(Point p) {
        return Float.isFinite(p.accuracy) && p.accuracy > 0 && p.accuracy <= 500;
    }
    private FamilyDistance() {}
}
