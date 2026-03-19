package ru.example.parentwatch.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
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
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.utils.DeviceInfoCollector
import ru.example.parentwatch.utils.RemoteLogger
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

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
    }

    private val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val startInFlight = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var waitForSocketJob: Job? = null

    @Volatile private var isRecording = false
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
        this.deviceId = deviceId
        this.serverUrl = serverUrl
        this.recordingMode = recordingMode
        this.sequence = 0
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

        waitForSocketJob?.cancel()
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
                return@launch
            }
            ensureCaptureRunning()
        }
    }

    fun stopStreaming() {
        Log.d(TAG, "AUDIO stop requested")
        isRecording = false
        startInFlight.set(false)
        recordingJob?.cancel()
        recordingJob = null
        waitForSocketJob?.cancel()
        waitForSocketJob = null
        releaseRecorder()
        Log.d(TAG, "AUDIO stop OK")
    }

    fun pauseCapture() {
        Log.d(TAG, "AUDIO capture pause requested")
        isRecording = false
        startInFlight.set(false)
        recordingJob?.cancel()
        recordingJob = null
        releaseRecorder()
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

    fun isActive(): Boolean = isRecording || WebSocketManager.isReady()

    fun ensureCaptureRunning() {
        if (isRecording) return
        val client = WebSocketManager.getClient()
        if (client == null) {
            requestSharedSocketReady()
            return
        }
        if (!client.isReady()) {
            requestSharedSocketReady()
            client.requestRegistration()
            return
        }
        startActualRecording()
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

    private fun startActualRecording() {
        val url = serverUrl
        val id = deviceId

        if (!startInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "AUDIO start already in progress")
            return
        }

        if (isRecording) {
            startInFlight.set(false)
            return
        }

        if (!hasRecordAudioPermission()) {
            startInFlight.set(false)
            emitCaptureDiagnostic("permission_missing", mapOf("permission" to "RECORD_AUDIO"))
            Log.e(TAG, "AUDIO RECORD_AUDIO permission missing")
            return
        }

        if (!WebSocketManager.isReady()) {
            startInFlight.set(false)
            requestSharedSocketReady()
            Log.w(TAG, "AUDIO WS not ready, capture start postponed")
            return
        }

        try {
            initializeAudioRecord()
            val recorder = audioRecord
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                emitCaptureDiagnostic("audio_record_not_initialized")
                Log.e(TAG, "AUDIO init failed")
                return
            }

            isRecording = true
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
                while (isRecording) {
                    recordAndSendChunk()
                }
            }
        } finally {
            startInFlight.set(false)
        }
    }

    private suspend fun recordAndSendChunk() {
        val client = WebSocketManager.getClient()
            ?: run {
                requestSharedSocketReady()
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
            try {
                val candidate = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    candidate.release()
                    continue
                }

                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    candidate.release()
                    continue
                }

                audioRecord = candidate
                Log.d(TAG, "AUDIO using source=${audioSourceName(source)} buffer=$bufferSize")
                return
            } catch (e: Exception) {
                Log.w(TAG, "AUDIO init failed for source=${audioSourceName(source)}", e)
            }
        }

        audioRecord = null
    }

    private fun recordChunk(): ByteArray? {
        val recorder = audioRecord ?: return null
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
                        emitCaptureDiagnostic("audio_read_error", mapOf("code" to read))
                        releaseRecorder()
                        initializeAudioRecord()
                        return null
                    }
                    read < 0 -> {
                        Log.e(TAG, "AUDIO negative read: $read")
                        emitCaptureDiagnostic("audio_read_error", mapOf("code" to read))
                        return null
                    }
                    read == 0 -> {
                        emptyReads += 1
                        if (emptyReads >= 3) {
                            Log.w(TAG, "AUDIO repeated empty reads while filling frame")
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
            emitCaptureDiagnostic("audio_read_exception", mapOf("error" to (e.message ?: "unknown")))
            null
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

        if (!ChatBackgroundService.isRunning || !WebSocketManager.isConnected()) {
            ChatBackgroundService.start(context, currentServerUrl, currentDeviceId)
        } else if (!WebSocketManager.isReady()) {
            WebSocketManager.getClient()?.requestRegistration()
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
