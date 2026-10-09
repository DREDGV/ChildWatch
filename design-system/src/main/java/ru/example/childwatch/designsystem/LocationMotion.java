package ru.example.childwatch.designsystem;
import android.location.Location;
import android.content.Context;
import android.provider.Settings;
import java.util.UUID;
import android.os.Build;
import org.json.JSONObject;
/** SI units; absent measurements remain absent. */
public final class LocationMotion {
    private LocationMotion() {}
    private static final String PROCESS_SESSION = "process-" + UUID.randomUUID();
    /** A process-only fallback deliberately prevents joining uncertain boot sessions. */
    public static synchronized String bootSessionId(Context context) {
        if (context == null) return PROCESS_SESSION;
        try {
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (boot < 0) return PROCESS_SESSION;
            android.content.SharedPreferences prefs = context.getSharedPreferences("location_measurement_identity", Context.MODE_PRIVATE);
            String installation = prefs.getString("installation", null);
            if (installation == null) {
                installation = UUID.randomUUID().toString();
                if (!prefs.edit().putString("installation", installation).commit()) return PROCESS_SESSION;
            }
            return installation + ":" + boot;
        } catch (RuntimeException denied) { return PROCESS_SESSION; }
    }
    public static Long measurementElapsedRealtimeNanos(Location location) {
        long nanos = location.getElapsedRealtimeNanos();
        return nanos > 0 ? nanos : null;
    }
    public static Float speed(Location location) {
        return location.hasSpeed() && Float.isFinite(location.getSpeed()) && location.getSpeed() >= 0 && location.getSpeed() <= 60 ? location.getSpeed() : null;
    }
    public static Float accuracy(Location location) {
        return Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy() && Float.isFinite(location.getSpeedAccuracyMetersPerSecond()) && location.getSpeedAccuracyMetersPerSecond() >= 0 ? location.getSpeedAccuracyMetersPerSecond() : null;
    }
    public static void put(JSONObject json, Location location) {
        put(json, location, null);
    }
    public static void put(JSONObject json, Location location, Context context) {
        try { Float speed = speed(location), accuracy = accuracy(location);
            if (speed != null) json.put("speedMps", speed);
            if (accuracy != null) json.put("speedAccuracyMps", accuracy);
            Long nanos = measurementElapsedRealtimeNanos(location);
            if (nanos != null) {
                // Decimal text is lossless beyond JavaScript's 53-bit numeric precision.
                json.put("measurementElapsedRealtimeNanos", Long.toString(nanos));
                json.put("bootSessionId", bootSessionId(context));
            }
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }
}
