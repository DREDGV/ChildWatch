package ru.example.childwatch.designsystem;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.function.Consumer;

/** Files and recording belong here; emotions have their own composer entry. */
public final class ChatAttachmentPickerDialog {
    private ChatAttachmentPickerDialog() { }
    public static BottomSheetDialog show(Context context, boolean voiceEnabled, Consumer<String> selected) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(context);
        title.setText(R.string.chat_media_attach);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        add(context, root, dialog, R.string.chat_media_photo, android.R.drawable.ic_menu_gallery, "IMAGE", selected);
        if (voiceEnabled) add(context, root, dialog, R.string.chat_media_audio, android.R.drawable.ic_btn_speak_now, "VOICE", selected);
        add(context, root, dialog, R.string.chat_media_file, android.R.drawable.ic_menu_save, "FILE", selected);
        MaterialButton close = new MaterialButton(context);
        close.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        close.setTextColor(context.getColor(R.color.cw_color_primary));
        close.setText(android.R.string.cancel);
        close.setMinHeight(dp(context, 48));
        close.setOnClickListener(view -> dialog.dismiss());
        root.addView(close, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(root);
        dialog.show();
        return dialog;
    }
    private static void add(Context context, LinearLayout root, BottomSheetDialog dialog,
            int label, int icon, String type, Consumer<String> selected) {
        MaterialButton button = new MaterialButton(context);
        button.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        button.setElevation(0);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(context.getColor(R.color.cw_color_on_surface));
        button.setIconResource(icon);
        button.setIconTint(ColorStateList.valueOf(context.getColor(R.color.cw_color_primary)));
        button.setIconPadding(dp(context, 20));
        button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(context, 64));
        button.setOnClickListener(view -> { dialog.dismiss(); selected.accept(type); });
        root.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
