package ru.example.childwatch.designsystem;
import android.location.Location;

/** Shared measurement and motion gates; timestamps describe capture, never upload time. */
public final class LocationQuality {
    public static boolean usable(Location fix) {
        if (fix == null || !Double.isFinite(fix.getLatitude()) || !Double.isFinite(fix.getLongitude()) ||
            Math.abs(fix.getLatitude()) > 90 || Math.abs(fix.getLongitude()) > 180 ||
            !fix.hasAccuracy() || !Float.isFinite(fix.getAccuracy()) || fix.getAccuracy() <= 0 || fix.getAccuracy() > 500) return false;
        long age = System.currentTimeMillis() - fix.getTime();
        return fix.getTime() > 0 && age >= -60000 && age <= 120000;
    }
    public static boolean moving(Location fix, Location previous, float threshold) {
        if (!usable(fix) || fix.getAccuracy() > 100) return false;
        if (fix.hasSpeed() && Float.isFinite(fix.getSpeed()) && fix.getSpeed() <= 60 &&
            fix.getSpeed() - (fix.hasSpeedAccuracy() ? fix.getSpeedAccuracyMetersPerSecond() : 0) >= threshold) return true;
        if (previous == null || !usable(previous)) return false;
        long elapsed = fix.getTime() - previous.getTime();
        if (elapsed <= 0 || elapsed > 120000) return false;
        float distance = fix.distanceTo(previous);
        float uncertainty = Math.max(12, fix.getAccuracy() + previous.getAccuracy());
        return distance > uncertainty && distance / (elapsed / 1000f) >= threshold && distance / (elapsed / 1000f) <= 60;
    }
    private LocationQuality() {}
}
