package ru.example.childwatch.designsystem;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.BooleanSupplier;

/** Recognition is a draft action; text leaves this dialog only through the explicit send button. */
public final class ChatTranscriptionDialog implements AutoCloseable {
    public interface Actions {
        void request(); void cancel(); void edited(String value); void send(String value); void closed();
    }
    private final AlertDialog dialog;
    private final BooleanSupplier scopeCurrent;
    private final Actions actions;
    private final TextView status;
    private final EditText editor;
    private final ProgressBar progress;
    private final Button request, cancel, send;
    private boolean closed, bindingText, initializedText, userEdited, sending, textEnqueued;
    private String state = "EMPTY";
    public ChatTranscriptionDialog(Context context, BooleanSupplier scopeCurrent, Actions actions) {
        this.scopeCurrent = scopeCurrent; this.actions = actions;
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        status = new TextView(context); status.setTextSize(16); layout.addView(status);
        progress = new ProgressBar(context); progress.setVisibility(View.GONE); layout.addView(progress);
        editor = new EditText(context); editor.setMinLines(3); editor.setMaxLines(8);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setHint(R.string.chat_transcription_editor_hint); layout.addView(editor);
        request = button(context, layout, R.string.chat_transcription_request);
        cancel = button(context, layout, R.string.chat_transcription_cancel);
        send = button(context, layout, R.string.chat_transcription_send);
        TextView hint = new TextView(context); hint.setText(R.string.chat_transcription_hint); layout.addView(hint);
        ScrollView scroll = new ScrollView(context); scroll.addView(layout);
        dialog = new AlertDialog.Builder(context).setTitle(R.string.chat_transcription_title).setView(scroll)
            .setNegativeButton(R.string.chat_transcription_close, (d, which) -> close()).create();
        dialog.setOnDismissListener(d -> close());
        request.setOnClickListener(v -> { if (valid()) { request.setEnabled(false); actions.request(); } });
        cancel.setOnClickListener(v -> { if (valid()) { cancel.setEnabled(false); actions.cancel(); } });
        send.setOnClickListener(v -> {
            if (!valid() || sending || !"SUCCEEDED".equals(state) || editor.getText().toString().trim().isEmpty()) return;
            sending = true; enableButtons(); actions.send(editor.getText().toString());
        });
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable value) {
                if (!bindingText && initializedText && valid()) { userEdited = true; actions.edited(value.toString()); }
                enableButtons();
            }
        });
        render("EMPTY", null, null, false, null, false);
    }
    public void show() { dialog.show(); }
    public void render(String state, String recognized, String edited, boolean isEdited, String error, boolean textEnqueued) {
        if (closed || !valid()) return;
        this.state = state;
        this.textEnqueued = textEnqueued;
        if ("TRANSCRIPTION_ACCESS_DENIED".equals(error)) {
            bindingText = true; editor.setText(""); bindingText = false;
            initializedText = false; userEdited = false;
        }
        boolean busy = "QUEUED".equals(state) || "RUNNING".equals(state) || "CANCEL_REQUESTED".equals(state);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setText(textEnqueued ? R.string.chat_transcription_enqueued : error != null && !error.isEmpty() ? errorText(error, state) : statusText(state));
        editor.setVisibility("SUCCEEDED".equals(state) ? View.VISIBLE : View.GONE);
        if (!initializedText && "SUCCEEDED".equals(state)) {
            bindingText = true; editor.setText(isEdited ? edited == null ? "" : edited : recognized == null ? "" : recognized);
            initializedText = true; userEdited = isEdited; bindingText = false;
        }
        request.setText("LOCAL_PENDING".equals(state) ? R.string.chat_transcription_retry : R.string.chat_transcription_request);
        request.setVisibility("EMPTY".equals(state) || "LOCAL_PENDING".equals(state) ? View.VISIBLE : View.GONE);
        cancel.setVisibility(busy || "LOCAL_PENDING".equals(state) ? View.VISIBLE : View.GONE);
        send.setVisibility("SUCCEEDED".equals(state) ? View.VISIBLE : View.GONE);
        enableButtons();
    }
    public void failedSending() { sending = false; enableButtons(); }
    public String editedText() { return initializedText && userEdited ? editor.getText().toString() : null; }
    private void enableButtons() {
        request.setEnabled(!sending); cancel.setEnabled(!sending);
        send.setEnabled(!sending && !textEnqueued && "SUCCEEDED".equals(state) && !editor.getText().toString().trim().isEmpty());
        editor.setEnabled(!sending && !textEnqueued);
    }
    private boolean valid() { if (!scopeCurrent.getAsBoolean()) { close(); return false; } return true; }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (scopeCurrent.getAsBoolean() && initializedText && userEdited) actions.edited(editor.getText().toString());
        if (dialog.isShowing()) dialog.dismiss(); actions.closed();
    }
    private static Button button(Context context, LinearLayout layout, int text) {
        Button value = new Button(context); value.setText(text); value.setMinHeight((int)(48 * context.getResources().getDisplayMetrics().density));
        layout.addView(value, new LinearLayout.LayoutParams(-1, -2)); return value;
    }
    private static int statusText(String state) {
        switch (state) {
            case "LOCAL_PENDING": return R.string.chat_transcription_pending;
            case "QUEUED": return R.string.chat_transcription_queued;
            case "RUNNING": return R.string.chat_transcription_running;
            case "CANCEL_REQUESTED": return R.string.chat_transcription_cancelling;
            case "SUCCEEDED": return R.string.chat_transcription_ready;
            case "FAILED": return R.string.chat_transcription_failed;
            case "CANCELLED": return R.string.chat_transcription_cancelled;
            default: return R.string.chat_transcription_start_hint;
        }
    }
    private static int errorText(String error, String state) {
        switch (error) {
            case "TRANSCRIPTION_TIMEOUT": return R.string.chat_transcription_timeout;
            case "TRANSCRIPTION_NOT_READY": case "TRANSCRIPTION_SERVER_UNAVAILABLE": case "RESOURCE_BENCHMARK_REQUIRED": return R.string.chat_transcription_not_ready;
            case "TRANSCRIPTION_ACCESS_DENIED": case "TRANSCRIPTION_HTTP_403": case "TRANSCRIPTION_HTTP_401": return R.string.chat_transcription_denied;
            case "AUDIO_TOO_LONG": case "VOICE_DURATION_LIMIT": case "TRANSCRIPTION_INVALID_DURATION": return R.string.chat_transcription_too_long;
            case "NO_SPEECH": return R.string.chat_transcription_no_speech;
            case "INVALID_AUDIO": case "TRANSCRIPTION_INVALID_AUDIO": return R.string.chat_transcription_invalid_audio;
            default: return "FAILED".equals(state) ? R.string.chat_transcription_failed : R.string.chat_transcription_network;
        }
    }
}
