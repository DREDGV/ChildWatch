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
     * This finds the painted area inside the cell and then cuts a circle that
     * contains it, centred on the artwork, so every preset fills its frame the
     * same way regardless of the view size or shape.
     */
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
        int cellLeft = Math.round(column * sheet.getWidth() / (float) COLUMNS);
        int cellTop = Math.round(row * sheet.getHeight() / (float) COLUMNS);
        int cellRight = Math.round((column + 1) * sheet.getWidth() / (float) COLUMNS);
        int cellBottom = Math.round((row + 1) * sheet.getHeight() / (float) COLUMNS);
        Rect cell = new Rect(cellLeft, cellTop, cellRight, cellBottom);

        Rect painted = findPaintedBounds(sheet, cell);
        if (painted == null) {
            return Bitmap.createBitmap(sheet, cell.left, cell.top, cell.width(), cell.height());
        }

        // A square centred on the artwork, then cut into a circle.
        int centreX = (painted.left + painted.right) / 2;
        int centreY = (painted.top + painted.bottom) / 2;
        int half = Math.max(painted.width(), painted.height()) / 2;
        int left = Math.max(cell.left, centreX - half);
        int top = Math.max(cell.top, centreY - half);
        int right = Math.min(cell.right, centreX + half);
        int bottom = Math.min(cell.bottom, centreY + half);
        int size = Math.min(right - left, bottom - top);
        if (size <= 0) {
            return Bitmap.createBitmap(sheet, cell.left, cell.top, cell.width(), cell.height());
        }

        Bitmap square = Bitmap.createBitmap(sheet, left, top, size, size);
        Bitmap circular = toCircle(square);
        if (circular != square) square.recycle();
        return circular;
    }

    /** Bounding box of the non-background pixels inside [cell], or null. */
    @Nullable
    private static Rect findPaintedBounds(Bitmap sheet, Rect cell) {
        int step = Math.max(1, Math.min(cell.width(), cell.height()) / 64);
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (int y = cell.top; y < cell.bottom; y += step) {
            for (int x = cell.left; x < cell.right; x += step) {
                int pixel = sheet.getPixel(x, y);
                // The sheet background is near-white; anything else is artwork.
                boolean background = android.graphics.Color.red(pixel) > 244
                        && android.graphics.Color.green(pixel) > 244
                        && android.graphics.Color.blue(pixel) > 244;
                if (background) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right <= left || bottom <= top) return null;
        return new Rect(left, top, right + 1, bottom + 1);
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
