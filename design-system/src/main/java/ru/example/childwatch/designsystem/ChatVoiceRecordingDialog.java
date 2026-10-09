package ru.example.childwatch.designsystem;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.Locale;

/** Small explicit recording sheet used by both existing chat composers. */
public final class ChatVoiceRecordingDialog implements AutoCloseable {
    public interface OnReady { void accept(File file, long durationMs, Runnable onCopied); }
    private final ChatVoiceRecording recording;
    private final java.util.function.BooleanSupplier scopeCurrent;
    private final AlertDialog dialog;
    private final TextView status;
    private final Button record, listen, remove, use;
    private ChatVoicePlaybackDialog playback;
    private boolean closed, copying;
    public ChatVoiceRecordingDialog(Context context, String scope, java.util.function.BooleanSupplier scopeCurrent, OnReady callback) {
        this.scopeCurrent = scopeCurrent;
        recording = new ChatVoiceRecording(context, scope);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        status = new TextView(context); status.setTextSize(16); layout.addView(status);
        record = button(context, layout, R.string.chat_voice_record);
        listen = button(context, layout, R.string.chat_voice_listen);
        remove = button(context, layout, R.string.chat_voice_delete);
        use = button(context, layout, R.string.chat_voice_use);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context); scroll.addView(layout);
        dialog = new AlertDialog.Builder(context).setTitle(R.string.chat_voice_title).setView(scroll)
            .setNegativeButton(R.string.chat_voice_keep, (ignored, which) -> close()).create();
        record.setOnClickListener(v -> {
            if (!scopeCurrent.getAsBoolean()) { close(); return; }
            if (recording.snapshot().state == ChatVoiceRecording.State.RECORDING) recording.stop();
            else recording.start();
        });
        listen.setOnClickListener(v -> {
            if (!scopeCurrent.getAsBoolean()) { close(); return; }
            ChatVoiceRecording.Draft draft = recording.snapshot().draft;
            if (draft != null) {
                if (playback != null) playback.close();
                playback = new ChatVoicePlaybackDialog(context, draft.file, () -> {});
                playback.show();
            }
        });
        remove.setOnClickListener(v -> {
            if (!scopeCurrent.getAsBoolean()) { close(); return; }
            if (playback != null) playback.close();
            recording.cancel();
        });
        use.setOnClickListener(v -> {
            if (!scopeCurrent.getAsBoolean()) { close(); return; }
            ChatVoiceRecording.Draft draft = recording.snapshot().draft;
            if (draft == null || copying) return;
            copying = true;
            record.setEnabled(false); listen.setEnabled(false); remove.setEnabled(false); use.setEnabled(false);
            callback.accept(draft.file, draft.durationMs, () -> {
                // Callback must run on the main thread only after the durable attachment copy succeeded.
                recording.consumeDraft(draft.file); close();
            });
        });
        dialog.setOnDismissListener(v -> close());
        recording.setListener(snapshot -> {
            if (!scopeCurrent.getAsBoolean()) { close(); return; }
            boolean active = snapshot.state == ChatVoiceRecording.State.RECORDING;
            boolean ready = snapshot.draft != null;
            long duration = ready ? snapshot.draft.durationMs : snapshot.elapsedMs;
            status.setText(context.getString(R.string.chat_voice_duration, time(duration)) + "\n" +
                context.getString(reasonText(snapshot.reason, active, ready)));
            record.setText(active ? R.string.chat_voice_stop : R.string.chat_voice_record);
            record.setEnabled(!ready && !copying);
            listen.setEnabled(!copying); remove.setEnabled(!copying);
            listen.setVisibility(ready ? View.VISIBLE : View.GONE);
            remove.setVisibility(ready ? View.VISIBLE : View.GONE);
            use.setVisibility(ready ? View.VISIBLE : View.GONE);
            use.setEnabled(ready && !copying);
        });
    }
    public void show() { dialog.show(); }
    public void failedCopy() {
        copying = false;
        if (!closed) { boolean ready = recording.snapshot().draft != null;
            use.setEnabled(ready); listen.setEnabled(ready); remove.setEnabled(ready); record.setEnabled(!ready); }
    }
    public void interrupt() { recording.interrupt(ChatVoiceRecording.Reason.BACKGROUND); close(); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (playback != null) playback.close();
        recording.close();
        if (dialog.isShowing()) dialog.dismiss();
    }
    private static Button button(Context context, LinearLayout layout, int text) {
        Button button = new Button(context); button.setText(text); button.setMinHeight((int)(48 * context.getResources().getDisplayMetrics().density));
        layout.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    static String time(long ms) { return String.format(Locale.getDefault(), "%d:%02d", ms / 60000, (ms / 1000) % 60); }
    private static int reasonText(ChatVoiceRecording.Reason reason, boolean active, boolean ready) {
        switch (reason) {
            case MICROPHONE_DENIED: return R.string.chat_voice_permission;
            case MICROPHONE_BUSY: return R.string.chat_voice_busy;
            case NO_SPACE: return R.string.chat_voice_no_space;
            case TOO_SHORT: return R.string.chat_voice_too_short;
            case RECORDER_FAILED: return R.string.chat_voice_failed;
            case AUDIO_INTERRUPTED: case BACKGROUND: case RESTORED_AFTER_INTERRUPTION: return R.string.chat_voice_interrupted;
            case LIMIT_REACHED: return R.string.chat_voice_limit;
            default: return active ? R.string.chat_voice_recording : ready ? R.string.chat_voice_draft : R.string.chat_voice_hint;
        }
    }
}
