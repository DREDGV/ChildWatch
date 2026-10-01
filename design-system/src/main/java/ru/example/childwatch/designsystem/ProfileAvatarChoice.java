package ru.example.childwatch.designsystem;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;

/** The image keeps its size; the selection ring sits outside its artwork. */
public final class ProfileAvatarChoice extends FrameLayout {
    public final ShapeableImageView image;
    private final TextView check;
    private final FrameLayout ring;
    private boolean checked;

    public ProfileAvatarChoice(Context context, int number) {
        super(context);
        setFocusable(true);
        setClickable(true);
        setContentDescription(context.getString(R.string.cw_profile_avatar_option, number));
        ring = new FrameLayout(context);
        addView(ring, new LayoutParams(dp(68), dp(68), Gravity.CENTER));
        image = new ShapeableImageView(context);
        image.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(26)).build());
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ring.addView(image, new LayoutParams(dp(52), dp(52), Gravity.CENTER));
        check = new TextView(context);
        check.setText("✓");
        check.setTextSize(12);
        check.setGravity(Gravity.CENTER);
        check.setTextColor(context.getColor(R.color.cw_color_on_primary));
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(context.getColor(R.color.cw_color_primary));
        badge.setStroke(dp(2), context.getColor(R.color.cw_color_surface));
        check.setBackground(badge);
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams badgeParams = new LayoutParams(dp(20), dp(20), Gravity.BOTTOM | Gravity.END);
        badgeParams.bottomMargin = dp(2);
        badgeParams.setMarginEnd(dp(2));
        ring.addView(check, badgeParams);
        android.content.res.TypedArray attrs = context.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
        setForeground(attrs.getDrawable(0));
        attrs.recycle();
        setChecked(false);
    }

    public void setChecked(boolean value) {
        checked = value;
        setSelected(value);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(value ? getContext().getColor(R.color.cw_color_primary_container) : android.graphics.Color.TRANSPARENT);
        if (value) ring.setStroke(dp(2), getContext().getColor(R.color.cw_color_primary));
        this.ring.setBackground(ring);
        check.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.RadioButton");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
