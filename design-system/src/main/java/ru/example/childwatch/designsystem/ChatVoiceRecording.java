package ru.example.childwatch.designsystem;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/** Foreground, explicit chat recording. Call interrupt(BACKGROUND) from onPause.
 * This component neither requests permission nor starts a background microphone service.
 * Completed drafts survive recreation in an exact account/conversation scope.
 * All public methods and callbacks belong to the main thread. */
public final class ChatVoiceRecording implements AutoCloseable {
    public static final long MAX_DURATION_MS = 180_000L;
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long START_FREE_BYTES = 2L * 1024 * 1024;
    private static final long STOP_FREE_BYTES = 512L * 1024;
    public enum State { EMPTY, RECORDING, DRAFT, ERROR }
    public enum Reason { NONE, BACKGROUND, AUDIO_INTERRUPTED, MICROPHONE_DENIED, MICROPHONE_BUSY,
        NO_SPACE, LIMIT_REACHED, TOO_SHORT, RECORDER_FAILED, RESTORED_AFTER_INTERRUPTION, DRAFT_EXISTS }
    public interface Listener { void onChanged(Snapshot snapshot); }
    public static final class Draft {
        public final File file;
        public final long durationMs, sizeBytes;
        private Draft(File file, long durationMs) {
            this.file = file; this.durationMs = durationMs; this.sizeBytes = file.length();
        }
    }
    public static final class Snapshot {
        public final State state;
        public final Reason reason;
        public final Draft draft;
        public final long elapsedMs;
        private Snapshot(State state, Reason reason, Draft draft, long elapsedMs) {
            this.state = state; this.reason = reason; this.draft = draft; this.elapsedMs = elapsedMs;
        }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File directory;
    private final SharedPreferences preferences;
    private final AudioManager audio;
    private AudioFocusRequest focus;
    private MediaRecorder recorder;
    private File active;
    private long startedMs;
    private State state = State.EMPTY;
    private Reason reason = Reason.NONE;
    private Draft draft;
    private Listener listener;
    private boolean closed;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (state != State.RECORDING) return;
            if (audio != null && audio.getMode() != AudioManager.MODE_NORMAL) {
                finish(Reason.AUDIO_INTERRUPTED); return;
            }
            if (directory.getUsableSpace() < STOP_FREE_BYTES) { finish(Reason.NO_SPACE); return; }
            if (elapsedMs() >= MAX_DURATION_MS) { finish(Reason.LIMIT_REACHED); return; }
            publish(); main.postDelayed(this, 250);
        }
    };

    public ChatVoiceRecording(Context context, String exactScopeAndConversation) {
        this.context = context.getApplicationContext();
        if (exactScopeAndConversation == null || exactScopeAndConversation.trim().isEmpty())
            throw new IllegalArgumentException("Exact chat scope is required");
        String scope = digest(exactScopeAndConversation);
        directory = new File(this.context.getFilesDir(), "chat-voice/" + scope);
        preferences = this.context.getSharedPreferences("chat_voice_" + scope, Context.MODE_PRIVATE);
        audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        restore();
    }
    public void setListener(Listener listener) { checkThread(); this.listener = listener; publish(); }
    public Snapshot snapshot() { return new Snapshot(state, reason, draft, elapsedMs()); }
    public boolean start() {
        checkThread();
        if (closed || state == State.RECORDING) return false;
        if (draft != null) { reason = Reason.DRAFT_EXISTS; publish(); return false; }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return fail(Reason.MICROPHONE_DENIED);
        if ((!directory.isDirectory() && !directory.mkdirs()) || directory.getUsableSpace() < START_FREE_BYTES)
            return fail(Reason.NO_SPACE);
        if (audio != null && audio.getMode() != AudioManager.MODE_NORMAL) return fail(Reason.MICROPHONE_BUSY);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    interrupt(Reason.AUDIO_INTERRUPTED);
            }, main).build();
        if (audio != null && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            abandonFocus(); return fail(Reason.MICROPHONE_BUSY);
        }
        active = new File(directory, UUID.randomUUID() + ".m4a");
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64_000);
            recorder.setAudioSamplingRate(44_100);
            // Leave encoder finalization margin inside the server's strict three-minute limit.
            recorder.setMaxDuration((int) MAX_DURATION_MS - 500);
            recorder.setMaxFileSize(MAX_BYTES);
            recorder.setOutputFile(active.getAbsolutePath());
            recorder.setOnInfoListener((source, what, extra) -> {
                if (source == recorder && (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) finish(Reason.LIMIT_REACHED);
            });
            recorder.setOnErrorListener((source, what, extra) -> {
                if (source == recorder) finish(Reason.RECORDER_FAILED);
            });
            if (Build.VERSION.SDK_INT >= 29) {
                recordingCallback = new AudioManager.AudioRecordingCallback() {
                    @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                        for (AudioRecordingConfiguration config : configs)
                            if (config.isClientSilenced()) { interrupt(Reason.AUDIO_INTERRUPTED); break; }
                    }
                };
                recorder.registerAudioRecordingCallback(context.getMainExecutor(), recordingCallback);
            }
            recorder.prepare();
            recorder.start();
            startedMs = SystemClock.elapsedRealtime();
            state = State.RECORDING; reason = Reason.NONE;
            // Store the candidate before yielding. A killed process can recover only a valid finalized container.
            if (!preferences.edit().putString("file", active.getName()).putBoolean("active", true).commit()) {
                finish(Reason.NO_SPACE); return false;
            }
            main.post(tick); publish(); return true;
        } catch (SecurityException denied) {
            releaseRecorder(); deleteActive(); return fail(Reason.MICROPHONE_DENIED);
        } catch (Exception failure) {
            releaseRecorder(); deleteActive(); return fail(Reason.RECORDER_FAILED);
        }
    }
    public Draft stop() { checkThread(); if (state == State.RECORDING) finish(Reason.NONE); return draft; }
    public void interrupt(Reason why) { checkThread(); if (state == State.RECORDING) finish(why); }
    public void cancel() {
        checkThread(); releaseRecorder(); deleteActive();
        if (draft != null) draft.file.delete();
        draft = null; preferences.edit().clear().commit(); state = State.EMPTY; reason = Reason.NONE; publish();
    }
    /** Call only after the attachment service has durably copied/enqueued this recording. */
    public void consumeDraft() { cancel(); }
    /** A late durable-copy acknowledgement must never consume a different recording. */
    public void consumeDraft(File expected) {
        checkThread();
        if (draft != null && draft.file.equals(expected)) cancel();
    }
    @Override public void close() {
        checkThread(); if (state == State.RECORDING) finish(Reason.BACKGROUND);
        closed = true; releaseRecorder(); listener = null;
    }
    private void finish(Reason why) {
        if (state != State.RECORDING) return;
        File completed = active;
        try { if (recorder != null) recorder.stop(); } catch (RuntimeException tooShortOrLost) { }
        releaseRecorder(); active = null;
        long duration = duration(completed);
        if (valid(completed, duration)) {
            draft = new Draft(completed, duration); state = State.DRAFT; reason = why;
            if (!preferences.edit().putString("file", completed.getName()).putBoolean("active", false).commit())
                reason = Reason.NO_SPACE;
        } else {
            if (completed != null) completed.delete();
            preferences.edit().clear().commit(); state = State.ERROR;
            reason = why == Reason.NONE ? Reason.TOO_SHORT : why;
        }
        publish();
    }
    private void restore() {
        String name = preferences.getString("file", null);
        if (name == null) return;
        if (!name.matches("[0-9a-fA-F-]{36}\\.m4a")) { preferences.edit().clear().commit(); return; }
        File candidate = new File(directory, name);
        long duration = duration(candidate);
        if (valid(candidate, duration)) {
            draft = new Draft(candidate, duration); state = State.DRAFT;
            reason = preferences.getBoolean("active", false) ? Reason.RESTORED_AFTER_INTERRUPTION : Reason.NONE;
            preferences.edit().putBoolean("active", false).commit();
        } else {
            candidate.delete(); preferences.edit().clear().commit();
            state = State.ERROR; reason = Reason.RECORDER_FAILED;
        }
    }
    private static boolean valid(File file, long duration) {
        return file != null && file.isFile() && file.length() > 0 && file.length() <= MAX_BYTES &&
            duration > 0 && duration <= MAX_DURATION_MS;
    }
    private static long duration(File file) {
        if (file == null || !file.isFile()) return 0;
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            return Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception invalid) { return 0; }
        finally { try { metadata.release(); } catch (Exception ignored) { } }
    }
    private void releaseRecorder() {
        main.removeCallbacks(tick);
        MediaRecorder old = recorder; recorder = null;
        if (old != null) {
            if (Build.VERSION.SDK_INT >= 29 && recordingCallback != null)
                try { old.unregisterAudioRecordingCallback(recordingCallback); } catch (RuntimeException ignored) { }
            recordingCallback = null;
            try { old.reset(); } catch (RuntimeException ignored) { }
            try { old.release(); } catch (RuntimeException ignored) { }
        }
        abandonFocus();
    }
    private void abandonFocus() {
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
        focus = null;
    }
    private void deleteActive() { if (active != null) active.delete(); active = null; preferences.edit().clear().commit(); }
    private boolean fail(Reason why) { state = State.ERROR; reason = why; publish(); return false; }
    private long elapsedMs() {
        if (state != State.RECORDING) return draft == null ? 0 : draft.durationMs;
        return Math.max(0, Math.min(MAX_DURATION_MS, SystemClock.elapsedRealtime() - startedMs));
    }
    private void publish() { if (listener != null) listener.onChanged(snapshot()); }
    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Use chat recorder on main thread");
    }
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
