package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArcanoidInstrumentedTest {
    @Test public void commonGamesChooserClosesAndLaunchesArcanoid() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        ArcanoidFixtureActivity host = (ArcanoidFixtureActivity)runner.startActivitySync(
            new Intent(runner.getTargetContext(), ArcanoidFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        com.google.android.material.bottomsheet.BottomSheetDialog[] menu = new com.google.android.material.bottomsheet.BottomSheetDialog[1];
        Instrumentation.ActivityMonitor monitor = runner.addMonitor(ArcanoidActivity.class.getName(), null, false);
        android.app.Activity launched = null;
        try {
            runner.runOnMainSync(() -> menu[0] = GamesMenu.show(host));
            Thread.sleep(600); runner.waitForIdleSync();
            assertTrue(menu[0].isShowing());
            assertEquals("Игры", host.getString(R.string.cw_home_games));
            screenshot(runner, host, "games-menu.png");
            runner.runOnMainSync(() -> menu[0].findViewById(R.id.gamesClose).performClick());
            assertFalse(menu[0].isShowing());
            runner.runOnMainSync(() -> {
                menu[0] = GamesMenu.show(host);
                menu[0].findViewById(R.id.gamesArcanoid).performClick();
            });
            launched = runner.waitForMonitorWithTimeout(monitor, 4000);
            assertNotNull("Choosing the game opens its production activity", launched);
            assertFalse(menu[0].isShowing());
        } finally {
            android.app.Activity selected = launched;
            runner.runOnMainSync(() -> {
                if (menu[0] != null) menu[0].dismiss();
                if (selected != null) selected.finish();
                host.finish();
            });
            runner.removeMonitor(monitor);
        }
    }
    @Before public void requiresUnlockedPhone() {
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager)InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
        assertFalse("Native acceptance needs an unlocked phone; device state was not changed", keyguard.isKeyguardLocked());
    }
    @Test public void realScreenStartsPausesAndRestores() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        ArcanoidFixtureActivity activity = (ArcanoidFixtureActivity)runner.startActivitySync(
            new Intent(runner.getTargetContext(), ArcanoidFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runner.runOnMainSync(() -> {
                if (activity.findViewById(R.id.arcanoidControls).getVisibility() == android.view.View.VISIBLE)
                    activity.findViewById(R.id.arcanoidControlsToggle).performClick();
                activity.game.restart(); activity.findViewById(R.id.arcanoidAction).performClick();
            });
            Thread.sleep(700); runner.waitForIdleSync();
            assertEquals(ArcanoidEngine.State.PLAYING, activity.game.state());
            assertTrue((activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
            assertTrue(activity.game.ballY() < activity.game.paddleY() - ArcanoidEngine.RADIUS);
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.arcanoidPanel).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.arcanoidControls).getVisibility());
            screenshot(runner, activity, "arcanoid-playing.png");
            runner.runOnMainSync(() -> {
                activity.findViewById(R.id.arcanoidPause).performClick();
                activity.findViewById(R.id.arcanoidControlsToggle).performClick();
                activity.findViewById(R.id.arcanoidLeft).performClick();
            });
            assertEquals(ArcanoidEngine.State.PAUSED, activity.game.state());
            assertEquals(0, activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            assertEquals(435, activity.game.paddleX(), 0);
            runner.waitForIdleSync(); Thread.sleep(500); screenshot(runner, activity, "arcanoid-paused.png");
            Bundle saved = new Bundle(); runner.runOnMainSync(() -> activity.onSaveInstanceState(saved));
            ArcanoidEngine copy = new ArcanoidEngine(); assertTrue(copy.restore(saved.getDoubleArray("arcanoid.v1")));
            assertEquals(ArcanoidEngine.State.PAUSED, copy.state());
            assertEquals(activity.game.ballY(), copy.ballY(), 0);
            runner.runOnMainSync(() -> activity.findViewById(R.id.arcanoidBack).performClick());
            runner.waitForIdleSync(); assertTrue(activity.isFinishing());
            ArcanoidFixtureActivity reopened = (ArcanoidFixtureActivity)runner.startActivitySync(
                new Intent(runner.getTargetContext(), ArcanoidFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                assertEquals(ArcanoidEngine.State.PAUSED, reopened.game.state());
                assertEquals(0, reopened.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                runner.waitForIdleSync(); Thread.sleep(500);
                assertEquals(copy.ballY(), reopened.game.ballY(), .01);
                assertEquals(copy.paddleX(), reopened.game.paddleX(), 0);
            } finally { runner.runOnMainSync(reopened::finish); }
        } finally { runner.runOnMainSync(activity::finish); }
    }
    @Test public void largeTextAndLandscapeRetainControls() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        // Test-only configuration; no phone-wide font/display settings are modified.
        ArcanoidFixtureActivity activity = (ArcanoidFixtureActivity)runner.startActivitySync(
            new Intent(runner.getTargetContext(), ArcanoidLandscapeFixtureActivity.class)
                .putExtra("fixtureLargeFont", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                TextView action = activity.findViewById(R.id.arcanoidAction);
                assertTrue(action.getHeight() >= 48 * activity.getResources().getDisplayMetrics().density);
                assertTrue(activity.findViewById(R.id.arcanoidField).getHeight() >= 160 * activity.getResources().getDisplayMetrics().density);
                assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, activity.getResources().getConfiguration().orientation);
            });
            Thread.sleep(700); screenshot(runner, activity, "arcanoid-large-text.png");
            runner.runOnMainSync(() -> {
                // Compact touch mode must leave the field visible in landscape too.
                if (activity.findViewById(R.id.arcanoidControls).getVisibility() == android.view.View.VISIBLE)
                    activity.findViewById(R.id.arcanoidControlsToggle).performClick();
                activity.game.restart();
                activity.findViewById(R.id.arcanoidAction).performClick();
            });
            Thread.sleep(400); runner.waitForIdleSync();
            assertEquals(ArcanoidEngine.State.PLAYING, activity.game.state());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.arcanoidPanel).getVisibility());
            screenshot(runner, activity, "arcanoid-landscape-playing.png");
        } finally { runner.runOnMainSync(activity::finish); }
    }
    @Test public void backgroundPausesAndReturnDoesNotAutoResume() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        ArcanoidFixtureActivity activity = (ArcanoidFixtureActivity)runner.startActivitySync(
            new Intent(runner.getTargetContext(), ArcanoidFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        android.app.Activity cover = null;
        try {
            runner.runOnMainSync(() -> { activity.game.restart(); activity.findViewById(R.id.arcanoidAction).performClick(); });
            runner.waitForIdleSync();
            assertEquals(ArcanoidEngine.State.PLAYING, activity.game.state());
            assertTrue((activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
            cover = runner.startActivitySync(new Intent(runner.getTargetContext(), ChatMediaFixtureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runner.waitForIdleSync();
            assertEquals(ArcanoidEngine.State.PAUSED, activity.game.state());
            assertEquals(0, activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            runner.runOnMainSync(cover::finish); cover = null;
            runner.waitForIdleSync();
            assertEquals(ArcanoidEngine.State.PAUSED, activity.game.state());
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.arcanoidPanel).getVisibility());
        } finally {
            if (cover != null) runner.runOnMainSync(cover::finish);
            runner.runOnMainSync(activity::finish);
        }
    }
    private void screenshot(Instrumentation runner, ArcanoidActivity activity, String name) throws Exception {
        Bitmap bitmap = runner.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
}

