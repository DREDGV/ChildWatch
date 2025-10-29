package ru.example.childwatch

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import ru.example.childwatch.alerts.CriticalAlertSyncScheduler
import ru.example.childwatch.databinding.ActivityMainMenuBinding
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.utils.BatteryOptimizationHelper
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecurityChecker
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.chat.ChatManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity with modern menu interface
 * 
 * ChildWatch v4.4.0 - Parental Monitoring Application
 * 
 * Features:
 * - Modern card-based menu interface
 * - Status monitoring display
 * - Quick actions for monitoring
 * - Navigation to different features
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainMenuBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var secureSettings: SecureSettingsManager
    private lateinit var batteryOptimizationHelper: BatteryOptimizationHelper
    private lateinit var chatManager: ChatManager
    private var hasConsent = false
    private var batteryOptimizationDialogDisplayed = false
    private val deviceInfoScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val networkClient by lazy { NetworkClient(this) }
    private val gson by lazy { Gson() }
    private var latestDeviceStatus: DeviceStatus? = null
    private var deviceStatusJob: Job? = null
    private var lastStatusFetchTime = 0L
    
    // Permission launchers for different permission groups
    private val basicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handleBasicPermissionResults(permissions)
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        handleBackgroundLocationResult(isGranted)
    }

    // Child selection launcher
    private val childSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val deviceId = result.data?.getStringExtra(ChildSelectionActivity.EXTRA_SELECTED_DEVICE_ID)
            if (deviceId != null) {
                updateSelectedChild(deviceId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        secureSettings = SecureSettingsManager(this)
        batteryOptimizationHelper = BatteryOptimizationHelper(this)
        chatManager = ChatManager(this)
        hasConsent = ConsentActivity.hasConsent(this)

        // Set app version
        binding.appVersionText.text = "v${BuildConfig.VERSION_NAME}"

        setupUI()
        updateUIState()
        updateChatBadge()
        
        // Perform security checks
        performSecurityChecks()
        checkBatteryOptimizationStatus()
        CriticalAlertSyncScheduler.schedule(this)
        ensureChatBackgroundService()
    }
    


    private fun setupUI() {
        setupBatteryOptimizationUi()

        // Quick action buttons
        binding.startMonitoringBtn.setOnClickListener {
            startMonitoring()
        }
        
        binding.stopMonitoringBtn.setOnClickListener {
            stopMonitoring()
        }

        // Emergency stop button
        binding.emergencyStopBtn.setOnClickListener {
            showEmergencyStopDialog()
        }

        // Menu card click listeners
        binding.homeCard.setOnClickListener {
            showToast(getString(R.string.home_already_here))
        }
        
        binding.locationCard.setOnClickListener {
            // Use Google Maps
            val intent = Intent(this, LocationMapActivity::class.java)
            startActivity(intent)
        }

        binding.audioStreamingCard.setOnClickListener {
            val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "https://childwatch-production.up.railway.app") ?: "https://childwatch-production.up.railway.app"
            val childDeviceId = prefs.getString("child_device_id", "")

            if (childDeviceId.isNullOrEmpty()) {
                showDeviceIdOptions(serverUrl)
            } else {
                val intent = Intent(this, AudioStreamingActivity::class.java).apply {
                    putExtra(AudioStreamingActivity.EXTRA_DEVICE_ID, childDeviceId)
                    putExtra(AudioStreamingActivity.EXTRA_SERVER_URL, serverUrl)
                }
                startActivity(intent)
            }
        }

        binding.chatCard.setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java)
            startActivity(intent)
        }
        
        binding.photoCard.setOnClickListener {
            val intent = Intent(this, PhotoActivity::class.java)
            startActivity(intent)
        }
        
        // Parent location card - using findViewById as workaround
        findViewById<View>(R.id.parentLocationCard)?.setOnClickListener {
            val intent = Intent(this, ParentLocationMapActivity::class.java)
            startActivity(intent)
        }
        
        binding.settingsCard.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Child selection - use only container click handler
        try {
            binding.childSelectionContainer.setOnClickListener {
                try {
                    Log.d(TAG, "Child selection clicked - opening device selection")
                    val intent = Intent(this, ChildSelectionActivity::class.java)
                    childSelectionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching ChildSelectionActivity", e)
                    showToast("Ошибка запуска: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up child selection click handler", e)
        }

        // Load and display selected child
        try {
            loadSelectedChild()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading selected child", e)
        }
    }
    
    private fun setupBatteryOptimizationUi() {
        binding.disableOptimizationButton.setOnClickListener {
            batteryOptimizationHelper.requestDisableBatteryOptimization()
        }
        binding.openPowerSaverButton.setOnClickListener {
            batteryOptimizationHelper.openPowerSaverSettings()
        }
    }

    private fun checkBatteryOptimizationStatus() {
        val ignoringOptimizations = batteryOptimizationHelper.isIgnoringBatteryOptimizations()
        val powerSaveEnabled = batteryOptimizationHelper.isPowerSaveEnabled()

        binding.batteryOptimizationRow.isVisible = !ignoringOptimizations
        binding.powerSaverRow.isVisible = powerSaveEnabled
        binding.powerSettingsCard.isVisible = !ignoringOptimizations || powerSaveEnabled

        if (ignoringOptimizations) {
            batteryOptimizationDialogDisplayed = false
        } else {
            maybeShowBatteryOptimizationDialog()
        }
    }

    private fun maybeShowBatteryOptimizationDialog() {
        if (prefs.getBoolean(KEY_BATTERY_PROMPT_SUPPRESSED, false) || batteryOptimizationDialogDisplayed) {
            return
        }
        batteryOptimizationDialogDisplayed = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_dialog_title)
            .setMessage(R.string.battery_optimization_dialog_message)
            .setPositiveButton(R.string.battery_optimization_button) { _, _ ->
                batteryOptimizationHelper.requestDisableBatteryOptimization()
            }
            .setNegativeButton(R.string.battery_optimization_dialog_later, null)
            .setNeutralButton(R.string.battery_optimization_dialog_never) { _, _ ->
                prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SUPPRESSED, true).apply()
            }
            .setOnDismissListener {
                if (batteryOptimizationHelper.isIgnoringBatteryOptimizations()) {
                    batteryOptimizationDialogDisplayed = false
                }
            }
            .show()
    }

    private fun updateUIState() {
        // Check consent first
        hasConsent = ConsentActivity.hasConsent(this)
        
        if (hasConsent) {
            val isMonitoring = MonitorService.isRunning
            
            // Update status display
            updateStatusDisplay(isMonitoring)
            
            // Update button states
            binding.startMonitoringBtn.isEnabled = !isMonitoring
            binding.stopMonitoringBtn.isEnabled = isMonitoring
            
        } else {
            // Show consent screen
            showConsentScreen()
        }

        updateDeviceInfoCard()
        checkBatteryOptimizationStatus()
    }
    
    private fun updateStatusDisplay(isMonitoring: Boolean) {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        if (isMonitoring) {
            binding.statusText.text = getString(R.string.monitoring_active_status)
            binding.statusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.colorAccent))
            
            // Update service running time
            val serviceStartTime = secureSettings.getServiceStartTime()
            if (serviceStartTime > 0) {
                val runningTime = System.currentTimeMillis() - serviceStartTime
                val hours = runningTime / (1000 * 60 * 60)
                val minutes = (runningTime % (1000 * 60 * 60)) / (1000 * 60)
                val timeString = "${hours}ч ${minutes}м"
                binding.serviceRunningTimeText.text = getString(R.string.service_running_time, timeString)
            } else {
                binding.serviceRunningTimeText.text = getString(R.string.service_running_time, getString(R.string.unknown))
            }
            
            // Update feature status
            binding.locationStatusText.text = getString(R.string.status_location_active)
            binding.audioStatusText.text = getString(R.string.status_audio_active)
            binding.photoStatusText.text = getString(R.string.status_photo_active)
            
        } else {
            binding.statusText.text = getString(R.string.monitoring_inactive_status)
            binding.statusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
            
            binding.serviceRunningTimeText.text = getString(R.string.service_running_time, getString(R.string.not_working))

            // Update feature status
            binding.locationStatusText.text = getString(R.string.status_location_inactive)
            binding.audioStatusText.text = getString(R.string.status_audio_inactive)
            binding.photoStatusText.text = getString(R.string.status_photo_inactive)
        }
        
        // Update last update times
        updateLastUpdateTimes(dateFormat)
        
        // Update permissions status
        updatePermissionsStatus()
        
        // Check battery optimization
        checkBatteryOptimization()
    }
    
    private fun updateLastUpdateTimes(dateFormat: SimpleDateFormat) {
        // Location update
        val lastLocationUpdate = secureSettings.getLastLocationUpdate()
        if (lastLocationUpdate > 0) {
            val timeString = dateFormat.format(Date(lastLocationUpdate))
            binding.lastUpdateText.text = getString(R.string.last_location_update, timeString)
        } else {
            binding.lastUpdateText.text = getString(R.string.last_location_update, getString(R.string.never))
        }

        // Audio update
        val lastAudioUpdate = secureSettings.getLastAudioUpdate()
        if (lastAudioUpdate > 0) {
            val timeString = dateFormat.format(Date(lastAudioUpdate))
            binding.lastAudioUpdateText.text = getString(R.string.last_audio_update, timeString)
        } else {
            binding.lastAudioUpdateText.text = getString(R.string.last_audio_update, getString(R.string.never))
        }

        // Photo update
        val lastPhotoUpdate = secureSettings.getLastPhotoUpdate()
        if (lastPhotoUpdate > 0) {
            val timeString = dateFormat.format(Date(lastPhotoUpdate))
            binding.lastPhotoUpdateText.text = getString(R.string.last_photo_update, timeString)
        } else {
            binding.lastPhotoUpdateText.text = getString(R.string.last_photo_update, getString(R.string.never))
        }
    }
    
    private fun updatePermissionsStatus() {
        val missingPermissions = PermissionHelper.getMissingPermissions(this)
        if (missingPermissions.isEmpty()) {
            binding.permissionsStatusText.text = getString(R.string.permissions_status, getString(R.string.permissions_all_granted))
        } else {
            val missingList = missingPermissions.joinToString(", ")
            binding.permissionsStatusText.text = getString(R.string.permissions_status, getString(R.string.permissions_missing, missingList))
        }
    }
    
    private fun checkBatteryOptimization() {
        // TODO: Implement battery optimization check
        // For now, hide the warning
        binding.batteryWarningText.visibility = View.GONE
    }
    
    private fun updateDeviceInfoCard() {
        binding.deviceInfoCard.isVisible = true
        binding.deviceInfoProgress.isVisible = false

        val childDeviceId = secureSettings.getChildDeviceId()?.takeIf { it.isNotBlank() }
            ?: run {
                val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
                val storedId = prefs.getString("child_device_id", null)
                if (!storedId.isNullOrBlank()) {
                    secureSettings.setChildDeviceId(storedId)
                }
                storedId
            }

        if (childDeviceId.isNullOrEmpty()) {
            binding.deviceInfoDeviceId.text = getString(R.string.device_info_device_id, getString(R.string.device_info_unknown))
            latestDeviceStatus = null
            showDeviceInfoMessage(getString(R.string.device_info_needs_pairing))
            return
        }

        binding.deviceInfoDeviceId.text = getString(R.string.device_info_device_id, childDeviceId)
        val cachedStatus = latestDeviceStatus ?: loadCachedDeviceStatus()
        if (cachedStatus != null) {
            applyDeviceStatus(cachedStatus)
        } else {
            binding.deviceInfoStatusMessage.text = getString(R.string.device_info_loading)
            binding.deviceInfoStatusMessage.isVisible = true
            binding.deviceInfoContent.isVisible = false
        }

        refreshChildDeviceStatus(force = false)
    }

    private fun loadCachedDeviceStatus(): DeviceStatus? {
        val cachedJson = secureSettings.getLastDeviceStatus() ?: return null
        return runCatching { gson.fromJson(cachedJson, DeviceStatus::class.java) }.getOrNull()
    }

    private fun applyDeviceStatus(status: DeviceStatus) {
        binding.deviceInfoProgress.isVisible = false
        binding.deviceInfoStatusMessage.isVisible = false
        binding.deviceInfoContent.isVisible = true

        binding.deviceInfoBatteryValue.text = status.batteryLevel?.takeIf { it in 0..100 }?.let { "$it%" } ?: getString(R.string.device_info_unknown)

        binding.deviceInfoChargingValue.text = when {
            status.isCharging == true && !status.chargingType.isNullOrBlank() ->
                "${getString(R.string.device_info_charging_yes)} (${status.chargingType})"
            status.isCharging == true -> getString(R.string.device_info_charging_yes)
            status.isCharging == false -> getString(R.string.device_info_charging_no)
            else -> getString(R.string.device_info_unknown)
        }

        binding.deviceInfoTemperatureValue.text = status.temperature?.takeIf { it > 0 }?.let {
            String.format(Locale.getDefault(), "%.1f C", it)
        } ?: getString(R.string.device_info_unknown)

        val modelText = listOfNotNull(status.manufacturer, status.model)
            .joinToString(" ")
            .trim()
        binding.deviceInfoModelValue.text = if (modelText.isNotEmpty()) modelText else getString(R.string.device_info_unknown)

        binding.deviceInfoCurrentAppValue.text = status.currentAppName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.device_info_unknown)

        val statusTimestamp = (status.timestamp ?: secureSettings.getLastDeviceStatusTimestamp()).takeIf { it > 0 }
        binding.deviceInfoUpdatedValue.text = if (statusTimestamp != null) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            timeFormat.format(Date(statusTimestamp))
        } else {
            getString(R.string.device_info_unknown)
        }

        latestDeviceStatus = status
    }

    private fun showDeviceInfoMessage(message: String) {
        binding.deviceInfoContent.isVisible = false
        binding.deviceInfoProgress.isVisible = false
        binding.deviceInfoStatusMessage.isVisible = true
        binding.deviceInfoStatusMessage.text = message
    }

    private fun refreshChildDeviceStatus(force: Boolean = false) {
        val childDeviceId = secureSettings.getChildDeviceId()
        if (childDeviceId.isNullOrEmpty()) {
            deviceStatusJob?.cancel()
            lastStatusFetchTime = 0L
            showDeviceInfoMessage(getString(R.string.device_info_needs_pairing))
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastStatusFetchTime < 60_000) {
            return
        }
        lastStatusFetchTime = now

        deviceStatusJob?.cancel()
        binding.deviceInfoProgress.isVisible = true
        binding.deviceInfoStatusMessage.isVisible = false

        deviceStatusJob = lifecycleScope.launch {
            try {
                val response = networkClient.getChildDeviceStatus(childDeviceId)
                if (response.isSuccessful) {
                    val status = response.body()?.status
                    if (status != null) {
                        val normalizedTimestamp = status.timestamp ?: System.currentTimeMillis()
                        val normalizedStatus = status.copy(timestamp = normalizedTimestamp)
                        secureSettings.setLastDeviceStatus(gson.toJson(normalizedStatus))
                        secureSettings.setLastDeviceStatusTimestamp(normalizedTimestamp)
                        applyDeviceStatus(normalizedStatus)
                    } else {
                        showDeviceInfoMessage(getString(R.string.device_info_not_available))
                    }
                } else {
                    showDeviceInfoMessage(getString(R.string.device_info_not_available))
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load device status", error)
                showDeviceInfoMessage(getString(R.string.device_info_not_available))
            } finally {
                binding.deviceInfoProgress.isVisible = false
            }
        }
    }
    
    private fun performSecurityChecks() {
        try {
            val securityReport = SecurityChecker.getSecurityReport(this)
            val securityWarnings = SecurityChecker.getSecurityWarnings(this)

            // Security events logging is disabled in current version
            // The logging was causing encoding issues with Russian text

            // Show security warnings if any (only in debug builds)
            if (BuildConfig.DEBUG && securityWarnings.isNotEmpty()) {
                val warningText = securityWarnings.joinToString("\n")
                Toast.makeText(this, "Предупреждения безопасности:\n$warningText", Toast.LENGTH_LONG).show()
            }

            // Log security score
            android.util.Log.i("Security", "Security score: ${securityReport.securityScore}/100 (${securityReport.securityLevel})")

        } catch (e: Exception) {
            android.util.Log.e("Security", "Error performing security checks", e)
        }
    }
    
    private fun showConsentScreen() {
        val intent = Intent(this, ConsentActivity::class.java)
        startActivity(intent)
        finish()
    }
    
    private fun startMonitoring() {
        if (!hasConsent) {
            showToast(getString(R.string.consent_required))
            return
        }
        
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            requestPermissions()
            return
        }
        
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START_MONITORING
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        ensureChatBackgroundService()
        showToast(getString(R.string.monitoring_started))
        updateUIState()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_MONITORING
        }
        startService(intent)

        stopChatBackgroundService()
        showToast(getString(R.string.monitoring_stopped))
        updateUIState()
    }

    private fun showEmergencyStopDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.emergency_stop_title))
            .setMessage(getString(R.string.emergency_stop_message))
            .setPositiveButton(getString(R.string.emergency_stop_yes)) { _, _ ->
                emergencyStopAll()
            }
            .setNegativeButton(getString(R.string.emergency_stop_cancel), null)
            .show()
    }

    private fun emergencyStopAll() {
        Log.w("ChildWatch", "EMERGENCY STOP triggered")

        // Stop audio playback if running
        if (ru.example.childwatch.service.AudioPlaybackService.isPlaying) {
            ru.example.childwatch.service.AudioPlaybackService.stopPlayback(this)
            Log.d("ChildWatch", "Audio playback stopped")
        }

        // Stop monitoring
        stopMonitoring()
        Log.d("ChildWatch", "Monitoring stopped")

        showToast(getString(R.string.emergency_stop_done))
        Log.w("ChildWatch", "EMERGENCY STOP completed")
    }

    private fun requestPermissions() {
        // First request basic permissions
        if (!PermissionHelper.hasBasicPermissions(this)) {
            requestBasicPermissions()
        } else {
            // Basic permissions granted, now request background location if needed
            requestBackgroundLocationPermission()
        }
    }
    
    private fun requestBasicPermissions() {
        val basicPermissions = PermissionHelper.getBasicPermissions()
        val missingBasicPermissions = basicPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingBasicPermissions.isNotEmpty()) {
            basicPermissionLauncher.launch(missingBasicPermissions.toTypedArray())
        }
    }
    
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!PermissionHelper.hasBackgroundLocationPermission(this)) {
                // Show explanation first
                showBackgroundLocationExplanation()
            } else {
                // All permissions granted
                onAllPermissionsGranted()
            }
        } else {
            // Android 9 and below don't need background location permission
            onAllPermissionsGranted()
        }
    }
    
    private fun showBackgroundLocationExplanation() {
        val explanation = PermissionHelper.getPermissionExplanation(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val consequences = PermissionHelper.getPermissionDenialConsequences(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Разрешение на фоновое местоположение")
            .setMessage("$explanation\n\n$consequences\n\nПредоставить?")
            .setPositiveButton("Разрешить") { _, _ ->
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Пропустить") { _, _ ->
                showToast("Мониторинг будет работать только когда приложение открыто")
                onAllPermissionsGranted()
            }
            .setNeutralButton("Настройки") { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .show()
    }
    
    private fun handleBasicPermissionResults(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys

        if (deniedPermissions.isEmpty()) {
            showToast("Основные разрешения предоставлены")
            // Now request background location permission
            requestBackgroundLocationPermission()
        } else {
            val deniedList = deniedPermissions.joinToString(", ")
            showToast("Отказано в разрешениях: $deniedList")

            // Show explanation for denied permissions
            showPermissionDeniedExplanation(deniedPermissions)
        }
    }

    private fun handleBackgroundLocationResult(isGranted: Boolean) {
        if (isGranted) {
            showToast("Все разрешения предоставлены")
            onAllPermissionsGranted()
        } else {
            showToast("Фоновое местоположение отклонено. Мониторинг будет работать только когда приложение открыто")
            onAllPermissionsGranted() // Continue anyway
        }
    }

    private fun onAllPermissionsGranted() {
        showToast("Все необходимые разрешения предоставлены")
        // Try to start monitoring if consent is given
        if (hasConsent) {
            startMonitoring()
        }
    }
    
    private fun ensureChatBackgroundService() {
        val serverUrl = prefs.getString("server_url", "https://childwatch-production.up.railway.app")
            ?: "https://childwatch-production.up.railway.app"
        val childDeviceId = prefs.getString("child_device_id", null)

        if (!childDeviceId.isNullOrEmpty()) {
            ChatBackgroundService.start(this, serverUrl, childDeviceId)
        } else {
            Log.w(TAG, "Cannot start chat background service: child_device_id is missing")
        }
    }

    private fun stopChatBackgroundService() {
        ChatBackgroundService.stop(this)
    }

    private fun showPermissionDeniedExplanation(deniedPermissions: Set<String>) {
        val explanations = deniedPermissions.map { permission ->
            "${PermissionHelper.getPermissionExplanation(permission)}\n${PermissionHelper.getPermissionDenialConsequences(permission)}"
        }

        val message = explanations.joinToString("\n\n")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Необходимы разрешения")
            .setMessage(message)
            .setPositiveButton("Настройки") { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .setNegativeButton("Закрыть") { _, _ -> }
            .show()
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        deviceStatusJob?.cancel()
        super.onDestroy()
    }
    
    override fun onResume() {
        super.onResume()
        updateUIState()
        updateChatBadge()
        refreshChildDeviceStatus(force = true)
        CriticalAlertSyncScheduler.triggerImmediate(this)
        ensureChatBackgroundService()
    }

    /**
     * Update chat badge with unread message count
     */
    private fun updateChatBadge() {
        val unreadCount = chatManager.getUnreadCount()

        if (unreadCount > 0) {
            binding.chatBadge.visibility = View.VISIBLE
            binding.chatBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
        } else {
            binding.chatBadge.visibility = View.GONE
        }

        Log.d("MainActivity", "Chat badge updated: $unreadCount unread messages")
    }

    private fun showDeviceIdOptions(serverUrl: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Device ID не задан")
            .setMessage("Выберите способ получения Device ID ребенка:")
            .setPositiveButton("Тестовый режим") { _, _ ->
                // Использовать тестовый ID для демонстрации
                val testDeviceId = "test-child-device-001"
                val intent = Intent(this, AudioStreamingActivity::class.java).apply {
                    putExtra(AudioStreamingActivity.EXTRA_DEVICE_ID, testDeviceId)
                    putExtra(AudioStreamingActivity.EXTRA_SERVER_URL, serverUrl)
                }
                startActivity(intent)
                showToast("Открыто в тестовом режиме с ID: $testDeviceId")
            }
            .setNeutralButton("Настройки") { _, _ ->
                // Перейти в настройки для ввода ID
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Загрузить и отобразить выбранное устройство
     */
    private fun loadSelectedChild() {
        lifecycleScope.launch {
            try {
                val selectedDeviceId = prefs.getString("selected_device_id", null)

                if (selectedDeviceId != null) {
                    val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@MainActivity)
                    val childDao = database.childDao()
                    val child = childDao.getByDeviceId(selectedDeviceId)

                    if (child != null) {
                        binding.selectedChildName.text = child.name
                        binding.selectedChildDeviceId.text = "ID: ${child.deviceId.take(12)}..."

                        // Загрузить аватар
                        if (child.avatarUrl != null) {
                            try {
                                val uri = android.net.Uri.parse(child.avatarUrl)
                                binding.selectedChildAvatar.setImageURI(uri)
                            } catch (e: SecurityException) {
                                Log.w(TAG, "Avatar URI no longer accessible", e)
                                binding.selectedChildAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading avatar", e)
                                binding.selectedChildAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
                            }
                        } else {
                            binding.selectedChildAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
                        }

                        Log.d(TAG, "Загружен профиль ребенка: ${child.name}")
                    } else {
                        showDefaultChildSelection()
                    }
                } else {
                    showDefaultChildSelection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки профиля ребенка", e)
                showDefaultChildSelection()
            }
        }
    }

    /**
     * Показать состояние по умолчанию (устройство не выбрано)
     */
    private fun showDefaultChildSelection() {
        try {
            binding.selectedChildName.text = "Выберите устройство"
            binding.selectedChildDeviceId.text = "Нажмите для выбора"
            binding.selectedChildAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
        } catch (e: Exception) {
            Log.e(TAG, "Error in showDefaultChildSelection", e)
        }
    }

    /**
     * Обновить выбранное устройство
     */
    private fun updateSelectedChild(deviceId: String) {
        lifecycleScope.launch {
            try {
                val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@MainActivity)
                val childDao = database.childDao()
                val child = childDao.getByDeviceId(deviceId)

                if (child != null) {
                    // Обновить UI
                    binding.selectedChildName.text = child.name
                    binding.selectedChildDeviceId.text = "ID: ${child.deviceId.take(12)}..."

                    // Обновить аватар
                    if (child.avatarUrl != null) {
                        try {
                            val uri = android.net.Uri.parse(child.avatarUrl)
                            binding.selectedChildAvatar.setImageURI(uri)
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Avatar URI no longer accessible", e)
                            binding.selectedChildAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading avatar", e)
                            binding.selectedChildAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
                        }
                    } else {
                        binding.selectedChildAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
                    }

                    // Сохранить в настройках
                    prefs.edit().putString("selected_device_id", deviceId).apply()

                    showToast("Выбрано устройство: ${child.name}")
                    Log.d(TAG, "Устройство выбрано: ${child.name} ($deviceId)")
                } else {
                    showToast("Ошибка: устройство не найдено")
                    showDefaultChildSelection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления профиля ребенка", e)
                showToast("Ошибка обновления устройства")
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_BATTERY_PROMPT_SUPPRESSED = "battery_prompt_suppressed"
    }
}
