package ru.example.childwatch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import ru.example.childwatch.databinding.ActivitySettingsBinding
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.service.MonitorService

/**
 * Settings Activity for monitoring configuration
 * 
 * Features:
 * - Configure monitoring intervals and durations
 * - Toggle monitoring features on/off
 * - Manage privacy settings
 * - Revoke consent
 */
class SettingsActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "childwatch_prefs"

        // Default values
        private const val DEFAULT_LOCATION_INTERVAL = 30
        private const val DEFAULT_AUDIO_DURATION = 20
        private const val DEFAULT_SERVER_URL = "http://31.28.27.96:3000"

        // Server URL presets
        private const val LOCALHOST_URL = "http://10.0.2.2:3000"
        private const val RAILWAY_URL = "https://childwatch-production.up.railway.app"
        private const val VPS_URL = "http://31.28.27.96:3000"
        
        // Keys
        private const val KEY_LOCATION_INTERVAL = "location_interval"
        private const val KEY_AUDIO_DURATION = "audio_duration"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CHILD_DEVICE_ID = "child_device_id"
        private const val KEY_LOCATION_ENABLED = "location_enabled"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_PHOTO_ENABLED = "photo_enabled"
        private const val KEY_SHARE_PARENT_LOCATION = "share_parent_location"
    }
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    // QR Scanner result launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedCode = result.data?.getStringExtra("SCANNED_QR_CODE")
            if (!scannedCode.isNullOrEmpty()) {
                binding.childDeviceIdInput.setText(scannedCode)
                Toast.makeText(this, "QR-код отсканирован: $scannedCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupUI()
        loadSettings()
    }
    
    private fun setupUI() {
        // Notification duration slider listener
        binding.notificationDurationSlider.addOnChangeListener { _, value, _ ->
            binding.durationValueText.text = "${value.toInt()} секунд"
        }

        // Notification priority slider listener
        binding.notificationPrioritySlider.addOnChangeListener { _, value, _ ->
            val priorityText = when (value.toInt()) {
                0 -> "Низкий"
                1 -> "Средний"
                2 -> "Высокий"
                else -> "Средний"
            }
            binding.priorityValueText.text = priorityText
        }

        // Save settings button
        binding.saveSettingsBtn.setOnClickListener {
            saveSettings()
        }

        // QR Scanner button
        binding.scanQrButton.setOnClickListener {
            val intent = Intent(this, QrScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        }

            // Show QR Code button
            binding.showQrCodeBtn.setOnClickListener {
                val intent = Intent(this, QrCodeActivity::class.java)
                startActivity(intent)
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

        // Reset settings button
        binding.resetSettingsBtn.setOnClickListener {
            showResetConfirmation()
        }

        // Revoke consent button
        binding.revokeConsentBtn.setOnClickListener {
            showRevokeConsentConfirmation()
        }

        // About button
        binding.aboutBtn.setOnClickListener {
            openAboutScreen()
        }
        
        // Share parent location switch with background permission check
        binding.shareParentLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndRequestBackgroundLocationPermission()
            }
        }
    }
    
    private fun loadSettings() {
        // Load monitoring settings
        val locationInterval = prefs.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
        val audioDuration = prefs.getInt(KEY_AUDIO_DURATION, DEFAULT_AUDIO_DURATION)
        val serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val childDeviceId = prefs.getString(KEY_CHILD_DEVICE_ID, "")

        binding.locationIntervalInput.setText(locationInterval.toString())
        binding.audioDurationInput.setText(audioDuration.toString())
        binding.serverUrlInput.setText(serverUrl)
        binding.childDeviceIdInput.setText(childDeviceId)

        // Load feature toggles
        binding.locationMonitoringSwitch.isChecked = prefs.getBoolean(KEY_LOCATION_ENABLED, true)
        binding.audioMonitoringSwitch.isChecked = prefs.getBoolean(KEY_AUDIO_ENABLED, true)
        binding.photoMonitoringSwitch.isChecked = prefs.getBoolean(KEY_PHOTO_ENABLED, false)
        binding.shareParentLocationSwitch.isChecked = prefs.getBoolean(KEY_SHARE_PARENT_LOCATION, true)

        // Load notification settings
        val notificationPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
        val notificationDuration = notificationPrefs.getInt("notification_duration", 10000) / 1000 // Convert ms to seconds
        val notificationSize = notificationPrefs.getString("notification_size", "expanded") ?: "expanded"
        val notificationPriority = notificationPrefs.getInt("notification_priority", 2)
        val notificationSound = notificationPrefs.getBoolean("notification_sound", true)
        val notificationVibration = notificationPrefs.getBoolean("notification_vibration", true)

        binding.notificationDurationSlider.value = notificationDuration.toFloat()
        binding.durationValueText.text = "$notificationDuration секунд"

        // Load notification size
        if (notificationSize == "compact") {
            binding.notificationSizeCompact.isChecked = true
        } else {
            binding.notificationSizeExpanded.isChecked = true
        }

        // Load notification priority
        binding.notificationPrioritySlider.value = notificationPriority.toFloat()
        val priorityText = when (notificationPriority) {
            0 -> "Низкий"
            1 -> "Средний"
            2 -> "Высокий"
            else -> "Средний"
        }
        binding.priorityValueText.text = priorityText

        // Load notification sound and vibration toggles
        binding.notificationSoundSwitch.isChecked = notificationSound
        binding.notificationVibrationSwitch.isChecked = notificationVibration

        Log.d(TAG, "Settings loaded")
    }
    
    private fun saveSettings() {
        try {
            // Validate and save monitoring settings
            val locationInterval = binding.locationIntervalInput.text.toString().toIntOrNull()
            val audioDuration = binding.audioDurationInput.text.toString().toIntOrNull()
            val serverUrl = binding.serverUrlInput.text.toString().trim()
            val childDeviceId = binding.childDeviceIdInput.text.toString().trim()
            
            // Validate location interval
            if (locationInterval == null || locationInterval < 10 || locationInterval > 300) {
                Toast.makeText(this, "Интервал геолокации должен быть от 10 до 300 секунд", Toast.LENGTH_LONG).show()
                return
            }
            
            // Validate audio duration
            if (audioDuration == null || audioDuration < 5 || audioDuration > 60) {
                Toast.makeText(this, "Длительность аудио должна быть от 5 до 60 секунд", Toast.LENGTH_LONG).show()
                return
            }
            
            // Validate server URL
            if (serverUrl.isEmpty() || (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://"))) {
                Toast.makeText(this, "Введите корректный URL сервера", Toast.LENGTH_LONG).show()
                return
            }
            
            // Save settings
            prefs.edit()
                .putInt(KEY_LOCATION_INTERVAL, locationInterval)
                .putInt(KEY_AUDIO_DURATION, audioDuration)
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_CHILD_DEVICE_ID, childDeviceId)
                .putBoolean(KEY_LOCATION_ENABLED, binding.locationMonitoringSwitch.isChecked)
                .putBoolean(KEY_AUDIO_ENABLED, binding.audioMonitoringSwitch.isChecked)
                .putBoolean(KEY_PHOTO_ENABLED, binding.photoMonitoringSwitch.isChecked)
                .putBoolean(KEY_SHARE_PARENT_LOCATION, binding.shareParentLocationSwitch.isChecked)
                .apply()

            // Save notification settings
            val notificationPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
            val notificationDurationSec = binding.notificationDurationSlider.value.toInt()
            val notificationSize = if (binding.notificationSizeCompact.isChecked) "compact" else "expanded"
            val notificationPriority = binding.notificationPrioritySlider.value.toInt()
            val notificationSound = binding.notificationSoundSwitch.isChecked
            val notificationVibration = binding.notificationVibrationSwitch.isChecked

            notificationPrefs.edit()
                .putInt("notification_duration", notificationDurationSec * 1000) // Convert to ms
                .putString("notification_size", notificationSize)
                .putInt("notification_priority", notificationPriority)
                .putBoolean("notification_sound", notificationSound)
                .putBoolean("notification_vibration", notificationVibration)
                .apply()

            Log.d(TAG, "Settings saved: interval=$locationInterval, audio=$audioDuration, url=$serverUrl, notif=$notificationDurationSec, size=$notificationSize, priority=$notificationPriority, sound=$notificationSound, vibration=$notificationVibration")
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            
            // Finish activity
            finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings", e)
            Toast.makeText(this, "Ошибка при сохранении настроек", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Сброс настроек")
            .setMessage("Вы уверены, что хотите сбросить все настройки к значениям по умолчанию?")
            .setPositiveButton("Сбросить") { _, _ ->
                resetToDefaults()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun resetToDefaults() {
        // Reset to default values
        binding.locationIntervalInput.setText(DEFAULT_LOCATION_INTERVAL.toString())
        binding.audioDurationInput.setText(DEFAULT_AUDIO_DURATION.toString())
        binding.serverUrlInput.setText(DEFAULT_SERVER_URL)
        
        binding.locationMonitoringSwitch.isChecked = true
        binding.audioMonitoringSwitch.isChecked = true
        binding.photoMonitoringSwitch.isChecked = false
        
        Log.d(TAG, "Settings reset to defaults")
        Toast.makeText(this, "Настройки сброшены к умолчанию", Toast.LENGTH_SHORT).show()
    }
    
    private fun showRevokeConsentConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Отзыв согласия")
            .setMessage("Вы уверены, что хотите отозвать согласие на мониторинг? Это остановит все функции отслеживания.")
            .setPositiveButton("Отозвать") { _, _ ->
                revokeConsent()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun revokeConsent() {
        // Revoke consent
        ConsentActivity.revokeConsent(this)
        
        // Stop monitoring service if running
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_MONITORING
        }
        startService(intent)
        
        Log.d(TAG, "Consent revoked")
        Toast.makeText(this, "Согласие отозвано", Toast.LENGTH_LONG).show()
        
        // Return to consent screen
        val consentIntent = Intent(this, ConsentActivity::class.java)
        consentIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(consentIntent)
        finish()
    }
    
    private fun openAboutScreen() {
        val intent = Intent(this, AboutActivity::class.java)
        startActivity(intent)
    }
    
    override fun onBackPressed() {
        // Check if settings were modified
        val currentLocationInterval = binding.locationIntervalInput.text.toString().toIntOrNull()
        val currentAudioDuration = binding.audioDurationInput.text.toString().toIntOrNull()
        val currentServerUrl = binding.serverUrlInput.text.toString().trim()
        
        val savedLocationInterval = prefs.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
        val savedAudioDuration = prefs.getInt(KEY_AUDIO_DURATION, DEFAULT_AUDIO_DURATION)
        val savedServerUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        
        val settingsChanged = (currentLocationInterval != savedLocationInterval ||
                currentAudioDuration != savedAudioDuration ||
                currentServerUrl != savedServerUrl ||
                binding.locationMonitoringSwitch.isChecked != prefs.getBoolean(KEY_LOCATION_ENABLED, true) ||
                binding.audioMonitoringSwitch.isChecked != prefs.getBoolean(KEY_AUDIO_ENABLED, true) ||
                binding.photoMonitoringSwitch.isChecked != prefs.getBoolean(KEY_PHOTO_ENABLED, false))
        
        if (settingsChanged) {
            AlertDialog.Builder(this)
                .setTitle("Несохраненные изменения")
                .setMessage("У вас есть несохраненные изменения. Сохранить их?")
                .setPositiveButton("Сохранить") { _, _ ->
                    saveSettings()
                }
                .setNegativeButton("Не сохранять") { _, _ ->
                    super.onBackPressed()
                }
                .setNeutralButton("Отмена", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
    
    /**
     * Check and request background location permission for Android 10+
     */
    private fun checkAndRequestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!PermissionHelper.hasBackgroundLocationPermission(this)) {
                // Show explanation dialog first
                AlertDialog.Builder(this)
                    .setTitle("Требуется разрешение")
                    .setMessage(
                        "Для отслеживания вашей локации в фоновом режиме необходимо разрешение " +
                        "\"Разрешить всегда\" в настройках локации.\n\n" +
                        "Это позволит ребенку видеть где вы находитесь на карте."
                    )
                    .setPositiveButton("Предоставить") { _, _ ->
                        requestBackgroundLocationPermission()
                    }
                    .setNegativeButton("Отмена") { _, _ ->
                        // Disable switch if permission denied
                        binding.shareParentLocationSwitch.isChecked = false
                    }
                    .show()
            }
        }
        // For Android 9 and below, background location is included in fine/coarse location
    }
    
    /**
     * Request background location permission
     */
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                PermissionHelper.REQUEST_CODE_BACKGROUND_LOCATION
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PermissionHelper.REQUEST_CODE_BACKGROUND_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "Разрешение на фоновую локацию получено",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Permission denied
                binding.shareParentLocationSwitch.isChecked = false
                Toast.makeText(
                    this,
                    "Без разрешения фоновой локации функция недоступна",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
