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
        private const val TAG = "AUDIO" // Этап A: unified tag
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "audio_playback_channel"

        // Этап A: Optimized for 16 kHz, 20ms frames
        private const val STREAM_SAMPLE_RATE = 16_000   // 16 kHz (was 44100)
        private const val STREAM_CHANNEL_COUNT = 1       // MONO
        private const val FRAME_MS = 20                  // 20ms frames
        private const val FRAME_BYTES = (STREAM_SAMPLE_RATE * FRAME_MS / 1000) * 2 // 640 bytes

        // Jitter buffer: 150-220 ms = 8-11 frames of 20ms
        private const val JITTER_BUFFER_MIN_FRAMES = 8   // 160 ms
        private const val JITTER_BUFFER_MAX_FRAMES = 100 // Max queue size (2 sec)

        const val ACTION_START_PLAYBACK = "ru.example.childwatch.START_PLAYBACK"
        const val ACTION_STOP_PLAYBACK = "ru.example.childwatch.STOP_PLAYBACK"
        const val ACTION_TOGGLE_RECORDING = "ru.example.childwatch.TOGGLE_RECORDING"

        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_RECORDING = "recording"

        // Service state
        var isPlaying = false
            private set
        var chunksReceived = 0
            private set
        var currentStatus = "Остановлено"
            private set
        var connectionQuality = "Отлично"
            private set
        var lastChunkTimestamp = 0L
            private set
        var streamingStartTime = 0L
            private set

        fun startPlayback(context: Context, deviceId: String, serverUrl: String, recording: Boolean = false) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START_PLAYBACK
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_RECORDING, recording)
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

    // Этап D: Metrics manager for diagnostics
    lateinit var metricsManager: MetricsManager
        private set
    private var streamRecorder: StreamRecorder? = null
    private var localRecordingActive = false

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
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var isRecording = false
    private var streamingStartTime: Long = 0L

    // Jitter buffer (Этап A: ArrayBlockingQueue with 50-100 frames capacity)
    private val chunkQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(JITTER_BUFFER_MAX_FRAMES)
    private var isBuffering = true
    private var bufferUnderrunCount = 0

    // Metrics (Этап A)
    private var totalBytesReceived = 0L
    private var lastMetricsLogTime = 0L
    private var underrunCount = 0

    // Waveform callback
    private var waveformCallback: ((ByteArray) -> Unit)? = null

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

    fun setFilterMode(mode: FilterMode) {
        val currentConfig = audioEnhancer.getConfig()
        audioEnhancer.updateConfig(currentConfig.copy(mode = mode))
        Log.d(TAG, "Filter mode changed to: $mode")
    }

    fun getAudioEnhancerConfig(): AudioEnhancer.Config = audioEnhancer.getConfig()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioPlaybackService created")

        // Этап D: Initialize metrics manager
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
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                val recording = intent.getBooleanExtra(EXTRA_RECORDING, false)
                
                Log.d(TAG, "Starting playback with deviceId: $deviceId, serverUrl: $serverUrl, recording: $recording")
                
                if (deviceId.isNullOrEmpty() || serverUrl.isNullOrEmpty()) {
                    Log.e(TAG, "Missing required parameters - deviceId: $deviceId, serverUrl: $serverUrl")
                    // Start foreground anyway to prevent crash
                    startForeground(NOTIFICATION_ID, createNotification("Ошибка: неверные параметры"))
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
                startForeground(NOTIFICATION_ID, createNotification("Неизвестная команда"))
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

            Log.d(TAG, "🎧 Starting audio playback in foreground service")

            // Этап D: Update metrics - buffering status
            metricsManager.updateAudioStatus(AudioStatus.BUFFERING)
            metricsManager.updateWsStatus(WsStatus.CONNECTING)

            // Start foreground with notification IMMEDIATELY (required by Android)
            startForeground(NOTIFICATION_ID, createNotification("Подключение..."))

            if (recording) {
                startLocalRecording()
            } else {
                stopLocalRecording(save = false)
            }

            // Acquire WakeLock (keeps CPU awake)
            wakeLock?.acquire(60*60*1000L /* 1 hour */)
            Log.d(TAG, "🔋 WakeLock acquired")

            // Acquire WiFi Lock (keeps WiFi active to prevent disconnection)
            wifiLock?.acquire()
            Log.d(TAG, "📶 WiFi Lock acquired (FULL_HIGH_PERF mode)")

            lifecycleScope.launch {
                try {
                    // Inform server that parent is listening before starting playback
                    networkClient?.let { client ->
                        val registered = client.startAudioStreaming(serverUrl, deviceId, recording)
                        if (!registered) {
                            Log.w(TAG, "Failed to register parent listener on server for $deviceId")
                        }
                    }

                    // SIMPLIFIED ARCHITECTURE - Direct WebSocket connection
                    // No HTTP request needed - connect directly to WebSocket
                    Log.d(TAG, "🎧 Starting direct WebSocket connection (simplified architecture)")

                    isPlaying = true
                    AudioPlaybackService.isPlaying = true
                    streamingStartTime = System.currentTimeMillis()
                    AudioPlaybackService.streamingStartTime = streamingStartTime
                    chunksReceived = 0
                    AudioPlaybackService.chunksReceived = 0

                    // Initialize audio playback
                    initializeAudioTrack()

                    // Connect to WebSocket directly
                    connectWebSocket()

                    updateNotification("Прослушка активна")
                    currentStatus = "Прослушка активна"
                    AudioPlaybackService.currentStatus = currentStatus
                    Log.d(TAG, "✅ Direct WebSocket streaming started at $streamingStartTime")

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting playback", e)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in startPlayback", e)
            // Ensure foreground is started even if there's an error
            try {
                startForeground(NOTIFICATION_ID, createNotification("Ошибка запуска"))
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

    private fun stopPlayback() {
        Log.d(TAG, "🛑 Stopping audio playback")

        // CRITICAL: Set flags FIRST to stop all loops
        isPlaying = false
        AudioPlaybackService.isPlaying = false
        stopLocalRecording(save = localRecordingActive)

        Log.d(TAG, "✅ isPlaying set to false")

        // Cancel playback job immediately
        playbackJob?.cancel()
        playbackJob = null
        Log.d(TAG, "✅ Playback job cancelled")

        // Stop and release AudioTrack FIRST (force stop audio)
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
                it.stop()
                it.release()
                Log.d(TAG, "✅ AudioTrack stopped and released")
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }

        // Clear queue
        chunkQueue.clear()
        Log.d(TAG, "✅ Chunk queue cleared")

        // Disconnect WebSocket
        webSocketClient?.cleanup()
        webSocketClient = null
        Log.d(TAG, "✅ WebSocket disconnected")

        // Release WakeLock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "✅ WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }

        // Release WiFi Lock
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "✅ WiFi Lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WiFi Lock", e)
        }

        // Reset all state
        chunksReceived = 0
        AudioPlaybackService.chunksReceived = 0
        currentStatus = "Остановлено"
        AudioPlaybackService.currentStatus = currentStatus
        connectionQuality = "--"
        AudioPlaybackService.connectionQuality = connectionQuality
        Log.d(TAG, "✅ State reset")

        // Stop streaming on server
        lifecycleScope.launch {
            try {
                deviceId?.let { id ->
                    serverUrl?.let { url ->
                        networkClient?.stopAudioStreaming(url, id)
                        Log.d(TAG, "✅ Server notified to stop streaming")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping streaming on server", e)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "✅ Service stopped")
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
                deviceId?.let { id ->
                    serverUrl?.let { url ->
                        val success = if (recording) {
                            networkClient?.startRecording(url, id)
                        } else {
                            networkClient?.stopRecording(url, id)
                        }

                        if (success == true) {
                            val status = if (recording) "Запись активна" else "Прослушка активна"
                            updateNotification(status)
                        }
                    }
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
        Log.d(TAG, "🔊 Volume set to $volumePercent%")
    }

    /**
     * Calculate connection quality based on chunk arrival timing
     */
    private fun updateConnectionQuality() {
        val currentTime = System.currentTimeMillis()
        if (lastChunkTimestamp > 0) {
            val timeSinceLastChunk = currentTime - lastChunkTimestamp

            connectionQuality = when {
                timeSinceLastChunk < 2500 -> "Отлично"
                timeSinceLastChunk < 4000 -> "Хорошо"
                timeSinceLastChunk < 6000 -> "Удовл."
                else -> "Плохо"
            }
            AudioPlaybackService.connectionQuality = connectionQuality
        }
        lastChunkTimestamp = currentTime
        AudioPlaybackService.lastChunkTimestamp = lastChunkTimestamp
    }

    private fun startLocalRecording() {
        if (localRecordingActive) return
        val recorder = streamRecorder ?: StreamRecorder(this).also { streamRecorder = it }
        if (recorder.start(STREAM_SAMPLE_RATE, STREAM_CHANNEL_COUNT)) {
            localRecordingActive = true
            showToast("Запись прослушки включена")
        } else {
            showToast("Не удалось начать запись прослушки", Toast.LENGTH_LONG)
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
            showToast("Запись сохранена: ${'$'}{metadata.fileName}")
        } else if (shouldSave) {
            showToast("Запись не содержит данных")
        }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            Toast.makeText(this, message, duration).show()
        }
    }

    private fun initializeAudioTrack() {
        try {
            val sampleRate = STREAM_SAMPLE_RATE
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val actualBuf = minBuf * 8 // Larger buffer for smoother playback

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(actualBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()

            // Этап A: Required log
            Log.d(TAG, "AUDIO player init: sr=${sampleRate}Hz, ch=MONO, minBuf=$minBuf, actualBuf=$actualBuf, jitter=${JITTER_BUFFER_MIN_FRAMES} frames")

        } catch (e: Exception) {
            Log.e(TAG, "AUDIO player init ERROR", e)
        }
    }

    private fun connectWebSocket() {
        try {
            val deviceId = this.deviceId ?: return
            val serverUrl = this.serverUrl ?: return

            webSocketClient = WebSocketClient(serverUrl, deviceId)

            // Set callback for receiving audio chunks (Этап A - jitter buffer)
            webSocketClient?.setAudioChunkCallback { audioData, sequence, timestamp ->
                totalBytesReceived += audioData.size

                // Этап A: offer() to queue; if full, drop oldest (head)
                val added = chunkQueue.offer(audioData)
                if (!added) {
                    // Queue full - drop oldest frame to prevent latency buildup
                    chunkQueue.poll() // Drop head (oldest)
                    chunkQueue.offer(audioData) // Add new
                    if (chunksReceived < 20) { // Log only first few drops
                        Log.w(TAG, "AUDIO queue full, dropped head")
                    }
                }

                chunksReceived++
                AudioPlaybackService.chunksReceived = chunksReceived
                lastChunkTimestamp = timestamp
                AudioPlaybackService.lastChunkTimestamp = timestamp

                // Этап A & D: Calculate and log metrics every ~2 seconds
                val now = System.currentTimeMillis()
                if (now - lastMetricsLogTime >= 2000) {
                    val queueDepth = chunkQueue.size
                    val bytesPerSecond = (totalBytesReceived * 1000) / (now - streamingStartTime).coerceAtLeast(1)

                    Log.d(TAG, "AUDIO recv queueDepth=$queueDepth, bytes/s=$bytesPerSecond, total=${totalBytesReceived}B, underruns=$underrunCount")

                    // Этап D: Update metrics manager
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
                updateNotification("Устройство отключилось")
            }

            // Connect
            webSocketClient?.connect(
                onConnected = {
                    Log.d(TAG, "✅ WebSocket connected")

                    // Этап D: Update metrics - connected
                    metricsManager.updateWsStatus(WsStatus.CONNECTED)

                    webSocketClient?.startHeartbeat()
                    startPlaybackJob()
                },
                onError = { error ->
                    Log.e(TAG, "❌ WebSocket error: $error")

                    // Этап D: Report error
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
                    // Wait for initial jitter buffer to fill (Этап A: 160ms = 8 frames)
                    if (isBuffering) {
                        if (chunkQueue.size >= JITTER_BUFFER_MIN_FRAMES) {
                            isBuffering = false
                            updateNotification("Воспроизведение...")
                            Log.d(TAG, "AUDIO buffer filled (${chunkQueue.size} frames), starting playback")

                            // Этап D: Update status to playing
                            metricsManager.updateAudioStatus(AudioStatus.PLAYING)
                        } else {
                            delay(20) // Wait one frame duration
                            continue
                        }
                    }

                    // Этап A: poll() with timeout
                    val chunk = withTimeoutOrNull(50) {
                        // Blocking poll - will wait if queue empty
                        val frame = chunkQueue.poll()
                        frame
                    }

                    if (chunk != null && isPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        // Process with filter
                        val processedChunk = audioEnhancer.process(chunk)

                        // Write to AudioTrack
                        val written = audioTrack?.write(processedChunk, 0, processedChunk.size, AudioTrack.WRITE_BLOCKING) ?: 0

                        // Этап A & D: track underruns
                        if (written < processedChunk.size) {
                            underrunCount++
                            metricsManager.incrementUnderrun() // Этап D
                            if (underrunCount < 10) { // Log first few
                                Log.w(TAG, "AUDIO write underrun: wrote $written < ${processedChunk.size}")
                            }
                        }

                        // Send chunk to waveform visualizer
                        waveformCallback?.invoke(processedChunk)

                        if (localRecordingActive) {
                            streamRecorder?.write(processedChunk)
                        }
                    } else {
                        // Этап A & D: Queue empty - silence, but don't block UI
                        if (chunkQueue.isEmpty()) {
                            underrunCount++
                            metricsManager.incrementUnderrun() // Этап D
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Аудио прослушка",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о прослушке"
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
            .setContentTitle("ChildWatch - Прослушка")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        currentStatus = contentText
        AudioPlaybackService.currentStatus = contentText
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AudioPlaybackService destroyed")
        stopLocalRecording(save = false)

        // Этап D: Cleanup metrics manager
        metricsManager.destroy()

        // Unregister broadcast receiver
        try {
            unregisterReceiver(filterModeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering filter mode receiver", e)
        }

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

        isPlaying = false
        AudioPlaybackService.isPlaying = false
    }
}
