package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Visual interpolation never changes the engine's accepted cells or its collision rules. */
public final class SnakeView extends View implements Choreographer.FrameCallback {
    private static final int[] SIDES = {-1, 1};
    private SnakeEngine game;
    private final Runnable changed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final float[][] previousX = new float[2][SnakeEngine.COLUMNS * SnakeEngine.ROWS];
    private final float[][] previousY = new float[2][SnakeEngine.COLUMNS * SnakeEngine.ROWS];
    private final int[] previousLength = new int[2];
    private boolean active, queued, feedback = true;
    private SnakeEngine.State displayedState;
    private long lastFrame;
    private float cell, left, top, touchX, touchY;
    public SnakeView(Context context, SnakeEngine engine, Runnable changed) {
        super(context); game = engine; this.changed = changed; remember();
        setContentDescription(context.getString(R.string.cw_snake_field));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setGame(SnakeEngine engine) { game = engine; remember(); lastFrame = 0; invalidate(); }
    public void setFeedbackEnabled(boolean enabled) { feedback = enabled; }
    public void setActive(boolean enabled) {
        active = enabled; lastFrame = 0;
        if (!enabled) { Choreographer.getInstance().removeFrameCallback(this); queued = false; }
        else animateGame();
    }
    public void animateGame() {
        invalidate();
        if (displayedState != game.state()) { remember(); displayedState = game.state(); }
        if (game.state() != SnakeEngine.State.PLAYING) {
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
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xff203e4c);
        canvas.drawRoundRect(-.25f, -.25f, SnakeEngine.COLUMNS + .25f, SnakeEngine.ROWS + .25f, .7f, .7f, paint);
        paint.setColor(0xff193541);
        canvas.drawRoundRect(0, 0, SnakeEngine.COLUMNS, SnakeEngine.ROWS, .5f, .5f, paint);
        paint.setColor(0x063effff);
        for (int y = 0; y < SnakeEngine.ROWS; y++) for (int x = y % 2; x < SnakeEngine.COLUMNS; x += 2)
            canvas.drawRect(x, y, x + 1, y + 1, paint);
        if (game.foodX() >= 0) {
            float x = game.foodX() + .5f, y = game.foodY() + .54f;
            paint.setColor(0xffff9c89); canvas.drawCircle(x - .13f, y, .29f, paint); canvas.drawCircle(x + .13f, y, .29f, paint);
            paint.setColor(0xffffc0ac); canvas.drawCircle(x - .15f, y - .12f, .09f, paint);
            paint.setColor(0xffa2d8ad); canvas.drawOval(x, y - .48f, x + .27f, y - .30f, paint);
        }
        float interpolation = game.state() == SnakeEngine.State.PLAYING && android.animation.ValueAnimator.areAnimatorsEnabled()
            ? (float)game.interpolation() : 1;
        for (int p = 0; p < game.players(); p++) {
            body.reset();
            for (int i = game.length(p) - 1; i >= 0; i--) {
                float x = visualX(p, i, interpolation) + .5f, y = visualY(p, i, interpolation) + .5f;
                if (i == game.length(p) - 1) body.moveTo(x, y); else body.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(.78f); paint.setColor(p == 0 ? 0xff64cfbc : 0xffa8c5ff); canvas.drawPath(body, paint);
            paint.setStyle(Paint.Style.FILL);
            float hx = visualX(p, 0, interpolation) + .5f, hy = visualY(p, 0, interpolation) + .5f;
            paint.setColor(p == 0 ? 0xffb2eed9 : 0xffd3e1ff); canvas.drawCircle(hx, hy, .41f, paint);
            float dx = game.direction(p) == SnakeEngine.Direction.LEFT ? -1 : game.direction(p) == SnakeEngine.Direction.RIGHT ? 1 : 0;
            float dy = game.direction(p) == SnakeEngine.Direction.UP ? -1 : game.direction(p) == SnakeEngine.Direction.DOWN ? 1 : 0;
            for (int side : SIDES) {
                float ex = hx + dx * .14f - dy * side * .17f, ey = hy + dy * .14f + dx * side * .17f;
                paint.setColor(0xffffffff); canvas.drawCircle(ex, ey, .115f, paint);
                paint.setColor(0xff17333d); canvas.drawCircle(ex + dx * .035f, ey + dy * .035f, .061f, paint);
            }
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
        if (game.state() != SnakeEngine.State.PLAYING) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = event.getX(); touchY = event.getY(); getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - touchX, dy = event.getY() - touchY;
                if (Math.max(Math.abs(dx), Math.abs(dy)) >= 18 * getResources().getDisplayMetrics().density) {
                    game.turn(0, Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? SnakeEngine.Direction.RIGHT : SnakeEngine.Direction.LEFT)
                        : (dy > 0 ? SnakeEngine.Direction.DOWN : SnakeEngine.Direction.UP));
                    touchX = event.getX(); touchY = event.getY();
                } return true;
            case MotionEvent.ACTION_UP: performClick(); return true;
            case MotionEvent.ACTION_CANCEL: return true;
            default: return false;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDetachedFromWindow() { setActive(false); super.onDetachedFromWindow(); }
}
