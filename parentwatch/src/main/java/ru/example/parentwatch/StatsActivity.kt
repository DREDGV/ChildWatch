package ru.example.parentwatch

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.example.parentwatch.databinding.ActivityStatsBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Statistics Activity for ParentWatch
 *
 * Displays:
 * - Monitoring service status
 * - Location tracking statistics
 * - Audio streaming statistics
 * - Chat message count
 * - Connection statistics
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val prefs by lazy { getSharedPreferences("parentwatch_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Статистика"

        setupUI()
        loadStatistics()
    }

    override fun onResume() {
        super.onResume()
        loadStatistics()
    }

    private fun setupUI() {
        // Refresh button
        binding.refreshButton.setOnClickListener {
            loadStatistics()
        }

        // Clear stats button
        binding.clearStatsButton.setOnClickListener {
            clearStatistics()
        }
    }

    private fun loadStatistics() {
        lifecycleScope.launch {
            try {
                // Service status
                val isServiceRunning = prefs.getBoolean("service_running", false)
                binding.serviceStatusText.text = if (isServiceRunning) {
                    "✅ Запущен"
                } else {
                    "⏸️ Остановлен"
                }
                binding.serviceStatusText.setTextColor(
                    if (isServiceRunning)
                        getColor(android.R.color.holo_green_dark)
                    else
                        getColor(android.R.color.holo_orange_dark)
                )

                // Connection status
                val serverUrl = prefs.getString("server_url", "Не настроен") ?: "Не настроен"
                val deviceId = prefs.getString("device_id", "Не настроен") ?: "Не настроен"
                binding.serverUrlText.text = serverUrl
                binding.deviceIdText.text = deviceId.take(16) + "..."

                // Location statistics
                val locationUpdateCount = prefs.getInt("location_update_count", 0)
                val lastLocationTime = prefs.getLong("last_location_time", 0)
                binding.locationCountText.text = locationUpdateCount.toString()
                binding.lastLocationTimeText.text = formatTimestamp(lastLocationTime)

                // Audio statistics
                val audioStreamCount = prefs.getInt("audio_stream_count", 0)
                val audioStreamDuration = prefs.getLong("audio_stream_duration", 0)
                binding.audioStreamCountText.text = audioStreamCount.toString()
                binding.audioStreamDurationText.text = formatDuration(audioStreamDuration)

                // Chat statistics
                val chatMessagesSent = prefs.getInt("chat_messages_sent", 0)
                val chatMessagesReceived = prefs.getInt("chat_messages_received", 0)
                binding.chatSentCountText.text = chatMessagesSent.toString()
                binding.chatReceivedCountText.text = chatMessagesReceived.toString()

                // System info
                val appStartTime = prefs.getLong("app_start_time", System.currentTimeMillis())
                val uptime = System.currentTimeMillis() - appStartTime
                binding.uptimeText.text = formatDuration(uptime)

                // Battery optimization status
                val batteryOptimizationDisabled = prefs.getBoolean("battery_optimization_disabled", false)
                binding.batteryOptimizationText.text = if (batteryOptimizationDisabled) {
                    "✅ Отключена"
                } else {
                    "⚠️ Включена"
                }

            } catch (e: Exception) {
                binding.serviceStatusText.text = "Ошибка загрузки: ${e.message}"
            }
        }
    }

    private fun clearStatistics() {
        prefs.edit()
            .putInt("location_update_count", 0)
            .putInt("audio_stream_count", 0)
            .putLong("audio_stream_duration", 0)
            .putInt("chat_messages_sent", 0)
            .putInt("chat_messages_received", 0)
            .apply()

        loadStatistics()

        // Show toast
        android.widget.Toast.makeText(this, "✅ Статистика очищена", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "Никогда"

        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun formatDuration(milliseconds: Long): String {
        if (milliseconds == 0L) return "0 сек"

        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "$days д ${hours % 24} ч"
            hours > 0 -> "$hours ч ${minutes % 60} мин"
            minutes > 0 -> "$minutes мин ${seconds % 60} сек"
            else -> "$seconds сек"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
