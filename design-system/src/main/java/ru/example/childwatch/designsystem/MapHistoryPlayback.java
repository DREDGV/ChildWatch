package ru.example.childwatch.designsystem;

import java.util.List;

/** Plays recorded fixes within a single observed segment. */
final class MapHistoryPlayback {
    static final int SPEED = 60;

    private final List<List<MapRouteSegments.Fix>> segments;

    MapHistoryPlayback(List<MapRouteSegments.Fix> fixes) {
        segments = MapRouteSegments.split(fixes);
    }

    List<List<MapRouteSegments.Fix>> segments() {
        return segments;
    }

    Long firstTime() {
        return segments.isEmpty() ? null : segments.get(0).get(0).timestampMs;
    }

    Long nextSegmentAfter(long time) {
        int next = segmentStartingAtOrBefore(time) + 1;
        return next < segments.size() ? segments.get(next).get(0).timestampMs : null;
    }

    boolean canPlay(long time) {
        List<MapRouteSegments.Fix> segment = segmentAt(time);
        return segment != null && time < last(segment).timestampMs;
    }

    MapRouteSegments.Fix pointAt(long time) {
        List<MapRouteSegments.Fix> segment = segmentAt(time);
        return segment == null ? null : pointIn(segment, time);
    }

    Frame advance(long time, long elapsedMs) {
        List<MapRouteSegments.Fix> segment = segmentAt(time);
        if (segment == null) return new Frame(time, null, true);
        long end = last(segment).timestampMs;
        long remaining = end - time;
        long elapsed = Math.max(0, elapsedMs);
        long next = elapsed > remaining / SPEED ? end : time + elapsed * SPEED;
        return new Frame(next, pointIn(segment, next), next == end);
    }

    static final class Frame {
        final long time;
        final MapRouteSegments.Fix point;
        final boolean ended;

        Frame(long time, MapRouteSegments.Fix point, boolean ended) {
            this.time = time;
            this.point = point;
            this.ended = ended;
        }
    }

    private List<MapRouteSegments.Fix> segmentAt(long time) {
        int index = segmentStartingAtOrBefore(time);
        if (index < 0) return null;
        List<MapRouteSegments.Fix> segment = segments.get(index);
        return time <= last(segment).timestampMs ? segment : null;
    }

    private int segmentStartingAtOrBefore(long time) {
        int left = -1;
        int right = segments.size();
        while (right - left > 1) {
            int middle = (left + right) / 2;
            if (segments.get(middle).get(0).timestampMs <= time) left = middle;
            else right = middle;
        }
        return left;
    }

    private static MapRouteSegments.Fix pointIn(List<MapRouteSegments.Fix> segment, long time) {
        int left = 0;
        int right = segment.size();
        while (right - left > 1) {
            int middle = (left + right) / 2;
            if (segment.get(middle).timestampMs <= time) left = middle;
            else right = middle;
        }
        return segment.get(left);
    }

    private static MapRouteSegments.Fix last(List<MapRouteSegments.Fix> segment) {
        return segment.get(segment.size() - 1);
    }
}
