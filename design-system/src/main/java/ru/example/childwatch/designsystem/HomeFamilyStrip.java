package ru.example.childwatch.designsystem;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;
import java.util.List;

/** A bounded row of people. The last control always opens the complete family. */
public class HomeFamilyStrip extends LinearLayout {
    public interface AvatarBinder { void bind(ShapeableImageView view, String key, String name); }
    public interface Selection { void select(String memberId); }
    public static final class Person {
        public final String id, name, avatar;
        public Person(String id, String name, String avatar) {
            this.id = id; this.name = name; this.avatar = avatar;
        }
    }
    public HomeFamilyStrip(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
    }
    public void render(List<Person> people, String selectedId, AvatarBinder binder,
                       Selection selection, Runnable openFamily, boolean canInvite) {
        removeAllViews();
        // Keep the selected person visible even when the family has many members.
        java.util.ArrayList<Person> visible = new java.util.ArrayList<>(people.subList(0, Math.min(4, people.size())));
        for (Person person : people) {
            if (person.id.equals(selectedId) && !visible.contains(person) && visible.size() == 4) {
                visible.set(3, person); break;
            }
        }
        for (Person person : visible) {
            boolean selected = person.id.equals(selectedId);
            LinearLayout column = new LinearLayout(getContext());
            column.setOrientation(VERTICAL); column.setGravity(Gravity.CENTER);
            column.setMinimumHeight(dp(64)); column.setPadding(dp(2), dp(2), dp(2), dp(2));
            column.setClickable(true); column.setFocusable(true); column.setSelected(selected);
            column.setContentDescription(person.name + (selected ? ", " + getContext().getString(R.string.cw_home_selected) : ""));
            column.setOnClickListener(v -> selection.select(person.id));
            MaterialCardView ring = new MaterialCardView(getContext());
            ring.setRadius(dp(25)); ring.setCardElevation(0); ring.setStrokeWidth(selected ? dp(2) : 0);
            ring.setStrokeColor(color(R.color.cw_color_primary));
            ring.setCardBackgroundColor(color(selected ? R.color.cw_color_primary_container : R.color.cw_color_background));
            ShapeableImageView image = new ShapeableImageView(getContext());
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            image.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(22)).build());
            android.widget.FrameLayout.LayoutParams imageParams = new android.widget.FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER);
            ring.addView(image, imageParams); binder.bind(image, person.avatar, person.name);
            column.addView(ring, new LayoutParams(dp(50), dp(50)));
            TextView label = new TextView(getContext()); label.setText(person.name); label.setTextSize(12);
            label.setTextColor(color(selected ? R.color.cw_color_primary : R.color.cw_color_on_surface));
            label.setGravity(Gravity.CENTER); label.setMaxLines(1); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            addView(column, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        com.google.android.material.button.MaterialButton more = new com.google.android.material.button.MaterialButton(getContext());
        more.setText(canInvite ? "+" : "•••"); more.setTextSize(20); more.setMinWidth(0); more.setMinimumWidth(0);
        more.setInsetTop(0); more.setInsetBottom(0); more.setCornerRadius(dp(24)); more.setStrokeWidth(dp(1));
        more.setStrokeColor(ColorStateList.valueOf(color(R.color.cw_color_outline_variant)));
        more.setBackgroundTintList(ColorStateList.valueOf(color(R.color.cw_color_background)));
        more.setTextColor(color(R.color.cw_color_primary)); more.setPadding(0, 0, 0, 0);
        more.setContentDescription(getContext().getString(canInvite ? R.string.cw_home_family_add : R.string.cw_home_family_all));
        more.setOnClickListener(v -> openFamily.run());
        addView(more, new LayoutParams(dp(48), dp(48)));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int id) { return getContext().getColor(id); }
}
