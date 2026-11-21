package ru.example.childwatch.designsystem;

import android.content.Context;
import android.text.InputFilter;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class ChatHistorySearchDialog {
    private final AlertDialog dialog;
    private final TextView status;
    private final ListView results;

    public ChatHistorySearchDialog(Context context, Consumer<String> search) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 24);
        content.setPadding(padding, 0, padding, dp(context, 8));
        EditText query = new EditText(context);
        query.setHint(R.string.chat_search_hint);
        query.setSingleLine();
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        content.addView(query);
        status = new TextView(context);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        content.addView(status);
        results = new ListView(context);
        int height = Math.min(dp(context, 280), context.getResources().getDisplayMetrics().heightPixels / 3);
        content.addView(results, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
        results.setVisibility(View.GONE);
        dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.chat_search_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chat_search_action, null)
                .create();
        Runnable submit = () -> {
            String text = query.getText().toString().trim();
            if (text.isEmpty()) return;
            setStatus(context.getString(R.string.chat_search_loading));
            android.view.inputmethod.InputMethodManager keyboard =
                    context.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
            search.accept(text);
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> submit.run());
            query.requestFocus();
        });
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.run();
            return true;
        });
    }

    public void show() { dialog.show(); }
    public void dismiss() { dialog.dismiss(); }
    public boolean isShowing() { return dialog.isShowing(); }
    public void onDismiss(Runnable action) { dialog.setOnDismissListener(ignored -> action.run()); }

    public void setStatus(String message) {
        showStatus(message);
        results.setVisibility(View.GONE);
        results.setAdapter(null);
    }

    public void showResults(List<String> labels, String message, IntConsumer select) {
        showStatus(message);
        results.setAdapter(new ArrayAdapter<>(results.getContext(), android.R.layout.simple_list_item_1, labels));
        results.setVisibility(labels.isEmpty() ? View.GONE : View.VISIBLE);
        results.setOnItemClickListener((parent, view, position, id) -> select.accept(position));
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
