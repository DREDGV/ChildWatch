package ru.example.childwatch.designsystem;

import java.util.ArrayList;
import java.util.List;

/** Keeps missing or unreliable fixes from becoming invented straight-line journeys. */
public final class MapRouteSegments {
    private static final long MAX_GAP_MS = 45_000L;
    private static final double MAX_SPEED_MPS = 60.0;
    private static final float MAX_ACCURACY_METERS = 200f;

    private MapRouteSegments() { }

    public static final class Fix {
        public final double latitude;
        public final double longitude;
        public final long timestampMs;
        public final float accuracyMeters;
        public final Float speedMps;
        public final Float speedAccuracyMps;
        public final String deviceId, bootSessionId;
        public final Long measurementElapsedRealtimeNanos;

        public Fix(double latitude, double longitude, long timestampMs, float accuracyMeters) {
            this(latitude, longitude, timestampMs, accuracyMeters, null, null);
        }
        public Fix(double latitude, double longitude, long timestampMs, float accuracyMeters, Float speedMps, Float speedAccuracyMps) {
            this(latitude, longitude, timestampMs, accuracyMeters, speedMps, speedAccuracyMps, null, null, null);
        }
        public Fix(double latitude, double longitude, long timestampMs, float accuracyMeters, Float speedMps, Float speedAccuracyMps, String deviceId, Long measurementElapsedRealtimeNanos, String bootSessionId) {
            this.deviceId = deviceId; this.measurementElapsedRealtimeNanos = measurementElapsedRealtimeNanos; this.bootSessionId = bootSessionId;
            this.speedMps = speedMps;
            this.speedAccuracyMps = speedAccuracyMps;
            this.latitude = latitude;
            this.longitude = longitude;
            this.timestampMs = timestampMs;
            this.accuracyMeters = accuracyMeters;
        }
    }

    /** Input must be sorted from oldest to newest. Single-fix segments show no line. */
    public static List<List<Fix>> split(List<Fix> fixes) {
        List<List<Fix>> segments = new ArrayList<>();
        List<Fix> current = null;
        Fix previous = null;
        for (Fix fix : fixes) {
            if (!isUsable(fix)) {
                current = null;
                previous = null;
                continue;
            }
            if (previous == null || !canConnect(previous, fix)) {
                current = new ArrayList<>();
                segments.add(current);
            }
            current.add(fix);
            previous = fix;
        }
        return segments;
    }

    public static double distanceMeters(Fix first, Fix second) {
        double lat1 = Math.toRadians(first.latitude);
        double lat2 = Math.toRadians(second.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(second.longitude - first.longitude);
        double a = Math.pow(Math.sin(dLat / 2), 2) +
                Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    /** Only removes near-collinear fixes; GPS uncertainty must not erase real turns. */
    public static List<Fix> simplify(List<Fix> segment) {
        if (segment.size() <= 2) return segment;
        float[] accuracies = new float[segment.size()];
        int accuracyCount = 0;
        for (Fix fix : segment) {
            if (fix.accuracyMeters > 0 && fix.accuracyMeters <= MAX_ACCURACY_METERS) {
                accuracies[accuracyCount++] = fix.accuracyMeters;
            }
        }
        java.util.Arrays.sort(accuracies, 0, accuracyCount);
        double typicalAccuracy = accuracyCount == 0 ? 6 : accuracies[accuracyCount / 2];
        double toleranceMeters = Math.min(2, Math.max(1, typicalAccuracy / 10));
        boolean[] keep = new boolean[segment.size()];
        keep[0] = true;
        keep[segment.size() - 1] = true;
        retainTurns(segment, 0, segment.size() - 1, toleranceMeters, keep);
        List<Fix> result = new ArrayList<>();
        for (int index = 0; index < segment.size(); index++) {
            if (keep[index]) result.add(segment.get(index));
        }
        return result;
    }

    private static void retainTurns(List<Fix> segment, int first, int last,
                                    double toleranceMeters, boolean[] keep) {
        if (last - first <= 1) return;
        double greatest = 0;
        int farthest = -1;
        for (int index = first + 1; index < last; index++) {
            double distance = distanceFromSegmentMeters(
                    segment.get(index), segment.get(first), segment.get(last));
            if (distance > greatest) {
                greatest = distance;
                farthest = index;
            }
        }
        if (farthest < 0 || greatest <= toleranceMeters) return;
        keep[farthest] = true;
        retainTurns(segment, first, farthest, toleranceMeters, keep);
        retainTurns(segment, farthest, last, toleranceMeters, keep);
    }

    private static double distanceFromSegmentMeters(Fix point, Fix start, Fix end) {
        double longitudeScale = 111195.0 * Math.cos(Math.toRadians(start.latitude));
        double ax = (end.longitude - start.longitude) * longitudeScale;
        double ay = (end.latitude - start.latitude) * 111195.0;
        double px = (point.longitude - start.longitude) * longitudeScale;
        double py = (point.latitude - start.latitude) * 111195.0;
        double lengthSquared = ax * ax + ay * ay;
        double fraction = lengthSquared == 0 ? 0 :
                Math.max(0, Math.min(1, (px * ax + py * ay) / lengthSquared));
        return Math.hypot(px - fraction * ax, py - fraction * ay);
    }

    private static boolean isUsable(Fix fix) {
        return !Double.isNaN(fix.latitude) && !Double.isInfinite(fix.latitude) &&
                !Double.isNaN(fix.longitude) && !Double.isInfinite(fix.longitude) &&
                Math.abs(fix.latitude) <= 90 && Math.abs(fix.longitude) <= 180 &&
                !(fix.latitude == 0 && fix.longitude == 0) && fix.timestampMs > 0 &&
                !Float.isNaN(fix.accuracyMeters) && !Float.isInfinite(fix.accuracyMeters) &&
                (fix.accuracyMeters > 0 && fix.accuracyMeters <= MAX_ACCURACY_METERS);
    }

    private static boolean canConnect(Fix first, Fix second) {
        long elapsed = second.timestampMs - first.timestampMs;
        if (elapsed <= 0 || elapsed > MAX_GAP_MS) return false;
        double allowance = Math.max(0, first.accuracyMeters) + Math.max(0, second.accuracyMeters);
        return distanceMeters(first, second) <= elapsed / 1000.0 * MAX_SPEED_MPS + allowance;
    }
}
