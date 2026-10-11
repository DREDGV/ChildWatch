package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.checkbox.MaterialCheckBox;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SnakeInstrumentedTest {
    @Before public void requiresUnlockedPhone() {
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager)InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
        assertFalse("Unlock is performed only by owner", keyguard.isKeyguardLocked());
    }
    @Test public void largeTextControlsPauseAndSavedParty() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        runner.getTargetContext().getSharedPreferences("snake.solo.v1", 0).edit().clear().commit();
        SnakeFixtureActivity activity = (SnakeFixtureActivity) runner.startActivitySync(
            new Intent(runner.getTargetContext(), SnakeFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runner.waitForIdleSync(); Thread.sleep(600);
            assertEquals(SnakeEngine.State.READY, activity.game.state());
            screenshot(runner, activity, "snake-ready.png");
            runner.runOnMainSync(() -> {
                ((MaterialCheckBox)activity.findViewById(R.id.snakeButtonsToggle)).setChecked(true);
                activity.findViewById(R.id.snakeAction).performClick();
            });
            runner.waitForIdleSync(); Thread.sleep(250);
            assertEquals(SnakeEngine.State.PLAYING, activity.game.state());
            assertNotEquals(0, activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            View field = activity.findViewById(R.id.snakeField);
            assertTrue(field.getHeight() > 0); assertTrue(field.getWidth() > 0);
            for (int id : new int[]{R.id.snakeLeft, R.id.snakeUp, R.id.snakeDown, R.id.snakeRight}) {
                Rect visible = new Rect(); View button = activity.findViewById(id);
                assertTrue("Direction button is visible", button.getGlobalVisibleRect(visible));
                assertTrue("Direction touch target remains 48dp", button.getHeight() >= 48 * button.getResources().getDisplayMetrics().density);
            }
            screenshot(runner, activity, "snake-playing.png");
            runner.runOnMainSync(() -> activity.findViewById(R.id.snakePause).performClick());
            assertEquals(SnakeEngine.State.PAUSED, activity.game.state());
            assertEquals(0, activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Bundle saved = new Bundle(); runner.runOnMainSync(() -> activity.onSaveInstanceState(saved));
            SnakeEngine copy = new SnakeEngine(); assertTrue(copy.restore(saved.getLongArray("snake.solo.v1")));
            long ticks = activity.game.ticks(); Thread.sleep(300); assertEquals("Paused game never advances", ticks, activity.game.ticks());
            runner.runOnMainSync(activity::finish); runner.waitForIdleSync();
            SnakeFixtureActivity reopened = (SnakeFixtureActivity) runner.startActivitySync(
                new Intent(runner.getTargetContext(), SnakeFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                assertEquals(SnakeEngine.State.PAUSED, reopened.game.state());
                assertArrayEquals(copy.snapshot(), reopened.game.snapshot());
            } finally { runner.runOnMainSync(reopened::finish); }
        } finally { runner.runOnMainSync(activity::finish); }
    }
    private static void screenshot(Instrumentation runner, android.app.Activity activity, String name) throws Exception {
        Bitmap image = runner.getUiAutomation().takeScreenshot();
        try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { image.recycle(); }
    }
}
