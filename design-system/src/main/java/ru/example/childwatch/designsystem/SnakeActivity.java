package ru.example.childwatch.designsystem;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Shared local game; no remote commands, radio scanning or microphone access. */
public class SnakeActivity extends AppCompatActivity {
    private static final String SAVE = "snake.solo.v1";
    SnakeEngine game;
    private SnakeView field;
    private SharedPreferences preferences;
    private TextView status, score;
    private MaterialButton action;
    private RadioGroup difficulty;
    private boolean changingDifficulty;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        preferences = getSharedPreferences(SAVE, MODE_PRIVATE);
        game = new SnakeEngine(SnakeEngine.Mode.SOLO, SnakeEngine.Difficulty.NORMAL, System.nanoTime());
        if (saved != null) game.restore(saved.getLongArray(SAVE)); else game.restore(readSaved());
        setContentView(R.layout.cw_activity_snake);
        status = findViewById(R.id.snakeStatus); score = findViewById(R.id.snakeScore);
        action = findViewById(R.id.snakeAction); difficulty = findViewById(R.id.snakeDifficulty);
        // Narrow screens and large text use stacked speed choices with full touch targets.
        boolean stacked = getResources().getConfiguration().fontScale >= 1.3f
            || getResources().getConfiguration().screenWidthDp < 360;
        difficulty.setOrientation(stacked ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL);
        for (int i = 0; i < difficulty.getChildCount(); i++) {
            android.widget.RadioGroup.LayoutParams params = new android.widget.RadioGroup.LayoutParams(
                stacked ? -1 : 0, -2, stacked ? 0 : 1);
            difficulty.getChildAt(i).setLayoutParams(params);
        }
        field = new SnakeView(this, game, this::refresh);
        ((FrameLayout)findViewById(R.id.snakeField)).addView(field, 0, new FrameLayout.LayoutParams(-1, -1));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.snakeRoot), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        AccessibilityManager manager = (AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);
        boolean accessible = manager != null && manager.isTouchExplorationEnabled();
        MaterialCheckBox buttons = findViewById(R.id.snakeButtonsToggle);
        buttons.setChecked(accessible || preferences.getBoolean("buttons", false));
        buttons.setEnabled(!accessible);
        buttons.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("buttons", checked).apply(); refresh();
        });
        MaterialCheckBox feedback = findViewById(R.id.snakeFeedback);
        feedback.setChecked(preferences.getBoolean("feedback", true));
        field.setFeedbackEnabled(feedback.isChecked());
        feedback.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("feedback", checked).apply(); field.setFeedbackEnabled(checked);
        });
        difficulty.setOnCheckedChangeListener((group, id) -> {
            if (changingDifficulty || game.state() != SnakeEngine.State.READY) return;
            SnakeEngine.Difficulty selected = id == R.id.snakeCalm ? SnakeEngine.Difficulty.CALM
                : id == R.id.snakeFast ? SnakeEngine.Difficulty.FAST : SnakeEngine.Difficulty.NORMAL;
            game = new SnakeEngine(SnakeEngine.Mode.SOLO, selected, System.nanoTime());
            field.setGame(game); refresh();
        });
        findViewById(R.id.snakeBack).setOnClickListener(view -> finish());
        findViewById(R.id.snakePause).setOnClickListener(view -> { game.pause(); refresh(); });
        action.setOnClickListener(view -> {
            if (game.state() == SnakeEngine.State.WON || game.state() == SnakeEngine.State.GAME_OVER) newParty();
            game.launchOrResume(); refresh(); field.animateGame();
        });
        findViewById(R.id.snakeRestart).setOnClickListener(view -> {
            game.pause(); refresh();
            new MaterialAlertDialogBuilder(this).setTitle(R.string.cw_snake_again).setMessage(R.string.cw_snake_reset)
                .setNegativeButton(R.string.cw_arcanoid_cancel, null)
                .setPositiveButton(R.string.cw_snake_again, (dialog, which) -> { newParty(); refresh(); }).show();
        });
        bindDirection(R.id.snakeLeft, SnakeEngine.Direction.LEFT); bindDirection(R.id.snakeRight, SnakeEngine.Direction.RIGHT);
        bindDirection(R.id.snakeUp, SnakeEngine.Direction.UP); bindDirection(R.id.snakeDown, SnakeEngine.Direction.DOWN);
        refresh();
    }
    private void bindDirection(int id, SnakeEngine.Direction direction) {
        findViewById(id).setOnClickListener(view -> { game.turn(0, direction); });
    }
    private void newParty() {
        game = new SnakeEngine(SnakeEngine.Mode.SOLO, game.difficulty(), System.nanoTime());
        field.setGame(game);
    }
    private void refresh() {
        boolean playing = game.state() == SnakeEngine.State.PLAYING;
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        findViewById(R.id.snakePanel).setVisibility(playing ? View.GONE : View.VISIBLE);
        findViewById(R.id.snakePause).setVisibility(playing ? View.VISIBLE : View.INVISIBLE);
        boolean buttons = ((MaterialCheckBox)findViewById(R.id.snakeButtonsToggle)).isChecked();
        findViewById(R.id.snakeControls).setVisibility(buttons ? View.VISIBLE : View.GONE);
        int best = Math.max(game.score() / 10, preferences.getInt("best." + game.difficulty().name(), 0));
        preferences.edit().putInt("best." + game.difficulty().name(), best).apply();
        score.setText(getString(R.string.cw_snake_score, game.score() / 10, best));
        int tempo = game.difficulty() == SnakeEngine.Difficulty.CALM ? R.string.cw_snake_calm
            : game.difficulty() == SnakeEngine.Difficulty.FAST ? R.string.cw_snake_fast : R.string.cw_snake_normal;
        ((TextView)findViewById(R.id.snakeTempo)).setText(getString(R.string.cw_snake_tempo, getString(tempo)));
        int text, button;
        switch (game.state()) {
            case PAUSED: text = R.string.cw_snake_paused; button = R.string.cw_snake_resume; break;
            case GAME_OVER: text = R.string.cw_snake_over; button = R.string.cw_snake_again; break;
            case WON: text = R.string.cw_snake_won; button = R.string.cw_snake_again; break;
            default: text = R.string.cw_snake_ready; button = R.string.cw_snake_start;
        }
        status.setText(getString(text) + (game.state() == SnakeEngine.State.GAME_OVER || game.state() == SnakeEngine.State.WON
            ? "\n" + getString(R.string.cw_snake_result, game.score() / 10) : "")); action.setText(button);
        changingDifficulty = true;
        difficulty.check(game.difficulty() == SnakeEngine.Difficulty.CALM ? R.id.snakeCalm
            : game.difficulty() == SnakeEngine.Difficulty.FAST ? R.id.snakeFast : R.id.snakeNormal);
        changingDifficulty = false;
        for (int i = 0; i < difficulty.getChildCount(); i++) difficulty.getChildAt(i).setEnabled(game.state() == SnakeEngine.State.READY);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (playing) bars.hide(WindowInsetsCompat.Type.systemBars()); else bars.show(WindowInsetsCompat.Type.systemBars());
        if (!playing) saveLocal();
        field.animateGame();
    }
    @Override protected void onResume() { super.onResume(); if (field != null) field.setActive(true); }
    @Override protected void onPause() {
        game.pause(); field.setActive(false); refresh(); saveLocal(); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        game.pause(); refresh(); state.putLongArray(SAVE, game.snapshot()); super.onSaveInstanceState(state);
    }
    private void saveLocal() {
        StringBuilder value = new StringBuilder(); for (long n : game.snapshot()) value.append(n).append(',');
        preferences.edit().putString("party", value.toString()).apply();
    }
    private long[] readSaved() {
        try {
            String[] parts = preferences.getString("party", "").split(",");
            if (parts.length > 4096) return null;
            long[] values = new long[parts.length]; for (int i = 0; i < parts.length; i++) values[i] = Long.parseLong(parts[i]);
            return values;
        } catch (NumberFormatException invalid) { return null; }
    }
}
