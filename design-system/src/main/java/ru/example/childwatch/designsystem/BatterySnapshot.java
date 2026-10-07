package ru.example.childwatch.designsystem;

import android.content.Context;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Battery capture has its own device and clock, independent of a GPS fix. */
public final class BatterySnapshot {
    public final String deviceId;
    public final int level;
    public final long timestamp;
    public final Boolean charging;
    private BatterySnapshot(String deviceId, int level, long timestamp, Boolean charging) {
        this.deviceId = deviceId; this.level = level; this.timestamp = timestamp; this.charging = charging;
    }
    public static BatterySnapshot create(String expected, String reported, Integer level,
            Long time, Boolean charging, long now) {
        if (expected == null || expected.trim().isEmpty() || !expected.equals(reported) ||
            level == null || level < 0 || level > 100 || time == null || time <= 0) return null;
        long millis = time < 100_000_000_000L ? time * 1000L : time;
        if (millis > now) return null;
        return new BatterySnapshot(expected, level, millis, charging);
    }
    public static BatterySnapshot fromFamilyPoint(JSONObject point) {
        JSONObject battery = point.optJSONObject("battery");
        if (battery == null) return null; // Compatible with servers lacking this field.
        Object rawLevel = battery.opt("level"), rawTime = battery.opt("timestamp");
        if (!(rawLevel instanceof Number) || !(rawTime instanceof Number)) return null;
        double value = ((Number) rawLevel).doubleValue();
        double time = ((Number) rawTime).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > 100 ||
            !Double.isFinite(time) || time <= 0 || time > Long.MAX_VALUE || time != Math.rint(time)) return null;
        Object rawCharging = battery.opt("isCharging");
        return create(point.optString("deviceId"), battery.optString("deviceId"),
            (int)value, (long)time, rawCharging instanceof Boolean ? (Boolean)rawCharging : null,
            System.currentTimeMillis());
    }
    public boolean isStale(long now) { return now - timestamp > 10 * 60_000L || timestamp > now; }
    public String label(Context context) {
        String percent = level + "%";
        if (Boolean.TRUE.equals(charging)) percent += context.getString(R.string.cw_battery_charging);
        String date = new SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(new Date(timestamp));
        return context.getString(isStale(System.currentTimeMillis()) ? R.string.cw_battery_capture_stale
            : R.string.cw_battery_capture, percent, date);
    }
}
