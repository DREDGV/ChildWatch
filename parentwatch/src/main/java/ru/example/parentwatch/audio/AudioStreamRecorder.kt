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

        // FIXED: Use fixed 24kHz for best quality and no resampling
        private const val DEFAULT_SAMPLE_RATE = 24_000          // 24 kHz fixed
        private const val CHUNK_DURATION_MS = 20L       // 20ms frames
        private const val FRAME_BYTES = (DEFAULT_SAMPLE_RATE * CHUNK_DURATION_MS / 1000) * 2 // 960 bytes

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

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE // stream sample rate (wire)
    private var frameBytes: Int = frameBytesForRate(DEFAULT_SAMPLE_RATE) // stream frame size
    private var captureSampleRate: Int = DEFAULT_SAMPLE_RATE
    private var captureFrameBytes: Int = frameBytesForRate(DEFAULT_SAMPLE_RATE)
    private var emptyReadDiagnostics = 0
    private var lastEmptyReadDiagnosticAt = 0L

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

    // Metrics for logging (Р В­РЎвЂљР В°Р С— A)
    private var totalBytesSent = 0L
    private var lastMetricsLogTime = 0L


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
        recordingMode: Boolean = false,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        this.deviceId = deviceId
        this.serverUrl = serverUrl
        this.recordingMode = recordingMode
        this.sampleRate = sanitizeSampleRate(sampleRate)
        this.frameBytes = frameBytesForRate(this.sampleRate)
        this.sequence = 0
        this.emptyReadDiagnostics = 0
        this.lastEmptyReadDiagnosticAt = 0L
        webSocketConnected = false
        hasReportedMissingWebSocket = false

        Log.d(TAG, "Starting audio streaming via WebSocket - recording mode: $recordingMode")
        RemoteLogger.info(
            serverUrl = this.serverUrl,
            deviceId = this.deviceId,
            source = TAG,
            message = "Starting audio streaming via WebSocket",
            meta = mapOf(
                "recordingMode" to recordingMode,
                "sampleRate" to this.sampleRate
            )
        )

        // Initialize WebSocket connection
        webSocketClient = WebSocketClient(serverUrl, deviceId)
        webSocketClient?.setCommandCallback { commandType, data ->
            Log.d(TAG, "Command callback invoked: $commandType")
            when (commandType) {
                "start_audio_stream" -> {
                    val requestedRecordingMode = data?.optBoolean("recording", recordingMode) ?: recordingMode
                    val requestedSampleRate = data?.optInt("sampleRate", sampleRate) ?: sampleRate
                    updateStreamConfig(requestedRecordingMode, requestedSampleRate)
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
            Log.d(TAG, "Parent disconnected from stream - keeping recorder warm")
            RemoteLogger.warn(
                serverUrl = this.serverUrl,
                deviceId = this.deviceId,
                source = TAG,
                message = "Parent disconnected notification received"
            )
            // Keep capture active to avoid "silent starts" when parent reconnects with remapped ID.
            // Server will drop chunks while no parent is attached.
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
                // Start immediately after connection to reduce delays
                startActualRecording()
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

        Log.d(TAG, "РІСљвЂ¦ AudioStreamRecorder setup complete - waiting for server command or parent connection")
        RemoteLogger.info(
            serverUrl = this.serverUrl,
            deviceId = this.deviceId,
            source = TAG,
            message = "AudioStreamRecorder setup complete; waiting for trigger"
        )
    }

    private fun sanitizeSampleRate(rate: Int): Int {
        return DEFAULT_SAMPLE_RATE
    }

    private fun frameBytesForRate(rate: Int): Int {
        return ((rate * CHUNK_DURATION_MS) / 1000).toInt() * 2
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            else -> "UNKNOWN($source)"
        }
    }

    private fun emitCaptureDiagnostic(reason: String, extra: Map<String, Any?> = emptyMap()) {
        val url = serverUrl
        val id = deviceId

        runCatching {
            val payload = org.json.JSONObject().apply {
                put("reason", reason)
                put("deviceId", id ?: "")
                put("timestamp", System.currentTimeMillis())
                if (extra.isNotEmpty()) {
                    put("meta", org.json.JSONObject(extra))
                }
            }
            webSocketClient?.emit("audio_capture_error", payload)
        }

        RemoteLogger.warn(
            serverUrl = url,
            deviceId = id,
            source = TAG,
            message = "Audio capture diagnostic: $reason",
            meta = extra
        )
    }

    private fun resamplePcm16Mono(input: ByteArray, inRate: Int, outRate: Int): ByteArray {
        if (input.isEmpty() || inRate <= 0 || outRate <= 0 || inRate == outRate) return input

        val inSamples = input.size / 2
        if (inSamples <= 1) return input

        val outSamples = ((inSamples.toLong() * outRate) / inRate).toInt().coerceAtLeast(1)
        val inputShorts = ShortArray(inSamples)
        java.nio.ByteBuffer.wrap(input)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(inputShorts)
        val outputShorts = ShortArray(outSamples)
        val ratio = inRate.toDouble() / outRate.toDouble()

        for (i in 0 until outSamples) {
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceIn(0, inSamples - 1)
            val next = (idx + 1).coerceAtMost(inSamples - 1)
            val frac = srcPos - idx
            val s1 = inputShorts[idx].toDouble()
            val s2 = inputShorts[next].toDouble()
            val interpolated = s1 + (s2 - s1) * frac
            val clamped = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputShorts[i] = clamped.toShort()
        }

        val output = java.nio.ByteBuffer.allocate(outSamples * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        output.asShortBuffer().put(outputShorts)
        return output.array()
    }

    /**
     * Actually start recording (called when command received from server)
     */
    private fun startActualRecording() {
        Log.d(TAG, "СЂСџР‹В¤ startActualRecording() called - checking conditions...")
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
            Log.w(TAG, "РІС™В РїС‘РЏ Already recording - skipping!")
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "startActualRecording called while already recording"
            )
            return
        }

        Log.d(TAG, "СЂСџР‹в„ўРїС‘РЏ Starting actual audio recording...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "STARTING ACTUAL AUDIO RECORDING NOW"
        )

        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "AUDIO RECORD_AUDIO permission missing - cannot capture")
            emitCaptureDiagnostic(
                reason = "permission_missing",
                extra = mapOf("permission" to "RECORD_AUDIO")
            )
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "RECORD_AUDIO permission missing; capture aborted"
            )
            return
        }

        // Initialize AudioRecord
        initializeAudioRecord()

        if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "РІСњРЉ AudioRecord not initialized - cannot start recording!")
            emitCaptureDiagnostic(
                reason = "audio_record_not_initialized",
                extra = mapOf(
                    "sampleRate" to sampleRate,
                    "captureSampleRate" to captureSampleRate
                )
            )
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioRecord not initialized - aborting recording"
            )
            return
        }

        isRecording = true
        Log.d(TAG, "РІСљвЂ¦ isRecording set to TRUE - launching recording coroutine...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Launching recording coroutine"
        )

        recordingJob = streamScope.launch {
            try {
                Log.d(TAG, "СЂСџвЂњРЋ Recording coroutine started - entering loop...")
                var chunkCount = 0
                while (isRecording) {
                    recordAndSendChunk()
                    chunkCount++
                    if (chunkCount == 1) {
                        Log.d(TAG, "РІСљвЂ¦ First chunk recorded and sent!")
                        RemoteLogger.info(
                            serverUrl = serverUrl,
                            deviceId = deviceId,
                            source = TAG,
                            message = "First audio chunk processed"
                        )
                    }
                }
                Log.d(TAG, "СЂСџвЂєвЂ Recording loop exited - total chunks: $chunkCount")
            } catch (e: Exception) {
                Log.e(TAG, "РІСњРЉ Streaming error in coroutine", e)
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

        Log.d(TAG, "РІСљвЂ¦ Recording job launched successfully")
    }

    fun isActive(): Boolean = isRecording || webSocketConnected

    /**
     * Ensure microphone capture is running when WebSocket session is alive.
     * Used by polling start command path when child was previously paused.
     */
    fun ensureCaptureRunning() {
        if (!webSocketConnected) return
        if (isRecording) return
        Log.d(TAG, "AUDIO ensureCaptureRunning: restarting capture")
        startActualRecording()
    }

    /**
     * Stop audio streaming (Р В­РЎвЂљР В°Р С— A - idempotent with proper cleanup)
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

            // Р В­РЎвЂљР В°Р С— A: Required log
            Log.d(TAG, "AUDIO stop ok")
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO stop ERROR", e)
        }
    }

    /**
     * Stop microphone capture but keep WebSocket session alive.
     * This avoids full teardown when parent briefly reconnects/re-registers.
     */
    private fun pauseRecordingForParentDisconnect() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        releaseRecorder()
        Log.d(TAG, "AUDIO capture paused, waiting for parent reconnect")
    }

    /**
     * Set recording mode (save to server or just stream)
     */
    fun setRecordingMode(enabled: Boolean) {
        Log.d(TAG, "Recording mode: $enabled")
        this.recordingMode = enabled
    }

    /**
     * Update stream configuration at runtime.
     * Re-initializes microphone capture when sample rate changes.
     */
    fun updateStreamConfig(newRecordingMode: Boolean, newSampleRate: Int) {
        val normalizedRate = sanitizeSampleRate(newSampleRate)
        val rateChanged = normalizedRate != sampleRate
        val recordingChanged = newRecordingMode != recordingMode

        recordingMode = newRecordingMode
        if (!rateChanged && !recordingChanged) {
            return
        }

        if (rateChanged) {
            Log.i(TAG, "AUDIO reconfig sampleRate: $sampleRate -> $normalizedRate")
            sampleRate = normalizedRate
            frameBytes = frameBytesForRate(sampleRate)
        } else {
            Log.d(TAG, "AUDIO reconfig recording mode: $recordingMode")
        }

        if (isRecording) {
            isRecording = false
            recordingJob?.cancel()
            recordingJob = null
            releaseRecorder()
            startActualRecording()
        }
    }

    /**
     * Record one chunk and send via WebSocket (Р В­РЎвЂљР В°Р С— A - with metrics)
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
                sampleRate = sampleRate,
                onSuccess = {
                    totalBytesSent += audioData.size
                    hasReportedMissingWebSocket = false

                    // Р В­РЎвЂљР В°Р С— A: Log bytes/s every ~1 second
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
     * Initialize AudioRecord - FIXED: Use fixed 24kHz, no resampling
     */
    private fun initializeAudioRecord() {
        try {
            releaseRecorder()

            if (!hasRecordAudioPermission()) {
                Log.e(TAG, "AUDIO init aborted: RECORD_AUDIO permission missing")
                releaseRecorder()
                return
            }

            // FIXED: Use fixed 24kHz - no fallback, no resampling
            val sampleRate = DEFAULT_SAMPLE_RATE
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf <= 0) {
                Log.e(TAG, "AUDIO min buffer invalid: rate=${sampleRate}Hz buf=$minBuf")
                releaseRecorder()
                return
            }

            val actualBuf = minBuf * 2
            
            // Try VOICE_COMMUNICATION first (best for voice), fallback to MIC
            val audioSources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC
            )
            
            var initialized = false
            for (source in audioSources) {
                try {
                    audioRecord = AudioRecord(
                        source,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        actualBuf
                    )

                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        initialized = true
                        Log.d(TAG, "AUDIO initialized with source: ${audioSourceName(source)}")
                        break
                    } else {
                        Log.w(TAG, "AUDIO init failed for source: ${audioSourceName(source)}")
                        audioRecord?.release()
                        audioRecord = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AUDIO init exception for source: ${audioSourceName(source)}", e)
                    audioRecord = null
                }
            }

            if (!initialized || audioRecord == null) {
                Log.e(TAG, "AUDIO record init FAILED for all sources")
                releaseRecorder()
                return
            }

            sessionId = audioRecord?.audioSessionId ?: 0
            audioRecord?.startRecording()
            
            val recordingState = audioRecord?.recordingState ?: AudioRecord.RECORDSTATE_STOPPED
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AUDIO startRecording failed: state=$recordingState")
                releaseRecorder()
                return
            }

            Log.d(
                TAG,
                "AUDIO record init OK: sid=$sessionId, minBuf=$minBuf, actualBuf=$actualBuf, frame=${FRAME_BYTES}B, rate=$sampleRate"
            )
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO init ERROR: ${e.message}", e)
            releaseRecorder()
        }
    }
        try {
            releaseRecorder()

            if (!hasRecordAudioPermission()) {
                Log.e(TAG, "AUDIO init aborted: RECORD_AUDIO permission missing")
                emitCaptureDiagnostic(
                    reason = "permission_missing_during_init",
                    extra = mapOf("permission" to "RECORD_AUDIO")
                )
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "AudioRecord init aborted: RECORD_AUDIO permission missing"
                )
                releaseRecorder()
                return
            }

            var initializedSource: Int? = null
            var initializedRate: Int? = null
            var minBuf = 0
            var actualBuf = 0

            sourceLoop@ for (candidateSource in CAPTURE_AUDIO_SOURCE_FALLBACKS) {
                for (candidateRate in CAPTURE_SAMPLE_RATE_FALLBACKS) {
                    val candidateMinBuf =
                        AudioRecord.getMinBufferSize(candidateRate, CHANNEL_CONFIG, AUDIO_FORMAT)
                    if (candidateMinBuf <= 0) {
                        Log.w(
                            TAG,
                            "AUDIO min buffer invalid: src=${audioSourceName(candidateSource)} rate=${candidateRate}Hz buf=$candidateMinBuf"
                        )
                        continue
                    }

                    val candidateActualBuf = candidateMinBuf * 2
                    val candidateRecord = AudioRecord(
                        candidateSource,
                        candidateRate,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        candidateActualBuf
                    )

                    if (candidateRecord.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { candidateRecord.release() }
                        Log.w(
                            TAG,
                            "AudioRecord init failed: src=${audioSourceName(candidateSource)} rate=${candidateRate}Hz"
                        )
                        continue
                    }

                    audioRecord = candidateRecord
                    initializedSource = candidateSource
                    initializedRate = candidateRate
                    minBuf = candidateMinBuf
                    actualBuf = candidateActualBuf
                    break@sourceLoop
                }
            }

            if (audioRecord == null || initializedRate == null || initializedSource == null) {
                Log.e(TAG, "AUDIO record init FAILED for all fallback rates")
                emitCaptureDiagnostic(
                    reason = "audio_record_init_failed",
                    extra = mapOf(
                        "streamSampleRate" to sampleRate,
                        "sourceCandidates" to CAPTURE_AUDIO_SOURCE_FALLBACKS.joinToString(","),
                        "rateCandidates" to CAPTURE_SAMPLE_RATE_FALLBACKS.joinToString(",")
                    )
                )
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "AudioRecord failed to initialize for all capture rates"
                )
                releaseRecorder()
                return
            }

            captureSampleRate = initializedRate
            captureFrameBytes = frameBytesForRate(captureSampleRate)

            sessionId = audioRecord?.audioSessionId ?: 0
            audioRecord?.startRecording()
            val recordingState = audioRecord?.recordingState ?: AudioRecord.RECORDSTATE_STOPPED
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(
                    TAG,
                    "AUDIO startRecording failed: state=$recordingState src=${audioSourceName(initializedSource)} rate=$captureSampleRate"
                )
                emitCaptureDiagnostic(
                    reason = "audio_record_start_failed",
                    extra = mapOf(
                        "recordingState" to recordingState,
                        "captureSampleRate" to captureSampleRate,
                        "audioSource" to audioSourceName(initializedSource)
                    )
                )
                releaseRecorder()
                return
            }

            val sourceName = audioSourceName(initializedSource)

            Log.d(
                TAG,
                "AUDIO record init sid=$sessionId, minBuf=$minBuf, actualBuf=$actualBuf, captureFrame=${captureFrameBytes}B, streamFrame=${frameBytes}B, src=$sourceName, captureRate=$captureSampleRate, streamRate=$sampleRate"
            )
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioRecord initialized",
                meta = mapOf(
                    "sessionId" to sessionId,
                    "minBuf" to minBuf,
                    "actualBuf" to actualBuf,
                    "frameBytes" to frameBytes,
                    "captureFrameBytes" to captureFrameBytes,
                    "sampleRate" to sampleRate,
                    "captureSampleRate" to captureSampleRate,
                    "audioSource" to sourceName,
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
            emitCaptureDiagnostic(
                reason = "audio_record_init_exception",
                extra = mapOf("error" to (e.message ?: "unknown"))
            )
            releaseRecorder()
        }
    }

    /**
     * Record a single chunk of audio (Р В­РЎвЂљР В°Р С— A - 20ms frames = 960 bytes)
     */
    private fun recordChunk(): ByteArray? {
        val captureBuffer = ByteArray(captureFrameBytes)

        try {
            val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioRecord?.read(captureBuffer, 0, captureFrameBytes, AudioRecord.READ_BLOCKING) ?: -1
            } else {
                audioRecord?.read(captureBuffer, 0, captureFrameBytes) ?: -1
            }

            if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                Log.e(TAG, "AUDIO read ERROR: $read (reinit required)")
                emitCaptureDiagnostic(
                    reason = "audio_read_error",
                    extra = mapOf(
                        "code" to read,
                        "captureSampleRate" to captureSampleRate
                    )
                )
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "AudioRecord.read error: $read - will reinitialize"
                )
                streamScope.launch {
                    delay(100)
                    initializeAudioRecord()
                }
                return null
            }

            if (read <= 0) {
                Log.w(TAG, "AUDIO read returned: $read")
                val now = System.currentTimeMillis()
                if (emptyReadDiagnostics < 5 || now - lastEmptyReadDiagnosticAt >= 5000L) {
                    emptyReadDiagnostics++
                    lastEmptyReadDiagnosticAt = now
                    emitCaptureDiagnostic(
                        reason = "audio_read_empty",
                        extra = mapOf(
                            "code" to read,
                            "recordingState" to (audioRecord?.recordingState ?: -1),
                            "captureSampleRate" to captureSampleRate,
                            "streamSampleRate" to sampleRate,
                            "diagnosticCount" to emptyReadDiagnostics
                        )
                    )
                }
                return null
            }

            if (emptyReadDiagnostics > 0) {
                emitCaptureDiagnostic(
                    reason = "audio_read_recovered",
                    extra = mapOf(
                        "bytesRead" to read,
                        "captureSampleRate" to captureSampleRate,
                        "streamSampleRate" to sampleRate
                    )
                )
                emptyReadDiagnostics = 0
                lastEmptyReadDiagnosticAt = 0L
            }

            if (read < captureFrameBytes) {
                Log.d(TAG, "AUDIO partial frame: $read bytes (expected $captureFrameBytes), padding silence")
                java.util.Arrays.fill(captureBuffer, read, captureFrameBytes, 0.toByte())
            }

            val fullCaptureFrame = if (read == captureFrameBytes) {
                captureBuffer
            } else {
                captureBuffer.copyOf(captureFrameBytes)
            }

            val streamFrame = if (captureSampleRate == sampleRate) {
                fullCaptureFrame
            } else {
                resamplePcm16Mono(fullCaptureFrame, captureSampleRate, sampleRate)
            }

            return when {
                streamFrame.size == frameBytes -> streamFrame
                streamFrame.size > frameBytes -> streamFrame.copyOf(frameBytes)
                else -> ByteArray(frameBytes).also { out ->
                    System.arraycopy(streamFrame, 0, out, 0, streamFrame.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO read exception", e)
            emitCaptureDiagnostic(
                reason = "audio_read_exception",
                extra = mapOf("error" to (e.message ?: "unknown"))
            )
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
     * Release AudioRecord resources (Р В­РЎвЂљР В°Р С— A+B - with effects cleanup)
     */
    private fun releaseRecorder() {
        try {

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

