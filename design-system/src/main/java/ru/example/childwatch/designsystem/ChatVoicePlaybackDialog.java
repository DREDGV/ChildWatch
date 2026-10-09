package ru.example.childwatch.designsystem;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;

/** Explicit local playback with bounded seeking; caller closes on background. */
public final class ChatVoicePlaybackDialog implements AutoCloseable {
    private final ChatVoiceRecordingPlayer player;
    private final AlertDialog dialog;
    private final Runnable onClose;
    private boolean closed;
    public ChatVoicePlaybackDialog(Context context, File file, Runnable onClose) {
        this.onClose = onClose;
        player = new ChatVoiceRecordingPlayer(context);
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        TextView position = new TextView(context); position.setTextSize(16); layout.addView(position);
        SeekBar seek = new SeekBar(context); seek.setContentDescription(context.getString(R.string.chat_voice_duration, "")); layout.addView(seek);
        Button play = new Button(context); play.setText(R.string.chat_voice_play); layout.addView(play);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context); scroll.addView(layout);
        dialog = new AlertDialog.Builder(context).setTitle(R.string.chat_voice_title).setView(scroll)
            .setNegativeButton(android.R.string.ok, (d, which) -> close()).create();
        dialog.setOnDismissListener(d -> close());
        play.setOnClickListener(v -> {
            if (player.snapshot().state == ChatVoiceRecordingPlayer.State.PLAYING) player.pause(); else player.play();
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar view, int value, boolean fromUser) { if (fromUser) player.seekTo(value); }
            public void onStartTrackingTouch(SeekBar view) { }
            public void onStopTrackingTouch(SeekBar view) { }
        });
        player.setListener(snapshot -> {
            boolean ready = snapshot.state != ChatVoiceRecordingPlayer.State.EMPTY && snapshot.state != ChatVoiceRecordingPlayer.State.PREPARING && snapshot.state != ChatVoiceRecordingPlayer.State.ERROR;
            play.setEnabled(ready); seek.setEnabled(ready);
            play.setText(snapshot.state == ChatVoiceRecordingPlayer.State.PLAYING ? R.string.chat_voice_pause : R.string.chat_voice_play);
            if (snapshot.state == ChatVoiceRecordingPlayer.State.ERROR) position.setText(R.string.chat_voice_unavailable);
            else position.setText(ChatVoiceRecordingDialog.time(snapshot.positionMs) + " / " + ChatVoiceRecordingDialog.time(snapshot.durationMs));
            seek.setMax((int)snapshot.durationMs); seek.setProgress((int)snapshot.positionMs);
        });
        player.load(file);
    }
    public void show() { dialog.show(); }
    @Override public void close() {
        if (closed) return;
        closed = true; player.close();
        if (dialog.isShowing()) dialog.dismiss();
        onClose.run();
    }
}
