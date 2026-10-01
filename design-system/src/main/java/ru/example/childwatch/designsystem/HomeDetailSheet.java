package ru.example.childwatch.designsystem;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

/** Moves the existing live views into a sheet; their listeners and async bindings survive. */
public final class HomeDetailSheet {
    private HomeDetailSheet() { }
    public static BottomSheetDialog show(View content, String title) {
        ViewGroup originalParent = (ViewGroup) content.getParent();
        int originalIndex = originalParent.indexOfChild(content);
        ViewGroup.LayoutParams originalParams = content.getLayoutParams();
        originalParent.removeView(content);
        LinearLayout column = new LinearLayout(content.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * content.getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        TextView heading = new TextView(content.getContext());
        heading.setText(title); heading.setTextAppearance(R.style.TextAppearance_ChildWatch_Headline);
        column.addView(heading);
        content.setVisibility(View.VISIBLE);
        column.addView(content, new LinearLayout.LayoutParams(-1, -2));
        MaterialButton close = new MaterialButton(content.getContext());
        close.setText(R.string.cw_home_close); column.addView(close);
        ScrollView scroll = new ScrollView(content.getContext()); scroll.addView(column);
        BottomSheetDialog dialog = new BottomSheetDialog(content.getContext());
        dialog.setContentView(scroll);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            column.removeView(content); content.setVisibility(View.GONE);
            originalParent.addView(content, originalIndex, originalParams);
        });
        dialog.show();
        return dialog;
    }
}
