package ru.example.parentwatch.audio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.example.parentwatch.network.NetworkHelper
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.utils.AppVisibilityTracker
import ru.example.parentwatch.utils.DeviceInfoCollector
import ru.example.parentwatch.utils.RemoteLogger
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Child-side microphone capture for live listening.
 *
 * This implementation intentionally keeps one fixed wire format:
 * - PCM16 mono
 * - 24 kHz
 * - 20 ms frames (960 bytes)
 *
 * The previous mixed fallback/resampling implementation caused startup races
 * and audible artifacts. This version keeps the contract narrow and stable.
 */
class AudioStreamRecorder(
    private val context: Context,
    @Suppress("unused") private val networkHelper: NetworkHelper
) {
    companion object {
        private const val TAG = "AUDIO"
        private const val SAMPLE_RATE = 24_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 20L
        private const val FRAME_BYTES = ((SAMPLE_RATE * CHUNK_DURATION_MS) / 1000).toInt() * 2
        private const val WS_READY_TIMEOUT_MS = 7_000L
        private const val WS_READY_POLL_MS = 100L
        private const val BATTERY_SNAPSHOT_REFRESH_MS = 15_000L
        private const val RECOVERY_NOTIFICATION_ID = 4102
        private const val RECOVERY_CHANNEL_ID = "microphone_recovery_v2"
        private val CAPTURE_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }

    private val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val startInFlight = AtomicBoolean(false)
    private val stateGeneration = AtomicLong(0L)
    private val recoveryLock = Any()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var waitForSocketJob: Job? = null
    private var recoveryJob: Job? = null

    @Volatile private var isRecording = false
    @Volatile private var streamingDesired = false
    @Volatile private var capturePaused = false
    @Volatile private var recoveryAttempt = 0
    @Volatile private var recordingMode = false
    @Volatile private var sequence = 0
    @Volatile private var lastBatteryLevel: Int? = null
    @Volatile private var lastBatteryCharging: Boolean? = null
    @Volatile private var lastBatteryTimestamp: Long = 0L
    @Volatile private var lastBatterySnapshotRefreshAt: Long = 0L

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var hasLoggedSocketNotReady = false

    fun startStreaming(
        deviceId: String,
        serverUrl: String,
        recordingMode: Boolean = false,
        sampleRate: Int = SAMPLE_RATE
    ) {
        val normalizedRate = sanitizeSampleRate(sampleRate)
        val wasDesired = streamingDesired
        streamingDesired = true
        if (!wasDesired) {
            capturePaused = false
            stateGeneration.incrementAndGet()
            recoveryAttempt = 0
            sequence = 0
        }
        this.deviceId = deviceId
        this.serverUrl = serverUrl
        this.recordingMode = recordingMode
        this.hasLoggedSocketNotReady = false

        Log.d(TAG, "AUDIO start requested: device=$deviceId sampleRate=$normalizedRate recording=$recordingMode")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Audio stream start requested",
            meta = mapOf(
                "sampleRate" to normalizedRate,
                "recordingMode" to recordingMode
            )
        )

        requestSharedSocketReady()

        if (!isRecording && waitForSocketJob?.isActive != true && !capturePaused) {
            waitForSocketJob = streamScope.launch {
                val ready = waitForWebSocketReady()
                if (!ready) {
                    Log.e(TAG, "AUDIO WS not ready within ${WS_READY_TIMEOUT_MS}ms")
                    RemoteLogger.error(
                        serverUrl = this@AudioStreamRecorder.serverUrl,
                        deviceId = this@AudioStreamRecorder.deviceId,
                        source = TAG,
                        message = "Audio WS was not ready before capture start"
                    )
                    startRecoveryLoop("websocket_timeout")
                    return@launch
                }
                ensureCaptureRunning()
            }
        }
    }

    fun stopStreaming() {
        Log.d(TAG, "AUDIO stop requested")
        streamingDesired = false
        capturePaused = false
        stateGeneration.incrementAndGet()
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        waitForSocketJob?.cancel()
        waitForSocketJob = null
        cancelRecoveryLoop()
        releaseRecorder()
        cancelMicrophoneRecoveryNotification()
        Log.d(TAG, "AUDIO stop OK")
    }

    fun pauseCapture() {
        Log.d(TAG, "AUDIO capture pause requested")
        capturePaused = true
        stateGeneration.incrementAndGet()
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        waitForSocketJob?.cancel()
        waitForSocketJob = null
        cancelRecoveryLoop()
        releaseRecorder()
    }

    fun resumeCapture() {
        if (!streamingDesired) return
        Log.d(TAG, "AUDIO capture resume requested")
        capturePaused = false
        stateGeneration.incrementAndGet()
        ensureCaptureRunning()
        if (!isRecording) {
            startRecoveryLoop("explicit_resume")
        }
    }

    fun retryCaptureAfterForeground() {
        if (!streamingDesired || capturePaused || isRecording) return
        Log.d(TAG, "AUDIO foreground recovery requested")
        stateGeneration.incrementAndGet()
        ensureCaptureRunning()
        if (!isRecording) {
            startRecoveryLoop("foreground_recovery")
        }
    }

    fun setRecordingMode(enabled: Boolean) {
        recordingMode = enabled
        Log.d(TAG, "AUDIO recording mode=$enabled")
    }

    fun updateStreamConfig(newRecordingMode: Boolean, newSampleRate: Int) {
        recordingMode = newRecordingMode
        val normalizedRate = sanitizeSampleRate(newSampleRate)
        if (normalizedRate != SAMPLE_RATE) {
            Log.w(TAG, "AUDIO requested unsupported sampleRate=$newSampleRate, forcing $SAMPLE_RATE")
        }
        if (!isRecording) {
            ensureCaptureRunning()
        }
    }

    fun isActive(): Boolean =
        isRecording ||
            startInFlight.get() ||
            waitForSocketJob?.isActive == true ||
            recoveryJob?.isActive == true ||
            (streamingDesired && capturePaused)

    fun isCapturing(): Boolean =
        isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun isStreamingDesired(): Boolean = streamingDesired

    fun ensureCaptureRunning() {
        if (!streamingDesired || capturePaused || isRecording) return
        val client = WebSocketManager.getClient()
        if (client == null) {
            requestSharedSocketReady()
            startRecoveryLoop("websocket_client_missing")
            return
        }
        if (!client.isReady()) {
            requestSharedSocketReady()
            client.requestRegistration()
            startRecoveryLoop("websocket_not_ready")
            return
        }
        startActualRecording(stateGeneration.get())
    }

    private suspend fun waitForWebSocketReady(): Boolean {
        return withTimeoutOrNull(WS_READY_TIMEOUT_MS) {
            while (!WebSocketManager.isReady()) {
                requestSharedSocketReady()
                delay(WS_READY_POLL_MS)
            }
            true
        } ?: false
    }

    private fun startActualRecording(expectedGeneration: Long) {
        val url = serverUrl
        val id = deviceId

        if (!shouldCapture(expectedGeneration)) return

        if (!startInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "AUDIO start already in progress")
            return
        }

        if (!hasRecordAudioPermission()) {
            emitCaptureDiagnostic("permission_missing", mapOf("permission" to "RECORD_AUDIO"))
            Log.e(TAG, "AUDIO RECORD_AUDIO permission missing")
            startInFlight.set(false)
            startRecoveryLoop("permission_missing")
            return
        }

        if (!WebSocketManager.isReady()) {
            startInFlight.set(false)
            requestSharedSocketReady()
            Log.w(TAG, "AUDIO WS not ready, capture start postponed")
            startRecoveryLoop("websocket_lost_before_capture")
            return
        }

        try {
            initializeAudioRecord()
            val recorder = audioRecord
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                val reason = if (AppVisibilityTracker.isVisible()) {
                    "audio_record_not_initialized"
                } else {
                    "background_microphone_restricted"
                }
                val recoveryNotificationPosted =
                    reason == "background_microphone_restricted" && showMicrophoneRecoveryNotification()
                emitCaptureDiagnostic(
                    reason,
                    mapOf("recoveryNotificationPosted" to recoveryNotificationPosted)
                )
                Log.e(TAG, "AUDIO init failed")
                startRecoveryLoop(reason)
                return
            }

            if (!shouldCapture(expectedGeneration)) {
                Log.d(TAG, "AUDIO start invalidated before capture became active")
                releaseRecorder()
                return
            }

            isRecording = true
            cancelMicrophoneRecoveryNotification()
            Log.d(TAG, "AUDIO init OK: rate=$SAMPLE_RATE frame=$FRAME_BYTES")
            RemoteLogger.info(
                serverUrl = url,
                deviceId = id,
                source = TAG,
                message = "AUDIO init OK",
                meta = mapOf(
                    "sampleRate" to SAMPLE_RATE,
                    "frameBytes" to FRAME_BYTES
                )
            )

            recordingJob?.cancel()
            recordingJob = streamScope.launch {
                while (isRecording && shouldCapture(expectedGeneration)) {
                    recordAndSendChunk()
                }
            }
        } finally {
            startInFlight.set(false)
            if (streamingDesired && !capturePaused && !isRecording) {
                startRecoveryLoop("start_did_not_become_active")
            }
        }
    }

    private suspend fun recordAndSendChunk() {
        val client = WebSocketManager.getClient()
            ?: run {
                requestSharedSocketReady()
                delay(WS_READY_POLL_MS)
                return
            }
        if (!client.isReady()) {
            if (!hasLoggedSocketNotReady) {
                hasLoggedSocketNotReady = true
                Log.w(TAG, "AUDIO WS not ready while sending")
            }
            requestSharedSocketReady()
            client.requestRegistration()
            delay(WS_READY_POLL_MS)
            return
        }

        val audioData = recordChunk() ?: return
        recoveryAttempt = 0
        hasLoggedSocketNotReady = false
        val sentSequence = sequence
        refreshBatterySnapshotIfNeeded()

        client.sendAudioChunk(
            sequence = sentSequence,
            audioData = audioData,
            recording = recordingMode,
            sampleRate = SAMPLE_RATE,
            batteryLevel = lastBatteryLevel,
            isCharging = lastBatteryCharging,
            deviceStatusTimestamp = lastBatteryTimestamp,
            onSuccess = {
                Log.d(TAG, "Sent audio chunk #$sentSequence")
            },
            onError = { error ->
                Log.e(TAG, "AUDIO send failed for chunk #$sentSequence: $error")
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "Failed to send audio chunk",
                    meta = mapOf(
                        "sequence" to sentSequence,
                        "error" to error
                    )
                )
            }
        )

        sequence = sentSequence + 1
    }

    private fun initializeAudioRecord() {
        releaseRecorder()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            Log.e(TAG, "AUDIO invalid min buffer: $minBufferSize")
            return
        }

        val bufferSize = maxOf(minBufferSize * 2, FRAME_BYTES * 4)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        for (source in sources) {
            var candidate: AudioRecord? = null
            try {
                val created = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                candidate = created
                if (created.state != AudioRecord.STATE_INITIALIZED) {
                    continue
                }

                created.startRecording()
                if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    continue
                }
                if (isRecorderSilenced(created)) {
                    Log.w(TAG, "AUDIO source=${audioSourceName(source)} is silenced by Android")
                    continue
                }

                audioRecord = created
                candidate = null
                Log.d(TAG, "AUDIO using source=${audioSourceName(source)} buffer=$bufferSize")
                return
            } catch (e: Exception) {
                Log.w(TAG, "AUDIO init failed for source=${audioSourceName(source)}", e)
            } finally {
                candidate?.let { unusedRecorder ->
                    runCatching {
                        if (unusedRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            unusedRecorder.stop()
                        }
                    }
                    runCatching { unusedRecorder.release() }
                }
            }
        }

        audioRecord = null
    }

    private fun recordChunk(): ByteArray? {
        val recorder = audioRecord ?: run {
            handleCaptureFailure("audio_recorder_missing")
            return null
        }
        if (isRecorderSilenced(recorder)) {
            val appVisible = AppVisibilityTracker.isVisible()
            val recoveryNotificationPosted = if (!appVisible) {
                showMicrophoneRecoveryNotification()
            } else {
                false
            }
            handleCaptureFailure(
                "audio_capture_silenced",
                mapOf(
                    "appVisible" to appVisible,
                    "recoveryNotificationPosted" to recoveryNotificationPosted
                )
            )
            return null
        }
        val buffer = ByteArray(FRAME_BYTES)

        return try {
            var offset = 0
            var emptyReads = 0

            while (offset < FRAME_BYTES && isRecording) {
                val remaining = FRAME_BYTES - offset
                val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    recorder.read(buffer, offset, remaining, AudioRecord.READ_BLOCKING)
                } else {
                    recorder.read(buffer, offset, remaining)
                }

                when {
                    read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AUDIO read error: $read")
                        handleCaptureFailure("audio_read_error", mapOf("code" to read))
                        return null
                    }
                    read < 0 -> {
                        Log.e(TAG, "AUDIO negative read: $read")
                        handleCaptureFailure("audio_read_error", mapOf("code" to read))
                        return null
                    }
                    read == 0 -> {
                        emptyReads += 1
                        if (emptyReads >= 3) {
                            Log.w(TAG, "AUDIO repeated empty reads while filling frame")
                            handleCaptureFailure(
                                "audio_empty_reads",
                                mapOf("count" to emptyReads)
                            )
                            return null
                        }
                    }
                    else -> {
                        offset += read
                        emptyReads = 0
                    }
                }
            }

            if (offset == FRAME_BYTES) buffer else null
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO read exception", e)
            handleCaptureFailure(
                "audio_read_exception",
                mapOf("error" to (e.message ?: "unknown"))
            )
            null
        }
    }

    private fun handleCaptureFailure(reason: String, extra: Map<String, Any?> = emptyMap()) {
        if (!isRecording && audioRecord == null) return
        isRecording = false
        releaseRecorder()
        emitCaptureDiagnostic(reason, extra)
        if (streamingDesired && !capturePaused) {
            startRecoveryLoop(reason)
        }
    }

    private fun releaseRecorder() {
        try {
            audioRecord?.let { recorder ->
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
                runCatching { recorder.release() }
            }
        } finally {
            audioRecord = null
        }
    }

    private fun sanitizeSampleRate(rate: Int): Int = SAMPLE_RATE

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isRecorderSilenced(recorder: AudioRecord): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            recorder.activeRecordingConfiguration?.isClientSilenced == true
        }.onFailure { error ->
            Log.w(TAG, "AUDIO failed to inspect recording configuration", error)
        }.getOrDefault(false)
    }

    private fun shouldCapture(expectedGeneration: Long = stateGeneration.get()): Boolean {
        return streamingDesired && !capturePaused && stateGeneration.get() == expectedGeneration
    }

    private fun startRecoveryLoop(reason: String) {
        if (!streamingDesired || capturePaused || isRecording) return

        synchronized(recoveryLock) {
            if (recoveryJob?.isActive == true) return
            recoveryJob = streamScope.launch {
                Log.w(TAG, "AUDIO recovery loop started: reason=$reason")
                while (streamingDesired && !capturePaused && !isRecording) {
                    val index = recoveryAttempt.coerceIn(0, CAPTURE_RETRY_DELAYS_MS.lastIndex)
                    val retryDelayMs = CAPTURE_RETRY_DELAYS_MS[index]
                    recoveryAttempt = (recoveryAttempt + 1).coerceAtMost(CAPTURE_RETRY_DELAYS_MS.size)
                    delay(retryDelayMs)
                    if (!streamingDesired || capturePaused || isRecording) break
                    ensureCaptureRunning()
                }
                synchronized(recoveryLock) {
                    recoveryJob = null
                }
            }
        }
    }

    private fun cancelRecoveryLoop() {
        synchronized(recoveryLock) {
            recoveryJob?.cancel()
            recoveryJob = null
            recoveryAttempt = 0
        }
    }

    private fun showMicrophoneRecoveryNotification(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "AUDIO recovery notification skipped: POST_NOTIFICATIONS denied")
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "AUDIO recovery notification skipped: notifications disabled")
            return false
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    context.getString(R.string.audio_recovery_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.audio_recovery_channel_description)
                    setShowBadge(false)
                }
            )
            if (manager.getNotificationChannel(RECOVERY_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "AUDIO recovery notification skipped: channel disabled")
                return false
            }
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            RECOVERY_NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.audio_recovery_notification_title))
            .setContentText(context.getString(R.string.audio_recovery_notification_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return runCatching {
            manager.notify(RECOVERY_NOTIFICATION_ID, notification)
            true
        }.onFailure { error ->
            Log.e(TAG, "AUDIO recovery notification failed", error)
        }.getOrDefault(false)
    }

    private fun cancelMicrophoneRecoveryNotification() {
        context.getSystemService(NotificationManager::class.java)
            .cancel(RECOVERY_NOTIFICATION_ID)
    }

    private fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> source.toString()
        }
    }

    private fun emitCaptureDiagnostic(reason: String, extra: Map<String, Any?> = emptyMap()) {
        val payload = JSONObject().apply {
            put("reason", reason)
            put("deviceId", deviceId ?: "")
            put("timestamp", System.currentTimeMillis())
            if (extra.isNotEmpty()) {
                put("meta", JSONObject(extra))
            }
        }
        runCatching { WebSocketManager.getClient()?.emit("audio_capture_error", payload) }
    }

    private fun requestSharedSocketReady() {
        val currentServerUrl = serverUrl ?: return
        val currentDeviceId = deviceId ?: return

        runCatching {
            if (!ChatBackgroundService.isRunning || !WebSocketManager.isConnected()) {
                ChatBackgroundService.start(context, currentServerUrl, currentDeviceId)
            } else if (!WebSocketManager.isReady()) {
                WebSocketManager.getClient()?.requestRegistration()
            }
        }.onFailure { error ->
            Log.w(TAG, "AUDIO shared WebSocket recovery request failed", error)
        }
    }

    private fun refreshBatterySnapshotIfNeeded(now: Long = System.currentTimeMillis()) {
        if ((now - lastBatterySnapshotRefreshAt) < BATTERY_SNAPSHOT_REFRESH_MS && lastBatteryTimestamp > 0L) {
            return
        }
        val snapshot = DeviceInfoCollector.getBatterySnapshot(context)
        lastBatteryLevel = snapshot.level
        lastBatteryCharging = snapshot.isCharging
        lastBatteryTimestamp = snapshot.timestamp
        lastBatterySnapshotRefreshAt = now
    }
}
