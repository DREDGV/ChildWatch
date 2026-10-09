package ru.example.childwatch.designsystem;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Isolated private fixtures only; never turns on the microphone or touches user chat data. */
@RunWith(AndroidJUnit4.class)
public class ChatVoiceRecordingTest {
    @Test public void interruptedInvalidContainerIsRemovedOnlyInsideItsScope() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String scope = "voice-fixture-" + UUID.randomUUID();
        String key = digest(scope);
        File directory = new File(context.getFilesDir(), "chat-voice/" + key);
        assertTrue(directory.mkdirs());
        File invalid = new File(directory, UUID.randomUUID() + ".m4a");
        try (FileOutputStream output = new FileOutputStream(invalid)) { output.write(new byte[]{1, 2, 3}); }
        SharedPreferences prefs = context.getSharedPreferences("chat_voice_" + key, Context.MODE_PRIVATE);
        assertTrue(prefs.edit().putString("file", invalid.getName()).putBoolean("active", true).commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ChatVoiceRecording other = new ChatVoiceRecording(context, scope + "-other");
            assertEquals(ChatVoiceRecording.State.EMPTY, other.snapshot().state);
            assertTrue(invalid.exists()); // Another account/conversation cannot consume our pending file.
            other.close();
            ChatVoiceRecording restored = new ChatVoiceRecording(context, scope);
            assertEquals(ChatVoiceRecording.State.ERROR, restored.snapshot().state);
            assertEquals(ChatVoiceRecording.Reason.RECORDER_FAILED, restored.snapshot().reason);
            assertFalse(invalid.exists());
            assertFalse(prefs.contains("file"));
            restored.close();
            restored.consumeDraft(); // A successful copy may finish after its dialog was closed.
            assertEquals(ChatVoiceRecording.State.EMPTY, restored.snapshot().state);
            assertFalse(restored.start());
        });
        assertTrue(directory.delete());
    }
    @Test public void traversalCandidateNeverReadsOrDeletesOutsideItsPrivateDirectory() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String scope = "voice-fixture-" + UUID.randomUUID();
        String key = digest(scope);
        File sentinel = new File(context.getFilesDir(), "voice-sentinel-" + UUID.randomUUID());
        try (FileOutputStream output = new FileOutputStream(sentinel)) { output.write(7); }
        SharedPreferences prefs = context.getSharedPreferences("chat_voice_" + key, Context.MODE_PRIVATE);
        assertTrue(prefs.edit().putString("file", "../../" + sentinel.getName()).commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ChatVoiceRecording restored = new ChatVoiceRecording(context, scope);
            assertEquals(ChatVoiceRecording.State.EMPTY, restored.snapshot().state);
            assertTrue(sentinel.exists());
            restored.close();
        });
        assertFalse(prefs.contains("file"));
        assertTrue(sentinel.delete());
    }
    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }
}
