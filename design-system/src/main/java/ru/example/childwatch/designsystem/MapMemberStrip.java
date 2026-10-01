package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
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
        Context context = container.getContext();
        container.removeAllViews();
        for (Entry entry : entries) {
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            int horizontal = dp(context, 7);
            tile.setPadding(horizontal, dp(context, 5), horizontal, dp(context, 5));
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                    dp(context, 92), LinearLayout.LayoutParams.WRAP_CONTENT);
            tileParams.setMargins(dp(context, 2), 0, dp(context, 2), 0);
            container.addView(tile, tileParams);

            ShapeableImageView avatar = new ShapeableImageView(context);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                    .setAllCornerSizes(new RelativeCornerSize(0.5f)).build());
            avatar.setStrokeWidth(dp(context, 2));
            avatar.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    entry.stale ? Color.GRAY : Color.rgb(0, 105, 92)));
            avatar.setAlpha(entry.stale ? 0.58f : 1f);
            tile.addView(avatar, new LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)));
            avatarBinder.bind(avatar, entry.avatar, entry.name);

            TextView name = new TextView(context);
            name.setText(entry.name);
            name.setTextColor(Color.rgb(32, 40, 48));
            name.setTextSize(11f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            tile.addView(name, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView age = new TextView(context);
            age.setText(entry.age);
            age.setTextColor(entry.stale ? Color.rgb(150, 80, 45) : Color.rgb(70, 85, 90));
            age.setTextSize(9f);
            age.setSingleLine(true);
            age.setGravity(Gravity.CENTER);
            tile.addView(age);

            if (entry.distance != null) {
                TextView distance = new TextView(context);
                distance.setText(entry.distance);
                distance.setTextSize(11f);
                distance.setTypeface(null, android.graphics.Typeface.BOLD);
                distance.setTextColor(context.getColor(R.color.cw_color_primary));
                distance.setGravity(Gravity.CENTER);
                distance.setMaxLines(2);
                tile.addView(distance);
            }
            if (entry.speed != null) {
                TextView speed = new TextView(context);
                speed.setText(entry.speed); speed.setTextSize(11f);
                speed.setTextColor(context.getColor(R.color.cw_color_on_surface_variant));
                speed.setGravity(Gravity.CENTER); speed.setMaxLines(2); tile.addView(speed);
            }
            tile.setContentDescription(entry.name + ", " + entry.age + (entry.distance == null ? "" : ", " + entry.distance) + (entry.speed == null ? "" : ", " + entry.speed));
            tile.setFocusable(true);
            tile.setClickable(true);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setCornerRadius(dp(context, 12));
            tile.setBackground(background);
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
