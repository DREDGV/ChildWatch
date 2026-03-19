package ru.example.childwatch

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import ru.example.childwatch.databinding.ActivitySettingsBinding
import ru.example.childwatch.utils.NotificationManager as ChatNotificationManager
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.service.MonitorService
import java.util.Locale

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
        private const val NOTIFICATION_PREFS_NAME = "notification_prefs"
        private const val DEFAULT_QUIET_HOURS_START = "22:00"
        private const val DEFAULT_QUIET_HOURS_END = "07:00"
    }
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var secureSettings: SecureSettingsManager
    private var quietHoursStart = DEFAULT_QUIET_HOURS_START
    private var quietHoursEnd = DEFAULT_QUIET_HOURS_END

    // QR Scanner result launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedCode = result.data?.getStringExtra("SCANNED_QR_CODE")
            if (!scannedCode.isNullOrEmpty()) {
                binding.childDeviceIdInput.setText(scannedCode)
                Toast.makeText(
                    this,
                    getString(R.string.settings_scanned_qr, scannedCode),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        secureSettings = SecureSettingsManager(this)

        setupUI()
        loadSettings()
    }
    
    private fun setupUI() {
        binding.notificationDurationSlider.addOnChangeListener { _, value, _ ->
            binding.durationValueText.text = formatNotificationDuration(value.toInt())
        }

        binding.notificationPrioritySlider.addOnChangeListener { _, value, _ ->
            binding.priorityValueText.text = notificationPriorityLabel(value.toInt())
        }

        binding.notificationQuietHoursSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateQuietHoursState(isChecked)
        }

        binding.quietHoursStartButton.setOnClickListener {
            showTimePicker(quietHoursStart) { selectedTime ->
                quietHoursStart = selectedTime
                updateQuietHoursButtons()
            }
        }

        binding.quietHoursEndButton.setOnClickListener {
            showTimePicker(quietHoursEnd) { selectedTime ->
                quietHoursEnd = selectedTime
                updateQuietHoursButtons()
            }
        }

        binding.saveSettingsBtn.setOnClickListener {
            saveSettings()
        }

        binding.scanQrButton.setOnClickListener {
            val intent = Intent(this, QrScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        }

        binding.showQrCodeBtn.setOnClickListener {
            val intent = Intent(this, QrCodeActivity::class.java)
            startActivity(intent)
        }

        binding.useVpsBtn.setOnClickListener {
            binding.serverUrlInput.setText(VPS_URL)
            Toast.makeText(this, R.string.settings_toast_vps_set, Toast.LENGTH_SHORT).show()
        }

        binding.useLocalhostBtn.setOnClickListener {
            binding.serverUrlInput.setText(LOCALHOST_URL)
            Toast.makeText(this, R.string.settings_toast_local_set, Toast.LENGTH_SHORT).show()
        }

        binding.resetSettingsBtn.setOnClickListener {
            showResetConfirmation()
        }

        binding.revokeConsentBtn.setOnClickListener {
            showRevokeConsentConfirmation()
        }

        binding.aboutBtn.setOnClickListener {
            openAboutScreen()
        }

        binding.testNotificationButton.setOnClickListener {
            ChatNotificationManager.showPreviewNotification(this, buildNotificationSettings())
            Toast.makeText(this, getString(R.string.notification_preview_sent), Toast.LENGTH_SHORT)
                .show()
        }

        binding.openNotificationSettingsButton.setOnClickListener {
            openSystemNotificationSettings()
        }

        binding.shareParentLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndRequestBackgroundLocationPermission()
            }
        }
    }

    private fun loadSettings() {
        val locationInterval = prefs.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
        val audioDuration = prefs.getInt(KEY_AUDIO_DURATION, DEFAULT_AUDIO_DURATION)
        val serverUrl = secureSettings.getServerUrl()
        val childDeviceId = prefs.getString(KEY_CHILD_DEVICE_ID, "")

        binding.locationIntervalInput.setText(locationInterval.toString())
        binding.audioDurationInput.setText(audioDuration.toString())
        binding.serverUrlInput.setText(serverUrl)
        binding.childDeviceIdInput.setText(childDeviceId)

        binding.locationMonitoringSwitch.isChecked = prefs.getBoolean(KEY_LOCATION_ENABLED, true)
        binding.audioMonitoringSwitch.isChecked = prefs.getBoolean(KEY_AUDIO_ENABLED, true)
        binding.photoMonitoringSwitch.isChecked = prefs.getBoolean(KEY_PHOTO_ENABLED, false)
        binding.shareParentLocationSwitch.isChecked = prefs.getBoolean(KEY_SHARE_PARENT_LOCATION, true)

        val notificationPrefs = getSharedPreferences(NOTIFICATION_PREFS_NAME, MODE_PRIVATE)
        val notificationDuration = notificationPrefs.getInt("notification_duration", 10000) / 1000
        val notificationSize = notificationPrefs.getString("notification_size", "expanded") ?: "expanded"
        val notificationPriority = notificationPrefs.getInt("notification_priority", 2)
        val notificationSound = notificationPrefs.getBoolean("notification_sound", true)
        val notificationVibration = notificationPrefs.getBoolean("notification_vibration", true)
        val notificationBadge = notificationPrefs.getBoolean("notification_badge", true)
        val notificationPreview = notificationPrefs.getString("notification_preview", "public") ?: "public"
        val notificationQuietHoursEnabled =
            notificationPrefs.getBoolean("notification_quiet_hours_enabled", false)
        quietHoursStart =
            notificationPrefs.getString(
                "notification_quiet_hours_start",
                DEFAULT_QUIET_HOURS_START
            ) ?: DEFAULT_QUIET_HOURS_START
        quietHoursEnd =
            notificationPrefs.getString(
                "notification_quiet_hours_end",
                DEFAULT_QUIET_HOURS_END
            ) ?: DEFAULT_QUIET_HOURS_END

        binding.notificationDurationSlider.value = notificationDuration.toFloat()
        binding.durationValueText.text = formatNotificationDuration(notificationDuration)
        binding.notificationPrioritySlider.value = notificationPriority.toFloat()
        binding.priorityValueText.text = notificationPriorityLabel(notificationPriority)
        binding.notificationSoundSwitch.isChecked = notificationSound
        binding.notificationVibrationSwitch.isChecked = notificationVibration
        binding.notificationBadgeSwitch.isChecked = notificationBadge
        binding.notificationQuietHoursSwitch.isChecked = notificationQuietHoursEnabled
        updateQuietHoursButtons()
        updateQuietHoursState(notificationQuietHoursEnabled)

        if (notificationSize == "compact") {
            binding.notificationSizeCompact.isChecked = true
        } else {
            binding.notificationSizeExpanded.isChecked = true
        }

        if (notificationPreview == "private") {
            binding.notificationPreviewPrivate.isChecked = true
        } else {
            binding.notificationPreviewPublic.isChecked = true
        }

        Log.d(TAG, "Settings loaded")
    }

    private fun saveSettings() {
        try {
            val locationInterval = binding.locationIntervalInput.text.toString().toIntOrNull()
            val audioDuration = binding.audioDurationInput.text.toString().toIntOrNull()
            val serverUrl = binding.serverUrlInput.text.toString().trim()
            val childDeviceId = binding.childDeviceIdInput.text.toString().trim()

            if (locationInterval == null || locationInterval < 10 || locationInterval > 300) {
                Toast.makeText(this, R.string.settings_validation_location_interval, Toast.LENGTH_LONG).show()
                return
            }

            if (audioDuration == null || audioDuration < 5 || audioDuration > 60) {
                Toast.makeText(this, R.string.settings_validation_audio_duration, Toast.LENGTH_LONG).show()
                return
            }

            if (serverUrl.isEmpty() || (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://"))) {
                Toast.makeText(this, R.string.settings_validation_server_url, Toast.LENGTH_LONG).show()
                return
            }

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

            val notificationPrefs = getSharedPreferences(NOTIFICATION_PREFS_NAME, MODE_PRIVATE)
            val notificationSettings = buildNotificationSettings()
            notificationPrefs.edit()
                .putInt("notification_duration", notificationSettings.durationMs)
                .putString("notification_size", notificationSettings.size)
                .putInt("notification_priority", notificationSettings.priority)
                .putBoolean("notification_sound", notificationSettings.enableSound)
                .putBoolean("notification_vibration", notificationSettings.enableVibration)
                .putBoolean("notification_badge", notificationSettings.showBadge)
                .putString("notification_preview", notificationSettings.previewMode)
                .putBoolean("notification_quiet_hours_enabled", notificationSettings.quietHoursEnabled)
                .putString("notification_quiet_hours_start", notificationSettings.quietHoursStart)
                .putString("notification_quiet_hours_end", notificationSettings.quietHoursEnd)
                .apply()

            ChatNotificationManager.createNotificationChannels(this, notificationSettings)
            secureSettings.setServerUrl(serverUrl)

            Log.d(
                TAG,
                "Settings saved: interval=$locationInterval, audio=$audioDuration, url=$serverUrl, " +
                    "notif=${notificationSettings.durationMs}, size=${notificationSettings.size}, " +
                    "priority=${notificationSettings.priority}, sound=${notificationSettings.enableSound}, " +
                    "vibration=${notificationSettings.enableVibration}, badge=${notificationSettings.showBadge}, " +
                    "preview=${notificationSettings.previewMode}"
            )
            Toast.makeText(this, getString(R.string.notification_settings_saved), Toast.LENGTH_SHORT)
                .show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings", e)
            Toast.makeText(this, R.string.settings_save_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setPositiveButton(R.string.settings_reset_confirm) { _, _ ->
                resetToDefaults()
            }
            .setNegativeButton(android.R.string.cancel, null)
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
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
    }
    
    private fun showRevokeConsentConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_revoke_title)
            .setMessage(R.string.settings_revoke_message)
            .setPositiveButton(R.string.settings_revoke_confirm) { _, _ ->
                revokeConsent()
            }
            .setNegativeButton(android.R.string.cancel, null)
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
        Toast.makeText(this, R.string.settings_revoke_done, Toast.LENGTH_LONG).show()
        
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

    private fun buildNotificationSettings(): ChatNotificationManager.ChatNotificationSettings {
        return ChatNotificationManager.ChatNotificationSettings(
            durationMs = binding.notificationDurationSlider.value.toInt() * 1000,
            priority = binding.notificationPrioritySlider.value.toInt(),
            size = if (binding.notificationSizeCompact.isChecked) "compact" else "expanded",
            enableSound = binding.notificationSoundSwitch.isChecked,
            enableVibration = binding.notificationVibrationSwitch.isChecked,
            showBadge = binding.notificationBadgeSwitch.isChecked,
            previewMode = if (binding.notificationPreviewPrivate.isChecked) "private" else "public",
            quietHoursEnabled = binding.notificationQuietHoursSwitch.isChecked,
            quietHoursStart = quietHoursStart,
            quietHoursEnd = quietHoursEnd
        )
    }

    private fun updateQuietHoursState(enabled: Boolean) {
        binding.notificationQuietHoursButtons.isVisible = enabled
    }

    private fun updateQuietHoursButtons() {
        binding.quietHoursStartButton.text =
            getString(R.string.notification_quiet_hours_start, quietHoursStart)
        binding.quietHoursEndButton.text =
            getString(R.string.notification_quiet_hours_end, quietHoursEnd)
    }

    private fun showTimePicker(initialValue: String, onSelected: (String) -> Unit) {
        val (hour, minute) = parseQuietHoursTime(initialValue)
        TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                onSelected(formatQuietHoursTime(selectedHour, selectedMinute))
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun parseQuietHoursTime(value: String): Pair<Int, Int> {
        val parts = value.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour to minute
    }

    private fun formatQuietHoursTime(hour: Int, minute: Int): String {
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun formatNotificationDuration(seconds: Int): String {
        return getString(R.string.notification_duration_value, seconds)
    }

    private fun notificationPriorityLabel(priority: Int): String {
        return when (priority) {
            0 -> getString(R.string.notification_priority_low)
            1 -> getString(R.string.notification_priority_medium)
            else -> getString(R.string.notification_priority_high)
        }
    }

    private fun openSystemNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.notification_settings_open_error), Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onBackPressed() {
        // Check if settings were modified
        val currentLocationInterval = binding.locationIntervalInput.text.toString().toIntOrNull()
        val currentAudioDuration = binding.audioDurationInput.text.toString().toIntOrNull()
        val currentServerUrl = binding.serverUrlInput.text.toString().trim()
        
        val savedLocationInterval = prefs.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
        val savedAudioDuration = prefs.getInt(KEY_AUDIO_DURATION, DEFAULT_AUDIO_DURATION)
        val savedServerUrl = secureSettings.getServerUrl()
        
        val settingsChanged = (currentLocationInterval != savedLocationInterval ||
                currentAudioDuration != savedAudioDuration ||
                currentServerUrl != savedServerUrl ||
                binding.locationMonitoringSwitch.isChecked != prefs.getBoolean(KEY_LOCATION_ENABLED, true) ||
                binding.audioMonitoringSwitch.isChecked != prefs.getBoolean(KEY_AUDIO_ENABLED, true) ||
                binding.photoMonitoringSwitch.isChecked != prefs.getBoolean(KEY_PHOTO_ENABLED, false))
        
        if (settingsChanged) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_unsaved_title)
                .setMessage(R.string.settings_unsaved_message)
                .setPositiveButton(R.string.settings_unsaved_save) { _, _ ->
                    saveSettings()
                }
                .setNegativeButton(R.string.settings_unsaved_discard) { _, _ ->
                    super.onBackPressed()
                }
                .setNeutralButton(android.R.string.cancel, null)
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
                    .setTitle(R.string.settings_background_permission_title)
                    .setMessage(R.string.settings_background_permission_message)
                    .setPositiveButton(R.string.settings_background_permission_grant) { _, _ ->
                        requestBackgroundLocationPermission()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
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
                    R.string.settings_background_permission_granted,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Permission denied
                binding.shareParentLocationSwitch.isChecked = false
                Toast.makeText(
                    this,
                    R.string.settings_background_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}





