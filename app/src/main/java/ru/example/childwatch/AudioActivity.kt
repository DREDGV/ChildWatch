package ru.example.childwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ru.example.childwatch.audio.AudioRecorder
import ru.example.childwatch.audio.FilterMode
import ru.example.childwatch.databinding.ActivityAudioBinding
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.ui.AudioVisualizer
import java.io.File

/**
 * Audio Activity for audio monitoring and recording
 * 
 * Features:
 * 1. Прослушка подключенного телефона (ребенка)
 * 2. Запись включенной прослушки с сохранением файла
 * - Manual audio recording
 * - Recording status display
 * - Audio file management
 * - Recording controls
 */
class AudioActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "AudioActivity"
        private const val AUDIO_PERMISSION_REQUEST_CODE = 1002
    }
    
    private lateinit var binding: ActivityAudioBinding
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioVisualizer: AudioVisualizer
    private var isRecording = false
    private var isMonitoring = false
    private var currentAudioFile: File? = null
    
    // Таймеры
    private var monitoringStartTime = 0L
    private var recordingStartTime = 0L
    private var timerRunnable: Runnable? = null
    private var blinkRunnable: Runnable? = null
    private var isBlinking = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize audio recorder
        audioRecorder = AudioRecorder(this)
        
        // Initialize audio visualizer
        audioVisualizer = binding.audioVisualizer
        
        // Setup UI
        setupUI()
        updateUI()
    }
    
    private lateinit var filterAdapter: ru.example.childwatch.audio.AudioFilterAdapter

    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Прослушивание окружения"

        // Monitor button - прослушка телефона
        binding.monitorButton.setOnClickListener {
            if (isMonitoring) stopMonitoring() else startMonitoring()
        }

        // Record button - запись прослушки
        binding.recordButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        // Test button
        binding.testButton.setOnClickListener { testAudioRecording() }

        // --- Новый блок: фильтры через RecyclerView ---
        val filterItems = listOf(
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.ORIGINAL,
                "📡", "Оригинал", "Без обработки, чистый звук"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.VOICE,
                "🎤", "Голос", "Усиление речи, шумоподавление"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.QUIET_SOUNDS,
                "🔇", "Тихие звуки", "Максимальное усиление"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.MUSIC,
                "🎵", "Музыка", "Естественное звучание"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.OUTDOOR,
                "🌳", "Улица", "Подавление ветра и шума"
            )
        )

        val savedMode = getSharedPreferences("audio_prefs", MODE_PRIVATE)
            .getString("filter_mode", FilterMode.ORIGINAL.name)
        val initialMode = try {
            FilterMode.valueOf(savedMode ?: FilterMode.ORIGINAL.name)
        } catch (e: Exception) {
            FilterMode.ORIGINAL
        }

        filterAdapter = ru.example.childwatch.audio.AudioFilterAdapter(
            items = filterItems,
            selectedMode = initialMode,
            onFilterSelected = { mode ->
                // Сохраняем выбранный режим
                getSharedPreferences("audio_prefs", MODE_PRIVATE).edit()
                    .putString("filter_mode", mode.name)
                    .apply()
                // Применяем режим к аудио
                updateFilterMode(mode)
                Log.d(TAG, "Filter mode changed to: $mode")
                Toast.makeText(this, "Режим фильтра: ${getModeName(mode)}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.filterRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@AudioActivity,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = filterAdapter
            setHasFixedSize(true)
        }
    }

    // loadFilterMode больше не нужен (логика выбора через адаптер)

    private fun updateFilterMode(mode: FilterMode) {
        // Update AudioPlaybackService if it's running
        if (ru.example.childwatch.service.AudioPlaybackService.isPlaying) {
            try {
                // Send broadcast to update filter mode in real-time
                val intent = android.content.Intent("ru.example.childwatch.UPDATE_FILTER_MODE")
                intent.putExtra("filter_mode", mode.name)
                sendBroadcast(intent)
                Log.d(TAG, "Filter mode broadcast sent: $mode")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating filter mode", e)
            }
        } else {
            Log.d(TAG, "Filter mode will be applied on next playback start")
        }
    }

    private fun getModeName(mode: FilterMode): String {
        return when (mode) {
            FilterMode.ORIGINAL -> "Оригинал (без фильтров)"
            FilterMode.VOICE -> "Голос"
            FilterMode.QUIET_SOUNDS -> "Тихие звуки"
            FilterMode.MUSIC -> "Музыка"
            FilterMode.OUTDOOR -> "Улица"
        }
    }
    
    /**
     * 1. Прослушка подключенного телефона (ребенка)
     */
    private fun startMonitoring() {
        if (!PermissionHelper.hasAudioPermission(this)) {
            requestAudioPermission()
            return
        }

        try {
            Log.d(TAG, "Starting audio monitoring from child device")

            // Получаем настройки
            val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            val serverUrl = SecureSettingsManager(this).getServerUrl().trim()
            val childDeviceId = prefs.getString("child_device_id", "")

            if (serverUrl.isBlank()) {
                Toast.makeText(this, getString(R.string.server_url_missing), Toast.LENGTH_LONG).show()
                return
            }

            if (childDeviceId.isNullOrEmpty()) {
                Toast.makeText(this, "⚠️ Сначала настройте ID телефона ребёнка", Toast.LENGTH_LONG).show()
                return
            }

            isMonitoring = true

            // Запускаем AudioPlaybackService для получения аудио стрима
            ru.example.childwatch.service.AudioPlaybackService.startPlayback(
                context = this,
                deviceId = childDeviceId,
                serverUrl = serverUrl,
                recording = false
            )

            // Активируем эквалайзер
            audioVisualizer.setActive(true)
            audioVisualizer.setRecordingMode(false)

            // Запускаем таймер
            monitoringStartTime = System.currentTimeMillis()
            startTimer()

            updateUI()
            Toast.makeText(this, "🎧 Запрос прослушки отправлен на телефон ребёнка", Toast.LENGTH_SHORT).show()

            Log.d(TAG, "Audio streaming started for device: $childDeviceId")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio monitoring", e)
            Toast.makeText(this, "Ошибка мониторинга: ${e.message}", Toast.LENGTH_SHORT).show()
            isMonitoring = false
        }
    }

    private fun stopMonitoring() {
        try {
            Log.d(TAG, "Stopping audio monitoring")
            isMonitoring = false

            // Останавливаем AudioPlaybackService
            ru.example.childwatch.service.AudioPlaybackService.stopPlayback(this)

            // Останавливаем эквалайзер
            audioVisualizer.setActive(false)

            // Останавливаем таймер
            stopTimer()

            updateUI()
            Toast.makeText(this, "🎧 Прослушка телефона остановлена", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio monitoring", e)
            Toast.makeText(this, "Ошибка остановки мониторинга: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 2. Запись включенной прослушки с сохранением файла
     */
    private fun startRecording() {
        if (!PermissionHelper.hasAudioPermission(this)) {
            requestAudioPermission()
            return
        }
        
        if (!audioRecorder.isAudioRecordingAvailable()) {
            Toast.makeText(this, "Аудио запись недоступна на этом устройстве", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            Log.d(TAG, "Starting audio recording")
            
            // Start recording for 60 seconds (longer for monitoring)
            val audioFile = audioRecorder.startRecording(60)
            
            if (audioFile != null) {
                currentAudioFile = audioFile
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                
                // Активируем режим записи в эквалайзере
                audioVisualizer.setRecordingMode(true)
                
                // Запускаем мигание кнопки REC
                startBlinking()
                
                updateUI()
                Toast.makeText(this, "🎙️ Запись прослушки начата", Toast.LENGTH_SHORT).show()
                
                // Auto-stop after 60 seconds
                binding.recordButton.postDelayed({
                    if (isRecording) {
                        stopRecording()
                    }
                }, 60000)
            } else {
                Toast.makeText(this, "Не удалось начать запись", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording", e)
            Toast.makeText(this, "Ошибка записи: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopRecording() {
        try {
            Log.d(TAG, "Stopping audio recording")
            
            val audioFile = audioRecorder.stopRecording()
            isRecording = false
            currentAudioFile = null
            
            // Останавливаем мигание
            stopBlinking()
            
            // Возвращаем обычный режим эквалайзера
            audioVisualizer.setRecordingMode(false)
            
            updateUI()
            
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                val fileSizeKB = audioFile.length() / 1024
                Toast.makeText(this, "🎙️ Запись сохранена: ${audioFile.name} (${fileSizeKB}KB)", Toast.LENGTH_LONG).show()
                Log.d(TAG, "Audio file created: ${audioFile.absolutePath}, size: ${audioFile.length()} bytes")
            } else {
                Toast.makeText(this, "Запись не удалась", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
            Toast.makeText(this, "Ошибка остановки записи: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testAudioRecording() {
        if (!PermissionHelper.hasAudioPermission(this)) {
            requestAudioPermission()
            return
        }
        
        if (!audioRecorder.isAudioRecordingAvailable()) {
            Toast.makeText(this, "Аудио запись недоступна", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Аудио запись доступна и готова к использованию", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Audio recording test passed")
    }
    
    private fun updateUI() {
        // Update monitoring button
        if (isMonitoring) {
            binding.monitorButton.text = "Остановить прослушивание"
            binding.monitorButton.setIconResource(android.R.drawable.ic_media_pause)
        } else {
            binding.monitorButton.text = "Включить прослушивание"
            binding.monitorButton.setIconResource(android.R.drawable.ic_media_play)
        }
        
        // Update recording button
        if (isRecording) {
            binding.recordButton.text = "Остановить запись"
            binding.recordButton.setIconResource(android.R.drawable.ic_media_pause)
        } else {
            binding.recordButton.text = "Записать окружение"
            binding.recordButton.setIconResource(android.R.drawable.ic_media_play)
        }
        
        // Update status text
        val statusText = buildString {
            when {
                isMonitoring && isRecording -> {
                    appendLine("🎧 Прослушка активна")
                    appendLine("🎙️ Запись в процессе...")
                    appendLine("Статус: Активный мониторинг с записью")
                }
                isMonitoring -> {
                    appendLine("🎧 Прослушка активна")
                    appendLine("Статус: Мониторинг без записи")
                }
                isRecording -> {
                    appendLine("🎙️ Запись в процессе...")
                    appendLine("Статус: Только запись")
                }
                else -> {
                    appendLine("Статус: Готов к работе")
                    appendLine("Выберите действие:")
                    appendLine("• Прослушка телефона")
                    appendLine("• Запись аудио")
                }
            }
        }
        
        binding.statusText.text = statusText
        
        // Update colors
        val statusColor = when {
            isMonitoring || isRecording -> android.R.color.holo_red_dark
            else -> android.R.color.holo_green_dark
        }
        binding.statusText.setTextColor(ContextCompat.getColor(this, statusColor))
        
        // Update visualizer status
        val visualizerStatus = when {
            isMonitoring && isRecording -> "🎵 Эквалайзер активен (запись)"
            isMonitoring -> "🎵 Эквалайзер активен (прослушка)"
            else -> "🎵 Эквалайзер неактивен"
        }
        binding.visualizerStatusText.text = visualizerStatus
    }
    
    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            AUDIO_PERMISSION_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение на аудио запись предоставлено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Разрешение на аудио запись необходимо для работы", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
        if (isMonitoring) {
            stopMonitoring()
        }
        stopTimer()
        stopBlinking()
        // audioRecorder.cleanup() - cleanup is private, skip for now
    }
    
    /**
     * Запускает таймер мониторинга
     */
    private fun startTimer() {
        monitoringStartTime = System.currentTimeMillis()
        timerRunnable = object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    updateTimerDisplay()
                    binding.recordButton.postDelayed(this, 1000) // Обновляем каждую секунду
                }
            }
        }
        timerRunnable?.run()
    }
    
    /**
     * Останавливает таймер мониторинга
     */
    private fun stopTimer() {
        timerRunnable?.let { binding.recordButton.removeCallbacks(it) }
        timerRunnable = null
    }
    
    /**
     * Обновляет отображение таймера
     */
    private fun updateTimerDisplay() {
        val elapsedTime = System.currentTimeMillis() - monitoringStartTime
        val minutes = (elapsedTime / 60000).toInt()
        val seconds = ((elapsedTime % 60000) / 1000).toInt()
        
        val timeString = String.format("%02d:%02d", minutes, seconds)
        binding.timerText.text = "Время прослушки: $timeString"
    }
    
    /**
     * Запускает мигание кнопки REC
     */
    private fun startBlinking() {
        isBlinking = true
        blinkRunnable = object : Runnable {
            override fun run() {
                if (isBlinking && isRecording) {
                    // Переключаем видимость кнопки
                    binding.recordButton.alpha = if (binding.recordButton.alpha < 0.5f) 1.0f else 0.3f
                    binding.recordButton.postDelayed(this, 500) // Мигаем каждые 500мс
                }
            }
        }
        blinkRunnable?.run()
    }
    
    /**
     * Останавливает мигание кнопки REC
     */
    private fun stopBlinking() {
        isBlinking = false
        blinkRunnable?.let { binding.recordButton.removeCallbacks(it) }
        binding.recordButton.alpha = 1.0f // Возвращаем полную видимость
    }
}
