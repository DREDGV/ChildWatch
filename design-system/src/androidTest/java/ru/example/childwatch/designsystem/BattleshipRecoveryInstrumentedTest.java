package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs only against the isolated fixture package, never the installed family profile. */
@RunWith(AndroidJUnit4.class)
public class BattleshipRecoveryInstrumentedTest {
    private <T extends View> T find(View view, Class<T> type, String text) {
        if (type.isInstance(view) && (text == null || view instanceof TextView
                && text.equals(((TextView)view).getText().toString()))) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            T found = find(((ViewGroup)view).getChildAt(i), type, text); if (found != null) return found;
        }
        return null;
    }
    @Test public void corruptDraftStillRestoresValidSoloMatch() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation(); Context context = runner.getTargetContext();
        String saved = new BattleshipEngine(72).snapshot();
        context.getSharedPreferences("battleship-local", 0).edit().clear()
                .putString("solo-draft", "{broken").putString("solo-match", saved).commit();
        BattleshipFixtureActivity activity = (BattleshipFixtureActivity)runner.startActivitySync(
                new Intent(context, BattleshipFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runner.waitForIdleSync(); View root = activity.getWindow().getDecorView();
            assertNotNull(find(root, TextView.class, "\u0412\u0430\u0448 \u0445\u043e\u0434"));
            assertNull(find(root, TextView.class, "\u0420\u0430\u0441\u0441\u0442\u0430\u0432\u044c\u0442\u0435 \u0441\u0432\u043e\u0439 \u0444\u043b\u043e\u0442"));
            assertEquals(saved, context.getSharedPreferences("battleship-local", 0).getString("solo-match", ""));
        } finally { runner.runOnMainSync(activity::finish); context.getSharedPreferences("battleship-local", 0).edit().clear().commit(); }
    }
    @Test public void selectedShipCanShiftInsideItsOldFootprint() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation(); Context context = runner.getTargetContext();
        int[] fleet = new int[100]; int[][] cells = {{0,1,2,3},{20,21,22},{25,26,27},{40,41},{44,45},{48,49},{60},{62},{64},{66}};
        for (int id = 0; id < cells.length; id++) for (int cell : cells[id]) fleet[cell] = id + 1;
        JSONArray draft = new JSONArray(); for (int id : fleet) draft.put(id);
        context.getSharedPreferences("battleship-local", 0).edit().clear().putString("solo-draft", draft.toString()).commit();
        BattleshipFixtureActivity activity = (BattleshipFixtureActivity)runner.startActivitySync(
                new Intent(context, BattleshipFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runner.waitForIdleSync(); BattleshipBoardView board = find(activity.getWindow().getDecorView(), BattleshipBoardView.class, null);
            assertNotNull(board);
            runner.runOnMainSync(() -> { tap(board, 0); tap(board, 1); });
            JSONArray shifted = new JSONArray(context.getSharedPreferences("battleship-local", 0).getString("solo-draft", "[]"));
            assertEquals(0, shifted.getInt(0)); for (int cell = 1; cell <= 4; cell++) assertEquals(1, shifted.getInt(cell));
            assertFalse(context.getSharedPreferences("battleship-local", 0).contains("solo-match"));
        } finally { runner.runOnMainSync(activity::finish); context.getSharedPreferences("battleship-local", 0).edit().clear().commit(); }
    }
    private void tap(BattleshipBoardView board, int cell) {
        float step = board.getWidth() / 11f;
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, step * (cell % 10 + 1.5f), step * (cell / 10 + 1.5f), 0);
        board.dispatchTouchEvent(event); event.recycle();
    }
}
