package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Color;
import java.util.List;

/** Position-derived speed is an estimate, never transport recognition or an instantaneous sensor value. */
public final class MapSpeed {
    private MapSpeed() {}
    public static Double estimate(List<MapRouteSegments.Fix> segment, int end) {
        if (end < 0 || end >= segment.size()) return null;
        Double measured = measured(segment.get(end));
        if (measured != null) return measured;
        if (end < 1) return null;
        MapRouteSegments.Fix latest = segment.get(end);
        if (!valid(latest)) return null;
        MapRouteSegments.Fix first = null;
        for (int i = end - 1; i >= 0; i--) {
            MapRouteSegments.Fix candidate = segment.get(i);
            long elapsed = latest.timestampMs - candidate.timestampMs;
            if (!valid(candidate) || elapsed > 45_000L || elapsed <= 0) break;
            // A speed window must not bridge an unreliable point or a route break.
            if (segment.get(i + 1).timestampMs - candidate.timestampMs > 45_000L) break;
            if (elapsed >= 15_000L) first = candidate;
        }
        if (first == null) return null;
        double seconds = (latest.timestampMs - first.timestampMs) / 1000.0;
        double distance = MapRouteSegments.distanceMeters(first, latest);
        double speed = distance / seconds;
        double uncertainty = (first.accuracyMeters + latest.accuracyMeters) / seconds;
        if (speed > 60 || uncertainty > Math.max(.7, speed * .35)) return null;
        return distance <= first.accuracyMeters + latest.accuracyMeters ? 0.0 : speed * 3.6;
    }
    public static Double latest(List<MapRouteSegments.Fix> fixes, long positionTime, long now) {
        if (fixes == null || fixes.isEmpty() || positionTime <= 0 || now - positionTime < -30_000L || now - positionTime > 45_000L) return null;
        List<List<MapRouteSegments.Fix>> parts = MapRouteSegments.split(fixes);
        if (parts.isEmpty()) return null;
        List<MapRouteSegments.Fix> last = parts.get(parts.size() - 1);
        if (last.isEmpty() || last.get(last.size() - 1).timestampMs != positionTime) return null;
        return estimate(last, last.size() - 1);
    }
    private static boolean valid(MapRouteSegments.Fix fix) {
        return fix.accuracyMeters > 0 && fix.accuracyMeters <= 50 && Float.isFinite(fix.accuracyMeters);
    }
    public static Double measured(MapRouteSegments.Fix fix) {
        if (fix == null || !valid(fix) || fix.speedMps == null || fix.speedAccuracyMps == null) return null;
        float speed = fix.speedMps, accuracy = fix.speedAccuracyMps;
        return Float.isFinite(speed) && speed >= 0 && speed <= 60 && Float.isFinite(accuracy) && accuracy >= 0 && accuracy <= Math.max(.7, speed * .35) ? speed * 3.6 : null;
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
