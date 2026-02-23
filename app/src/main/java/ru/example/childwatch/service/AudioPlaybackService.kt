package ru.example.childwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ru.example.childwatch.MainActivity
import ru.example.childwatch.R
import ru.example.childwatch.audio.AudioEnhancer
import ru.example.childwatch.audio.FilterMode
import ru.example.childwatch.audio.RecordingRepository
import ru.example.childwatch.diagnostics.MetricsManager
import ru.example.childwatch.diagnostics.AudioStatus
import ru.example.childwatch.diagnostics.WsStatus
import ru.example.childwatch.diagnostics.ErrorSeverity
import ru.example.childwatch.audio.StreamRecorder
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketClient
import ru.example.childwatch.utils.AlertNotifier

/**
 * Foreground Service for audio playback
 * Allows listening to continue when app is in background or screen is off
 */
class AudioPlaybackService : LifecycleService() {

    companion object {
        private const val TAG = "AUDIO" // Р­С‚Р°Рї A: unified tag
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "audio_playback_channel"

        // РћРїС‚РёРјРёР·РёСЂРѕРІР°РЅРѕ РґР»СЏ 24 РєР“С†, РєР°РґСЂРѕРІ РїРѕ 20 РјСЃ
        private const val DEFAULT_STREAM_SAMPLE_RATE = 24_000   // 24 kHz
        private const val STREAM_CHANNEL_COUNT = 1       // MONO
        private const val FRAME_MS = 20                  // 20ms frames
        private const val DEFAULT_FRAME_BYTES =
            (DEFAULT_STREAM_SAMPLE_RATE * FRAME_MS / 1000) * 2 // 960 bytes
        // Keep explicit support for all app capture rates; avoid 44.1k fallback mismatch artifacts.
        private val PLAYBACK_SAMPLE_RATES = intArrayOf(24_000, 32_000, 48_000, 16_000)
        private val INPUT_SAMPLE_RATES = intArrayOf(24_000, 32_000, 48_000)

        // Jitter buffer: Increased for poor network conditions
        // 250-400 ms = 12-20 frames of 20ms (improved for unstable connections)
        private const val JITTER_BUFFER_MIN_FRAMES = 12  // 240 ms (increased from 160ms)
        private const val JITTER_BUFFER_MAX_FRAMES = 60  // Max queue size (1.2 sec, increased from 1 sec)
        private const val JITTER_BUFFER_AGGRESSIVE_THRESHOLD = 20 // Trigger aggressive drop (400ms)
        private const val STREAM_TIMEOUT_MINUTES = 30

        const val ACTION_START_PLAYBACK = "ru.example.childwatch.START_PLAYBACK"
        const val ACTION_STOP_PLAYBACK = "ru.example.childwatch.STOP_PLAYBACK"
        const val ACTION_TOGGLE_RECORDING = "ru.example.childwatch.TOGGLE_RECORDING"

        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_SAMPLE_RATE = "sample_rate"

        // Service state
        var isPlaying = false
            private set
        var chunksReceived = 0
            private set
        var currentStatus = "Stopped"
            private set
        var connectionQuality = "Excellent"
            private set
        var lastChunkTimestamp = 0L
            private set
        var streamingStartTime = 0L
            private set
        var playbackSampleRate = DEFAULT_STREAM_SAMPLE_RATE
            private set
        var inputStreamSampleRate = DEFAULT_STREAM_SAMPLE_RATE
            private set
        var requestedStreamSampleRate = DEFAULT_STREAM_SAMPLE_RATE
            private set
        var audioTrackInitError: String? = null
            private set

        fun startPlayback(
            context: Context,
            deviceId: String,
            serverUrl: String,
            recording: Boolean = false,
            sampleRate: Int = DEFAULT_STREAM_SAMPLE_RATE
        ) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START_PLAYBACK
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_RECORDING, recording)
                putExtra(EXTRA_SAMPLE_RATE, sampleRate)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopPlayback(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_STOP_PLAYBACK
            }
            context.startService(intent)
        }

        fun toggleRecording(context: Context, recording: Boolean) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_TOGGLE_RECORDING
                putExtra(EXTRA_RECORDING, recording)
            }
            context.startService(intent)
        }
    }

    private var networkClient: NetworkClient? = null
    private var webSocketClient: WebSocketClient? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val audioEnhancer = AudioEnhancer()
    private lateinit var recordingRepository: RecordingRepository

    // Р­С‚Р°Рї D: Metrics manager for diagnostics
    lateinit var metricsManager: MetricsManager
        private set
    private var streamRecorder: StreamRecorder? = null
    private var localRecordingActive = false

    // Improvement: Audio Focus for handling calls/notifications
    private var audioManager: AudioManager? = null
    private var wasPlayingBeforeFocusLoss = false
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState: Boolean = false

    private val filterModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ru.example.childwatch.UPDATE_FILTER_MODE") {
                val modeName = intent.getStringExtra("filter_mode")
                if (modeName != null) {
                    try {
                        val mode = FilterMode.valueOf(modeName)
                        setFilterMode(mode)
                        Log.d(TAG, "Filter mode updated via broadcast: $mode")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid filter mode received: $modeName", e)
                    }
                }
            }
        }
    }

    // Improvement: Audio focus listener to pause on calls/notifications
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Lost focus for unknown duration - stop playback
                Log.w(TAG, "рџЋµ AUDIOFOCUS_LOSS - stopping playback")
                wasPlayingBeforeFocusLoss = isPlaying
                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Lost focus temporarily (e.g., incoming call)
                Log.w(TAG, "рџЋµ AUDIOFOCUS_LOSS_TRANSIENT - pausing temporarily")
                wasPlayingBeforeFocusLoss = isPlaying
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lost focus but can reduce volume
                Log.i(TAG, "рџЋµ AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK - reducing volume")
                audioTrack?.setVolume(0.3f) // Duck to 30% volume
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained focus
                Log.i(TAG, "рџЋµ AUDIOFOCUS_GAIN - resumed")
                audioTrack?.setVolume(1.0f) // Restore volume
                // Optionally resume if it was playing before
                if (wasPlayingBeforeFocusLoss && !isPlaying) {
                    Log.d(TAG, "Resuming playback after focus regained")
                    // Note: Full resume might need manual action from user
                }
            }
            else -> {
                Log.d(TAG, "рџЋµ Unknown audio focus change: $focusChange")
            }
        }
    }

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var isRecording = false
    private var streamingStartTime: Long = 0L
    private var requestedSampleRate: Int = DEFAULT_STREAM_SAMPLE_RATE
    private var inputSampleRate: Int = DEFAULT_STREAM_SAMPLE_RATE
    private var inputFrameBytes: Int = DEFAULT_FRAME_BYTES
    private var outputSampleRate: Int = DEFAULT_STREAM_SAMPLE_RATE
    private var outputFrameBytes: Int = DEFAULT_FRAME_BYTES

    // Jitter buffer (Р­С‚Р°Рї A: ArrayBlockingQueue with 50-100 frames capacity)
    private val chunkQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(JITTER_BUFFER_MAX_FRAMES)
    private var isBuffering = true
    private var bufferUnderrunCount = 0


    // Metrics (Р­С‚Р°Рї A)
    private var totalBytesReceived = 0L
    private var lastMetricsLogTime = 0L
    private var underrunCount = 0
    private var lastReceivedSequence = -1

    // Waveform callback
    private var waveformCallback: ((ByteArray) -> Unit)? = null

    // Reliability helpers
    private var startCommandJob: Job? = null
    private var streamWatchdogJob: Job? = null

    // Binder for Activity communication
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    /**
     * Set callback for waveform visualization
     */
    fun setWaveformCallback(callback: ((ByteArray) -> Unit)?) {
        waveformCallback = callback
    }
    
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume)
        Log.d(TAG, "Volume set to: ${(volume * 100).toInt()}%")
    }

    /**
     * Request remote capture sample-rate change without full local restart.
     * Returns true if request was accepted for dispatch.
     */
    fun requestStreamSampleRate(newRate: Int): Boolean {
        if (!isPlaying) return false

        val normalized = sanitizeInputSampleRate(newRate)
        if (normalized == requestedSampleRate) return true

        requestedSampleRate = normalized
        requestedStreamSampleRate = normalized
        metricsManager.update { metrics ->
            metrics.copy(sampleRate = normalized, frameSize = frameBytesForRate(normalized))
        }

        lifecycleScope.launch(Dispatchers.IO) {
            sendStartCommand("quality_change_${normalized}")
        }
        Log.d(TAG, "Requested stream quality change to ${normalized}Hz")
        return true
    }

    fun setFilterMode(mode: FilterMode) {
        val currentConfig = audioEnhancer.getConfig()
        audioEnhancer.updateConfig(currentConfig.copy(mode = mode))
        Log.d(TAG, "Filter mode changed to: $mode")
    }

    fun getAudioEnhancerConfig(): AudioEnhancer.Config = audioEnhancer.getConfig()

    fun setAudioEnhancerConfig(config: AudioEnhancer.Config) {
        audioEnhancer.updateConfig(config)
        Log.d(TAG, "AudioEnhancer config updated: volumeMode=${config.volumeMode}")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioPlaybackService created")

        // Р­С‚Р°Рї D: Initialize metrics manager
        metricsManager = MetricsManager(applicationContext)

        networkClient = NetworkClient(this)
        recordingRepository = RecordingRepository(this)
        streamRecorder = StreamRecorder(this)
        createNotificationChannel()

        // Load saved filter mode
        loadFilterMode()

        // Register broadcast receiver for filter mode updates
        val filter = IntentFilter("ru.example.childwatch.UPDATE_FILTER_MODE")
        registerReceiver(filterModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Initialize WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChildWatch::AudioPlaybackWakeLock"
        )

        // Initialize WiFi Lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "ChildWatch::AudioWifiLock"
        )

        // Improvement: Initialize AudioManager for audio focus handling
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "рџЋµ AudioManager initialized for audio focus handling")
    }

    private fun loadFilterMode() {
        val prefs = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
        val savedMode = prefs.getString("filter_mode", FilterMode.ORIGINAL.name)
        val mode = try {
            FilterMode.valueOf(savedMode ?: FilterMode.ORIGINAL.name)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid filter mode: $savedMode, using ORIGINAL")
            FilterMode.ORIGINAL
        }
        setFilterMode(mode)
        Log.d(TAG, "Loaded filter mode: $mode")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_PLAYBACK -> {
                val requestedDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                val deviceId = resolvePreferredTargetDeviceId(requestedDeviceId)
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                val recording = intent.getBooleanExtra(EXTRA_RECORDING, false)
                val requestedRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, DEFAULT_STREAM_SAMPLE_RATE)
                requestedSampleRate = sanitizeInputSampleRate(requestedRate)
                inputSampleRate = requestedSampleRate
                inputFrameBytes = frameBytesForRate(inputSampleRate)
                requestedStreamSampleRate = requestedSampleRate
                inputStreamSampleRate = inputSampleRate
                metricsManager.update { metrics ->
                    metrics.copy(
                        sampleRate = inputSampleRate,
                        channelCount = STREAM_CHANNEL_COUNT,
                        frameSize = inputFrameBytes
                    )
                }
                
                Log.d(
                    TAG,
                    "Starting playback with deviceId: $deviceId (requested=$requestedDeviceId), serverUrl: $serverUrl, recording: $recording, sampleRate=$requestedSampleRate"
                )
                
                if (deviceId.isNullOrEmpty() || serverUrl.isNullOrEmpty()) {
                    Log.e(TAG, "Missing required parameters - deviceId: $deviceId, serverUrl: $serverUrl")
                    // Start foreground anyway to prevent crash
                    startForeground(NOTIFICATION_ID, createNotification("РћС€РёР±РєР°: РЅРµРІРµСЂРЅС‹Рµ РїР°СЂР°РјРµС‚СЂС‹"))
                    stopSelf()
                    return START_NOT_STICKY
                }

                startPlayback(deviceId, serverUrl, recording)
            }
            ACTION_STOP_PLAYBACK -> {
                Log.d(TAG, "Stopping playback")
                stopPlayback()
            }
            ACTION_TOGGLE_RECORDING -> {
                val recording = intent.getBooleanExtra(EXTRA_RECORDING, false)
                Log.d(TAG, "Toggling recording: $recording")
                toggleRecording(recording)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                // Start foreground anyway to prevent crash
                startForeground(NOTIFICATION_ID, createNotification("РќРµРёР·РІРµСЃС‚РЅР°СЏ РєРѕРјР°РЅРґР°"))
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun startPlayback(deviceId: String, serverUrl: String, recording: Boolean) {
        try {
            if (isPlaying) {
                Log.w(TAG, "Already playing")
                return
            }

            this.deviceId = deviceId
            this.serverUrl = serverUrl
            this.isRecording = recording

            Log.d(TAG, "рџЋ§ Starting audio playback in foreground service")

            // Р­С‚Р°Рї D: Update metrics - buffering status
            metricsManager.updateAudioStatus(AudioStatus.BUFFERING)
            metricsManager.updateWsStatus(WsStatus.CONNECTING)

            // Start foreground with notification IMMEDIATELY (required by Android)
            startForeground(NOTIFICATION_ID, createNotification("РџРѕРґРєР»СЋС‡РµРЅРёРµ..."))

            if (recording) {
                startLocalRecording()
            } else {
                stopLocalRecording(save = false)
            }

            // Acquire WakeLock (keeps CPU awake)
            wakeLock?.acquire(60*60*1000L /* 1 hour */)
            Log.d(TAG, "рџ”‹ WakeLock acquired")

            // Acquire WiFi Lock (keeps WiFi active to prevent disconnection)
            wifiLock?.acquire()
            Log.d(TAG, "рџ“¶ WiFi Lock acquired (FULL_HIGH_PERF mode)")

            // Improvement: Request audio focus to handle calls/notifications
            val focusResult = audioManager?.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(TAG, "рџЋµ Audio focus granted")
            } else {
                Log.w(TAG, "рџЋµ Audio focus NOT granted (other app playing?)")
            }

            // Force media to speaker to avoid silent routing on some devices.
            audioManager?.let { manager ->
                previousAudioMode = manager.mode
                previousSpeakerphoneState = manager.isSpeakerphoneOn
                manager.mode = AudioManager.MODE_NORMAL
                manager.isSpeakerphoneOn = true
                Log.d(
                    TAG,
                    "Audio route forced to speaker (prevMode=$previousAudioMode, prevSpeaker=$previousSpeakerphoneState)"
                )
            }

            lifecycleScope.launch {
                try {
                    // Inform server that parent is listening before starting playback
                    networkClient?.let { client ->
                        val registered = client.startAudioStreaming(
                            serverUrl,
                            deviceId,
                            recording,
                            STREAM_TIMEOUT_MINUTES,
                            requestedSampleRate
                        )
                        if (!registered) {
                            Log.w(TAG, "Failed to register parent listener on server for $deviceId")
                        }
                    }

                    // SIMPLIFIED ARCHITECTURE - Direct WebSocket connection
                    // No HTTP request needed - connect directly to WebSocket
                    Log.d(TAG, "рџЋ§ Starting direct WebSocket connection (simplified architecture)")

                    isPlaying = true
                    AudioPlaybackService.isPlaying = true
                    streamingStartTime = System.currentTimeMillis()
                    AudioPlaybackService.streamingStartTime = streamingStartTime
                    chunksReceived = 0
                    AudioPlaybackService.chunksReceived = 0
                    lastReceivedSequence = -1

                    // Initialize audio playback
                    initializeAudioTrack()
                    if (audioTrack == null) {
                        val errorText = audioTrackInitError ?: "AudioTrack init failed"
                        updateNotification("РћС€РёР±РєР° Р°СѓРґРёРѕ: $errorText")
                        Log.e(TAG, "Cannot start playback: $errorText")
                        stopSelf()
                        return@launch
                    }

                    // Connect to WebSocket directly
                    connectWebSocket()

                    updateNotification(getString(R.string.audio_monitor_status_active))
                    currentStatus = getString(R.string.audio_monitor_status_active)
                    AudioPlaybackService.currentStatus = currentStatus
                    Log.d(TAG, "вњ… Direct WebSocket streaming started at $streamingStartTime")
                    startStreamWatchdog()

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting playback", e)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in startPlayback", e)
            // Ensure foreground is started even if there's an error
            try {
                startForeground(NOTIFICATION_ID, createNotification("РћС€РёР±РєР° Р·Р°РїСѓСЃРєР°"))
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to start foreground after error", ex)
            }
            stopSelf()
        }
    }

    private fun handleCriticalAlert(alert: ru.example.childwatch.network.WebSocketClient.CriticalAlertMessage) {
        val title = getString(R.string.critical_alert_title)
        AlertNotifier.show(this, title, alert.message, notificationId = alert.id.toInt())

        val currentDeviceId = deviceId
        val currentServerUrl = serverUrl
        val client = networkClient

        if (client != null && !currentDeviceId.isNullOrBlank() && !currentServerUrl.isNullOrBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    client.acknowledgeCriticalAlerts(currentServerUrl, currentDeviceId, listOf(alert.id))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to acknowledge alert ${alert.id}", e)
                }
            }
        }
    }

    /**
     * Pause playback temporarily (for incoming calls, etc.)
     * Can be resumed without full reconnection
     */
    private fun pausePlayback() {
        Log.d(TAG, "вЏёпёЏ Pausing audio playback")
        isPlaying = false

        // Stop playback job but keep everything else
        playbackJob?.cancel()
        playbackJob = null

        // Pause audio track
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            Log.d(TAG, "вњ… AudioTrack paused")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AudioTrack", e)
        }

        // Note: Keep the same status (PLAYING) so we know to resume
    }

    private fun stopPlayback() {
        Log.d(TAG, "рџ›‘ Stopping audio playback")

        // CRITICAL: Set flags FIRST to stop all loops
        isPlaying = false
        AudioPlaybackService.isPlaying = false
        val stopDeviceId = deviceId
        val stopServerUrl = serverUrl
        val stopNetworkClient = networkClient

        // Send WS stop before socket cleanup (best effort).
        runCatching {
            webSocketClient?.sendCommand("stop_audio_stream")
        }.onFailure {
            Log.w(TAG, "WS stop command failed before cleanup: ${it.message}")
        }
        stopLocalRecording(save = localRecordingActive)
        startCommandJob?.cancel()
        streamWatchdogJob?.cancel()
        startCommandJob = null
        streamWatchdogJob = null

        Log.d(TAG, "вњ… isPlaying set to false")

        // Cancel playback job immediately
        playbackJob?.cancel()
        playbackJob = null
        Log.d(TAG, "вњ… Playback job cancelled")

        // Stop and release AudioTrack FIRST (force stop audio)
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
                it.stop()
                it.release()
                Log.d(TAG, "вњ… AudioTrack stopped and released")
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }

        // Clear queue
        chunkQueue.clear()
        Log.d(TAG, "вњ… Chunk queue cleared")

        // Disconnect WebSocket
        webSocketClient?.cleanup()
        webSocketClient = null
        Log.d(TAG, "вњ… WebSocket disconnected")

        // Release WakeLock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "вњ… WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }

        // Release WiFi Lock
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "вњ… WiFi Lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WiFi Lock", e)
        }

        // Improvement: Release audio focus so other apps can play
        try {
            audioManager?.abandonAudioFocus(audioFocusListener)
            Log.d(TAG, "рџЋµ Audio focus abandoned")
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }

        // Restore original audio route settings.
        try {
            audioManager?.let { manager ->
                manager.isSpeakerphoneOn = previousSpeakerphoneState
                manager.mode = previousAudioMode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring audio route", e)
        }

        // Reset all state
        chunksReceived = 0
        AudioPlaybackService.chunksReceived = 0
        lastReceivedSequence = -1
        currentStatus = "РћСЃС‚Р°РЅРѕРІР»РµРЅРѕ"
        AudioPlaybackService.currentStatus = currentStatus
        connectionQuality = "--"
        AudioPlaybackService.connectionQuality = connectionQuality
        requestedSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        inputSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        inputFrameBytes = DEFAULT_FRAME_BYTES
        outputSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        outputFrameBytes = DEFAULT_FRAME_BYTES
        playbackSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        requestedStreamSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        inputStreamSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        audioTrackInitError = null
        metricsManager.update { metrics ->
            metrics.copy(
                sampleRate = DEFAULT_STREAM_SAMPLE_RATE,
                channelCount = STREAM_CHANNEL_COUNT,
                frameSize = DEFAULT_FRAME_BYTES
            )
        }
        Log.d(TAG, "вњ… State reset")

        // Stop streaming on server (detached scope to avoid cancellation during stopSelf)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val id = stopDeviceId
                val url = stopServerUrl
                if (!id.isNullOrBlank() && !url.isNullOrBlank()) {
                    stopNetworkClient?.stopAudioStreaming(url, id)
                    Log.d(TAG, "Server notified to stop streaming")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping streaming on server", e)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "вњ… Service stopped")
    }

    private fun toggleRecording(recording: Boolean) {
        if (!isPlaying) return

        isRecording = recording

        if (recording) {
            startLocalRecording()
        } else {
            stopLocalRecording(save = true)
        }

        lifecycleScope.launch {
            try {
                val id = deviceId ?: return@launch
                val url = serverUrl ?: return@launch

                val success = if (recording) {
                    networkClient?.startRecording(url, id)
                } else {
                    networkClient?.stopRecording(url, id)
                }

                if (success == true) {
                    val status = if (recording) {
                        getString(R.string.audio_monitor_status_recording)
                    } else {
                        getString(R.string.audio_monitor_status_active)
                    }
                    updateNotification(status)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling recording", e)
            }
        }
    }

    /**
     * Set volume level (0-100)
     */
    fun setVolume(volumePercent: Int) {
        val volume = (volumePercent / 100f).coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
        Log.d(TAG, "рџ”Љ Volume set to $volumePercent%")
    }

    /**
     * Calculate connection quality based on chunk arrival timing
     */
    private fun updateConnectionQuality() {
        val currentTime = System.currentTimeMillis()
        if (lastChunkTimestamp > 0) {
            val timeSinceLastChunk = currentTime - lastChunkTimestamp

            connectionQuality = when {
                timeSinceLastChunk < 2500 -> "Excellent"
                timeSinceLastChunk < 4000 -> "Good"
                timeSinceLastChunk < 6000 -> "Fair"
                else -> "Poor"
            }
            AudioPlaybackService.connectionQuality = connectionQuality
        }
        lastChunkTimestamp = currentTime
        AudioPlaybackService.lastChunkTimestamp = lastChunkTimestamp
    }

    private fun sanitizeInputSampleRate(rate: Int): Int {
        return if (INPUT_SAMPLE_RATES.contains(rate)) rate else DEFAULT_STREAM_SAMPLE_RATE
    }

    private fun frameBytesForRate(rate: Int): Int {
        return ((rate * FRAME_MS) / 1000) * 2
    }

    private fun inferRateFromChunkSize(chunkSize: Int): Int? {
        if (chunkSize <= 0) return null
        return INPUT_SAMPLE_RATES.firstOrNull { frameBytesForRate(it) == chunkSize }
    }

    private fun resolveInputSampleRate(rateFromMeta: Int, chunkSize: Int): Int {
        val normalizedMeta = sanitizeInputSampleRate(rateFromMeta)
        val inferred = inferRateFromChunkSize(chunkSize)
        return inferred ?: normalizedMeta
    }

    private fun normalizeIncomingFrame(data: ByteArray, expectedSize: Int): ByteArray {
        if (expectedSize <= 0) return data
        return when {
            data.size == expectedSize -> data
            data.size > expectedSize -> data.copyOf(expectedSize)
            else -> ByteArray(expectedSize).also { out ->
                System.arraycopy(data, 0, out, 0, data.size)
            }
        }
    }

    private fun offerChunkToQueue(chunk: ByteArray, logDrops: Boolean) {
        val added = chunkQueue.offer(chunk)
        if (!added) {
            chunkQueue.poll()
            chunkQueue.offer(chunk)
            if (logDrops && chunksReceived < 20) {
                Log.w(TAG, "AUDIO queue full, dropped head")
            }
        }
    }

    private fun startLocalRecording() {
        if (localRecordingActive) return
        val recorder = streamRecorder ?: StreamRecorder(this).also { streamRecorder = it }
        if (recorder.start(inputSampleRate, STREAM_CHANNEL_COUNT)) {
            localRecordingActive = true
            showToast("Р—Р°РїРёСЃСЊ РїСЂРѕСЃР»СѓС€РєРё РІРєР»СЋС‡РµРЅР°")
        } else {
            showToast("РќРµ СѓРґР°Р»РѕСЃСЊ РЅР°С‡Р°С‚СЊ Р·Р°РїРёСЃСЊ РїСЂРѕСЃР»СѓС€РєРё", Toast.LENGTH_LONG)
        }
    }

    private fun stopLocalRecording(save: Boolean) {
        val recorder = streamRecorder ?: return
        val shouldSave = save && localRecordingActive
        val metadata = if (shouldSave) {
            recorder.stop()
        } else {
            recorder.cancel()
            null
        }
        localRecordingActive = false
        if (metadata != null) {
            recordingRepository.addRecording(metadata)
            showToast("Р—Р°РїРёСЃСЊ СЃРѕС…СЂР°РЅРµРЅР°: ${'$'}{metadata.fileName}")
        } else if (shouldSave) {
            showToast("Р—Р°РїРёСЃСЊ РЅРµ СЃРѕРґРµСЂР¶РёС‚ РґР°РЅРЅС‹С…")
        }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            Toast.makeText(this, message, duration).show()
        }
    }
    private fun initializeAudioTrack() {
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        var lastError: String? = null
        val playbackCandidates = linkedSetOf(
            inputSampleRate,
            requestedSampleRate,
            48_000,
            32_000,
            24_000,
            16_000
        ).filter { it > 0 && PLAYBACK_SAMPLE_RATES.contains(it) }

        audioTrack?.runCatching {
            pause()
            flush()
            stop()
            release()
        }
        audioTrack = null

        for (candidateRate in playbackCandidates) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(candidateRate, channelConfig, audioFormat)
                if (minBuf <= 0) {
                    lastError = "Invalid min buffer for ${candidateRate}Hz: $minBuf"
                    continue
                }
                val actualBuf = minBuf * 8

                val candidateTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(candidateRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(actualBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                candidateTrack.play()
                val playState = candidateTrack.playState
                if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                    candidateTrack.release()
                    lastError = "AudioTrack playState=$playState for ${candidateRate}Hz"
                    continue
                }

                audioTrack = candidateTrack
                outputSampleRate = candidateRate
                outputFrameBytes = ((candidateRate * FRAME_MS) / 1000) * 2
                playbackSampleRate = candidateRate
                audioTrackInitError = null
                metricsManager.update { metrics ->
                    metrics.copy(
                        sampleRate = inputSampleRate,
                        channelCount = STREAM_CHANNEL_COUNT,
                        frameSize = inputFrameBytes
                    )
                }
                Log.d(
                    TAG,
                    "AUDIO player init: in=${inputSampleRate}Hz out=${candidateRate}Hz, minBuf=$minBuf, actualBuf=$actualBuf, jitter=${JITTER_BUFFER_MIN_FRAMES} frames"
                )
                return
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "AudioTrack init failed for ${candidateRate}Hz: $lastError")
            }
        }

        audioTrackInitError = lastError ?: "No supported playback sample rate"
        metricsManager.reportError(
            "AudioTrack init failed: ${audioTrackInitError}",
            ErrorSeverity.ERROR
        )
        Log.e(TAG, "AUDIO player init ERROR: ${audioTrackInitError}")
    }

    private fun resamplePcm16Mono(input: ByteArray, inRate: Int, outRate: Int): ByteArray {
        if (inRate <= 0 || outRate <= 0 || inRate == outRate || input.size < 4) {
            return input
        }

        val inSamples = input.size / 2
        if (inSamples <= 1) return input

        val outSamples = ((inSamples.toLong() * outRate) / inRate).toInt().coerceAtLeast(1)
        val inputShorts = ShortArray(inSamples)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputShorts)
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

        val output = ByteBuffer.allocate(outSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        output.asShortBuffer().put(outputShorts)
        return output.array()
    }

    private fun connectWebSocket() {

        try {
            val deviceId = this.deviceId ?: return
            val serverUrl = this.serverUrl ?: return

            webSocketClient = WebSocketClient(serverUrl, deviceId)

            // Set callback for receiving audio chunks (Р­С‚Р°Рї A - jitter buffer)
            webSocketClient?.setAudioChunkCallback { audioData, sequence, timestamp, sampleRate, channels ->
                totalBytesReceived += audioData.size

                val resolvedRate = resolveInputSampleRate(sampleRate, audioData.size)
                if (resolvedRate != inputSampleRate) {
                    val previousRate = inputSampleRate
                    inputSampleRate = resolvedRate
                    inputFrameBytes = frameBytesForRate(inputSampleRate)
                    inputStreamSampleRate = inputSampleRate
                    metricsManager.update { metrics ->
                        metrics.copy(
                            sampleRate = inputSampleRate,
                            channelCount = STREAM_CHANNEL_COUNT,
                            frameSize = inputFrameBytes
                        )
                    }
                    // Flush queued old-rate frames first, then rebuild/flush player state.
                    isBuffering = true
                    chunkQueue.clear()

                    // Rebuild AudioTrack on real input-rate change to avoid long-term resampling artifacts.
                    if (outputSampleRate != inputSampleRate) {
                        Log.i(TAG, "Input sample-rate changed ${previousRate}Hz -> ${inputSampleRate}Hz, reinitializing AudioTrack")
                        initializeAudioTrack()
                    } else {
                        runCatching {
                            audioTrack?.pause()
                            audioTrack?.flush()
                            audioTrack?.play()
                        }.onFailure {
                            Log.w(TAG, "AudioTrack flush after rate switch failed: ${it.message}")
                        }
                    }
                }
                if (lastReceivedSequence >= 0 && sequence > lastReceivedSequence + 1) {
                    val missingFrames = (sequence - lastReceivedSequence - 1).coerceAtMost(6)
                    repeat(missingFrames) {
                        offerChunkToQueue(ByteArray(inputFrameBytes), logDrops = false)
                    }
                    Log.w(TAG, "Detected audio gap: seq $lastReceivedSequence -> $sequence, inserted $missingFrames silence frame(s)")
                }
                lastReceivedSequence = sequence

                val normalizedChunk = normalizeIncomingFrame(audioData, inputFrameBytes)
                offerChunkToQueue(normalizedChunk, logDrops = true)

                chunksReceived++
                AudioPlaybackService.chunksReceived = chunksReceived
                lastChunkTimestamp = System.currentTimeMillis()
                AudioPlaybackService.lastChunkTimestamp = lastChunkTimestamp
                if (chunksReceived == 1) {
                    startCommandJob?.cancel()
                    startCommandJob = null
                }

                // Р­С‚Р°Рї A & D: Calculate and log metrics every ~2 seconds
                val now = System.currentTimeMillis()
                if (now - lastMetricsLogTime >= 2000) {
                    val queueDepth = chunkQueue.size
                    val bytesPerSecond = (totalBytesReceived * 1000) / (now - streamingStartTime).coerceAtLeast(1)

                    // Task 2: Enhanced logging with queue health status
                    val queueStatus = when {
                        queueDepth in 8..11 -> "OPTIMAL"
                        queueDepth < 8 -> "LOW"
                        queueDepth > JITTER_BUFFER_AGGRESSIVE_THRESHOLD -> "HIGH"
                        else -> "OK"
                    }
                    Log.d(TAG, "AUDIO recv queueDepth=$queueDepth [$queueStatus], bytes/s=$bytesPerSecond, total=${totalBytesReceived}B, underruns=$underrunCount")

                    // Р­С‚Р°Рї D: Update metrics manager
                    metricsManager.updateDataRate(bytesPerSecond, chunksReceived.toLong())
                    metricsManager.updateQueue(queueDepth, JITTER_BUFFER_MAX_FRAMES)

                    lastMetricsLogTime = now
                }

                // Update connection quality based on timing
                updateConnectionQuality()
            }

            webSocketClient?.setCriticalAlertCallback { alert ->
                handleCriticalAlert(alert)
            }

            // Set callback for child disconnect
            webSocketClient?.setChildDisconnectedCallback {
                updateNotification("РЈСЃС‚СЂРѕР№СЃС‚РІРѕ РѕС‚РєР»СЋС‡РёР»РѕСЃСЊ")
            }

            webSocketClient?.setChildConnectedCallback {
                // Child acknowledged socket registration; ensure streaming command delivered
                lifecycleScope.launch { sendStartCommand("child_connected") }
            }

            webSocketClient?.setRegisteredCallback {
                lifecycleScope.launch {
                    // Require connected + registered before issuing WS start commands
                    sendStartCommand("registered")
                    startStartCommandRepeater()
                }
            }

            // Connect
            webSocketClient?.connect(
                onConnected = {
                    Log.d(TAG, "вњ… WebSocket connected")

                    // Р­С‚Р°Рї D: Update metrics - connected
                    metricsManager.updateWsStatus(WsStatus.CONNECTED)

                    webSocketClient?.startHeartbeat()
                    webSocketClient?.requestRegistration()
                    startPlaybackJob()
                },
                onError = { error ->
                    Log.e(TAG, "вќЊ WebSocket error: $error")

                    // Р­С‚Р°Рї D: Report error
                    metricsManager.updateWsStatus(WsStatus.ERROR)
                    metricsManager.reportError("WebSocket error: $error", ErrorSeverity.ERROR)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting WebSocket", e)
        }
    }

    private fun startPlaybackJob() {
        isBuffering = true
        chunkQueue.clear()
        underrunCount = 0

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "AUDIO playback loop started")

            while (isActive && isPlaying) {
                try {
                    // Wait for initial jitter buffer to fill
                    if (isBuffering) {
                        if (chunkQueue.size >= JITTER_BUFFER_MIN_FRAMES) {
                            isBuffering = false
                            updateNotification("Р’РѕСЃРїСЂРѕРёР·РІРµРґРµРЅРёРµ...")
                            Log.d(TAG, "AUDIO buffer filled (${chunkQueue.size} frames), starting playback")

                            // Р­С‚Р°Рї D: Update status to playing
                            metricsManager.updateAudioStatus(AudioStatus.PLAYING)
                        } else {
                            delay(20) // Wait one frame duration
                            continue
                        }
                    }

                    // Р­С‚Р°Рї A: poll() with timeout
                    val chunk = withTimeoutOrNull(50) {
                        // Blocking poll - will wait if queue empty
                        val frame = chunkQueue.poll()
                        frame
                    }

                    val track = audioTrack
                    if (track != null && isPlaying && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { track.play() }
                            .onFailure { Log.w(TAG, "AudioTrack replay failed: ${it.message}") }
                    }

            if (chunk != null && isPlaying && track?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        // Process with filter
                        val processedChunk = audioEnhancer.process(chunk)
                        val playbackChunk = if (outputSampleRate != inputSampleRate) {
                            resamplePcm16Mono(processedChunk, inputSampleRate, outputSampleRate)
                        } else {
                            processedChunk
                        }

                        // Write to AudioTrack
                        val written = track.write(playbackChunk, 0, playbackChunk.size, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) {
                            Log.e(TAG, "AudioTrack write error: $written")
                            if (written == AudioTrack.ERROR_DEAD_OBJECT || written == AudioTrack.ERROR_INVALID_OPERATION) {
                                initializeAudioTrack()
                            }
                            delay(20)
                            continue
                        }

                        // Р­С‚Р°Рї A & D: track underruns
                        if (written < playbackChunk.size) {
                            underrunCount++
                            metricsManager.incrementUnderrun() // Р­С‚Р°Рї D
                            if (underrunCount < 10) { // Log first few
                                Log.w(TAG, "AUDIO write underrun: wrote $written < ${playbackChunk.size}")
                            }
                        }

                        // Send chunk to waveform visualizer
                        waveformCallback?.invoke(processedChunk)

                        if (localRecordingActive) {
                            streamRecorder?.write(processedChunk)
                        }
                    } else {
                        // Р­С‚Р°Рї A & D: Queue empty - silence, but don't block UI
                        if (chunkQueue.isEmpty()) {
                            underrunCount++
                            metricsManager.incrementUnderrun() // Р­С‚Р°Рї D
                            if (underrunCount % 10 == 1) { // Log every 10th
                                Log.w(TAG, "AUDIO queue empty (underrun #$underrunCount)")
                            }
                            delay(20) // Wait one frame before retrying
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "AUDIO playback loop error", e)
                    delay(100) // Back off on error
                }
            }

            Log.d(TAG, "AUDIO playback loop exited")
        }
    }

    private fun resolvePreferredTargetDeviceId(initialDeviceId: String?): String? {
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val ownId = prefs.getString("device_id", null)?.trim().orEmpty()
        val excluded = listOf(
            ownId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        return listOf(
            initialDeviceId,
            prefs.getString("selected_device_id", null),
            prefs.getString("child_device_id", null),
            legacyPrefs.getString("selected_device_id", null),
            legacyPrefs.getString("child_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && it !in excluded }
    }

    /**
     * Re-send start command until the first audio chunk is received.
     * Helps when the initial HTTP trigger is missed after reboot/network change.
     */
    private fun startStartCommandRepeater() {
        startCommandJob?.cancel()
        startCommandJob = lifecycleScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && isPlaying && chunksReceived == 0) {
                attempt++
                sendStartCommand("retry_$attempt")
                val delayMs = (3000L * attempt).coerceAtMost(12_000L)
                delay(delayMs)
            }
        }
    }

    /**
     * Send start command via both HTTP (polling compatibility) and WebSocket (real-time).
     */
    private suspend fun sendStartCommand(reason: String) {
        val id = deviceId
        val url = serverUrl
        if (id.isNullOrBlank() || url.isNullOrBlank()) return

        Log.d(TAG, "Sending start command to child (reason=$reason)")

        runCatching {
            networkClient?.startAudioStreaming(url, id, isRecording, STREAM_TIMEOUT_MINUTES, requestedSampleRate)
        }.onFailure { Log.w(TAG, "HTTP start command failed: ${it.message}") }

        if (webSocketClient?.isReady() == true) {
            val data = JSONObject().apply {
                put("recording", isRecording)
                put("sampleRate", requestedSampleRate)
            }
            runCatching {
                webSocketClient?.sendCommand("start_audio_stream", data)
            }.onFailure { Log.w(TAG, "WS start command failed: ${it.message}") }
        } else {
            Log.d(TAG, "WS command skipped (not ready yet): reason=$reason")
        }
    }

    /**
     * Watchdog to revive audio stream if chunks stop arriving.
     */
    private fun startStreamWatchdog() {
        streamWatchdogJob?.cancel()
        streamWatchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isPlaying) {
                val last = if (lastChunkTimestamp > 0) lastChunkTimestamp else streamingStartTime
                val silenceFor = System.currentTimeMillis() - last
                if (silenceFor > 10_000) {
                    Log.w(TAG, "No audio for ${silenceFor}ms, re-sending start command")
                    sendStartCommand("watchdog_${silenceFor}ms")
                }
                delay(5_000)
            }
        }
    }

    /**
     * Best-effort stop signal to child over WebSocket.
     */
    private suspend fun sendStopCommand() {
        if (webSocketClient?.isConnected() == true) {
            runCatching {
                webSocketClient?.sendCommand("stop_audio_stream", null)
            }.onFailure { Log.w(TAG, "WS stop command failed: ${it.message}") }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.audio_monitor_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.audio_monitor_notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.audio_monitor_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val diagnosticTail = if (isPlaying) {
            val sr = "${playbackSampleRate / 1000.0}kHz"
            val ageMs = if (lastChunkTimestamp > 0L) {
                (System.currentTimeMillis() - lastChunkTimestamp).coerceAtLeast(0L)
            } else {
                -1L
            }
            val ageText = if (ageMs >= 0L) "${ageMs}ms" else "вЂ”"
            " вЂў $sr вЂў #$chunksReceived вЂў ${ageText}"
        } else {
            ""
        }
        val fullText = contentText + diagnosticTail
        currentStatus = fullText
        AudioPlaybackService.currentStatus = fullText
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(fullText))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AudioPlaybackService destroyed")
        stopLocalRecording(save = false)

        // Р­С‚Р°Рї D: Cleanup metrics manager
        metricsManager.destroy()

        // Unregister broadcast receiver
        try {
            unregisterReceiver(filterModeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering filter mode receiver", e)
        }

        startCommandJob?.cancel()
        streamWatchdogJob?.cancel()
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        webSocketClient?.cleanup()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }

        try {
            audioManager?.let { manager ->
                manager.isSpeakerphoneOn = previousSpeakerphoneState
                manager.mode = previousAudioMode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring audio route on destroy", e)
        }

        isPlaying = false
        AudioPlaybackService.isPlaying = false
        playbackSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        requestedStreamSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        inputStreamSampleRate = DEFAULT_STREAM_SAMPLE_RATE
        audioTrackInitError = null
    }
}

