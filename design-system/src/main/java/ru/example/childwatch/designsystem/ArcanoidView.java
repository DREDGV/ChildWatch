package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/** Only draws local gameplay. The engine is independent of frame rate and touch events. */
public final class ArcanoidView extends View implements Choreographer.FrameCallback {
    private final ArcanoidEngine game;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors = {0xff60c9c0, 0xff9ac4ff, 0xffbfabed, 0xffffc17c, 0xfff49caa};
    private float scale, offsetX, offsetY;
    private int safeTop, safeBottom;
    public void setSafeInsets(int top, int bottom) {
        safeTop = top; safeBottom = bottom; sizeField(getWidth(), getHeight()); invalidate();
    }
    private long lastFrame;
    private boolean active, queued;
    private final Runnable changed;
    public ArcanoidView(Context context, ArcanoidEngine game, Runnable changed) {
        super(context); this.game = game; this.changed = changed;
        setBackgroundColor(0xff152e3d);
        setContentDescription(context.getString(R.string.cw_arcanoid_field));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setActive(boolean value) {
        active = value; lastFrame = 0;
        if (!active) { Choreographer.getInstance().removeFrameCallback(this); queued = false; }
        else animateGame();
    }
    public void animateGame() {
        invalidate();
        if (game.state() != ArcanoidEngine.State.PLAYING) lastFrame = 0;
        if (active && !queued && game.state() == ArcanoidEngine.State.PLAYING) {
            queued = true; Choreographer.getInstance().postFrameCallback(this);
        }
    }
    @Override public void doFrame(long time) {
        queued = false;
        if (!active) return;
        ArcanoidEngine.State before = game.state(); int score = game.score();
        if (lastFrame != 0) game.update((time - lastFrame) / 1e9);
        lastFrame = time;
        if (before != game.state() || score != game.score()) changed.run();
        animateGame();
    }
    @Override protected void onDetachedFromWindow() { setActive(false); super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        sizeField(w, h);
    }
    private void sizeField(int w, int h) {
        if (w <= 0 || h <= safeTop + safeBottom) return;
        game.resize((h - safeTop - safeBottom) * ArcanoidEngine.WIDTH / w);
        scale = (float)Math.min(w / ArcanoidEngine.WIDTH, (h - safeTop - safeBottom) / game.height());
        offsetX = (w - (float)ArcanoidEngine.WIDTH * scale) / 2;
        offsetY = safeTop + (h - safeTop - safeBottom - (float)game.height() * scale) / 2;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (scale <= 0) return;
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(0xff375463);
        canvas.drawRoundRect(3, 3, 997, (float)game.height() - 3, 28, 28, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < ArcanoidEngine.BRICKS; i++) if (game.hasBrick(i)) {
            float x = (float)ArcanoidEngine.brickX(i), y = (float)game.blockY(i);
            paint.setColor(colors[i / ArcanoidEngine.COLUMNS]);
            canvas.drawRoundRect(x, y, x + 136, y + (float)game.blockHeight(), 12, 12, paint);
            paint.setColor(0x30ffffff); canvas.drawRoundRect(x + 6, y + 5, x + 130, y + 12, 3, 3, paint);
        }
        paint.setColor(0xff60c9c0);
        canvas.drawRoundRect((float)game.paddleX() - 95, (float)game.paddleY(), (float)game.paddleX() + 95, (float)game.paddleY() + 22, 11, 11, paint);
        paint.setColor(Color.WHITE); canvas.drawCircle((float)game.ballX(), (float)game.ballY(), (float)ArcanoidEngine.RADIUS, paint);
        canvas.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (scale <= 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                game.movePaddle((event.getX() - offsetX) / scale); invalidate(); return true;
            case MotionEvent.ACTION_UP: changed.run(); performClick(); return true;
            case MotionEvent.ACTION_CANCEL: return true;
            default: return false;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
