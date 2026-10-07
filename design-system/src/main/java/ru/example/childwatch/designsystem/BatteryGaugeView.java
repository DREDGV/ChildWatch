package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Ten cells with exact partial fill. Adjacent text carries percentage and capture age. */
public final class BatteryGaugeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Integer level;
    private boolean charging, current;

    public BatteryGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setState(Integer level, boolean charging, boolean current) {
        this.level = level != null && level >= 0 && level <= 100 ? level : null;
        this.charging = charging;
        this.current = current;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min((getWidth() - getPaddingLeft() - getPaddingRight()) / 84f,
            (getHeight() - getPaddingTop() - getPaddingBottom()) / 28f);
        if (scale <= 0) return;
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop() +
            (getHeight() - getPaddingTop() - getPaddingBottom() - 28 * scale) / 2);
        canvas.scale(scale, scale);
        int outline = getContext().getColor(R.color.cw_color_on_surface_variant);
        int color = getContext().getColor(level == null || !current ? R.color.cw_color_status_neutral
            : level <= 15 ? R.color.cw_color_error
            : level <= 35 ? R.color.cw_color_warning : R.color.cw_color_success);
        paint.setColor(level != null && current && level <= 35 ? color : outline);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        rect.set(1, 4, 66, 24);
        canvas.drawRoundRect(rect, 3, 3, paint);
        paint.setStyle(Paint.Style.FILL);
        rect.set(67, 10, 70, 18);
        canvas.drawRoundRect(rect, 1, 1, paint);
        float left = 4, gap = 1.3f, cell = (59 - 9 * gap) / 10;
        for (int index = 0; index < 10; index++) {
            float x = left + index * (cell + gap);
            paint.setColor(getContext().getColor(R.color.cw_color_outline_variant));
            rect.set(x, 7, x + cell, 21);
            canvas.drawRoundRect(rect, .8f, .8f, paint);
            if (level != null) {
                float fill = Math.max(0, Math.min(1, (level - index * 10) / 10f));
                if (fill > 0) {
                    paint.setColor(color);
                    rect.set(x, 7, x + cell * fill, 21);
                    canvas.drawRect(rect, paint);
                }
            }
        }
        if (level == null) {
            paint.setColor(getContext().getColor(R.color.cw_color_surface));
            rect.set(25, 6, 42, 22);
            canvas.drawRoundRect(rect, 2, 2, paint);
            paint.setColor(outline);
            paint.setTextSize(16);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("?", 33.5f, 20, paint);
        }
        if (charging) {
            // Kept outside the cells so all ten divisions remain readable.
            Path bolt = new Path();
            bolt.moveTo(80, 5); bolt.lineTo(73, 15); bolt.lineTo(78, 15);
            bolt.lineTo(75, 23); bolt.lineTo(83, 12); bolt.lineTo(78, 12); bolt.close();
            paint.setColor(current ? getContext().getColor(R.color.cw_color_primary) : outline);
            canvas.drawPath(bolt, paint);
        }
        canvas.restore();
    }
}
