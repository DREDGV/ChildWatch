package ru.example.childwatch.designsystem;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapMarkerMotionPolicyTest {
    private static final long NOW = 1_791_200_000_000L;
    private MapMarkerMotionPolicy.Fix fix(String device, double longitude, long age, double accuracy) {
        return new MapMarkerMotionPolicy.Fix(device, 0, longitude, NOW - age, accuracy);
    }
    @Test public void freshWalkAndTravelCanTransition() {
        assertTrue(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 10_000, 3), fix("a", .0001, 0, 3), NOW));
        assertTrue(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 30_000, 5), fix("a", .005, 0, 5), NOW));
    }
    @Test public void uncertaintyIsNotTravel() {
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 10_000, 10), fix("a", .0001, 0, 10), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 10_000, 0), fix("a", .0001, 0, 3), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 10_000, 3), fix("a", .0001, 0, 26), NOW));
    }
    @Test public void rebootDeviceReplacementAndOldPacketsDoNotAnimate() {
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 10_000, 3), fix("b", .0001, 0, 3), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(null, fix("a", .0001, 0, 3), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 0, 3), fix("a", .0001, 10_000, 3), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 0, 3), fix("a", .0001, 0, 3), NOW));
    }
    @Test public void staleFutureAndGapDoNotAnimate() {
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 45_001, 3), fix("a", .0001, 0, 3), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 10_000, 3), fix("a", .0001, -1, 3), NOW));
        assertTrue(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 45_000, 3), fix("a", .0001, 0, 3), NOW));
    }
    @Test public void gpsJumpsAndInvalidCoordinatesDoNotAnimate() {
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 30_000, 3), fix("a", 1, 0, 3), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 100, 3), fix("a", .001, 0, 3), NOW));
        assertFalse(MapMarkerMotionPolicy.shouldAnimate(fix("a", 0, 10_000, 3), fix("a", Double.NaN, 0, 3), NOW));
    }
    @Test public void datelineUsesTheShortArc() {
        assertTrue(MapMarkerMotionPolicy.shouldAnimate(fix("a", 179.9999, 10_000, 3), fix("a", -179.9999, 0, 3), NOW));
        double middle = MapMarkerMotionPolicy.longitudeAt(179.9999, -179.9999, .5);
        assertEquals(180, Math.abs(middle), 0.000001);
        assertEquals(-179.9999, MapMarkerMotionPolicy.longitudeAt(179.9999, -179.9999, 1), .000001);
    }
}
