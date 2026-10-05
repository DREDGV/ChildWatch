package ru.example.parentwatch.management

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.example.parentwatch.service.CameraService
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.MonitoringRecovery
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.utils.AppVisibilityTracker
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run prepareFixture, reboot externally, then captureAfterBoot. Never run on a real family device. */
@RunWith(AndroidJUnit4::class)
class ManagedBootCaptureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireIsolatedEmulator() {
        assertTrue("Only an isolated emulator may use this fixture", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertTrue("Android must have enrolled ChildDevice as device owner", ManagedDeviceAccess.isDeviceOwner(context))
        assertFalse("The child UI must remain closed", AppVisibilityTracker.isVisible())
    }

    @Test fun prepareFixture() {
        requireIsolatedEmulator()
        val sessions = ChildActiveSessionStore(context)
        sessions.applySession(sessions.buildSession(
            name = "Isolated boot test", serverUrl = "http://127.0.0.1:9",
            ownChildDeviceId = "isolated-managed-boot-probe", linkedParentDeviceId = ""
        ))
        MonitoringRecovery.enable(context)
        context.startForegroundService(Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        })
    }

    @Test fun captureAfterBoot() {
        requireIsolatedEmulator()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!LocationService.isMonitoringActive && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Boot must restore the actual monitoring service", LocationService.isMonitoringActive)
        // This action is issued inside the owning app, without an Activity or shell service launch.
        context.startForegroundService(Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START_AUDIO_STREAM
        })
        SystemClock.sleep(1500)
        val min = AudioRecord.getMinBufferSize(24_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        assertTrue(min > 0)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 24_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 3840))
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, recorder.state)
            recorder.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, recorder.recordingState)
            val count = recorder.read(ByteArray(960), 0, 960, AudioRecord.READ_BLOCKING)
            assertTrue("Microphone must supply frames", count > 0)
            assertFalse("Android must not silence this capture", recorder.activeRecordingConfiguration?.isClientSilenced == true)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        val camera = CameraService(context)
        val delivered = CountDownLatch(1)
        var photo: File? = null
        try {
            camera.capturePhoto(CameraService.CameraFacing.BACK) {
                photo = it
                delivered.countDown()
            }
            assertTrue("Camera must complete without opening the child UI", delivered.await(20, TimeUnit.SECONDS))
            assertNotNull("Photo failure: ${camera.consumeLastFailureReason()}", photo)
            assertTrue("A real JPEG must be produced", photo!!.length() > 0)
            assertFalse(AppVisibilityTracker.isVisible())
        } finally {
            camera.release()
        }
    }
}
