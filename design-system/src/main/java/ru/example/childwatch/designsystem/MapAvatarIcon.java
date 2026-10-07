package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

/** A compact family-map marker; names and timestamps live in the member strip. */
public final class MapAvatarIcon {
    private MapAvatarIcon() { }

    public static Drawable create(Context context, Drawable portrait, int accentColor, boolean stale) {
        return create(context, portrait, accentColor, stale, null);
    }

    /** The avatar remains at bitmap center, so an existing CENTER anchor stays on the GPS fix. */
    public static Drawable create(Context context, Drawable portrait, int accentColor, boolean stale,
                                  String speedLabel) {
        float density = context.getResources().getDisplayMetrics().density;
        TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTextSize(11f * context.getResources().getDisplayMetrics().scaledDensity);
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        String label = speedLabel == null || speedLabel.trim().isEmpty() || stale ? null
            : TextUtils.ellipsize(speedLabel.trim(), labelPaint, 120f * density, TextUtils.TruncateAt.END).toString();
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float labelHeight = label == null ? 0 : metrics.descent - metrics.ascent + 8f * density;
        float labelWidth = label == null ? 0 : labelPaint.measureText(label) + 16f * density;
        int width = Math.round(Math.max(44f * density, labelWidth + 2f * density));
        int height = Math.round(label == null ? 44f * density : 2 * (25f * density + labelHeight));
        float centerX = width / 2f;
        float centerY = height / 2f;
        float portraitRadius = 19f * density;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(centerX, centerY, 21f * density, paint);
        paint.setColor(accentColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        canvas.drawCircle(centerX, centerY, 20f * density, paint);
        paint.setStyle(Paint.Style.FILL);

        if (portrait != null) {
            int saved = canvas.save();
            Path clip = new Path();
            clip.addCircle(centerX, centerY, portraitRadius, Path.Direction.CW);
            canvas.clipPath(clip);
            portrait.setBounds(Math.round(centerX - portraitRadius), Math.round(centerY - portraitRadius),
                    Math.round(centerX + portraitRadius), Math.round(centerY + portraitRadius));
            portrait.draw(canvas);
            canvas.restoreToCount(saved);
        }
        if (stale) {
            paint.setColor(0x88FFFFFF);
            canvas.drawCircle(centerX, centerY, portraitRadius, paint);
        }
        if (label != null) {
            float top = centerY + 24f * density;
            android.graphics.RectF badge = new android.graphics.RectF(centerX - labelWidth / 2,
                top, centerX + labelWidth / 2, top + labelHeight);
            paint.setColor(context.getColor(R.color.cw_color_surface));
            canvas.drawRoundRect(badge, 8f * density, 8f * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            paint.setColor(context.getColor(R.color.cw_color_outline_variant));
            canvas.drawRoundRect(badge, 8f * density, 8f * density, paint);
            labelPaint.setColor(context.getColor(R.color.cw_color_on_surface));
            canvas.drawText(label, centerX - labelPaint.measureText(label) / 2,
                top + (labelHeight - metrics.descent - metrics.ascent) / 2, labelPaint);
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
