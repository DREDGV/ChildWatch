package ru.example.childwatch.designsystem;
import android.location.Location;
import android.os.Build;
import org.json.JSONObject;
/** SI units; absent measurements remain absent. */
public final class LocationMotion {
    private LocationMotion() {}
    public static Float speed(Location location) {
        return location.hasSpeed() && Float.isFinite(location.getSpeed()) && location.getSpeed() >= 0 && location.getSpeed() <= 60 ? location.getSpeed() : null;
    }
    public static Float accuracy(Location location) {
        return Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy() && Float.isFinite(location.getSpeedAccuracyMetersPerSecond()) && location.getSpeedAccuracyMetersPerSecond() >= 0 ? location.getSpeedAccuracyMetersPerSecond() : null;
    }
    public static void put(JSONObject json, Location location) {
        try { Float speed = speed(location), accuracy = accuracy(location);
            if (speed != null) json.put("speedMps", speed);
            if (accuracy != null) json.put("speedAccuracyMps", accuracy);
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }
}
