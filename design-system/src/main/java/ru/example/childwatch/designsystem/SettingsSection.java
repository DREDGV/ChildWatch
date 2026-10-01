package ru.example.childwatch.designsystem;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A named task group. Its original form stays in the hierarchy, including when collapsed. */
public class SettingsSection extends LinearLayout {
    private final LinearLayout header;
    private final TextView indicator;
    private View body;
    private boolean expanded;
    public SettingsSection(Context context, AttributeSet attrs) {
        super(context, attrs); setOrientation(VERTICAL);
        TypedArray values = context.obtainStyledAttributes(attrs, R.styleable.CwSettingsSection);
        String title = values.getString(R.styleable.CwSettingsSection_cwSectionTitle);
        String description = values.getString(R.styleable.CwSettingsSection_cwSectionDescription);
        int icon = values.getResourceId(R.styleable.CwSettingsSection_cwSectionIcon, R.drawable.cw_home_settings);
        expanded = values.getBoolean(R.styleable.CwSettingsSection_cwSectionExpanded, false);
        values.recycle();
        GradientDrawable surface = new GradientDrawable(); surface.setCornerRadius(dp(16));
        surface.setColor(context.getColor(R.color.cw_color_surface));
        surface.setStroke(dp(1), context.getColor(R.color.cw_color_diagnostics_outline)); setBackground(surface);
        setClipToOutline(true);
        header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(14), dp(14), dp(14)); header.setMinimumHeight(dp(72));
        header.setClickable(true); header.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        if (ripple.resourceId != 0) header.setForeground(context.getDrawable(ripple.resourceId));
        header.setContentDescription(title + ". " + description);
        ImageView image = new ImageView(context); image.setImageResource(icon);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.cw_color_primary)));
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(image, new LayoutParams(dp(24), dp(24)));
        LinearLayout copy = new LinearLayout(context); copy.setOrientation(VERTICAL);
        LayoutParams copyParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1); copyParams.setMarginStart(dp(12));
        header.addView(copy, copyParams);
        TextView name = new TextView(context); name.setText(title);
        name.setTextAppearance(R.style.TextAppearance_ChildWatch_Home_Title); name.setTextSize(16);
        name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); copy.addView(name);
        TextView hint = new TextView(context); hint.setText(description);
        hint.setTextAppearance(R.style.TextAppearance_ChildWatch_Home_Supporting); hint.setPadding(0, dp(4), 0, 0);
        hint.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); copy.addView(hint);
        indicator = new TextView(context); indicator.setTextSize(20); indicator.setGravity(Gravity.CENTER);
        indicator.setTextColor(context.getColor(R.color.cw_color_primary));
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(indicator, new LayoutParams(dp(24), dp(32)));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        header.setOnClickListener(v -> setExpanded(!expanded));
        header.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
                info.addAction(expanded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }
            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                    setExpanded(action == AccessibilityNodeInfo.ACTION_EXPAND); return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }
    @Override protected void onFinishInflate() {
        super.onFinishInflate(); body = getChildAt(1); setExpanded(expanded);
    }
    public void setExpanded(boolean value) {
        expanded = value;
        if (body != null) body.setVisibility(value ? VISIBLE : GONE);
        indicator.setText(value ? "⌃" : "⌄");
        if (Build.VERSION.SDK_INT >= 30) header.setStateDescription(getContext().getString(value ? R.string.cw_settings_expanded : R.string.cw_settings_collapsed));
    }
    public static void reveal(View field) {
        for (ViewParent parent = field.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof SettingsSection) ((SettingsSection) parent).setExpanded(true);
        }
        field.requestFocus();
        field.post(() -> field.requestRectangleOnScreen(new Rect(0, 0, field.getWidth(), field.getHeight()), true));
    }
    @Override protected Parcelable onSaveInstanceState() {
        SavedState state = new SavedState(super.onSaveInstanceState()); state.expanded = expanded; return state;
    }
    @Override protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) { super.onRestoreInstanceState(state); return; }
        SavedState saved = (SavedState) state; super.onRestoreInstanceState(saved.getSuperState()); setExpanded(saved.expanded);
    }
    public static final class SavedState extends BaseSavedState {
        boolean expanded;
        SavedState(Parcelable state) { super(state); }
        SavedState(Parcel parcel) { super(parcel); expanded = parcel.readInt() != 0; }
        @Override public void writeToParcel(Parcel parcel, int flags) { super.writeToParcel(parcel, flags); parcel.writeInt(expanded ? 1 : 0); }
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel parcel) { return new SavedState(parcel); }
            public SavedState[] newArray(int size) { return new SavedState[size]; }
        };
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
