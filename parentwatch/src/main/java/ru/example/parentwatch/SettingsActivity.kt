package ru.example.parentwatch

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import ru.example.parentwatch.databinding.ActivitySettingsBinding
import ru.example.parentwatch.service.AppUsageTracker

/**
 * Settings Activity for ParentWatch
 * 
 * Features:
 * - Server URL configuration
 * - Device ID display and management
 * - Monitoring intervals
 * - About information
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"
        
        setupUI()
        loadSettings()
    }
    
    private fun setupUI() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val notificationPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)

        // Load current settings
        val serverUrl = prefs.getString("server_url", getString(R.string.server_url_hint)) ?: getString(R.string.server_url_hint)
        val deviceId = prefs.getString("device_id", "Не настроен") ?: "Не настроен"

        binding.serverUrlInput.setText(serverUrl)
        binding.deviceIdText.setText(deviceId)

        // Update usage permission status
        updateUsagePermissionStatus()

        // Load notification settings
        val notificationDuration = notificationPrefs.getInt("notification_duration", 10000) / 1000 // Convert ms to seconds
        val notificationSound = notificationPrefs.getBoolean("notification_sound", true)
        val notificationVibration = notificationPrefs.getBoolean("notification_vibration", true)

        binding.notificationDurationSlider.value = notificationDuration.toFloat()
        binding.durationValueText.text = "$notificationDuration секунд"
        binding.notificationSoundSwitch.isChecked = notificationSound
        binding.notificationVibrationSwitch.isChecked = notificationVibration

        // Notification duration slider listener
        binding.notificationDurationSlider.addOnChangeListener { _, value, _ ->
            binding.durationValueText.text = "${value.toInt()} секунд"
        }

        // Save button
        binding.saveButton.setOnClickListener {
            saveSettings()
        }

        // Localhost button
        binding.useLocalhostBtn.setOnClickListener {
            binding.serverUrlInput.setText("http://10.0.2.2:3000")
        }

        // Railway button
        binding.useRailwayBtn.setOnClickListener {
            binding.serverUrlInput.setText("https://childwatch-production.up.railway.app")
        }

        // Copy Device ID button
        binding.copyIdButton.setOnClickListener {
            copyDeviceId()
        }

        // Show QR Code button
        binding.showQrButton.setOnClickListener {
            showQRCode()
        }

        // Request usage stats permission button
        binding.requestUsagePermissionButton.setOnClickListener {
            requestUsageStatsPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUsagePermissionStatus()
    }

    private fun updateUsagePermissionStatus() {
        val appUsageTracker = AppUsageTracker(this)
        val hasPermission = appUsageTracker.hasUsageStatsPermission()

        if (hasPermission) {
            binding.usagePermissionStatus.isVisible = true
            binding.requestUsagePermissionButton.text = "✅ Разрешение предоставлено"
            binding.requestUsagePermissionButton.isEnabled = false
        } else {
            binding.usagePermissionStatus.isVisible = false
            binding.requestUsagePermissionButton.text = "🔓 Предоставить разрешение"
            binding.requestUsagePermissionButton.isEnabled = true
        }
    }

    private fun requestUsageStatsPermission() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Найдите ParentWatch в списке и включите разрешение",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Не удалось открыть настройки разрешений",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun loadSettings() {
        // Settings are loaded in setupUI
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val notificationPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
        val serverUrl = binding.serverUrlInput.text.toString().trim()

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Введите URL сервера", Toast.LENGTH_SHORT).show()
            return
        }

        // Save server URL
        prefs.edit()
            .putString("server_url", serverUrl)
            .apply()

        // Save notification settings
        val notificationDurationSec = binding.notificationDurationSlider.value.toInt()
        val notificationSound = binding.notificationSoundSwitch.isChecked
        val notificationVibration = binding.notificationVibrationSwitch.isChecked

        notificationPrefs.edit()
            .putInt("notification_duration", notificationDurationSec * 1000) // Convert to ms
            .putBoolean("notification_sound", notificationSound)
            .putBoolean("notification_vibration", notificationVibration)
            .apply()

        Toast.makeText(this, "✅ Настройки сохранены", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun copyDeviceId() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        
        if (deviceId == null) {
            Toast.makeText(this, "Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "✅ Device ID скопирован", Toast.LENGTH_SHORT).show()
    }
    
    private fun showQRCode() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)

        if (deviceId.isNullOrEmpty()) {
            Toast.makeText(this, "Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, QrCodeActivity::class.java)
        startActivity(intent)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

