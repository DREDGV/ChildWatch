package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws a readable stand-in avatar for a person who has no picture.
 *
 * Chat and profile rows previously fell back to a blank silhouette or the
 * application icon, which told the user nothing and looked broken. A coloured
 * circle with the person's initial is recognizable, and because the colour is
 * derived from the name it stays the same for that person everywhere.
 *
 * It lives in the design system so ParentMonitor and ChildDevice render the
 * identical fallback.
 */
public final class LetterAvatarFactory {

    /** Palette chosen so the white initial stays legible on every entry. */
    private static final int[] PALETTE = {
            Color.parseColor("#126C67"), // teal, the brand colour
            Color.parseColor("#3F51B5"), // indigo
            Color.parseColor("#8E24AA"), // purple
            Color.parseColor("#C2185B"), // raspberry
            Color.parseColor("#EF6C00"), // orange
            Color.parseColor("#2E7D32"), // green
            Color.parseColor("#0277BD"), // blue
            Color.parseColor("#5D4037"), // brown
    };

    private LetterAvatarFactory() {
    }

    /**
     * @param displayName name used for the initial and the colour; may be null
     * @return a circular drawable with the initial, never null
     */
    @NonNull
    public static Drawable create(@NonNull Context context, @Nullable String displayName) {
        int size = dp(context, 48);
        LetterAvatarDrawable drawable = new LetterAvatarDrawable(displayName, dp(context, 20));
        drawable.setIntrinsicWidth(size);
        drawable.setIntrinsicHeight(size);
        return drawable;
    }

    /** Stable colour for a person: the same name always gets the same one. */
    @ColorInt
    public static int colorFor(@Nullable String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            return PALETTE[0];
        }
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            hash = (hash * 31 + name.charAt(i)) & 0x7FFFFFFF;
        }
        return PALETTE[hash % PALETTE.length];
    }

    /** First letter of the name, or "?" when there is nothing to show. */
    @NonNull
    public static String initialFor(@Nullable String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            return "?";
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                return String.valueOf(Character.toUpperCase(ch));
            }
        }
        return "?";
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** A filled circle with the initial centred on top of it. */
    private static final class LetterAvatarDrawable extends ShapeDrawable {
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String letter;

        LetterAvatarDrawable(@Nullable String displayName, float textSize) {
            super(new OvalShape());
            this.letter = initialFor(displayName);
            getPaint().setColor(colorFor(displayName));
            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(textSize);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            super.draw(canvas);
            float centerX = getBounds().width() / 2f;
            float centerY = getBounds().height() / 2f;
            // Centre the glyph with its own metrics instead of assuming a baseline.
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(letter, centerX, baseline, textPaint);
        }
    }
}
