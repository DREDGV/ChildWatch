package ru.example.parentwatch

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.example.parentwatch.databinding.ActivitySettingsBinding

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

