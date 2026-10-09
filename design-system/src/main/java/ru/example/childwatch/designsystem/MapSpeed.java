package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Color;
import java.util.List;

/** Position-derived speed is an estimate, never transport recognition or an instantaneous sensor value. */
public final class MapSpeed {
    private MapSpeed() {}
    public static MapSpeedEstimator.Sample sample(MapRouteSegments.Fix fix) {
        return new MapSpeedEstimator.Sample(fix.deviceId, fix.latitude, fix.longitude, fix.timestampMs,
                fix.accuracyMeters, fix.speedMps, fix.speedAccuracyMps,
                fix.measurementElapsedRealtimeNanos, fix.bootSessionId);
    }
    public static MapSpeedEstimator.Result result(List<MapRouteSegments.Fix> fixes, int end, long now) {
        MapSpeedEstimator estimator = new MapSpeedEstimator();
        MapSpeedEstimator.Result result = MapSpeedEstimator.evaluate(null, now);
        if (fixes == null || end < 0 || end >= fixes.size()) return result;
        for (MapRouteSegments.Fix fix : ordered(fixes.subList(0, end + 1))) result = estimator.accept(sample(fix), now);
        return result;
    }
    /** Sort within a boot only; group order remains the caller's observed boot order. */
    public static List<MapRouteSegments.Fix> ordered(List<MapRouteSegments.Fix> fixes) {
        java.util.LinkedHashMap<String, java.util.ArrayList<MapRouteSegments.Fix>> groups = new java.util.LinkedHashMap<>();
        int legacy = 0;
        for (MapRouteSegments.Fix fix : fixes) {
            String key = fix.deviceId != null && fix.bootSessionId != null && fix.measurementElapsedRealtimeNanos != null
                    ? fix.deviceId.length() + ":" + fix.deviceId + ":" + fix.bootSessionId : "legacy:" + legacy++;
            groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(fix);
        }
        java.util.ArrayList<MapRouteSegments.Fix> sorted = new java.util.ArrayList<>();
        for (java.util.ArrayList<MapRouteSegments.Fix> group : groups.values()) {
            if (group.size() > 1) group.sort(java.util.Comparator.comparingLong(fix -> fix.measurementElapsedRealtimeNanos));
            sorted.addAll(group);
        }
        return sorted;
    }
    public static MapSpeedEstimator.Result measuredResult(MapRouteSegments.Fix fix, long now) {
        return MapSpeedEstimator.measured(fix == null ? null : sample(fix), now);
    }
    public static Double estimate(List<MapRouteSegments.Fix> segment, int end) {
        if (segment == null || end < 0 || end >= segment.size()) return null;
        return result(segment, end, segment.get(end).timestampMs).kmh();
    }
    public static Double latest(List<MapRouteSegments.Fix> fixes, long positionTime, long now) {
        if (fixes == null || fixes.isEmpty() || fixes.get(fixes.size() - 1).timestampMs != positionTime) return null;
        return result(fixes, fixes.size() - 1, now).kmh();
    }
    public static Double measured(MapRouteSegments.Fix fix) {
        return measuredResult(fix, fix == null ? 0 : fix.timestampMs).kmh();
    }
    public static String text(Context context, Double kmh) {
        if (kmh == null) return context.getString(R.string.cw_map_speed_unknown);
        return context.getString(R.string.cw_map_speed_value, Math.round(kmh));
    }
    public static int color(Context context, Double kmh) {
        if (kmh == null) return context.getColor(R.color.cw_map_speed_unknown_color);
        double[] stops = {0, 7, 25, 60, 100};
        int[] colors = {context.getColor(R.color.cw_map_speed_slow), context.getColor(R.color.cw_map_speed_walk),
            context.getColor(R.color.cw_map_speed_medium), context.getColor(R.color.cw_map_speed_fast), context.getColor(R.color.cw_map_speed_high)};
        for (int i = 1; i < stops.length; i++) {
            if (kmh <= stops[i]) return blend(colors[i - 1], colors[i], Math.max(0, (kmh - stops[i - 1]) / (stops[i] - stops[i - 1])));
        }
        return colors[colors.length - 1];
    }
    private static int blend(int first, int second, double fraction) {
        return Color.rgb((int)Math.round(Color.red(first) + (Color.red(second) - Color.red(first)) * fraction),
            (int)Math.round(Color.green(first) + (Color.green(second) - Color.green(first)) * fraction),
            (int)Math.round(Color.blue(first) + (Color.blue(second) - Color.blue(first)) * fraction));
    }
}
