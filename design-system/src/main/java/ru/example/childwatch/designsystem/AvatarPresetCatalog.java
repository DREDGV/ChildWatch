package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared, stable catalog for built-in family avatars.
 *
 * <p>The source sheet is intentionally kept as one supplied image. Each preset is cropped at
 * runtime, so the parent and child applications always render the exact same avatar for a stored
 * {@code preset:*} key and do not need to duplicate 25 bitmap resources.</p>
 */
public final class AvatarPresetCatalog {
    private static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            "preset:corgi", "preset:dinosaur", "preset:robot", "preset:cactus", "preset:penguin",
            "preset:astronaut", "preset:donut", "preset:cat", "preset:pizza", "preset:unicorn",
            "preset:monster", "preset:mug", "preset:avocado", "preset:panda", "preset:rocket",
            "preset:alien", "preset:shark", "preset:burger", "preset:chick", "preset:frog",
            "preset:llama", "preset:sloth", "preset:controller", "preset:pineapple", "preset:cloud"
    ));
    private static final int COLUMNS = 5;
    private static final Map<String, Bitmap> BITMAP_CACHE = new HashMap<>();
    @Nullable
    private static Bitmap sheetCache;

    private AvatarPresetCatalog() { }

    public static List<String> keys() {
        return KEYS;
    }

    public static boolean isPreset(@Nullable String value) {
        return value != null && KEYS.contains(value.trim());
    }

    @Nullable
    public static Drawable createDrawable(Context context, @Nullable String value) {
        if (!isPreset(value)) return null;
        String key = value.trim();
        Bitmap avatar;
        synchronized (BITMAP_CACHE) {
            avatar = BITMAP_CACHE.get(key);
            if (avatar == null) {
                avatar = crop(context, KEYS.indexOf(key));
                if (avatar != null) BITMAP_CACHE.put(key, avatar);
            }
        }
        return avatar == null ? null : new BitmapDrawable(context.getResources(), avatar);
    }

    @Nullable
    private static Bitmap crop(Context context, int index) {
        Bitmap sheet = sheetCache;
        if (sheet == null || sheet.isRecycled()) {
            sheet = BitmapFactory.decodeResource(
                    context.getApplicationContext().getResources(),
                    R.drawable.avatar_presets_sheet
            );
            sheetCache = sheet;
        }
        if (sheet == null) return null;

        int row = index / COLUMNS;
        int column = index % COLUMNS;
        int left = Math.round(column * sheet.getWidth() / (float) COLUMNS);
        int top = Math.round(row * sheet.getHeight() / (float) COLUMNS);
        int right = Math.round((column + 1) * sheet.getWidth() / (float) COLUMNS);
        int bottom = Math.round((row + 1) * sheet.getHeight() / (float) COLUMNS);
        Rect source = new Rect(left, top, right, bottom);
        return Bitmap.createBitmap(sheet, source.left, source.top, source.width(), source.height());
    }
}
