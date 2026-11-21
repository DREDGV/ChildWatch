package ru.example.childwatch.designsystem;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class MapHistoryPlaybackTest {
    @Test public void usesElapsedTimeAndOnlyShowsRecordedFixesAtOrBeforeTheCursor() {
        MapRouteSegments.Fix first = fix(100_000);
        MapRouteSegments.Fix second = fix(110_000);
        MapHistoryPlayback history = history(first, second, fix(125_000));

        MapHistoryPlayback.Frame before = history.advance(first.timestampMs, 150);
        assertEquals(109_000, before.time);
        assertSame(first, before.point);
        assertFalse(before.ended);
        assertSame(second, history.advance(before.time, 100).point);
    }

    @Test public void stalledFrameStopsBeforeTheGapAndNextSegmentRequiresAnExplicitJump() {
        MapRouteSegments.Fix lastBeforeGap = fix(135_000);
        MapRouteSegments.Fix next = fix(200_000);
        MapHistoryPlayback history = history(fix(100_000), lastBeforeGap, next, fix(215_000));

        MapHistoryPlayback.Frame frame = history.advance(100_000, 50_000);
        assertTrue(frame.ended);
        assertSame(lastBeforeGap, frame.point);
        assertEquals(lastBeforeGap.timestampMs, frame.time);
        assertFalse(history.canPlay(frame.time));
        assertNull(history.pointAt(190_000));
        assertEquals(Long.valueOf(next.timestampMs), history.nextSegmentAfter(frame.time));
        assertTrue(history.canPlay(next.timestampMs));
    }

    @Test public void ignoresBadFixesAndStopsAtTheLastAvailablePoint() {
        MapRouteSegments.Fix last = fix(130_000);
        MapRouteSegments.Fix invalid = new MapRouteSegments.Fix(55.0, 83.0, 140_000, 500);
        MapHistoryPlayback history = history(fix(110_000), last, invalid);
        assertNull(history.pointAt(invalid.timestampMs));
        assertNull(history.nextSegmentAfter(last.timestampMs));
        assertTrue(history.advance(110_000, Long.MAX_VALUE).ended);
        assertSame(last, history.advance(110_000, Long.MAX_VALUE).point);
    }

    @Test public void emptyAndIsolatedFixesDoNotInventMotion() {
        MapHistoryPlayback empty = new MapHistoryPlayback(Collections.emptyList());
        assertNull(empty.firstTime());
        assertFalse(empty.canPlay(100_000));
        MapRouteSegments.Fix point = fix(100_000);
        MapHistoryPlayback single = history(point);
        assertEquals(Long.valueOf(point.timestampMs), single.firstTime());
        assertSame(point, single.pointAt(point.timestampMs));
        assertFalse(single.canPlay(point.timestampMs));
        assertNull(single.pointAt(point.timestampMs + 1));
    }

    private static MapHistoryPlayback history(MapRouteSegments.Fix... fixes) {
        return new MapHistoryPlayback(Arrays.asList(fixes));
    }

    private static MapRouteSegments.Fix fix(long time) {
        return new MapRouteSegments.Fix(55.0, 83.0, time, 10);
    }
}
