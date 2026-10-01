package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** A compact family-map marker; names and timestamps live in the member strip. */
public final class MapAvatarIcon {
    private MapAvatarIcon() { }

    public static Drawable create(Context context, Drawable portrait, int accentColor, boolean stale) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(44f * density);
        float center = size / 2f;
        float portraitRadius = 19f * density;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(center, center, 21f * density, paint);
        paint.setColor(accentColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        canvas.drawCircle(center, center, 20f * density, paint);
        paint.setStyle(Paint.Style.FILL);

        if (portrait != null) {
            int saved = canvas.save();
            Path clip = new Path();
            clip.addCircle(center, center, portraitRadius, Path.Direction.CW);
            canvas.clipPath(clip);
            portrait.setBounds(Math.round(center - portraitRadius), Math.round(center - portraitRadius),
                    Math.round(center + portraitRadius), Math.round(center + portraitRadius));
            portrait.draw(canvas);
            canvas.restoreToCount(saved);
        }
        if (stale) {
            paint.setColor(0x88FFFFFF);
            canvas.drawCircle(center, center, portraitRadius, paint);
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
