package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.List;

/** Compact read-only roster; close stays reachable below the scrolling list. */
public final class ChatParticipantsDialog {
    public static final class Entry {
        public final String name, status;
        public final boolean chatOpen;
        public Entry(String name, String status, boolean chatOpen) {
            this.name = name; this.status = status; this.chatOpen = chatOpen;
        }
    }
    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final LinearLayout rows;
    private final TextView summary;

    public ChatParticipantsDialog(Activity activity) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        TextView title = text(activity.getString(R.string.chat_participants_title), 20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        summary = text(activity.getString(R.string.chat_participants_explanation), 14);
        summary.setTextColor(activity.getColor(R.color.cw_color_on_surface_variant));
        summary.setPadding(0, dp(8), 0, dp(12));
        root.addView(summary);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        int maximum = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.48f);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maximum));
        MaterialButton close = new MaterialButton(activity);
        close.setText(R.string.chat_participants_close);
        close.setAllCaps(false);
        close.setBackgroundTintList(ColorStateList.valueOf(activity.getColor(R.color.cw_color_surface_variant)));
        close.setTextColor(activity.getColor(R.color.cw_color_primary));
        close.setMinHeight(dp(48));
        close.setOnClickListener(v -> close());
        root.addView(close, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(root);
    }

    public void render(List<Entry> entries, boolean known) {
        rows.removeAllViews();
        summary.setText(known ? R.string.chat_participants_explanation : R.string.chat_participants_unknown);
        for (Entry entry : entries) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            TextView name = text(entry.name, 17);
            row.addView(name);
            TextView status = text(entry.status, 14);
            status.setPadding(0, dp(4), 0, 0);
            status.setTextColor(activity.getColor(entry.chatOpen ? R.color.cw_color_primary : R.color.cw_color_on_surface_variant));
            row.addView(status);
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    public void show() { dialog.show(); }
    public void close() { dialog.dismiss(); }
    private TextView text(String value, int size) {
        TextView result = new TextView(activity);
        result.setText(value); result.setTextSize(size);
        result.setTextColor(activity.getColor(R.color.cw_color_on_surface));
        return result;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
