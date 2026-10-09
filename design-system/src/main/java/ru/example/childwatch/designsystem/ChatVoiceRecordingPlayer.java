package ru.example.childwatch.designsystem;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.File;

/** Local draft preview. Caller pauses on background and closes when its UI is destroyed. */
public final class ChatVoiceRecordingPlayer implements AutoCloseable {
    public enum State { EMPTY, PREPARING, READY, PLAYING, PAUSED, COMPLETED, ERROR }
    public interface Listener { void onChanged(Snapshot snapshot); }
    public static final class Snapshot {
        public final State state;
        public final long positionMs, durationMs;
        private Snapshot(State state, long positionMs, long durationMs) {
            this.state = state; this.positionMs = positionMs; this.durationMs = durationMs;
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private AudioFocusRequest focus;
    private MediaPlayer player;
    private State state = State.EMPTY;
    private Listener listener;
    private boolean playWhenReady;
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (state == State.PLAYING) { publish(); main.postDelayed(this, 250); } }
    };
    public ChatVoiceRecordingPlayer(Context context) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }
    public void setListener(Listener listener) { checkThread(); this.listener = listener; publish(); }
    public void load(File file) {
        checkThread(); release(); playWhenReady = false;
        if (file == null || !file.isFile() || file.length() == 0) { state = State.ERROR; publish(); return; }
        MediaPlayer candidate = new MediaPlayer(); player = candidate; state = State.PREPARING;
        try {
            candidate.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            candidate.setDataSource(file.getAbsolutePath());
            candidate.setOnPreparedListener(value -> {
                if (value != player) return;
                state = State.READY; publish(); if (playWhenReady) play();
            });
            candidate.setOnCompletionListener(value -> {
                if (value != player) return;
                state = State.COMPLETED; main.removeCallbacks(tick); abandonFocus(); publish();
            });
            candidate.setOnErrorListener((value, what, extra) -> {
                if (value == player) { release(); state = State.ERROR; publish(); }
                return true;
            });
            candidate.prepareAsync(); publish();
        } catch (Exception invalid) { release(); state = State.ERROR; publish(); }
    }
    public void play() {
        checkThread();
        if (state == State.PREPARING) { playWhenReady = true; return; }
        if (player == null || state == State.ERROR || state == State.EMPTY) return;
        try {
            if (focus == null && audio != null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause();
                    }, main).build();
                if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    abandonFocus(); playWhenReady = false; state = State.PAUSED; publish(); return;
                }
            }
            if (state == State.COMPLETED) player.seekTo(0);
            player.start(); state = State.PLAYING; playWhenReady = false; main.removeCallbacks(tick);
            main.post(tick); publish();
        } catch (RuntimeException invalid) { release(); state = State.ERROR; publish(); }
    }
    public void pause() {
        checkThread(); playWhenReady = false;
        if (state != State.PLAYING || player == null) { abandonFocus(); return; }
        try { player.pause(); state = State.PAUSED; } catch (RuntimeException invalid) { release(); state = State.ERROR; }
        main.removeCallbacks(tick); abandonFocus(); publish();
    }
    public void seekTo(long positionMs) {
        checkThread();
        if (player == null || state == State.PREPARING || state == State.ERROR) return;
        try {
            long bounded = Math.max(0, Math.min(player.getDuration(), positionMs));
            player.seekTo(bounded, MediaPlayer.SEEK_CLOSEST);
            if (state == State.COMPLETED) state = State.PAUSED;
            publish();
        } catch (RuntimeException invalid) { release(); state = State.ERROR; publish(); }
    }
    public Snapshot snapshot() {
        long position = 0, duration = 0;
        if (player != null && state != State.PREPARING && state != State.ERROR)
            try { position = player.getCurrentPosition(); duration = player.getDuration(); } catch (RuntimeException ignored) { }
        return new Snapshot(state, position, duration);
    }
    @Override public void close() { checkThread(); release(); state = State.EMPTY; listener = null; }
    private void release() {
        main.removeCallbacks(tick); playWhenReady = false; abandonFocus();
        MediaPlayer previous = player; player = null;
        if (previous != null) { previous.setOnPreparedListener(null); previous.setOnCompletionListener(null);
            previous.setOnErrorListener(null); previous.release(); }
    }
    private void abandonFocus() {
        AudioFocusRequest previous = focus; focus = null;
        if (audio != null && previous != null) audio.abandonAudioFocusRequest(previous);
    }
    private void publish() { if (listener != null) listener.onChanged(snapshot()); }
    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Use chat player on main thread");
    }
}
