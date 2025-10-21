package ru.example.parentwatch.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.example.parentwatch.network.NetworkHelper
import ru.example.parentwatch.network.WebSocketClient
import ru.example.parentwatch.utils.RemoteLogger
import java.io.File

/**
 * Audio Stream Recorder
 * Records audio in chunks and transmits via WebSocket for real-time streaming
 * Uses PCM format for compatibility with AudioTrack playback
 */
class AudioStreamRecorder(
    private val context: Context,
    private val networkHelper: NetworkHelper
) {
    companion object {
        private const val TAG = "AUDIO"

        // Optimized for low-latency voice streaming (Этап A)
        private const val SAMPLE_RATE = 16_000          // 16 kHz for voice (was 44100)
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 20L       // 20ms frames (was 500ms)

        // Frame size: 16000 * 20 / 1000 = 320 samples; 320 * 2 bytes = 640 bytes
        private const val FRAME_SAMPLES = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000).toInt() // 320
        private const val FRAME_BYTES = FRAME_SAMPLES * 2 // 640 bytes per frame

        // Reconnection strategy with exponential backoff
        private const val RECONNECTION_DELAY_MS = 1_000L
        private const val MAX_RECONNECTION_DELAY_MS = 8_000L
        private const val INITIAL_CONNECTION_TIMEOUT_MS = 7_000L
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var sequence = 0
    private var sessionId: Int = 0 // for logging

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var recordingMode: Boolean = false // true if saving recording
    private var webSocketClient: WebSocketClient? = null
    private var webSocketConnected = false
    private val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionDeferred: CompletableDeferred<Boolean>? = null
    private var hasReportedMissingWebSocket = false

    // Reconnection backoff state
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    // Metrics for logging (Этап A)
    private var totalBytesSent = 0L
    private var lastMetricsLogTime = 0L

    // System Audio Effects (Этап B)
    private var systemAudioEffects: SystemAudioEffects? = null
    private var currentFilterMode: FilterMode = FilterMode.ORIGINAL

    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "audio_chunks").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Start audio streaming via WebSocket
     */
    fun startStreaming(
        deviceId: String,
        serverUrl: String,
        recordingMode: Boolean = false
    ) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        this.deviceId = deviceId
        this.serverUrl = serverUrl
        this.recordingMode = recordingMode
        this.sequence = 0
        webSocketConnected = false
        hasReportedMissingWebSocket = false

        Log.d(TAG, "Starting audio streaming via WebSocket - recording mode: $recordingMode")
        RemoteLogger.info(
            serverUrl = this.serverUrl,
            deviceId = this.deviceId,
            source = TAG,
            message = "Starting audio streaming via WebSocket",
            meta = mapOf("recordingMode" to recordingMode)
        )

        // Initialize WebSocket connection
        webSocketClient = WebSocketClient(serverUrl, deviceId)
        webSocketClient?.setCommandCallback { commandType, data ->
            Log.d(TAG, "Command callback invoked: $commandType")
            when (commandType) {
                "start_audio_stream" -> {
                    Log.d(TAG, "START AUDIO STREAM command received - beginning audio recording!")
                    RemoteLogger.info(
                        serverUrl = this.serverUrl,
                        deviceId = this.deviceId,
                        source = TAG,
                        message = "START command received via WebSocket"
                    )
                    startActualRecording()
                }
                "stop_audio_stream" -> {
                    Log.d(TAG, "STOP AUDIO STREAM command received!")
                    RemoteLogger.info(
                        serverUrl = this.serverUrl,
                        deviceId = this.deviceId,
                        source = TAG,
                        message = "STOP command received via WebSocket"
                    )
                    stopStreaming()
                }
            }
        }

        webSocketClient?.setParentConnectedCallback {
            Log.d(TAG, "Parent connected notification received - starting audio")
            RemoteLogger.info(
                serverUrl = this.serverUrl,
                deviceId = this.deviceId,
                source = TAG,
                message = "Parent connected notification received"
            )
            startActualRecording()
        }

        webSocketClient?.setParentDisconnectedCallback {
            Log.d(TAG, "Parent disconnected from stream - stopping audio")
            RemoteLogger.warn(
                serverUrl = this.serverUrl,
                deviceId = this.deviceId,
                source = TAG,
                message = "Parent disconnected notification received"
            )
            stopStreaming()
        }

        webSocketClient?.connect(
            onConnected = {
                Log.d(TAG, "WebSocket connected - waiting for start command from server...")
                RemoteLogger.info(
                    serverUrl = this.serverUrl,
                    deviceId = this.deviceId,
                    source = TAG,
                    message = "WebSocket connected, awaiting START command"
                )
                webSocketClient?.startHeartbeat()
                webSocketConnected = true
            },
            onError = { error ->
                Log.e(TAG, "WebSocket connection failed: $error")
                RemoteLogger.error(
                    serverUrl = this.serverUrl,
                    deviceId = this.deviceId,
                    source = TAG,
                    message = "WebSocket connection failed: $error"
                )
                webSocketConnected = false
            }
        )

        Log.d(TAG, "✅ AudioStreamRecorder setup complete - waiting for server command or parent connection")
        RemoteLogger.info(
            serverUrl = this.serverUrl,
            deviceId = this.deviceId,
            source = TAG,
            message = "AudioStreamRecorder setup complete; waiting for trigger"
        )
    }

    /**
     * Actually start recording (called when command received from server)
     */
    private fun startActualRecording() {
        Log.d(TAG, "🎤 startActualRecording() called - checking conditions...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "startActualRecording() called",
            meta = mapOf(
                "isRecording" to isRecording,
                "webSocketConnected" to webSocketConnected
            )
        )

        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording - skipping!")
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "startActualRecording called while already recording"
            )
            return
        }

        Log.d(TAG, "🎙️ Starting actual audio recording...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "STARTING ACTUAL AUDIO RECORDING NOW"
        )

        // Initialize AudioRecord
        initializeAudioRecord()

        if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord not initialized - cannot start recording!")
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioRecord not initialized - aborting recording"
            )
            return
        }

        isRecording = true
        Log.d(TAG, "✅ isRecording set to TRUE - launching recording coroutine...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Launching recording coroutine"
        )

        recordingJob = streamScope.launch {
            try {
                Log.d(TAG, "📡 Recording coroutine started - entering loop...")
                var chunkCount = 0
                while (isRecording) {
                    recordAndSendChunk()
                    chunkCount++
                    if (chunkCount == 1) {
                        Log.d(TAG, "✅ First chunk recorded and sent!")
                        RemoteLogger.info(
                            serverUrl = serverUrl,
                            deviceId = deviceId,
                            source = TAG,
                            message = "First audio chunk processed"
                        )
                    }
                }
                Log.d(TAG, "🛑 Recording loop exited - total chunks: $chunkCount")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Streaming error in coroutine", e)
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "Streaming error in recording coroutine",
                    throwable = e
                )
                stopStreaming()
            }
        }

        Log.d(TAG, "✅ Recording job launched successfully")
    }

    /**
     * Stop audio streaming (Этап A - idempotent with proper cleanup)
     */
    fun stopStreaming() {
        try {
            Log.d(TAG, "AUDIO stop requested")
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Stopping audio streaming"
            )

            isRecording = false
            recordingJob?.cancel()
            recordingJob = null
            reconnectJob?.cancel()
            reconnectJob = null

            // Cleanup WebSocket
            webSocketClient?.cleanup()
            webSocketClient = null
            webSocketConnected = false
            hasReportedMissingWebSocket = false

            releaseRecorder()
            cleanupChunks()

            // Этап A: Required log
            Log.d(TAG, "AUDIO stop ok")
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO stop ERROR", e)
        }
    }

    /**
     * Set recording mode (save to server or just stream)
     */
    fun setRecordingMode(enabled: Boolean) {
        Log.d(TAG, "Recording mode: $enabled")
        this.recordingMode = enabled
    }

    /**
     * Change filter mode (Этап B - runtime filter switching)
     * Note: Requires AudioRecord reinitialization to change audio source
     */
    fun setFilterMode(mode: FilterMode) {
        if (currentFilterMode == mode) {
            Log.d(TAG, "FX mode already $mode, skipping")
            return
        }

        Log.d(TAG, "FX changing mode: $currentFilterMode → $mode")
        currentFilterMode = mode

        // If recording, reinitialize AudioRecord with new source
        if (isRecording) {
            Log.d(TAG, "FX reinitializing AudioRecord for new mode")
            streamScope.launch {
                delay(100)
                initializeAudioRecord()
            }
        }
    }

    /**
     * Record one chunk and send via WebSocket (Этап A - with metrics)
     */
    private suspend fun recordAndSendChunk() {
        try {
            val audioData = recordChunk()
            if (audioData == null || audioData.isEmpty()) {
                if (sequence < 5) { // Log only first few failures
                    Log.w(TAG, "AUDIO no data for chunk #$sequence")
                }
                return
            }

            if (!webSocketConnected) {
                if (!hasReportedMissingWebSocket) {
                    Log.w(TAG, "AUDIO WS unavailable, skipping chunk #$sequence")
                    RemoteLogger.warn(
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        source = TAG,
                        message = "WebSocket unavailable when sending chunk",
                        meta = mapOf("sequence" to sequence)
                    )
                    hasReportedMissingWebSocket = true
                }
                return
            }

            webSocketClient?.sendAudioChunk(
                sequence = sequence,
                audioData = audioData,
                recording = recordingMode,
                onSuccess = {
                    totalBytesSent += audioData.size
                    hasReportedMissingWebSocket = false

                    // Этап A: Log bytes/s every ~1 second
                    val now = System.currentTimeMillis()
                    if (now - lastMetricsLogTime >= 1000) {
                        val bytesPerSec = totalBytesSent - (totalBytesSent - audioData.size)
                        Log.d(TAG, "AUDIO send ${audioData.size} bytes, total: ${totalBytesSent}B, ~${bytesPerSec}B/s")
                        lastMetricsLogTime = now
                    }

                    if (sequence == 0) {
                        RemoteLogger.info(
                            serverUrl = serverUrl,
                            deviceId = deviceId,
                            source = TAG,
                            message = "First audio chunk sent successfully",
                            meta = mapOf("bytes" to audioData.size)
                        )
                    }
                },
                onError = { error ->
                    Log.e(TAG, "AUDIO send chunk #$sequence FAILED: $error")
                    RemoteLogger.error(
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        source = TAG,
                        message = "Failed to send audio chunk: $error",
                        meta = mapOf("sequence" to sequence)
                    )
                    webSocketConnected = false
                }
            )

            sequence++
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO chunk #$sequence ERROR", e)
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Error recording or sending chunk",
                throwable = e,
                meta = mapOf("sequence" to sequence)
            )
        }
    }

    /**
     * Initialize AudioRecord (Этап A+B - with system effects)
     */
    private fun initializeAudioRecord() {
        try {
            releaseRecorder()

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val actualBuf = minBuf * 2 // Этап A: minBuf * 2

            // Этап B: Select audio source based on filter mode
            val audioSource = SystemAudioEffects.getAudioSourceForMode(currentFilterMode)

            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                actualBuf
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AUDIO record init FAILED")
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "AudioRecord failed to initialize"
                )
                releaseRecorder()
                return
            }

            sessionId = audioRecord?.audioSessionId ?: 0

            // Этап B: Apply system audio effects
            systemAudioEffects = SystemAudioEffects(sessionId)
            systemAudioEffects?.checkAvailability()
            systemAudioEffects?.applyMode(currentFilterMode)

            audioRecord?.startRecording()

            // Этап A: Required log
            val sourceName = when (audioSource) {
                MediaRecorder.AudioSource.MIC -> "MIC"
                MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
                else -> "UNKNOWN($audioSource)"
            }
            Log.d(TAG, "AUDIO record init sid=$sessionId, minBuf=$minBuf, actualBuf=$actualBuf, frame=${FRAME_BYTES}B, src=$sourceName, mode=$currentFilterMode")
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioRecord initialized with system effects",
                meta = mapOf(
                    "sessionId" to sessionId,
                    "minBuf" to minBuf,
                    "actualBuf" to actualBuf,
                    "frameBytes" to FRAME_BYTES,
                    "sampleRate" to SAMPLE_RATE,
                    "audioSource" to sourceName,
                    "filterMode" to currentFilterMode.name
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO init ERROR: ${e.message}", e)
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Error initializing AudioRecord",
                throwable = e
            )
            releaseRecorder()
        }
    }

    /**
     * Record a single chunk of audio (Этап A - 20ms frames = 640 bytes)
     */
    private fun recordChunk(): ByteArray? {
        val buffer = ByteArray(FRAME_BYTES) // 640 bytes for 20ms at 16kHz

        try {
            val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioRecord?.read(buffer, 0, FRAME_BYTES, AudioRecord.READ_BLOCKING) ?: -1
            } else {
                audioRecord?.read(buffer, 0, FRAME_BYTES) ?: -1
            }

            // Check for critical errors
            if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                Log.e(TAG, "AUDIO read ERROR: $read (reinit required)")
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "AudioRecord.read error: $read - will reinitialize"
                )
                // Этап A: Full reinitialization on errors
                streamScope.launch {
                    delay(100) // Small delay before reinit
                    initializeAudioRecord()
                }
                return null
            }

            // Check for empty read
            if (read <= 0) {
                Log.w(TAG, "AUDIO read returned: $read")
                return null
            }

            // Check for partial frame
            if (read < FRAME_BYTES) {
                // Этап A: Send actual size if less than expected
                Log.d(TAG, "AUDIO partial frame: $read bytes (expected $FRAME_BYTES)")
                return buffer.copyOf(read)
            }

            // Full frame read successfully
            return buffer

        } catch (e: Exception) {
            Log.e(TAG, "AUDIO read exception", e)
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Error reading from AudioRecord",
                throwable = e
            )
            return null
        }
    }

    /**
     * Release AudioRecord resources (Этап A+B - with effects cleanup)
     */
    private fun releaseRecorder() {
        try {
            // Этап B: Release system effects first
            systemAudioEffects?.releaseEffects()
            systemAudioEffects = null

            audioRecord?.let { recorder ->
                try {
                    val state = recorder.recordingState
                    if (state == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AUDIO stop() error (ignored)", e)
                }

                try {
                    recorder.release()
                } catch (e: Exception) {
                    Log.w(TAG, "AUDIO release() error (ignored)", e)
                }
            }
        } finally {
            audioRecord = null
            sessionId = 0
        }
    }

    /**
     * Clean up temporary chunk files
     */
    private fun cleanupChunks() {
        try {
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("chunk_")) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up chunks", e)
        }
    }
}
