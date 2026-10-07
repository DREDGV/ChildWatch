package ru.example.childwatch.designsystem;
import org.junit.Test;
import static org.junit.Assert.*;

public class BatterySnapshotTest {
    private final long now = 1_800_000_000_000L;
    @Test public void doesNotBorrowAnotherPhonesCharge() {
        assertNull(BatterySnapshot.create("child", "parent", 80, now, true, now));
        assertNull(BatterySnapshot.create("", "", 80, now, true, now));
    }
    @Test public void unknownAndInvalidAreNotZero() {
        assertNull(BatterySnapshot.create("child", "child", null, now, false, now));
        assertNull(BatterySnapshot.create("child", "child", -1, now, false, now));
        assertNull(BatterySnapshot.create("child", "child", 101, now, false, now));
        assertEquals(0, BatterySnapshot.create("child", "child", 0, now, false, now).level);
        assertEquals(100, BatterySnapshot.create("child", "child", 100, now, true, now).level);
    }
    @Test public void captureClockIsIndependentAndSecondsNormalize() {
        BatterySnapshot snapshot = BatterySnapshot.create("child", "child", 90, now / 1000, null, now);
        assertEquals(now, snapshot.timestamp);
        assertNull(snapshot.charging);
        assertFalse(snapshot.isStale(now + 600_000));
        assertTrue(snapshot.isStale(now + 600_001));
    }
    @Test public void missingAndFutureCaptureCannotLookCurrent() {
        assertNull(BatterySnapshot.create("child", "child", 90, null, true, now));
        assertNull(BatterySnapshot.create("child", "child", 90, 0L, true, now));
        assertNull(BatterySnapshot.create("child", "child", 90, now + 1, true, now));
    }
}
