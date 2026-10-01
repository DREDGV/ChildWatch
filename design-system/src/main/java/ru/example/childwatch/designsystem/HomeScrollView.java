package ru.example.childwatch.designsystem;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.util.IdentityHashMap;

/** Fits normal phone heights by reducing spacing, preserving readable text and touch targets.
 * Extreme font scales and landscape retain scrolling rather than clipping actions. */
public class HomeScrollView extends ScrollView {
    private Boolean compact;
    private final IdentityHashMap<View, Integer> topMargins = new IdentityHashMap<>();
    public HomeScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean next = View.MeasureSpec.getSize(heightSpec) < dp(740);
        if (compact == null || compact != next) {
            compact = next;
            if (getChildCount() > 0) {
                ViewGroup home = (ViewGroup) getChildAt(0);
                for (int i = 0; i < home.getChildCount(); i++) {
                    View child = home.getChildAt(i);
                    if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                        if (!topMargins.containsKey(child)) topMargins.put(child, params.topMargin);
                        int original = topMargins.get(child);
                        params.topMargin = next ? Math.min(original, dp(4)) : original;
                    }
                }
                adjust(home, next);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    private void adjust(View view, boolean compact) {
        Object tag = view.getTag();
        if ("homeAction".equals(tag)) {
            int pad = dp(compact ? 8 : 10); view.setPadding(pad, pad, pad, pad);
            view.setMinimumHeight(dp(compact ? 56 : 64));
        } else if ("homePerson".equals(tag)) {
            int pad = dp(compact ? 10 : 12); view.setPadding(pad, pad, pad, pad);
            view.setMinimumHeight(dp(compact ? 88 : 96));
        } else if ("homeService".equals(tag)) {
            view.setPadding(dp(12), dp(compact ? 4 : 6), dp(12), dp(compact ? 4 : 6));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) adjust(group.getChildAt(i), compact);
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
