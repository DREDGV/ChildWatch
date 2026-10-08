package ru.example.childwatch.designsystem;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapFollowSelectionTest {
    @Test public void followsOnlySelectedPersonAndDevice() {
        MapFollowSelection p = new MapFollowSelection();
        assertTrue(p.start("family", "child", "phone", 1000, 1000));
        assertTrue(p.matches("family", "child", "phone"));
        assertFalse(p.matches("family", "sibling", "phone"));
        assertFalse(p.matches("family", "child", "other-phone"));
    }
    @Test public void newFamilyOrDeviceCancelsIntent() {
        MapFollowSelection p = new MapFollowSelection();
        p.start("family", "child", "phone", 1000, 1000);
        assertFalse(p.refresh("other-family", "child", "phone", 1001, 1001));
        assertFalse(p.active());
        p.start("family", "child", "phone", 1000, 1000);
        assertFalse(p.refresh("family", "child", "new-phone", 1001, 1001));
    }
    @Test public void oldLocationCanStartButFutureOrMissingLocationCannot() {
        MapFollowSelection p = new MapFollowSelection();
        assertTrue(p.start("family", "child", "phone", 1000, 46001));
        assertFalse(MapFollowSelection.hasFreshLocation(1000, 46001));
        assertFalse(p.start("family", "child", "phone", 1002, 1001));
        assertFalse(p.start("family", "child", "phone", 0, 1001));
    }
    @Test public void manualStopCannotBeUndoneByLateUpdate() {
        MapFollowSelection p = new MapFollowSelection();
        p.start("family", "child", "phone", 1000, 1000);
        p.stop();
        assertFalse(p.refresh("family", "child", "phone", 1002, 1002));
        assertFalse(p.active());
    }
    @Test public void staleUpdatesRetainIntentAndNewLocationResumesWithoutAnotherStart() {
        MapFollowSelection p = new MapFollowSelection();
        p.start("family", "child", "phone", 1000, 1000);
        assertTrue(p.refresh("family", "child", "phone", 2000, 2000));
        assertTrue(p.refresh("family", "child", "phone", 2000, 47001));
        assertTrue(p.active());
        assertFalse(MapFollowSelection.hasFreshLocation(2000, 47001));
        assertTrue(p.refresh("family", "child", "phone", 48000, 48000));
        assertTrue(MapFollowSelection.hasFreshLocation(48000, 48000));
    }
    @Test public void stoppingWhileWaitingPreventsAutomaticResume() {
        MapFollowSelection p = new MapFollowSelection();
        assertTrue(p.start("family", "child", "phone", 1000, 50000));
        p.stop();
        assertFalse(p.refresh("family", "child", "phone", 51000, 51000));
        assertFalse(p.active());
    }
    @Test public void freshnessRejectsUnknownFutureAndExpiredMeasurements() {
        assertFalse(MapFollowSelection.hasFreshLocation(0, 1000));
        assertFalse(MapFollowSelection.hasFreshLocation(1001, 1000));
        assertTrue(MapFollowSelection.hasFreshLocation(1000, 46000));
        assertFalse(MapFollowSelection.hasFreshLocation(1000, 46001));
    }
}
