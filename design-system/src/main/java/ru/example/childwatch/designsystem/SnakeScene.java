package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Shared artwork in cell coordinates. It never reads or mutates game state. */
final class SnakeScene {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int ocean, grid, mint, blue, coral, white, dark, highlight;

    SnakeScene(Context context) {
        ocean = context.getColor(R.color.cw_game_ocean);
        grid = context.getColor(R.color.cw_game_grid);
        mint = context.getColor(R.color.cw_game_mint);
        blue = context.getColor(R.color.cw_snake_partner);
        coral = context.getColor(R.color.cw_snake_apple);
        white = context.getColor(R.color.cw_game_white);
        dark = context.getColor(R.color.cw_snake_depth);
        highlight = context.getColor(R.color.cw_snake_highlight);
    }

    void board(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(grid);
        canvas.drawRoundRect(-.16f, -.16f, SnakeEngine.COLUMNS + .16f, SnakeEngine.ROWS + .16f, .5f, .5f, paint);
        paint.setColor(ocean);
        canvas.drawRoundRect(0, 0, SnakeEngine.COLUMNS, SnakeEngine.ROWS, .4f, .4f, paint);
        paint.setColor(grid); paint.setAlpha(90); paint.setStrokeWidth(.035f);
        for (int x = 1; x < SnakeEngine.COLUMNS; x++) canvas.drawLine(x, 0, x, SnakeEngine.ROWS, paint);
        for (int y = 1; y < SnakeEngine.ROWS; y++) canvas.drawLine(0, y, SnakeEngine.COLUMNS, y, paint);
        paint.setAlpha(255);
    }

    void apple(Canvas canvas, float x, float y) {
        paint.setColor(dark); canvas.drawOval(x - .4f, y + .2f, x + .4f, y + .45f, paint);
        paint.setColor(coral);
        canvas.drawOval(x - .4f, y - .26f, x + .09f, y + .35f, paint);
        canvas.drawOval(x - .09f, y - .26f, x + .4f, y + .35f, paint);
        paint.setColor(highlight); paint.setAlpha(190);
        canvas.drawOval(x - .27f, y - .17f, x - .15f, y + .02f, paint); paint.setAlpha(255);
        paint.setColor(mint); canvas.drawOval(x + .02f, y - .48f, x + .31f, y - .29f, paint);
        paint.setColor(white); paint.setStrokeWidth(.055f);
        canvas.drawLine(x, y - .26f, x + .03f, y - .43f, paint);
    }

    void spine(Canvas canvas, Path path, boolean primary) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeWidth(.56f);
        paint.setColor(primary ? mint : blue); paint.setAlpha(155);
        canvas.drawPath(path, paint); paint.setAlpha(255); paint.setStyle(Paint.Style.FILL);
    }

    void segment(Canvas canvas, float x, float y, boolean primary, boolean head, float dx, float dy) {
        float radius = head ? .43f : .39f;
        paint.setColor(dark);
        canvas.drawRoundRect(x - radius, y - radius + .06f, x + radius, y + radius + .06f, .22f, .22f, paint);
        paint.setColor(primary ? mint : blue);
        canvas.drawRoundRect(x - radius, y - radius, x + radius, y + radius, .22f, .22f, paint);
        paint.setColor(highlight); paint.setAlpha(head ? 130 : 65);
        canvas.drawRoundRect(x - radius + .08f, y - radius + .06f, x + radius - .08f, y - .13f, .12f, .12f, paint);
        paint.setAlpha(255);
        if (!head) return;
        // Two eyes and a small mouth remain legible without animated decoration.
        for (int side = -1; side <= 1; side += 2) {
            float ex = x + dx * .13f - dy * side * .18f;
            float ey = y + dy * .13f + dx * side * .18f;
            paint.setColor(white); canvas.drawCircle(ex, ey, .105f, paint);
            paint.setColor(ocean); canvas.drawCircle(ex + dx * .03f, ey + dy * .03f, .052f, paint);
        }
        paint.setColor(ocean); paint.setStrokeWidth(.045f); paint.setStrokeCap(Paint.Cap.ROUND);
        float mx = x + dx * .3f, my = y + dy * .3f;
        canvas.drawLine(mx - dy * .07f, my + dx * .07f, mx + dy * .07f, my - dx * .07f, paint);
    }
}
