package ru.example.parentwatch.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.example.parentwatch.network.NetworkHelper
import java.util.concurrent.atomic.AtomicLong

/** Exercises the active-loop branch without opening the microphone or sending commands. */
@RunWith(AndroidJUnit4::class)
class AudioStreamRecorderResumeTest {
    @Test fun duplicateResumeDoesNotInvalidateTheRunningLoop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = AudioStreamRecorder(context, NetworkHelper(context))
        field("streamingDesired").setBoolean(recorder, true)
        field("isRecording").setBoolean(recorder, true)
        val generation = field("stateGeneration").get(recorder) as AtomicLong
        generation.set(7L)

        recorder.resumeCapture()
        recorder.resumeCapture()

        assertEquals("Repeated resume must preserve the active capture generation", 7L, generation.get())
        val shouldCapture = AudioStreamRecorder::class.java
            .getDeclaredMethod("shouldCapture", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        assertTrue("The existing loop must remain eligible to deliver frames", shouldCapture.invoke(recorder, 7L) as Boolean)
        assertTrue(field("isRecording").getBoolean(recorder))
    }

    @Test fun resumeDoesNotEnableAStoppedStream() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = AudioStreamRecorder(context, NetworkHelper(context))
        val generation = field("stateGeneration").get(recorder) as AtomicLong
        generation.set(7L)

        recorder.resumeCapture()

        assertEquals(7L, generation.get())
        assertTrue(!recorder.isStreamingDesired())
        assertTrue(!field("isRecording").getBoolean(recorder))
    }

    private fun field(name: String) = AudioStreamRecorder::class.java
        .getDeclaredField(name).apply { isAccessible = true }
}
