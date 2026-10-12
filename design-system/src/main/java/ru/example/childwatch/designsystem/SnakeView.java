package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Visual interpolation never changes the engine's accepted cells or its collision rules. */
public final class SnakeView extends View implements Choreographer.FrameCallback {
    private SnakeEngine game;
    private final Runnable changed;
    private final SnakeScene scene;
    private final Path body = new Path();
    private final float[][] previousX = new float[2][SnakeEngine.COLUMNS * SnakeEngine.ROWS];
    private final float[][] previousY = new float[2][SnakeEngine.COLUMNS * SnakeEngine.ROWS];
    private final int[] previousLength = new int[2];
    private boolean active, queued, feedback = true;
    private SnakeEngine.State displayedState;
    private long lastFrame;
    private float cell, left, top;
    private final SnakeTouch touch;
    public SnakeView(Context context, SnakeEngine engine, Runnable changed) {
        super(context); scene = new SnakeScene(context); game = engine; this.changed = changed; touch = new SnakeTouch(getResources().getDisplayMetrics().density, direction -> game.turn(0, direction)); remember();
        setContentDescription(context.getString(R.string.cw_snake_field));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setGame(SnakeEngine engine) { touch.cancel(); game = engine; remember(); lastFrame = 0; invalidate(); }
    public void setFeedbackEnabled(boolean enabled) { feedback = enabled; }
    public void setActive(boolean enabled) {
        active = enabled; lastFrame = 0;
        if (!enabled) { touch.cancel(); Choreographer.getInstance().removeFrameCallback(this); queued = false; }
        else animateGame();
    }
    public void animateGame() {
        invalidate();
        if (displayedState != game.state()) { remember(); displayedState = game.state(); }
        if (game.state() != SnakeEngine.State.PLAYING) {
            touch.cancel();
            lastFrame = 0; Choreographer.getInstance().removeFrameCallback(this); queued = false;
        } else if (active && !queued) { queued = true; Choreographer.getInstance().postFrameCallback(this); }
    }
    @Override public void doFrame(long time) {
        queued = false; if (!active) return;
        int score = game.score(); SnakeEngine.State before = game.state(); long ticks = game.ticks();
        // Store the last accepted field just before the next tick, not predicted future positions.
        double delta = lastFrame == 0 ? 0 : (time - lastFrame) / 1e9;
        if (game.interpolation() + delta / game.tickSeconds() >= 1) remember();
        game.update(delta); lastFrame = time;
        if (game.ticks() - ticks > 1) remember(); // Never draw a shortcut through several accepted corners.
        if (game.score() != score && feedback) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (game.score() != score || before != game.state()) changed.run();
        animateGame();
    }
    private void remember() {
        for (int p = 0; p < game.players(); p++) {
            previousLength[p] = game.length(p);
            for (int i = 0; i < game.length(p); i++) {
                previousX[p][i] = game.segmentX(p, i); previousY[p][i] = game.segmentY(p, i);
            }
        }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float margin = 12 * getResources().getDisplayMetrics().density;
        cell = Math.max(0, Math.min((w - 2 * margin) / SnakeEngine.COLUMNS, (h - 2 * margin) / SnakeEngine.ROWS));
        left = (w - cell * SnakeEngine.COLUMNS) / 2; top = (h - cell * SnakeEngine.ROWS) / 2;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (cell <= 0) return;
        canvas.save(); canvas.translate(left, top); canvas.scale(cell, cell);
        scene.board(canvas);
        if (game.foodX() >= 0) scene.apple(canvas, game.foodX() + .5f, game.foodY() + .5f);
        float interpolation = game.state() == SnakeEngine.State.PLAYING && android.animation.ValueAnimator.areAnimatorsEnabled()
            ? (float)game.interpolation() : 1;
        for (int p = 0; p < game.players(); p++) {
            body.reset();
            for (int i = game.length(p) - 1; i >= 0; i--) {
                float x = visualX(p, i, interpolation) + .5f, y = visualY(p, i, interpolation) + .5f;
                if (i == game.length(p) - 1) body.moveTo(x, y); else body.lineTo(x, y);
            }
            scene.spine(canvas, body, p == 0);
            float dx = game.direction(p) == SnakeEngine.Direction.LEFT ? -1 : game.direction(p) == SnakeEngine.Direction.RIGHT ? 1 : 0;
            float dy = game.direction(p) == SnakeEngine.Direction.UP ? -1 : game.direction(p) == SnakeEngine.Direction.DOWN ? 1 : 0;
            for (int i = game.length(p) - 1; i >= 0; i--)
                scene.segment(canvas, visualX(p, i, interpolation) + .5f, visualY(p, i, interpolation) + .5f,
                    p == 0, i == 0, dx, dy);
        }
        canvas.restore();
    }
    private float visualX(int p, int i, float amount) {
        int old = Math.min(i, previousLength[p] - 1); if (old < 0) return game.segmentX(p, i);
        return previousX[p][old] + (game.segmentX(p, i) - previousX[p][old]) * amount;
    }
    private float visualY(int p, int i, float amount) {
        int old = Math.min(i, previousLength[p] - 1); if (old < 0) return game.segmentY(p, i);
        return previousY[p][old] + (game.segmentY(p, i) - previousY[p][old]) * amount;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (game.state() != SnakeEngine.State.PLAYING) { touch.cancel(); return false; }
        return touch.handle(this, event);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDetachedFromWindow() { setActive(false); super.onDetachedFromWindow(); }
}
