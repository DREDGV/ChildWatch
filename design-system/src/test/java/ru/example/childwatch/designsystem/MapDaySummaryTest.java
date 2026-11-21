package ru.example.childwatch.designsystem;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class MapDaySummaryTest {
    private static final long START = 1_800_000_000_000L;

    private MapRouteSegments.Fix point(long seconds, double longitude, float accuracy, Float speed) {
        return new MapRouteSegments.Fix(55, longitude, START + seconds * 1000, accuracy, speed, null);
    }

    @Test public void coverageUsesOnlyConnectedMeasurementsAndSeeksInsideGap() {
        List<List<MapRouteSegments.Fix>> segments = MapRouteSegments.split(Arrays.asList(
                point(10, 0, 5, null), point(50, 0, 5, null), point(90, 0, 5, null), point(130, 0, 5, null),
                point(200, .001, 5, null), point(220, .002, 5, null)));
        MapDaySummary day = new MapDaySummary(segments, START, START + 250_000, true);
        assertEquals(140_000L, day.observedMs);
        assertEquals(1, day.stops);
        assertEquals(3, day.gaps);
        assertEquals(START + 10_000, day.events.get(1).seekTime());
        assertTrue(day.events.get(2).gap);
        assertEquals(START + 165_000, day.events.get(2).seekTime());
        assertEquals(1, new MapDaySummary(segments, START, START + 250_000, false).gaps);
    }

    @Test public void reportedMovementAndImpreciseFixesCannotConfirmAStop() {
        List<List<MapRouteSegments.Fix>> segments = MapRouteSegments.split(Arrays.asList(
                point(0, 0, 5, null), point(40, 0, 5, 4f), point(80, 0, 35, null), point(120, 0, 5, null)));
        assertEquals(0, new MapDaySummary(segments, START, START + 120_000, true).stops);
    }
}
