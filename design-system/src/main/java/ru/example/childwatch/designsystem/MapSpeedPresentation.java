package ru.example.childwatch.designsystem;

import android.content.Context;

/** Compact map labels and details use the same quality-qualified result. */
public final class MapSpeedPresentation {
    private MapSpeedPresentation() { }

    public static String label(Context context, MapSpeedEstimator.Result result) {
        if (result == null || result.valueMps == null) return null;
        int resource = result.source == MapSpeedEstimator.Source.MEASURED
                ? R.string.cw_map_speed_measured : R.string.cw_map_speed_value;
        long rounded = Math.round(result.valueMps * 3.6);
        String value = result.valueMps > 0 && rounded == 0
                ? context.getString(result.source == MapSpeedEstimator.Source.MEASURED
                    ? R.string.cw_map_speed_below_one : R.string.cw_map_speed_estimated_below_one)
                : context.getString(resource, rounded);
        return result.freshness == MapSpeedEstimator.Freshness.AGING
                ? context.getString(R.string.cw_map_speed_aging_value, value) : value;
    }

    public static String details(Context context, MapSpeedEstimator.Result result, long now) {
        if (result == null || result.valueMps == null) return context.getString(
                result != null && result.freshness == MapSpeedEstimator.Freshness.EXPIRED
                        ? R.string.cw_map_speed_stale : R.string.cw_map_speed_unknown);
        String source = context.getString(result.source == MapSpeedEstimator.Source.MEASURED
                ? R.string.cw_map_speed_source_measured : R.string.cw_map_speed_source_estimated);
        return context.getString(R.string.cw_map_speed_details, source,
                result.uncertaintyMps * 3.6, Math.max(0, now - result.capturedAtMs) / 1000);
    }
}
