package ru.example.childwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import ru.example.childwatch.audio.RecordingRepository
import ru.example.childwatch.audio.StreamRecorder
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketClient

/**
 * Foreground Service for audio playback
 * Allows listening to continue when app is in background or screen is off
 */
class AudioPlaybackService : LifecycleService() {

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "audio_playback_channel"

        private const val MIN_BUFFER_CHUNKS = 3
        private const val MAX_BUFFER_CHUNKS = 6
        private const val STREAM_SAMPLE_RATE = 44100
        private const val STREAM_CHANNEL_COUNT = 1

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
    private var streamRecorder: StreamRecorder? = null
    private var localRecordingActive = false
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var isRecording = false
    private var streamingStartTime: Long = 0L

    // Buffering
    private val chunkQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private var isBuffering = true
    private var currentMinBuffer = MIN_BUFFER_CHUNKS
    private var bufferUnderrunCount = 0

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

    fun updateAudioEnhancer(noiseSuppression: Boolean, gainBoostDb: Int) {
        val normalizedGain = gainBoostDb.coerceIn(0, 12)
        audioEnhancer.updateConfig(AudioEnhancer.Config(noiseSuppressionEnabled = noiseSuppression, gainBoostDb = normalizedGain))
    }

    fun getAudioEnhancerConfig(): AudioEnhancer.Config = audioEnhancer.getConfig()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioPlaybackService created")

        networkClient = NetworkClient(this)
        recordingRepository = RecordingRepository(this)
        streamRecorder = StreamRecorder(this)
        createNotificationChannel()

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_PLAYBACK -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return START_NOT_STICKY
                val recording = intent.getBooleanExtra(EXTRA_RECORDING, false)

                startPlayback(deviceId, serverUrl, recording)
            }
            ACTION_STOP_PLAYBACK -> {
                stopPlayback()
            }
            ACTION_TOGGLE_RECORDING -> {
                val recording = intent.getBooleanExtra(EXTRA_RECORDING, false)
                toggleRecording(recording)
            }
        }

        return START_STICKY
    }

    private fun startPlayback(deviceId: String, serverUrl: String, recording: Boolean) {
        if (isPlaying) {
            Log.w(TAG, "Already playing")
            return
        }

        this.deviceId = deviceId
        this.serverUrl = serverUrl
        this.isRecording = recording

        if (recording) {
            startLocalRecording()
        } else {
            stopLocalRecording(save = false)
        }


        Log.d(TAG, "🎧 Starting audio playback in foreground service")

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification("Подключение..."))

        // Acquire WakeLock (keeps CPU awake)
        wakeLock?.acquire(60*60*1000L /* 1 hour */)
        Log.d(TAG, "🔋 WakeLock acquired")

        // Acquire WiFi Lock (keeps WiFi active to prevent disconnection)
        wifiLock?.acquire()
        Log.d(TAG, "📶 WiFi Lock acquired (FULL_HIGH_PERF mode)")

        lifecycleScope.launch {
            try {
                // HTTP request to start streaming
                val success = networkClient?.startAudioStreaming(serverUrl, deviceId, recording) ?: false

                if (success) {
                    isPlaying = true
                    AudioPlaybackService.isPlaying = true
                    streamingStartTime = System.currentTimeMillis()
                    AudioPlaybackService.streamingStartTime = streamingStartTime
                    chunksReceived = 0
                    AudioPlaybackService.chunksReceived = 0

                    // Initialize audio playback
                    initializeAudioTrack()

                    // Connect to WebSocket
                    connectWebSocket()

                    updateNotification("Прослушка активна")
                    currentStatus = "Прослушка активна"
                    AudioPlaybackService.currentStatus = currentStatus
                    Log.d(TAG, "✅ Streaming started at $streamingStartTime")
                } else {
                    Log.e(TAG, "Failed to start streaming")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting playback", e)
                stopSelf()
            }
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

            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

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
                .setBufferSizeInBytes(bufferSize * 8)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioTrack", e)
        }
    }

    private fun connectWebSocket() {
        try {
            val deviceId = this.deviceId ?: return
            val serverUrl = this.serverUrl ?: return

            webSocketClient = WebSocketClient(serverUrl, deviceId)

            // Set callback for receiving audio chunks
            webSocketClient?.setAudioChunkCallback { audioData, _, _ ->
                if (chunkQueue.size < MAX_BUFFER_CHUNKS) {
            }

            webSocketClient?.setCriticalAlertCallback { alert ->
                handleCriticalAlert(alert)
            }

            webSocketClient?.setAudioChunkCallback { audioData, _, _ ->

                    chunkQueue.offer(audioData)
                } else {
                    chunkQueue.poll()
                    chunkQueue.offer(audioData)
                }
                chunksReceived++
                AudioPlaybackService.chunksReceived = chunksReceived

                // Update connection quality based on timing
                updateConnectionQuality()

                // Start playback if buffered enough
                if (isBuffering && chunkQueue.size >= currentMinBuffer) {
                    isBuffering = false
                    bufferUnderrunCount = 0
                    updateNotification("Воспроизведение...")
                }
            }

            // Set callback for child disconnect
            webSocketClient?.setChildDisconnectedCallback {
                updateNotification("Устройство отключилось")
            }

            // Connect
            webSocketClient?.connect(
                onConnected = {
                    Log.d(TAG, "✅ WebSocket connected")
                    webSocketClient?.startHeartbeat()
                    startPlaybackJob()
                },
                onError = { error ->
                    Log.e(TAG, "❌ WebSocket error: $error")
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting WebSocket", e)
        }
    }

    private fun startPlaybackJob() {
        isBuffering = true
        chunkQueue.clear()

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "🎧 Starting continuous playback")

            while (isActive && isPlaying) {
                try {
                    // Check if we should stop (defensive programming)
                    if (!isPlaying) {
                        Log.d(TAG, "⛔ isPlaying=false detected in loop, breaking")
                        break
                    }

                    // Continuous playback
                    if (!isBuffering && chunkQueue.isNotEmpty()) {
                        val chunk = chunkQueue.poll()
                        if (chunk != null && isPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            val processedChunk = audioEnhancer.process(chunk)
                            audioTrack?.write(processedChunk, 0, processedChunk.size, AudioTrack.WRITE_BLOCKING)

                            // Send chunk to waveform visualizer
                            waveformCallback?.invoke(processedChunk)

                            if (localRecordingActive) {
                                streamRecorder?.write(processedChunk)
                            }

                            // Check queue health
                            if (chunkQueue.size < 2) {
                                bufferUnderrunCount++
                                if (bufferUnderrunCount >= 3 && currentMinBuffer < MAX_BUFFER_CHUNKS) {
                                    currentMinBuffer = minOf(currentMinBuffer + 1, MAX_BUFFER_CHUNKS)
                                }
                            } else {
                                bufferUnderrunCount = 0
                            }
                        }
                    } else if (!isBuffering && chunkQueue.isEmpty()) {
                        isBuffering = true
                        updateNotification("Буферизация...")
                        delay(100)
                    } else if (isBuffering) {
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback loop", e)
                    if (!isPlaying) break // Exit if stopped
                }
            }

            Log.d(TAG, "🎧 Playback loop exited")

            Log.d(TAG, "🛑 Playback stopped")
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
