package ru.example.childwatch.designsystem;

import android.animation.ValueAnimator;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real Android animator, isolated synthetic coordinates; never contacts family devices. */
@RunWith(AndroidJUnit4.class)
public class MapMarkerMotionInstrumentedTest {
    private static final String KEY = "fixture-child";
    private static final String SCOPE = "fixture-family";
    private static final double END = .0001;

    private void onMain(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    private MapMarkerMotionPolicy.Fix fix(double longitude, long capturedAt) {
        return new MapMarkerMotionPolicy.Fix("fixture-phone", 0, longitude, capturedAt, 3);
    }

    private void update(MapMarkerMotion motion, MapMarkerMotionPolicy.Fix fix,
                        boolean enabled, MapMarkerMotion.Position position) {
        motion.update(SCOPE, KEY, fix, enabled, position, () -> { });
    }

    @Test public void realFramesStayBetweenMeasuredEndpointsAndFinishAtTarget() throws Exception {
        assumeTrue("System animations disabled; test does not change device settings", ValueAnimator.areAnimatorsEnabled());
        MapMarkerMotion motion = new MapMarkerMotion();
        CountDownLatch intermediate = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean overshot = new AtomicBoolean(false);
        long now = System.currentTimeMillis();
        try {
            onMain(() -> {
                motion.setFrameListener((key, latitude, longitude) -> {
                    if (latitude != 0 || longitude < -1e-10 || longitude > END + 1e-10) overshot.set(true);
                    if (longitude > 0 && longitude < END) intermediate.countDown();
                    if (Math.abs(longitude - END) < 1e-10) completed.countDown();
                });
                update(motion, fix(0, now - 6_000), true, (lat, lon) -> { });
                update(motion, fix(END, now - 3_000), true, (lat, lon) -> { });
            });
            assertTrue("No real intermediate animation frame", intermediate.await(4, TimeUnit.SECONDS));
            assertTrue("Animation did not reach measured endpoint", completed.await(5, TimeUnit.SECONDS));
            assertFalse("Display interpolated beyond measured coordinates", overshot.get());
            onMain(() -> assertEquals(END, motion.displayedPosition(KEY).longitude, 1e-10));
        } finally {
            onMain(motion::clear);
        }
    }

    @Test public void interruptionAndSameFixRebindKeepCurrentDisplayedPosition() throws Exception {
        assumeTrue("System animations disabled", ValueAnimator.areAnimatorsEnabled());
        MapMarkerMotion motion = new MapMarkerMotion();
        CountDownLatch checked = new CountDownLatch(1);
        AtomicBoolean handled = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long now = System.currentTimeMillis();
        MapMarkerMotionPolicy.Fix second = fix(END, now - 3_000);
        try {
            onMain(() -> {
                motion.setFrameListener((key, lat, lon) -> {
                    if (lon > 0 && lon < END / 2 && handled.compareAndSet(false, true)) {
                        // Check in this frame rather than racing a busy main looper from the test thread.
                        try {
                            double displayed = motion.displayedPosition(KEY).longitude;
                            double[] rebound = { Double.NaN };
                            update(motion, second, true, (reboundLat, reboundLon) -> rebound[0] = reboundLon);
                            assertEquals("Same-fix rebind snapped to endpoint", displayed, rebound[0], 1e-12);
                            double[] interrupted = { Double.NaN };
                            update(motion, fix(END * 2, now), true, (newLat, newLon) -> interrupted[0] = newLon);
                            assertEquals("New fix snapped to previous target", displayed, interrupted[0], 1e-12);
                            assertEquals(displayed, motion.displayedPosition(KEY).longitude, 1e-12);
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            checked.countDown();
                        }
                    }
                });
                update(motion, fix(0, now - 6_000), true, (lat, lon) -> { });
                update(motion, second, true, (lat, lon) -> { });
            });
            assertTrue("No intermediate frame to interrupt", checked.await(4, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Frame continuity check failed", failure.get());
        } finally {
            onMain(motion::clear);
        }
    }

    @Test public void disabledAndClearedControllerHaveNoContinuingMotion() throws Exception {
        assumeTrue("System animations disabled", ValueAnimator.areAnimatorsEnabled());
        MapMarkerMotion motion = new MapMarkerMotion();
        long now = System.currentTimeMillis();
        CountDownLatch lateFrame = new CountDownLatch(1);
        AtomicBoolean disabled = new AtomicBoolean(false);
        try {
            onMain(() -> {
                update(motion, fix(0, now - 6_000), true, (lat, lon) -> { });
                update(motion, fix(END, now - 3_000), true, (lat, lon) -> {
                    if (disabled.get()) lateFrame.countDown();
                });
                double[] snapped = { Double.NaN };
                update(motion, fix(END * 2, now), false, (lat, lon) -> snapped[0] = lon);
                disabled.set(true);
                assertEquals(END * 2, snapped[0], 1e-12);
                assertNull(motion.displayedPosition(KEY));
                disabled.set(false);
                MapMarkerMotion.Position guarded = (lat, lon) -> {
                    if (disabled.get()) lateFrame.countDown();
                };
                update(motion, fix(0, now - 6_000), true, guarded);
                update(motion, fix(END, now - 3_000), true, guarded);
                motion.clear();
                disabled.set(true);
                assertNull(motion.displayedPosition(KEY));
            });
            assertFalse("Cancelled animator still wrote positions", lateFrame.await(300, TimeUnit.MILLISECONDS));
        } finally {
            onMain(motion::clear);
        }
    }
}
