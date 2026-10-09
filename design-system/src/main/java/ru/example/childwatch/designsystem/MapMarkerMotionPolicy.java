package ru.example.childwatch.designsystem;

/** Display interpolation only: never a speed estimate or a predicted GPS fix. */
public final class MapMarkerMotionPolicy {
    private MapMarkerMotionPolicy() { }

    public static final class Fix {
        public final String deviceId;
        public final double latitude, longitude, accuracy;
        public final long timeMs;
        public final Long elapsedRealtimeNanos;
        public final String bootId;
        public Fix(String deviceId, double latitude, double longitude, long timeMs, double accuracy) {
            this(deviceId, latitude, longitude, timeMs, accuracy, null, null);
        }
        public Fix(String deviceId, double latitude, double longitude, long timeMs, double accuracy,
                   Long elapsedRealtimeNanos, String bootId) {
            this.deviceId = deviceId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.timeMs = timeMs;
            this.accuracy = accuracy;
            this.elapsedRealtimeNanos = elapsedRealtimeNanos;
            this.bootId = bootId;
        }
    }

    private static boolean usable(Fix fix, long now) {
        return fix != null && fix.deviceId != null && !fix.deviceId.isEmpty()
            && Double.isFinite(fix.latitude) && Math.abs(fix.latitude) <= 90
            && Double.isFinite(fix.longitude) && Math.abs(fix.longitude) <= 180
            && Double.isFinite(fix.accuracy) && fix.accuracy > 0 && fix.accuracy <= 25
            && fix.timeMs > 0 && fix.timeMs <= now && now - fix.timeMs <= 45_000;
    }

    public static boolean sameMeasurement(Fix first, Fix second) {
        return first != null && second != null && first.deviceId != null
            && first.deviceId.equals(second.deviceId) && first.timeMs == second.timeMs
            && Double.compare(first.latitude, second.latitude) == 0
            && Double.compare(first.longitude, second.longitude) == 0
            && Double.compare(first.accuracy, second.accuracy) == 0
            && java.util.Objects.equals(first.bootId, second.bootId)
            && java.util.Objects.equals(first.elapsedRealtimeNanos, second.elapsedRealtimeNanos);
    }

    private static long intervalMs(Fix previous, Fix next) {
        if (!java.util.Objects.equals(previous.bootId, next.bootId)) return -1;
        if ((previous.elapsedRealtimeNanos == null) != (next.elapsedRealtimeNanos == null)) return -1;
        if (previous.elapsedRealtimeNanos != null && next.elapsedRealtimeNanos != null) {
            if (previous.bootId == null || previous.bootId.isEmpty()) return -1;
            long first = previous.elapsedRealtimeNanos, second = next.elapsedRealtimeNanos;
            if (first < 0 || second <= first) return -1;
            return (second - first) / 1_000_000;
        }
        return next.timeMs - previous.timeMs;
    }

    /** Duration describes display only, never a measurement interval for speed. */
    public static long durationMs(Fix previous, Fix next) {
        if (previous == null || next == null) return 600;
        return Math.max(600, Math.min(2_000, intervalMs(previous, next) / 2));
    }

    public static boolean shouldAnimate(Fix previous, Fix next, long now) {
        if (!usable(previous, now) || !usable(next, now)
                || !previous.deviceId.equals(next.deviceId)) return false;
        long elapsed = intervalMs(previous, next);
        if (elapsed <= 0 || elapsed > 30_000) return false;
        double lat = Math.toRadians(next.latitude - previous.latitude);
        double lon = Math.toRadians(longitudeDelta(previous.longitude, next.longitude));
        double a = Math.sin(lat / 2) * Math.sin(lat / 2)
            + Math.cos(Math.toRadians(previous.latitude)) * Math.cos(Math.toRadians(next.latitude))
            * Math.sin(lon / 2) * Math.sin(lon / 2);
        double metres = 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
        // Do not turn overlapping uncertainty circles or a GPS jump into apparent travel.
        return metres > Math.max(3, previous.accuracy + next.accuracy)
            && metres <= 1_500 && metres / (elapsed / 1000.0) <= 55;
    }

    public static double longitudeDelta(double from, double to) {
        return ((to - from + 540) % 360) - 180;
    }

    public static double longitudeAt(double from, double to, double fraction) {
        return ((from + longitudeDelta(from, to) * fraction + 540) % 360) - 180;
    }
}
