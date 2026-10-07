package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.shape.RelativeCornerSize;

import java.util.List;
import java.util.function.Consumer;

/** Compact, accessible family selector shared by the two map screens. */
public final class MapMemberStrip {
    private MapMemberStrip() { }

    public static final class Entry {
        public final String id;
        public final String name;
        public final String avatar;
        public final String age;
        public final boolean stale;
        public final String distance;
        public final String speed;

        public Entry(String id, String name, String avatar, String age, boolean stale) {
            this(id, name, avatar, age, stale, null);
        }
        public Entry(String id, String name, String avatar, String age, boolean stale, String distance) {
            this(id, name, avatar, age, stale, distance, null);
        }
        public Entry(String id, String name, String avatar, String age, boolean stale, String distance, String speed) {
            this.speed = speed;
            this.distance = distance;
            this.id = id;
            this.name = name;
            this.avatar = avatar;
            this.age = age;
            this.stale = stale;
        }
    }

    public interface AvatarBinder {
        void bind(ImageView view, String avatar, String name);
    }

    public static void render(
            LinearLayout container,
            List<Entry> entries,
            AvatarBinder avatarBinder,
            Consumer<Entry> onSelect
    ) {
        render(container, entries, avatarBinder, false, null, onSelect);
    }

    /** Map-only compact presentation; other callers retain the original detailed tiles. */
    public static void render(
            LinearLayout container,
            List<Entry> entries,
            AvatarBinder avatarBinder,
            boolean compact,
            String selectedId,
            Consumer<Entry> onSelect
    ) {
        Context context = container.getContext();
        container.removeAllViews();
        for (Entry entry : entries) {
            boolean selected = compact && selectedId != null && selectedId.equals(entry.id);
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            int horizontal = dp(context, 7);
            tile.setPadding(horizontal, dp(context, 5), horizontal, dp(context, 5));
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                    dp(context, compact ? (context.getResources().getConfiguration().fontScale >= 1.3f ? 120 : 100) : 92),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tileParams.setMargins(dp(context, 2), 0, dp(context, 2), 0);
            container.addView(tile, tileParams);

            ShapeableImageView avatar = new ShapeableImageView(context);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                    .setAllCornerSizes(new RelativeCornerSize(0.5f)).build());
            avatar.setStrokeWidth(dp(context, compact ? 1 : 2));
            avatar.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    compact ? context.getColor(R.color.cw_color_outline_variant)
                            : entry.stale ? Color.GRAY : Color.rgb(0, 105, 92)));
            avatar.setAlpha(compact ? 1f : entry.stale ? 0.58f : 1f);
            if (compact) {
                // Keep the selection ring outside the photo so a stale photo remains recognizable.
                FrameLayout ring = new FrameLayout(context);
                GradientDrawable ringBackground = new GradientDrawable();
                ringBackground.setShape(GradientDrawable.OVAL);
                ringBackground.setColor(Color.TRANSPARENT);
                if (selected) ringBackground.setStroke(dp(context, 2), context.getColor(R.color.cw_color_primary));
                ring.setBackground(ringBackground);
                ring.addView(avatar, new FrameLayout.LayoutParams(dp(context, 36), dp(context, 36), Gravity.CENTER));
                tile.addView(ring, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
            } else {
                tile.addView(avatar, new LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)));
            }
            avatarBinder.bind(avatar, entry.avatar, entry.name);

            TextView name = new TextView(context);
            name.setText(entry.name);
            name.setTextColor(compact ? context.getColor(R.color.cw_color_on_surface) : Color.rgb(32, 40, 48));
            name.setTextSize(compact ? 13f : 11f);
            if (selected) name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            tile.addView(name, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView age = new TextView(context);
            age.setText(entry.age);
            age.setTextColor(compact ? context.getColor(entry.stale ? R.color.cw_color_warning : R.color.cw_color_on_surface_variant)
                    : entry.stale ? Color.rgb(150, 80, 45) : Color.rgb(70, 85, 90));
            age.setTextSize(compact ? 11f : 9f);
            age.setSingleLine(true);
            if (compact) age.setEllipsize(android.text.TextUtils.TruncateAt.END);
            age.setGravity(Gravity.CENTER);
            if (compact) tile.addView(age, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            else tile.addView(age);

            if (!compact && entry.distance != null) {
                TextView distance = new TextView(context);
                distance.setText(entry.distance);
                distance.setTextSize(11f);
                distance.setTypeface(null, android.graphics.Typeface.BOLD);
                distance.setTextColor(context.getColor(R.color.cw_color_primary));
                distance.setGravity(Gravity.CENTER);
                distance.setMaxLines(2);
                tile.addView(distance);
            }
            if (entry.speed != null && (!compact || selected)) {
                TextView speed = new TextView(context);
                speed.setText(entry.speed); speed.setTextSize(11f);
                speed.setTextColor(context.getColor(selected ? R.color.cw_color_primary : R.color.cw_color_on_surface_variant));
                if (selected) speed.setTypeface(null, android.graphics.Typeface.BOLD);
                if (compact) speed.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                speed.setGravity(Gravity.CENTER); speed.setMaxLines(compact ? 3 : 2); tile.addView(speed);
            }
            tile.setContentDescription(entry.name + ", " + entry.age + (entry.distance == null ? "" : ", " + entry.distance) + (entry.speed == null ? "" : ", " + entry.speed));
            if (compact) {
                tile.setSelected(selected);
                tile.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                age.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName("android.widget.RadioButton");
                        info.setCheckable(true);
                        info.setChecked(selected);
                    }
                });
            }
            tile.setFocusable(true);
            tile.setClickable(true);
            GradientDrawable background = new GradientDrawable();
            background.setColor(compact ? context.getColor(selected ? R.color.cw_color_selected_surface : R.color.cw_color_surface) : Color.WHITE);
            background.setCornerRadius(dp(context, 12));
            tile.setBackground(compact ? new RippleDrawable(android.content.res.ColorStateList.valueOf(
                    context.getColor(R.color.cw_color_primary_container)), background, null) : background);
            tile.setOnClickListener(v -> onSelect.accept(entry));
        }
        container.getParent().requestLayout();
        View parent = (View) container.getParent();
        parent.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
