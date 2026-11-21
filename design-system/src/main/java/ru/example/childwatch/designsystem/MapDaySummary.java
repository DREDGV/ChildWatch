package ru.example.childwatch.designsystem;

import java.util.ArrayList;
import java.util.List;

/** Coverage inferred only between connected measurements of the selected route. */
final class MapDaySummary {
    private static final long MIN_STOP_MS = 120_000L;
    private static final float MAX_STOP_ACCURACY = 25f;
    private static final double STOP_RADIUS_METERS = 20.0;

    static final class Event {
        final boolean gap;
        final long start;
        final long end;

        Event(boolean gap, long start, long end) {
            this.gap = gap;
            this.start = start;
            this.end = end;
        }

        long seekTime() {
            return gap ? start + (end - start) / 2 : start;
        }
    }

    final List<Event> events = new ArrayList<>();
    long observedMs;
    int stops;
    int gaps;

    MapDaySummary(List<List<MapRouteSegments.Fix>> segments, long from, long to, boolean complete) {
        Long coveredUntil = complete ? from : null;
        for (List<MapRouteSegments.Fix> segment : segments) {
            long start = segment.get(0).timestampMs;
            long end = segment.get(segment.size() - 1).timestampMs;
            if (coveredUntil != null) addGap(coveredUntil, start);
            observedMs += end - start;
            addStops(segment);
            coveredUntil = end;
        }
        if (complete && coveredUntil != null) addGap(coveredUntil, to);
    }

    private void addGap(long start, long end) {
        if (end - start < 2) return;
        events.add(new Event(true, start, end));
        gaps++;
    }

    private void addStops(List<MapRouteSegments.Fix> segment) {
        int first = 0;
        while (first < segment.size()) {
            MapRouteSegments.Fix anchor = segment.get(first);
            if (!looksStill(anchor)) {
                first++;
                continue;
            }
            int next = first + 1;
            while (next < segment.size()) {
                MapRouteSegments.Fix point = segment.get(next);
                if (!looksStill(point) || MapRouteSegments.distanceMeters(anchor, point) > STOP_RADIUS_METERS) break;
                next++;
            }
            long end = segment.get(next - 1).timestampMs;
            if (next - first >= 3 && end - anchor.timestampMs >= MIN_STOP_MS) {
                events.add(new Event(false, anchor.timestampMs, end));
                stops++;
            }
            first = next;
        }
    }

    private static boolean looksStill(MapRouteSegments.Fix point) {
        return point.accuracyMeters <= MAX_STOP_ACCURACY
            && (point.speedMps == null || !Float.isFinite(point.speedMps) || point.speedMps <= 1.2f);
    }
}
