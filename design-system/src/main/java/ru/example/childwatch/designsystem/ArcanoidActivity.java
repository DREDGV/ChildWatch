package ru.example.childwatch.designsystem;

import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

/** Local entertainment only: no permissions, remote commands or changes to monitoring. */
public class ArcanoidActivity extends AppCompatActivity {
    private static final String SAVE_KEY = "arcanoid.v1";
    final ArcanoidEngine game = new ArcanoidEngine();
    private ArcanoidView field;
    private MaterialButton action;
    private TextView status, score;
    private Slider slider;
    private boolean buttonsEnabled;
    private int bottomInset;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.cw_activity_arcanoid_fullscreen);
        if (state != null) game.restore(state.getDoubleArray(SAVE_KEY));
        else game.restore(readSaved());
        status = findViewById(R.id.arcanoidStatus); score = findViewById(R.id.arcanoidScore);
        action = findViewById(R.id.arcanoidAction); slider = findViewById(R.id.arcanoidPaddle);
        slider.setValueFrom(95); slider.setValueTo(905); slider.setValue((float)game.paddleX());
        field = new ArcanoidView(this, game, this::refresh);
        ((FrameLayout)findViewById(R.id.arcanoidField)).addView(field,
                new FrameLayout.LayoutParams(-1, -1));
        AccessibilityManager accessibility = (AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);
        buttonsEnabled = (accessibility != null && accessibility.isTouchExplorationEnabled())
            || getSharedPreferences(SAVE_KEY, MODE_PRIVATE).getBoolean("buttons", false);
        View hud = findViewById(R.id.arcanoidHud);
        View controls = findViewById(R.id.arcanoidControls);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.arcanoidRoot), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            hud.setPadding(bars.left, bars.top, bars.right, 0);
            bottomInset = bars.bottom;
            controls.setPadding(bars.left + dp(12), 0, bars.right + dp(12), bars.bottom);
            return insets;
        });
        findViewById(R.id.arcanoidRoot).getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int bottom = buttonsEnabled ? controls.getHeight() : bottomInset;
            field.setSafeInsets(hud.getHeight(), bottom);
            View panel = findViewById(R.id.arcanoidPanel);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)panel.getLayoutParams();
            int width = Math.min(findViewById(R.id.arcanoidRoot).getWidth() - dp(40), dp(420));
            int top = hud.getHeight() + dp(12), lower = bottom + dp(12);
            if (width > 0 && (params.width != width || params.topMargin != top || params.bottomMargin != lower)) {
                params.width = width; params.topMargin = top; params.bottomMargin = lower;
                panel.setLayoutParams(params);
            }
        });
        findViewById(R.id.arcanoidPause).setOnClickListener(v -> { game.pause(); refresh(); });
        findViewById(R.id.arcanoidControlsToggle).setOnClickListener(v -> {
            buttonsEnabled = !buttonsEnabled;
            if (accessibility != null && accessibility.isTouchExplorationEnabled()) buttonsEnabled = true;
            getSharedPreferences(SAVE_KEY, MODE_PRIVATE).edit().putBoolean("buttons", buttonsEnabled).apply(); refresh();
        });
        slider.addOnChangeListener((s, value, user) -> {
            if (user) { game.movePaddle(value); field.invalidate(); }
        });
        findViewById(R.id.arcanoidLeft).setOnClickListener(v -> move(-65));
        findViewById(R.id.arcanoidRight).setOnClickListener(v -> move(65));
        action.setOnClickListener(v -> {
            if (game.state() == ArcanoidEngine.State.PLAYING) game.pause();
            else if (game.state() == ArcanoidEngine.State.WON && !game.campaignComplete()) { game.nextLevel(); game.launchOrResume(); }
            else if (game.state() == ArcanoidEngine.State.WON || game.state() == ArcanoidEngine.State.GAME_OVER) game.restart();
            else game.launchOrResume();
            refresh(); field.animateGame();
        });
        findViewById(R.id.arcanoidRestart).setOnClickListener(v -> {
            game.pause(); refresh();
            new MaterialAlertDialogBuilder(this).setTitle(R.string.cw_arcanoid_reset_title)
                .setMessage(R.string.cw_arcanoid_reset_body)
                .setNegativeButton(R.string.cw_arcanoid_cancel, null)
                .setPositiveButton(R.string.cw_arcanoid_restart, (dialog, which) -> {
                    game.restart(); slider.setValue((float)game.paddleX()); refresh(); field.invalidate();
                }).show();
        });
        findViewById(R.id.arcanoidBack).setOnClickListener(v -> finish());
        refresh();
    }
    private void move(double delta) {
        game.movePaddle(game.paddleX() + delta); slider.setValue((float)game.paddleX()); field.invalidate();
    }
    private void refresh() {
        slider.setValue((float)game.paddleX());
        boolean playing = game.state() == ArcanoidEngine.State.PLAYING;
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        findViewById(R.id.arcanoidPanel).setVisibility(playing ? View.GONE : View.VISIBLE);
        findViewById(R.id.arcanoidPause).setVisibility(playing ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.arcanoidControls).setVisibility(buttonsEnabled ? View.VISIBLE : View.GONE);
        ((TextView)findViewById(R.id.arcanoidControlsToggle)).setText(buttonsEnabled
            ? R.string.cw_arcanoid_hide_controls : R.string.cw_arcanoid_show_controls);
        WindowInsetsControllerCompat window = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        window.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (playing) window.hide(WindowInsetsCompat.Type.systemBars()); else window.show(WindowInsetsCompat.Type.systemBars());
        score.setText(getString(R.string.cw_arcanoid_level_score, game.level(), game.levelCount(), game.score(), game.lives()));
        int text, button;
        switch (game.state()) {
            case PLAYING: text = R.string.cw_arcanoid_playing; button = R.string.cw_arcanoid_pause; break;
            case PAUSED: text = R.string.cw_arcanoid_paused; button = R.string.cw_arcanoid_resume; break;
            case WON: text = game.campaignComplete() ? R.string.cw_arcanoid_campaign_won : R.string.cw_arcanoid_level_won;
                button = game.campaignComplete() ? R.string.cw_arcanoid_restart : R.string.cw_arcanoid_next_level; break;
            case GAME_OVER: text = R.string.cw_arcanoid_over; button = R.string.cw_arcanoid_restart; break;
            default: text = R.string.cw_arcanoid_ready; button = R.string.cw_arcanoid_start;
        }
        status.setText(text); action.setText(button);
        if (game.state() != ArcanoidEngine.State.PLAYING) saveLocal();
        field.invalidate();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); field.setActive(true); }
    @Override protected void onPause() {
        game.pause(); field.setActive(false); saveLocal(); refresh(); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        game.pause(); refresh(); state.putDoubleArray(SAVE_KEY, game.snapshot()); super.onSaveInstanceState(state);
    }
    private void saveLocal() {
        StringBuilder value = new StringBuilder();
        for (double n : game.snapshot()) value.append(n).append(',');
        getSharedPreferences(SAVE_KEY, MODE_PRIVATE).edit().putString("party", value.toString()).apply();
    }
    private double[] readSaved() {
        String value = getSharedPreferences(SAVE_KEY, MODE_PRIVATE).getString("party", "");
        try {
            String[] parts = value.split(","); double[] saved = new double[parts.length];
            for (int i = 0; i < parts.length; i++) saved[i] = Double.parseDouble(parts[i]);
            return saved;
        } catch (NumberFormatException invalid) { return null; }
    }
}
