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

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "parentwatch_prefs"

        // Default values
        private const val DEFAULT_SERVER_URL = "http://31.28.27.96:3000"

        // Server URL presets
        private const val LOCALHOST_URL = "http://10.0.2.2:3000"
        private const val RAILWAY_URL = "https://childwatch-production.up.railway.app"
        private const val VPS_URL = "http://31.28.27.96:3000"
    }

    private lateinit var binding: ActivitySettingsBinding
    
    // QR Scanner result launcher
    private val qrScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedCode = result.data?.getStringExtra("SCANNED_QR_CODE")
            if (scannedCode != null) {
                saveChildDeviceId(scannedCode)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
    // Set up toolbar
    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.title = "Настройки"
        
        setupUI()
        loadSettings()
    }
    
    private fun setupUI() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val notificationPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)

        // Load current settings
        val serverUrl = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        
        // Generate device_id if not exists
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = "child-" + java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs.edit()
                .putString("device_id", deviceId)
                .putBoolean("device_id_permanent", true)
                .apply()
        }

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

        // Server URL preset buttons
        binding.useVpsBtn.setOnClickListener {
            binding.serverUrlInput.setText(VPS_URL)
            Toast.makeText(this, "VPS URL установлен", Toast.LENGTH_SHORT).show()
        }

        binding.useRailwayBtn.setOnClickListener {
            binding.serverUrlInput.setText(RAILWAY_URL)
            Toast.makeText(this, "Railway URL установлен", Toast.LENGTH_SHORT).show()
        }

        binding.useLocalhostBtn.setOnClickListener {
            binding.serverUrlInput.setText(LOCALHOST_URL)
            Toast.makeText(this, "Localhost URL установлен", Toast.LENGTH_SHORT).show()
        }

        // Copy Device ID button
        binding.copyIdButton.setOnClickListener {
            copyDeviceId()
        }

        // Show QR Code button
        binding.showQrButton.setOnClickListener {
            showQRCode()
        }

        // Scan Parent QR button
        binding.scanParentQrButton.setOnClickListener {
            val intent = Intent(this, QrScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        }

        // Update parent connection status
        updateParentConnectionStatus()

        // Request usage stats permission button
        binding.requestUsagePermissionButton.setOnClickListener {
            requestUsageStatsPermission()
        }

        // Service controls
        val isRunning = prefs.getBoolean("service_running", false)
        updateServiceButtons(isRunning)
        binding.startStopServiceButton.setOnClickListener {
            val currentlyRunning = prefs.getBoolean("service_running", false)
            if (currentlyRunning) stopMonitoring() else startMonitoring()
        }
        binding.emergencyStopButtonSettings.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🚨 Экстренная остановка")
                .setMessage("Это немедленно остановит ВСЕ функции: прослушку, геолокацию, фоновые процессы. Продолжить?")
                .setPositiveButton("Остановить всё") { _, _ -> emergencyStopAll() }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // About & Stats
        binding.openAboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.openStatsButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUsagePermissionStatus()
        // Refresh service button state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        updateServiceButtons(prefs.getBoolean("service_running", false))
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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

    private fun startMonitoring() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            Toast.makeText(this, "Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(this, ru.example.parentwatch.service.LocationService::class.java).apply {
                action = ru.example.parentwatch.service.LocationService.ACTION_START
                putExtra("server_url", serverUrl)
                putExtra("device_id", deviceId)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            prefs.edit().putBoolean("service_running", true).apply()
            updateServiceButtons(true)
            Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка запуска: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoring() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        try {
            val intent = Intent(this, ru.example.parentwatch.service.LocationService::class.java).apply {
                action = ru.example.parentwatch.service.LocationService.ACTION_STOP
            }
            stopService(intent)
            ru.example.parentwatch.service.ChatBackgroundService.stop(this)
            ru.example.parentwatch.service.PhotoCaptureService.stop(this)
            prefs.edit().putBoolean("service_running", false).apply()
            updateServiceButtons(false)
            Toast.makeText(this, "Мониторинг остановлен", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка остановки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun emergencyStopAll() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        try {
            val intent = Intent(this, ru.example.parentwatch.service.LocationService::class.java).apply {
                action = ru.example.parentwatch.service.LocationService.ACTION_EMERGENCY_STOP
            }
            startService(intent)
            prefs.edit().putBoolean("service_running", false).apply()
            updateServiceButtons(false)
            Toast.makeText(this, "Экстренная остановка выполнена", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экстренной остановки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateServiceButtons(running: Boolean) {
        if (running) {
            binding.startStopServiceButton.text = "Остановить мониторинг"
            binding.startStopServiceButton.icon = androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.ic_media_pause)
        } else {
            binding.startStopServiceButton.text = "Запустить мониторинг"
            binding.startStopServiceButton.icon = androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.ic_media_play)
        }
    }
    
    private fun copyDeviceId() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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
    
        private fun saveChildDeviceId(childId: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString("child_device_id", childId)
            .apply()

        val compat = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        compat.edit().putString("child_device_id", childId).apply()

        Toast.makeText(this, "OK: ID ������ ��������", Toast.LENGTH_LONG).show()
        updateParentConnectionStatus()
    }
    private fun updateParentConnectionStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val childId = prefs.getString("child_device_id", null)
            ?: getSharedPreferences("childwatch_prefs", MODE_PRIVATE).getString("child_device_id", null)

        if (!childId.isNullOrEmpty()) {
            binding.parentIdStatus.text = "��������� (${childId.take(8)}...)"
            binding.parentIdStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.parentIdStatus.text = "��� ID ������"
            binding.parentIdStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }
override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}



