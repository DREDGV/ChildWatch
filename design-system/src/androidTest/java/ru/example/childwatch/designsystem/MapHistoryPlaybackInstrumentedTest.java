package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MapHistoryPlaybackInstrumentedTest {
    @Test public void reachesGapAndWaitsForUserAtLargeFont() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        GamesFixtureActivity activity = (GamesFixtureActivity) runner.startActivitySync(
            new Intent(runner.getTargetContext(), GamesFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long start = 1_000_000L;
        long endpoint = start + 30_000L;
        long next = start + 120_000L;
        AtomicLong shown = new AtomicLong();
        AtomicBoolean noCoordinates = new AtomicBoolean();
        CountDownLatch reachedGap = new CountDownLatch(1);
        try {
            CoordinatorLayout[] root = new CoordinatorLayout[1];
            MapHistoryPanel[] panel = new MapHistoryPanel[1];
            runner.runOnMainSync(() -> {
                ContextThemeWrapper large = new ContextThemeWrapper(activity, R.style.Theme_ChildWatch_Arcanoid);
                Configuration config = new Configuration();
                config.fontScale = 2f;
                large.applyOverrideConfiguration(config);
                root[0] = new CoordinatorLayout(large);
                activity.setContentView(root[0]);
                panel[0] = new MapHistoryPanel(large, root[0], "Записанный маршрут", start, next + 45_000,
                    new MapHistoryPanel.Listener() {
                        public void onPoint(MapRouteSegments.Fix point) {
                            noCoordinates.set(point == null);
                            if (point != null) {
                                shown.set(point.timestampMs);
                                if (point.timestampMs == endpoint) reachedGap.countDown();
                            }
                        }
                        public void onLive() {}
                        public void onRetry() {}
                        public void onEvents() {}
                    });
                panel[0].update(Arrays.asList(fix(start), fix(endpoint), fix(next), fix(next + 40_000)), true);
                findButton(root[0], large.getString(R.string.cw_history_play, MapHistoryPlayback.SPEED)).performClick();
            });

            assertTrue("Playback should reach the first endpoint", reachedGap.await(4, TimeUnit.SECONDS));
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                MaterialButton step = findButton(root[0], root[0].getContext().getString(R.string.cw_history_next_segment));
                Rect visible = new Rect();
                assertTrue(step.getGlobalVisibleRect(visible));
                assertEquals(step.getHeight(), visible.height());
                assertEquals(endpoint, shown.get());
                SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                String gap = root[0].getContext().getString(R.string.cw_day_gap,
                    clock.format(new Date(endpoint)), clock.format(new Date(next)),
                    root[0].getContext().getString(R.string.cw_day_duration_minutes, 1L));
                MaterialButton event = findButton(root[0], gap);
                assertNotNull(event);
                event.requestRectangleOnScreen(new Rect(0, 0, event.getWidth(), event.getHeight()), true);
                assertTrue("The gap row must be reachable in the scrollable summary", event.getGlobalVisibleRect(new Rect()));
                event.performClick();
                assertTrue(noCoordinates.get());
            });
            Bitmap image = runner.getUiAutomation().takeScreenshot();
            if (image != null) {
                try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), "map-history-playback-200.png"))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, out);
                } finally {
                    image.recycle();
                }
            }
            runner.runOnMainSync(() -> {
                findButton(root[0], root[0].getContext().getString(R.string.cw_history_next_segment)).performClick();
                assertEquals(next, shown.get());
                findButton(root[0], root[0].getContext().getString(R.string.cw_history_play, MapHistoryPlayback.SPEED)).performClick();
                panel[0].hide();
            });
            Thread.sleep(900);
            assertEquals("Hiding the panel must stop playback", next, shown.get());
        } finally {
            runner.runOnMainSync(activity::finish);
        }
    }

    private static MapRouteSegments.Fix fix(long time) {
        return new MapRouteSegments.Fix(55, 83, time, 10);
    }

    private static MaterialButton findButton(View view, String text) {
        if (view instanceof MaterialButton && text.contentEquals(((MaterialButton) view).getText())) return (MaterialButton) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                MaterialButton match = findButton(group.getChildAt(index), text);
                if (match != null) return match;
            }
        }
        return null;
    }
}
