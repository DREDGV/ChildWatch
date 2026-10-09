package ru.example.childwatch.designsystem;

import java.util.List;
import java.util.Objects;

/** Pure measurement-domain estimator. Display interpolation and packet arrival time are never inputs. */
public final class MapSpeedEstimator {
    public enum Source { MEASURED, ESTIMATED, UNKNOWN }
    public enum Quality { HIGH, ACCEPTABLE, UNRELIABLE }
    public enum Freshness { FRESH, AGING, EXPIRED }
    public static final class Sample {
        public final String deviceId, bootSessionId;
        public final double latitude, longitude, accuracyMeters;
        public final long capturedAtMs;
        public final Long elapsedRealtimeNanos;
        public final Float speedMps, speedAccuracyMps;
        public Sample(String deviceId, double latitude, double longitude, long capturedAtMs,
                double accuracyMeters, Float speedMps, Float speedAccuracyMps,
                Long elapsedRealtimeNanos, String bootSessionId) {
            this.deviceId = deviceId; this.latitude = latitude; this.longitude = longitude;
            this.capturedAtMs = capturedAtMs; this.accuracyMeters = accuracyMeters;
            this.speedMps = speedMps; this.speedAccuracyMps = speedAccuracyMps;
            this.elapsedRealtimeNanos = elapsedRealtimeNanos; this.bootSessionId = bootSessionId;
        }
    }
    public static final class Result {
        public final Double valueMps, uncertaintyMps;
        public final Source source;
        public final Quality quality;
        public final Freshness freshness;
        public final long capturedAtMs;
        private Result(Double value, Double uncertainty, Source source, Quality quality, long time, long now) {
            long age = now - time;
            freshness = time <= 0 || age < -30_000 || age > 45_000 ? Freshness.EXPIRED
                    : age > 15_000 ? Freshness.AGING : Freshness.FRESH;
            this.valueMps = freshness == Freshness.EXPIRED ? null : value;
            this.uncertaintyMps = uncertainty;
            this.source = this.valueMps == null ? Source.UNKNOWN : source;
            this.quality = this.valueMps == null ? Quality.UNRELIABLE : quality;
            capturedAtMs = time;
        }
        public Double kmh() { return valueMps == null ? null : valueMps * 3.6; }
    }
    private Sample last;
    private Axis east, north;
    private double originLat, originLon;
    private long firstNanos, stopStartNanos;
    private int acceptedCount, stopCount;
    private double stopEast, stopNorth;
    private boolean stopped;
    private static final double EARTH = 6_371_000;
    private static final double STOP_SPEED_MPS = .05f, STOP_UNCERTAINTY_MPS = .1f;

    public static Result evaluate(List<Sample> samples, long now) {
        MapSpeedEstimator estimator = new MapSpeedEstimator();
        Result result = unknown(0, now);
        if (samples != null) for (Sample sample : samples) result = estimator.accept(sample, now);
        return result;
    }
    public static Result measured(Sample sample, long now) {
        if (sample == null) return unknown(0, now);
        Double speed = sensorSpeed(sample);
        // An isolated zero is not proof of a stop.
        return speed == null || speed <= STOP_SPEED_MPS ? unknown(sample.capturedAtMs, now)
                : result(speed, (double)sample.speedAccuracyMps, Source.MEASURED, sample, now);
    }
    public Result accept(Sample sample, long now) {
        if (sample == null) { reset(); return unknown(0, now); }
        boolean clock = trustedClock(sample);
        boolean same = last != null && Objects.equals(last.deviceId, sample.deviceId)
                && Objects.equals(last.bootSessionId, sample.bootSessionId);
        double dt = clock && last != null && trustedClock(last)
                ? (sample.elapsedRealtimeNanos - last.elapsedRealtimeNanos) / 1e9 : -1;
        if (!same || dt <= 0 || dt > 30) reset();
        Double measured = sensorSpeed(sample);
        if (!clock) {
            reset(); last = sample;
            return measured(sample, now);
        }
        if (!positionUsable(sample)) {
            east = north = null; acceptedCount = 0; last = sample;
            if (measured != null && measured <= STOP_SPEED_MPS && sample.speedAccuracyMps <= STOP_UNCERTAINTY_MPS) {
                if (stopCount == 0) stopStartNanos = sample.elapsedRealtimeNanos;
                stopCount++;
                if (stopCount >= 3 && sample.elapsedRealtimeNanos - stopStartNanos >= 10_000_000_000L)
                    return result(0, sample.speedAccuracyMps, Source.MEASURED, sample, now);
            } else clearStop();
            return measured(sample, now);
        }
        double x = EARTH * Math.toRadians(longitudeDelta(originLon, sample.longitude))
                * Math.cos(Math.toRadians(originLat));
        double y = EARTH * Math.toRadians(sample.latitude - originLat);
        if (east == null) {
            originLat = sample.latitude; originLon = sample.longitude; x = y = 0;
            east = new Axis(sample.accuracyMeters); north = new Axis(sample.accuracyMeters);
            firstNanos = sample.elapsedRealtimeNanos; acceptedCount = 1;
        } else {
            east.predict(dt); north.predict(dt);
            double variance = sample.accuracyMeters * sample.accuracyMeters;
            double residual = square(x - east.position) / (east.pp + variance)
                    + square(y - north.position) / (north.pp + variance);
            // Chi-square gate for two coordinates; reject rather than invent velocity from GPS jumps.
            if (!Double.isFinite(residual) || residual > 13.82) {
                reset(); last = sample; return measured(sample, now);
            }
            east.correct(x, variance); north.correct(y, variance); acceptedCount++;
        }
        last = sample;
        double speed = measured != null ? measured : Math.hypot(east.velocity, north.velocity);
        double uncertainty = measured != null ? sample.speedAccuracyMps
                : Math.sqrt(Math.max(east.vv, north.vv));
        Quality quality = quality(speed, uncertainty);
        // Near-zero tolerance is 0.05 m/s, requiring uncertainty <= 0.1 m/s for ten seconds.
        // Ordinary slow walking must remain a positive reading, never a confirmed stop.
        boolean stopCandidate = measured != null ? measured <= STOP_SPEED_MPS && sample.speedAccuracyMps <= STOP_UNCERTAINTY_MPS
                : quality != Quality.UNRELIABLE && speed <= STOP_SPEED_MPS && uncertainty <= STOP_UNCERTAINTY_MPS && sample.accuracyMeters <= 5;
        if (stopCandidate) {
            if (stopCount == 0) {
                stopStartNanos = sample.elapsedRealtimeNanos; stopEast = x; stopNorth = y;
            }
            if (measured == null && Math.hypot(x - stopEast, y - stopNorth) > Math.max(1, sample.accuracyMeters * 2)) clearStop();
            else {
                stopCount++;
                stopped = stopCount >= 3 && sample.elapsedRealtimeNanos - stopStartNanos >= 10_000_000_000L;
            }
        } else clearStop();
        if (stopped) return result(0, uncertainty, measured != null ? Source.MEASURED : Source.ESTIMATED, sample, now);
        if (speed <= STOP_SPEED_MPS) return unknown(sample.capturedAtMs, now);
        if (measured != null) return result(speed, uncertainty, Source.MEASURED, sample, now);
        if (acceptedCount < 3 || sample.elapsedRealtimeNanos - firstNanos < 5_000_000_000L)
            return unknown(sample.capturedAtMs, now);
        return result(speed, uncertainty, Source.ESTIMATED, sample, now);
    }
    private void clearStop() { stopCount = 0; stopped = false; stopStartNanos = 0; }
    private void reset() { last = null; east = north = null; acceptedCount = 0; clearStop(); }
    private static boolean trustedClock(Sample sample) {
        return sample.elapsedRealtimeNanos != null && sample.elapsedRealtimeNanos >= 0
                && sample.deviceId != null && !sample.deviceId.isEmpty()
                && sample.bootSessionId != null && !sample.bootSessionId.isEmpty();
    }
    private static boolean positionUsable(Sample s) {
        return Double.isFinite(s.latitude) && Math.abs(s.latitude) <= 90 && Double.isFinite(s.longitude)
                && Math.abs(s.longitude) <= 180 && Double.isFinite(s.accuracyMeters)
                && s.accuracyMeters > 0 && s.accuracyMeters <= 200;
    }
    private static Double sensorSpeed(Sample sample) {
        if (sample.speedMps == null || sample.speedAccuracyMps == null) return null;
        double speed = sample.speedMps, uncertainty = sample.speedAccuracyMps;
        return quality(speed, uncertainty) == Quality.UNRELIABLE ? null : speed;
    }
    public static Quality quality(double speed, double uncertainty) {
        if (!Double.isFinite(speed) || speed < 0 || speed > 60 || !Double.isFinite(uncertainty) || uncertainty < 0)
            return Quality.UNRELIABLE;
        if (uncertainty <= Math.max(.3, speed * .10)) return Quality.HIGH;
        if (uncertainty <= Math.max(.7, speed * .25)) return Quality.ACCEPTABLE;
        return Quality.UNRELIABLE;
    }
    private static Result result(double speed, double uncertainty, Source source, Sample sample, long now) {
        Quality quality = quality(speed, uncertainty);
        return quality == Quality.UNRELIABLE ? unknown(sample.capturedAtMs, now)
                : new Result(speed, uncertainty, source, quality, sample.capturedAtMs, now);
    }
    private static Result unknown(long time, long now) {
        return new Result(null, null, Source.UNKNOWN, Quality.UNRELIABLE, time, now);
    }
    private static double square(double value) { return value * value; }
    private static double longitudeDelta(double from, double to) { return ((to - from + 540) % 360) - 180; }
    private static final class Axis {
        double position, velocity, pp, pv, vv = 100;
        Axis(double accuracy) { pp = accuracy * accuracy; }
        void predict(double dt) {
            position += velocity * dt;
            // Discrete constant-velocity model with acceleration standard deviation 2 m/s².
            double q = 4, d2 = dt * dt;
            pp += 2 * dt * pv + d2 * vv + q * d2 * d2 / 4;
            pv += dt * vv + q * d2 * dt / 2;
            vv += q * d2;
        }
        void correct(double measurement, double variance) {
            double innovation = measurement - position, s = pp + variance;
            double kp = pp / s, kv = pv / s, oldPv = pv;
            position += kp * innovation; velocity += kv * innovation;
            pp = Math.max(0, (1 - kp) * pp);
            pv = (1 - kp) * oldPv;
            vv = Math.max(0, vv - kv * oldPv);
        }
    }
}




