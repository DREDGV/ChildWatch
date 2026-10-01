package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collection;
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
 *
 * <p>The original six keys (see {@link #LEGACY_ALIASES}) are answered from that same sheet. They
 * are still accepted by the server and are still handed out as the default picture for a member
 * who never chose one, so a client that did not know them drew a letter for that person even
 * though the server had sent a picture. Answering them here is what keeps both applications in
 * step with the server's accepted list.</p>
 */
public final class AvatarPresetCatalog {
    private static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            "preset:corgi", "preset:dinosaur", "preset:robot", "preset:cactus", "preset:penguin",
            "preset:astronaut", "preset:donut", "preset:cat", "preset:pizza", "preset:unicorn",
            "preset:monster", "preset:mug", "preset:avocado", "preset:panda", "preset:rocket",
            "preset:alien", "preset:shark", "preset:burger", "preset:chick", "preset:frog",
            "preset:llama", "preset:sloth", "preset:controller", "preset:pineapple", "preset:cloud"
    ));

    /**
     * The six colour-named avatars that came before the sheet.
     *
     * <p>The server keeps them in {@code AVATAR_PRESETS} and still returns them for members whose
     * picture was chosen by {@code defaultAvatarKeyFor}, so they are not dead values: roughly one
     * member in five carries one. Each maps onto the sheet picture that suits its name, so both
     * applications show the same face for the same stored key.</p>
     */
    private static final Map<String, String> LEGACY_ALIASES =
            Collections.unmodifiableMap(aliasMap());

    private static final int COLUMNS = 5;
    private static final Map<String, Bitmap> BITMAP_CACHE = new HashMap<>();
    @Nullable
    private static Bitmap sheetCache;

    private AvatarPresetCatalog() { }

    private static Map<String, String> aliasMap() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("preset:sky", "preset:cloud");
        aliases.put("preset:mint", "preset:frog");
        aliases.put("preset:sun", "preset:donut");
        aliases.put("preset:coral", "preset:unicorn");
        aliases.put("preset:lilac", "preset:llama");
        aliases.put("preset:ocean", "preset:shark");
        return aliases;
    }

    public static List<String> keys() {
        return KEYS;
    }

    /**
     * The offered preset that stands for a stored value, or null when it is not a built-in picture.
     *
     * <p>For a picker this is what turns "the person already has the old {@code preset:sky}" into
     * "the cloud choice is the selected one", so opening the editor and saving does not quietly
     * move the person onto a picture they did not choose.</p>
     */
    @Nullable
    public static String offeredPresetFor(@Nullable String value) {
        return resolve(value);
    }

    /**
     * Whether a stored value is one of the built-in pictures.
     *
     * <p>True for the offered presets and for the legacy six, so a caller that guards a save with
     * this test does not silently drop the picture a person already has.</p>
     */
    public static boolean isPreset(@Nullable String value) {
        return resolve(value) != null;
    }

    @Nullable
    public static Drawable createDrawable(Context context, @Nullable String value) {
        String key = resolve(value);
        if (key == null) return null;
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

    /**
     * Decodes the sheet and the named avatars ahead of time, off the caller's thread.
     *
     * <p>Cropping a preset needs the whole sheet in memory, and that decode plus the
     * per-cell artwork scan is far too slow for the main thread: the first screen that
     * drew avatars on a map skipped about a second of frames. A screen that knows it is
     * about to draw avatars calls this on a background thread first, and the drawing
     * calls that follow only crop from what is already decoded.</p>
     */
    public static void warmUp(@Nullable Context context, @Nullable Collection<String> values) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        ensureSheet(appContext);
        if (values == null) return;
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            createDrawable(appContext, value);
        }
    }

    /** The sheet itself, decoded once and kept. */
    @Nullable
    private static Bitmap ensureSheet(Context context) {
        synchronized (BITMAP_CACHE) {
            Bitmap sheet = sheetCache;
            if (sheet != null && !sheet.isRecycled()) return sheet;
            sheet = BitmapFactory.decodeResource(
                    context.getApplicationContext().getResources(),
                    R.drawable.avatar_presets_sheet
            );
            sheetCache = sheet;
            return sheet;
        }
    }

    /**
     * The offered preset a stored value stands for, or null when it is not a built-in picture.
     *
     * <p>A legacy key resolves to the offered preset it is drawn from, so callers only ever have
     * to handle a value that the sheet actually contains.</p>
     */
    @Nullable
    private static String resolve(@Nullable String value) {
        if (value == null) return null;
        String key = value.trim();
        if (KEYS.contains(key)) return key;
        return LEGACY_ALIASES.get(key);
    }

    /**
     * Crops one preset out of the sheet as a ready-to-use circular avatar.
     *
     * The sheet is a grid of painted circles on a white background, and each
     * circle sits inside its grid cell with white margin around it. Cropping the
     * cell and letting the view scale it therefore showed the avatar smaller
     * than its frame and looking off-centre, differently for every picture,
     * because the artwork inside each circle is not the same size.
     *
     * A fixed square centred on each painted circle gives every preset the same
     * circular outline, including artwork that reaches beyond its background.
     */
    @Nullable
    private static Bitmap crop(Context context, int index) {
        Bitmap sheet = ensureSheet(context);
        if (sheet == null) return null;

        int row = index / COLUMNS;
        int column = index % COLUMNS;
        // The supplied 1254px sheet is not centred on equal-size grid cells:
        // the first circle starts at x=27, successive columns at +245px, and
        // successive rows at +240px. Match those circles exactly, scaled with
        // the resource, instead of scanning protruding artwork or cell edges.
        float scaleX = sheet.getWidth() / 1254f;
        float scaleY = sheet.getHeight() / 1254f;
        int size = Math.round(218f * Math.min(scaleX, scaleY));
        int centerX = Math.round((136f + 245f * column) * scaleX);
        int centerY = Math.round((139f + 240f * row) * scaleY);
        int left = Math.max(0, Math.min(sheet.getWidth() - size, centerX - size / 2));
        int top = Math.max(0, Math.min(sheet.getHeight() - size, centerY - size / 2));
        Bitmap square = Bitmap.createBitmap(sheet, left, top, size, size);
        Bitmap circular = toCircle(square);
        if (circular != square) square.recycle();
        return circular;
    }

    /** Copies [square] into a circle with transparent corners. */
    private static Bitmap toCircle(Bitmap square) {
        int size = square.getWidth();
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.BitmapShader(
                square,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP
        ));
        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        return output;
    }
}
