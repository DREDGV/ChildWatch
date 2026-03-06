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
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.service.ParentLocationService
import ru.example.childwatch.utils.BatteryOptimizationHelper
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecurityChecker
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.chat.ChatManager
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.contacts.ContactFeatures
import ru.example.childwatch.contacts.ContactRoles
import ru.example.childwatch.database.entity.Child
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
    private var deviceStatusRefreshJob: Job? = null
    private var badgeRefreshJob: Job? = null
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
        ensureParentLocationService()
        
        // Initialize WebSocket for commands (remote camera, etc.)
        initializeWebSocket()
    }
    
    /**
     * Initialize WebSocket connection for real-time commands
     */
    private fun initializeWebSocket() {
        try {
            val serverUrl = getConfiguredServerUrl()
            val targetDeviceId = resolveTargetDeviceId()
            
            if (!targetDeviceId.isNullOrBlank() && !serverUrl.isNullOrBlank()) {
                ru.example.childwatch.network.WebSocketManager.initialize(
                    this,
                    serverUrl,
                    targetDeviceId
                )
                ru.example.childwatch.network.WebSocketManager.connect(
                    onConnected = {
                        Log.d(TAG, "WebSocket connected for target: $targetDeviceId")
                    },
                    onError = { error ->
                        Log.e(TAG, "РІСњРЉ WebSocket connection error: $error")
                    }
                )
            } else {
                Log.w(
                    TAG,
                    "WebSocket init skipped: serverUrlPresent=${!serverUrl.isNullOrBlank()}, targetDeviceId=$targetDeviceId"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebSocket", e)
        }
    }


    private fun setupUI() {
        setupBatteryOptimizationUi()

        // Unified monitoring toggle button with visual feedback
        binding.monitoringToggleBtn.setOnClickListener {
            // Prevent double-tap by disabling button during state transition
            if (!binding.monitoringToggleBtn.isEnabled) return@setOnClickListener
            
            val isMonitoring = MonitorService.isRunning
            Log.d(TAG, "Monitoring toggle clicked: current state isRunning=$isMonitoring")
            
            // Disable button temporarily to prevent rapid clicks
            binding.monitoringToggleBtn.isEnabled = false
            
            if (isMonitoring) {
                stopMonitoring()
            } else {
                startMonitoring()
            }
            
            // Re-enable after 2 seconds (enough time for service to start/stop)
            binding.monitoringToggleBtn.postDelayed({
                binding.monitoringToggleBtn.isEnabled = true
            }, 2000)
        }

        // Keep old buttons for compatibility (hidden in layout)
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

        // Menu card click listeners (unified emerald design)
        // locationCard hidden; using parentLocationCard (DualLocationMapActivity) only

        binding.audioStreamingCard.setOnClickListener {
            val serverUrl = getConfiguredServerUrl()
            if (serverUrl.isNullOrBlank()) {
                showToast(getString(R.string.server_url_missing))
                return@setOnClickListener
            }
            val targetDeviceId = resolveTargetDeviceId()
            if (targetDeviceId.isNullOrBlank()) {
                showDeviceIdOptions(serverUrl)
                return@setOnClickListener
            }
            val intent = Intent(this@MainActivity, AudioStreamingActivity::class.java).apply {
                putExtra(AudioStreamingActivity.EXTRA_DEVICE_ID, targetDeviceId)
                putExtra(AudioStreamingActivity.EXTRA_SERVER_URL, serverUrl)
            }
            startActivity(intent)
        }

        binding.chatCard.setOnClickListener {
            val targetDeviceId = resolveTargetDeviceId()
            if (targetDeviceId.isNullOrBlank()) {
                showToast(getString(R.string.main_toast_set_child_device_id))
                return@setOnClickListener
            }
            ru.example.childwatch.utils.NotificationManager.resetUnreadCount()
            try {
                chatManager.markAllAsRead()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark chat messages as read before opening chat", e)
            }
            updateChatBadge()
            prefs.edit()
                .putString("child_device_id", targetDeviceId)
                .putString("selected_device_id", targetDeviceId)
                .apply()
            val intent = Intent(this@MainActivity, ChatActivity::class.java)
            startActivity(intent)
        }
        
        // Р•РґРёРЅСЃС‚РІРµРЅРЅР°СЏ РєРЅРѕРїРєР° РєР°РјРµСЂС‹ - СѓРґР°Р»С‘РЅРЅР°СЏ СЃСЉС‘РјРєР°
        binding.remoteCameraCard.setOnClickListener {
            openRemoteCamera()
        }
        
        // Location map card (legacy mode): parent + selected child
        findViewById<View>(R.id.parentLocationCard)?.setOnClickListener {
            val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            val myId = listOf(
                prefs.getString("device_id", null),
                prefs.getString("parent_device_id", null)
            )
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val otherId = resolveTargetDeviceId()
            if (otherId.isNullOrBlank()) {
                showToast(getString(R.string.main_toast_set_child_device_id))
                return@setOnClickListener
            }
            val intent = DualLocationMapActivity.createIntent(
                context = this,
                myRole = DualLocationMapActivity.ROLE_PARENT,
                myId = myId,
                otherId = otherId
            )
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
                    showToast(getString(R.string.main_toast_launch_error, e.message ?: "unknown"))
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

        // Ensure legacy single-device setups work without manual contact creation
        ensureLegacyContact()
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

    private fun updateUIState(skipAutoRecovery: Boolean = false) {
        // Check consent first
        hasConsent = ConsentActivity.hasConsent(this)
        
        if (hasConsent) {
            // Use only runtime flag for UI state to avoid confusion
            val isMonitoring = MonitorService.isRunning
            
            // Auto-recover ONLY on app resume if persisted state says monitoring should be on
            // BUT skip during user actions or if runtime already matches
            if (!skipAutoRecovery && !isMonitoring) {
                val persistedEnabled = secureSettings.isMonitoringEnabled()
                if (persistedEnabled) {
                    try {
                        val intent = Intent(this, MonitorService::class.java).apply {
                            action = MonitorService.ACTION_START_MONITORING
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        android.util.Log.d(TAG, "Auto-recovered MonitorService to match persisted state")
                        // Give service time to start before updating UI
                        binding.monitoringToggleBtn.postDelayed({
                            updateUIState(skipAutoRecovery = true)
                        }, 500)
                        return
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to auto-start MonitorService", e)
                    }
                }
            }
            
            // Update status display
            updateStatusDisplay(isMonitoring)
            
            // Update unified toggle button with animation
            updateMonitoringButton(isMonitoring)
            
            // Update old button states (hidden, for compatibility)
            binding.startMonitoringBtn.isEnabled = !isMonitoring
            binding.stopMonitoringBtn.isEnabled = isMonitoring
            
        } else {
            // Show consent screen
            showConsentScreen()
        }

        updateDeviceInfoCard()
        checkBatteryOptimizationStatus()
    }
    
    private fun updateMonitoringButton(isMonitoring: Boolean) {
        binding.monitoringToggleBtn.apply {
            // Animate scale
            animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    // Update appearance
                    if (isMonitoring) {
                        // Active state - red/danger color
                        text = getString(R.string.stop_monitoring)
                        setIconResource(R.drawable.ic_stop)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(context, android.R.color.holo_red_dark)
                        )
                    } else {
                        // Inactive state - emerald/start color
                        text = getString(R.string.start_monitoring)
                        setIconResource(R.drawable.ic_play)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.emerald_primary)
                        )
                    }
                    
                    // Scale back
                    animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
    }
    
    private fun updateStatusDisplay(isMonitoring: Boolean) {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        if (isMonitoring) {
            // Active monitoring - bright green with pulsing animation
            binding.statusText.text = getString(R.string.monitoring_active_status)
            binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.statusIcon.setImageResource(android.R.drawable.presence_online)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
            
            // Add pulsing animation to icon
            binding.statusIcon.animate()
                .alpha(0.3f)
                .setDuration(800)
                .withEndAction {
                    binding.statusIcon.animate()
                        .alpha(1.0f)
                        .setDuration(800)
                        .start()
                }
                .start()
            
            // Update service running time
            val serviceStartTime = secureSettings.getServiceStartTime()
            if (serviceStartTime > 0) {
                val runningTime = System.currentTimeMillis() - serviceStartTime
                val hours = runningTime / (1000 * 60 * 60)
                val minutes = (runningTime % (1000 * 60 * 60)) / (1000 * 60)
                val timeString = getString(R.string.service_running_time_value, hours, minutes)
                binding.serviceRunningTimeText.text = getString(R.string.service_running_time, timeString)
                binding.serviceRunningTimeText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            } else {
                binding.serviceRunningTimeText.text = getString(R.string.service_running_time, getString(R.string.unknown))
            }
            
            // Update feature status
            binding.locationStatusText.text = getString(R.string.status_location_active)
            binding.audioStatusText.text = getString(R.string.status_audio_active)
            binding.photoStatusText.text = getString(R.string.status_photo_active)
            
        } else {
            // Inactive monitoring - gray
            binding.statusText.text = getString(R.string.monitoring_inactive_status)
            binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.statusIcon.setImageResource(android.R.drawable.presence_offline)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
            
            // Stop any animation
            binding.statusIcon.animate().cancel()
            binding.statusIcon.alpha = 1.0f
            
            binding.serviceRunningTimeText.text = getString(R.string.service_running_time, getString(R.string.not_working))
            binding.serviceRunningTimeText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

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

        val childDeviceId = resolveDeviceIdForStatus()
        if (!childDeviceId.isNullOrBlank()) {
            secureSettings.setChildDeviceId(childDeviceId)
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
        val statusTimestamp = normalizeEpochMillis(status.timestamp ?: secureSettings.getLastDeviceStatusTimestamp())
        val isStale = statusTimestamp?.let { System.currentTimeMillis() - it > DEVICE_STATUS_STALE_MS } == true
        if (statusTimestamp == null) {
            Log.w(TAG, "Device status timestamp missing")
        } else if (isStale) {
            Log.d(TAG, "Device status is stale")
        }

        binding.deviceInfoBatteryValue.text = status.batteryLevel
            ?.takeIf { it in 0..100 }
            ?.let { "$it%" }
            ?: getString(R.string.device_info_unknown)

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

        binding.deviceInfoUpdatedValue.text = if (statusTimestamp != null) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val base = timeFormat.format(Date(statusTimestamp))
            if (isStale) "$base ${getString(R.string.device_info_stale_suffix)}" else base
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
        val childDeviceId = resolveDeviceIdForStatus()
        if (childDeviceId.isNullOrEmpty()) {
            deviceStatusJob?.cancel()
            lastStatusFetchTime = 0L
            showDeviceInfoMessage(getString(R.string.device_info_needs_pairing))
            return
        }
        secureSettings.setChildDeviceId(childDeviceId)

        val serverUrl = getConfiguredServerUrl()
        if (serverUrl.isNullOrBlank()) {
            Log.w(TAG, "Device status fetch skipped: server URL not configured")
            if (latestDeviceStatus == null) {
                showDeviceInfoMessage(getString(R.string.server_url_missing))
            }
            binding.deviceInfoProgress.isVisible = false
            return
        }

        Log.d(TAG, "Fetching device status: deviceId=$childDeviceId serverUrl=$serverUrl")

        val now = System.currentTimeMillis()
        if (!force && now - lastStatusFetchTime < 60_000) {
            return
        }
        
        // Р вЂўРЎРѓР В»Р С‘ force=false Р С‘ Р В·Р В°Р С–РЎР‚РЎС“Р В·Р С”Р В° РЎС“Р В¶Р Вµ Р С‘Р Т‘РЎвЂРЎвЂљ, Р Р…Р Вµ Р В·Р В°Р С—РЎС“РЎРѓР С”Р В°Р ВµР С Р Р…Р С•Р Р†РЎС“РЎР‹
        if (!force && deviceStatusJob?.isActive == true) {
            return
        }
        
        // Р вЂўРЎРѓР В»Р С‘ force=true, Р С•РЎвЂљР СР ВµР Р…РЎРЏР ВµР С РЎРѓРЎвЂљР В°РЎР‚РЎС“РЎР‹ Р В·Р В°Р С–РЎР‚РЎС“Р В·Р С”РЎС“ Р С‘ Р В·Р В°Р С—РЎС“РЎРѓР С”Р В°Р ВµР С Р Р…Р С•Р Р†РЎС“РЎР‹
        if (force) {
            deviceStatusJob?.cancel()
        }
        
        lastStatusFetchTime = now

        binding.deviceInfoProgress.isVisible = true
        binding.deviceInfoStatusMessage.isVisible = false

        deviceStatusJob = lifecycleScope.launch {
            try {
                var attempt = 0
                while (attempt < 3) {
                    attempt++
                    val response = withContext(Dispatchers.IO) {
                        networkClient.getChildDeviceStatus(childDeviceId)
                    }
                    if (response.isSuccessful) {
                        val status = response.body()?.status
                        if (status != null) {
                            val normalizedTimestamp = normalizeEpochMillis(status.timestamp) ?: System.currentTimeMillis()
                            val normalizedStatus = status.copy(timestamp = normalizedTimestamp)
                            secureSettings.setLastDeviceStatus(gson.toJson(normalizedStatus))
                            secureSettings.setLastDeviceStatusTimestamp(normalizedTimestamp)
                            applyDeviceStatus(normalizedStatus)
                            return@launch
                        }
                    }

                    if (attempt < 3) {
                        delay(500L * attempt)
                    }
                }

                if (latestDeviceStatus == null) {
                    showDeviceInfoMessage(getString(R.string.device_info_not_available))
                }
            } catch (error: CancellationException) {
                // Р ВР С–Р Р…Р С•РЎР‚Р С‘РЎР‚РЎС“Р ВµР С Р С•РЎвЂљР СР ВµР Р…РЎС“ Р С”Р С•РЎР‚РЎС“РЎвЂљР С‘Р Р…РЎвЂ№ - РЎРЊРЎвЂљР С• Р Р…Р С•РЎР‚Р СР В°Р В»РЎРЉР Р…Р С•
                Log.d(TAG, "Device status fetch cancelled")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load device status", error)
                if (latestDeviceStatus == null) {
                    showDeviceInfoMessage(getString(R.string.device_info_not_available))
                }
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

            // Keep debug security information in logs only to avoid noisy popups and mojibake in UI.
            if (BuildConfig.DEBUG && securityWarnings.isNotEmpty()) {
                Log.w(TAG, "Security warnings: ${securityWarnings.joinToString("; ")}")
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
        
        Log.d(TAG, "User action: starting monitoring")
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
        // Optimistically update button state, then refresh UI
        updateMonitoringButton(true)
        // Skip auto-recovery during user action to avoid race condition
        updateUIState(skipAutoRecovery = true)
    }

    private fun stopMonitoring() {
        Log.d(TAG, "User action: stopping monitoring")
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_MONITORING
        }
        startService(intent)

        stopChatBackgroundService()
        showToast(getString(R.string.monitoring_stopped))
        // Optimistically update button state, then refresh UI
        updateMonitoringButton(false)
        // Skip auto-recovery during user action to avoid race condition
        updateUIState(skipAutoRecovery = true)
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
            .setTitle("Р В Р В°Р В·РЎР‚Р ВµРЎв‚¬Р ВµР Р…Р С‘Р Вµ Р Р…Р В° РЎвЂћР С•Р Р…Р С•Р Р†Р С•Р Вµ Р СР ВµРЎРѓРЎвЂљР С•Р С—Р С•Р В»Р С•Р В¶Р ВµР Р…Р С‘Р Вµ")
            .setMessage("$explanation\n\n$consequences\n\nР СџРЎР‚Р ВµР Т‘Р С•РЎРѓРЎвЂљР В°Р Р†Р С‘РЎвЂљРЎРЉ?")
            .setPositiveButton("Р В Р В°Р В·РЎР‚Р ВµРЎв‚¬Р С‘РЎвЂљРЎРЉ") { _, _ ->
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Р СџРЎР‚Р С•Р С—РЎС“РЎРѓРЎвЂљР С‘РЎвЂљРЎРЉ") { _, _ ->
                showToast("Р СљР С•Р Р…Р С‘РЎвЂљР С•РЎР‚Р С‘Р Р…Р С– Р В±РЎС“Р Т‘Р ВµРЎвЂљ РЎР‚Р В°Р В±Р С•РЎвЂљР В°РЎвЂљРЎРЉ РЎвЂљР С•Р В»РЎРЉР С”Р С• Р С”Р С•Р С–Р Т‘Р В° Р С—РЎР‚Р С‘Р В»Р С•Р В¶Р ВµР Р…Р С‘Р Вµ Р С•РЎвЂљР С”РЎР‚РЎвЂ№РЎвЂљР С•")
                onAllPermissionsGranted()
            }
            .setNeutralButton("Р СњР В°РЎРѓРЎвЂљРЎР‚Р С•Р в„–Р С”Р С‘") { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .show()
    }
    
    private fun handleBasicPermissionResults(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys

        if (deniedPermissions.isEmpty()) {
            showToast("Р С›РЎРѓР Р…Р С•Р Р†Р Р…РЎвЂ№Р Вµ РЎР‚Р В°Р В·РЎР‚Р ВµРЎв‚¬Р ВµР Р…Р С‘РЎРЏ Р С—РЎР‚Р ВµР Т‘Р С•РЎРѓРЎвЂљР В°Р Р†Р В»Р ВµР Р…РЎвЂ№")
            // Now request background location permission
            requestBackgroundLocationPermission()
        } else {
            val deniedList = deniedPermissions.joinToString(", ")
            showToast("Р С›РЎвЂљР С”Р В°Р В·Р В°Р Р…Р С• Р Р† РЎР‚Р В°Р В·РЎР‚Р ВµРЎв‚¬Р ВµР Р…Р С‘РЎРЏРЎвЂ¦: $deniedList")

            // Show explanation for denied permissions
            showPermissionDeniedExplanation(deniedPermissions)
        }
    }

    private fun handleBackgroundLocationResult(isGranted: Boolean) {
        if (isGranted) {
            showToast("Р вЂ™РЎРѓР Вµ РЎР‚Р В°Р В·РЎР‚Р ВµРЎв‚¬Р ВµР Р…Р С‘РЎРЏ Р С—РЎР‚Р ВµР Т‘Р С•РЎРѓРЎвЂљР В°Р Р†Р В»Р ВµР Р…РЎвЂ№")
            onAllPermissionsGranted()
        } else {
            showToast("Р В¤Р С•Р Р…Р С•Р Р†Р С•Р Вµ Р СР ВµРЎРѓРЎвЂљР С•Р С—Р С•Р В»Р С•Р В¶Р ВµР Р…Р С‘Р Вµ Р С•РЎвЂљР С”Р В»Р С•Р Р…Р ВµР Р…Р С•. Р СљР С•Р Р…Р С‘РЎвЂљР С•РЎР‚Р С‘Р Р…Р С– Р В±РЎС“Р Т‘Р ВµРЎвЂљ РЎР‚Р В°Р В±Р С•РЎвЂљР В°РЎвЂљРЎРЉ РЎвЂљР С•Р В»РЎРЉР С”Р С• Р С”Р С•Р С–Р Т‘Р В° Р С—РЎР‚Р С‘Р В»Р С•Р В¶Р ВµР Р…Р С‘Р Вµ Р С•РЎвЂљР С”РЎР‚РЎвЂ№РЎвЂљР С•")
            onAllPermissionsGranted() // Continue anyway
        }
    }

    private fun onAllPermissionsGranted() {
        showToast("Р вЂ™РЎРѓР Вµ Р Р…Р ВµР С•Р В±РЎвЂ¦Р С•Р Т‘Р С‘Р СРЎвЂ№Р Вµ РЎР‚Р В°Р В·РЎР‚Р ВµРЎв‚¬Р ВµР Р…Р С‘РЎРЏ Р С—РЎР‚Р ВµР Т‘Р С•РЎРѓРЎвЂљР В°Р Р†Р В»Р ВµР Р…РЎвЂ№")
        // Try to start monitoring if consent is given
        if (hasConsent) {
            startMonitoring()
        }
    }
    
    private fun ensureChatBackgroundService() {
        val serverUrl = getConfiguredServerUrl()
        val childDeviceId = resolveTargetDeviceId()

        if (!childDeviceId.isNullOrEmpty() && !serverUrl.isNullOrBlank()) {
            ChatBackgroundService.start(this, serverUrl, childDeviceId)
        } else {
            Log.w(TAG, "Cannot start chat background service: serverUrl=$serverUrl child_device_id=$childDeviceId")
        }
    }

    private fun ensureParentLocationService() {
        try {
            val shareEnabled = prefs.getBoolean("share_parent_location", true)
            val serverUrl = getConfiguredServerUrl()
            val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val ownDeviceId = listOf(
                secureSettings.getDeviceId(),
                prefs.getString("device_id", null),
                prefs.getString("parent_device_id", null),
                prefs.getString("linked_parent_device_id", null),
                legacyPrefs.getString("device_id", null),
                legacyPrefs.getString("parent_device_id", null),
                legacyPrefs.getString("linked_parent_device_id", null)
            )
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotBlank() }

            if (shareEnabled && !serverUrl.isNullOrBlank() && !ownDeviceId.isNullOrBlank()) {
                ParentLocationService.start(this)
                Log.d(TAG, "ParentLocationService ensured (independent from WS state)")
            } else {
                ParentLocationService.stop(this)
                Log.d(
                    TAG,
                    "ParentLocationService stopped: shareEnabled=$shareEnabled serverConfigured=${!serverUrl.isNullOrBlank()} ownIdPresent=${!ownDeviceId.isNullOrBlank()}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure ParentLocationService", e)
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
            .setTitle("Р СњР ВµР С•Р В±РЎвЂ¦Р С•Р Т‘Р С‘Р СРЎвЂ№ РЎР‚Р В°Р В·РЎР‚Р ВµРЎв‚¬Р ВµР Р…Р С‘РЎРЏ")
            .setMessage(message)
            .setPositiveButton("Р СњР В°РЎРѓРЎвЂљРЎР‚Р С•Р в„–Р С”Р С‘") { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .setNegativeButton("Р вЂ”Р В°Р С”РЎР‚РЎвЂ№РЎвЂљРЎРЉ") { _, _ -> }
            .show()
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun resolveTargetDeviceId(): String? {
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val myDeviceId = prefs.getString("device_id", null)?.trim().orEmpty()
        val excluded = listOf(
            myDeviceId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        // Keep legacy single-pair behavior stable: prefer child_device_id over selected_device_id.
        val resolved = listOf(
            prefs.getString("child_device_id", null),
            secureSettings.getChildDeviceId(),
            legacyPrefs.getString("child_device_id", null),
            prefs.getString("selected_device_id", null),
            legacyPrefs.getString("selected_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && it !in excluded }
            ?: return null

        if (prefs.getString("child_device_id", null).isNullOrBlank()) {
            prefs.edit().putString("child_device_id", resolved).apply()
        }
        if (prefs.getString("selected_device_id", null).isNullOrBlank()) {
            prefs.edit().putString("selected_device_id", resolved).apply()
        }
        if (secureSettings.getChildDeviceId().isNullOrBlank()) {
            secureSettings.setChildDeviceId(resolved)
        }

        return resolved
    }

    private fun getConfiguredServerUrl(): String? {
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val raw = listOf(
            secureSettings.getServerUrl(),
            prefs.getString("server_url", null),
            legacyPrefs.getString("server_url", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        return normalizeServerUrl(raw)
    }

    private fun normalizeEpochMillis(raw: Long?): Long? {
        if (raw == null || raw <= 0L) return null
        return when {
            raw < 10_000_000_000L -> raw * 1000L // seconds -> millis
            raw > 10_000_000_000_000L -> raw / 1000L // micros -> millis
            else -> raw
        }
    }

    private fun resolveDeviceIdForStatus(): String? {
        val preferred = resolveTargetDeviceId()
        if (!preferred.isNullOrBlank()) {
            return preferred
        }

        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val myDeviceId = prefs.getString("device_id", null)?.trim().orEmpty()
        val excluded = listOf(
            myDeviceId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        return listOf(
            prefs.getString("selected_device_id", null),
            prefs.getString("child_device_id", null),
            secureSettings.getChildDeviceId(),
            legacyPrefs.getString("selected_device_id", null),
            legacyPrefs.getString("child_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && it !in excluded }
    }

    private fun normalizeServerUrl(raw: String): String {
        val candidate = extractUrlCandidate(raw)
        if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
            return candidate
        }

        val looksLikeLocalOrIp = candidate.startsWith("localhost", ignoreCase = true) ||
            candidate.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?$"))
        return if (looksLikeLocalOrIp) "http://$candidate" else "https://$candidate"
    }

    private fun extractUrlCandidate(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value

        // Legacy bug: two URLs could be saved in one field.
        // Prefer the last valid URL token (most recently entered by user).
        val regex = Regex("""https?://[^\s,;]+|(?:localhost|\d{1,3}(?:\.\d{1,3}){3}|[A-Za-z0-9.-]+\.[A-Za-z]{2,})(?::\d+)?""")
        val matches = regex.findAll(value).map { it.value.trim() }.toList()
        return matches.lastOrNull().orEmpty().ifBlank { value.lineSequence().lastOrNull()?.trim().orEmpty() }
    }

    private fun startDeviceStatusRefreshLoop() {
        if (deviceStatusRefreshJob?.isActive == true) return
        deviceStatusRefreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshChildDeviceStatus(force = true)
                delay(30_000)
            }
        }
    }
    
    override fun onDestroy() {
        badgeRefreshJob?.cancel()
        deviceStatusJob?.cancel()
        deviceStatusRefreshJob?.cancel()
        super.onDestroy()
    }
    
    override fun onResume() {
        super.onResume()
        prefs.edit().putBoolean("chat_open", false).apply()
        updateUIState()
        updateChatBadge()
        startBadgeRefreshLoop()
        refreshChildDeviceStatus(force = true)
        startDeviceStatusRefreshLoop()
        CriticalAlertSyncScheduler.triggerImmediate(this)
        ensureChatBackgroundService()
        ensureParentLocationService()
        initializeWebSocket()
    }

    override fun onPause() {
        badgeRefreshJob?.cancel()
        deviceStatusRefreshJob?.cancel()
        super.onPause()
    }

    /**
     * Update chat badge with unread message count
     */
    private fun updateChatBadge() {
        lifecycleScope.launch(Dispatchers.IO) {
            val unreadFromStore = try { chatManager.getUnreadCount() } catch (_: Exception) { 0 }
            val unreadFromNotifications = try { ru.example.childwatch.utils.NotificationManager.getUnreadCount() } catch (_: Exception) { 0 }
            val unread = maxOf(unreadFromStore, unreadFromNotifications)
            withContext(Dispatchers.Main) {
                if (unread > 0) {
                    binding.chatBadge.visibility = View.VISIBLE
                    binding.chatBadge.text = if (unread > 99) "99+" else unread.toString()
                } else {
                    binding.chatBadge.visibility = View.GONE
                }
                Log.d("MainActivity", "Chat badge updated: $unread unread messages")
            }
        }
    }

    private fun startBadgeRefreshLoop() {
        if (badgeRefreshJob?.isActive == true) return
        badgeRefreshJob = lifecycleScope.launch {
            while (isActive) {
                updateChatBadge()
                delay(2000)
            }
        }
    }

    private fun showDeviceIdOptions(serverUrl: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.main_dialog_device_id_missing_title))
            .setMessage(getString(R.string.main_dialog_device_id_missing_message))
            .setPositiveButton(getString(R.string.main_dialog_device_id_missing_test)) { _, _ ->
                // Р ВРЎРѓР С—Р С•Р В»РЎРЉР В·Р С•Р Р†Р В°РЎвЂљРЎРЉ РЎвЂљР ВµРЎРѓРЎвЂљР С•Р Р†РЎвЂ№Р в„– ID Р Т‘Р В»РЎРЏ Р Т‘Р ВµР СР С•Р Р…РЎРѓРЎвЂљРЎР‚Р В°РЎвЂ Р С‘Р С‘
                val testDeviceId = "test-child-device-001"
                val intent = Intent(this, AudioStreamingActivity::class.java).apply {
                    putExtra(AudioStreamingActivity.EXTRA_DEVICE_ID, testDeviceId)
                    putExtra(AudioStreamingActivity.EXTRA_SERVER_URL, serverUrl)
                }
                startActivity(intent)
                showToast(getString(R.string.main_toast_started_test_mode, testDeviceId))
            }
            .setNeutralButton(getString(R.string.main_dialog_device_id_missing_settings)) { _, _ ->
                // Р СџР ВµРЎР‚Р ВµР в„–РЎвЂљР С‘ Р Р† Р Р…Р В°РЎРѓРЎвЂљРЎР‚Р С•Р в„–Р С”Р С‘ Р Т‘Р В»РЎРЏ Р Р†Р Р†Р С•Р Т‘Р В° ID
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * Р вЂ”Р В°Р С–РЎР‚РЎС“Р В·Р С‘РЎвЂљРЎРЉ Р С‘ Р С•РЎвЂљР С•Р В±РЎР‚Р В°Р В·Р С‘РЎвЂљРЎРЉ Р Р†РЎвЂ№Р В±РЎР‚Р В°Р Р…Р Р…Р С•Р Вµ РЎС“РЎРѓРЎвЂљРЎР‚Р С•Р в„–РЎРѓРЎвЂљР Р†Р С•
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

                        // Р вЂ”Р В°Р С–РЎР‚РЎС“Р В·Р С‘РЎвЂљРЎРЉ Р В°Р Р†Р В°РЎвЂљР В°РЎР‚
                        if (child.avatarUrl != null) {
                            try {
                                val uri = android.net.Uri.parse(child.avatarUrl)
                                binding.selectedChildAvatar.setImageURI(uri)
                            } catch (e: SecurityException) {
                                Log.w(TAG, "Avatar URI no longer accessible", e)
                                binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading avatar", e)
                                binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                            }
                        } else {
                            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                        }

                        Log.d(TAG, "Р вЂ”Р В°Р С–РЎР‚РЎС“Р В¶Р ВµР Р… Р С—РЎР‚Р С•РЎвЂћР С‘Р В»РЎРЉ РЎР‚Р ВµР В±Р ВµР Р…Р С”Р В°: ${child.name}")
                    } else {
                        showDefaultChildSelection()
                    }
                } else {
                    showDefaultChildSelection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Р С›РЎв‚¬Р С‘Р В±Р С”Р В° Р В·Р В°Р С–РЎР‚РЎС“Р В·Р С”Р С‘ Р С—РЎР‚Р С•РЎвЂћР С‘Р В»РЎРЏ РЎР‚Р ВµР В±Р ВµР Р…Р С”Р В°", e)
                showDefaultChildSelection()
            }
        }
    }

    /**
     * Р СџР С•Р С”Р В°Р В·Р В°РЎвЂљРЎРЉ РЎРѓР С•РЎРѓРЎвЂљР С•РЎРЏР Р…Р С‘Р Вµ Р С—Р С• РЎС“Р СР С•Р В»РЎвЂЎР В°Р Р…Р С‘РЎР‹ (РЎС“РЎРѓРЎвЂљРЎР‚Р С•Р в„–РЎРѓРЎвЂљР Р†Р С• Р Р…Р Вµ Р Р†РЎвЂ№Р В±РЎР‚Р В°Р Р…Р С•)
     */
    private fun showDefaultChildSelection() {
        try {
            binding.selectedChildName.text = getString(R.string.main_select_contact_placeholder_title)
            binding.selectedChildDeviceId.text = getString(R.string.main_select_contact_placeholder_subtitle)
            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(0, "child"))
        } catch (e: Exception) {
            Log.e(TAG, "Error in showDefaultChildSelection", e)
        }
    }

    private suspend fun getSelectedContact(): Child? {
        return try {
            val selectedId = prefs.getString("selected_device_id", null)
                ?: prefs.getString("child_device_id", null)
            if (selectedId.isNullOrBlank()) return null
            val database = ChildWatchDatabase.getInstance(this)
            database.childDao().getByDeviceId(selectedId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load selected contact", e)
            withContext(Dispatchers.Main) {
                showToast(getString(R.string.main_toast_contacts_db_error))
            }
            null
        }
    }

    private fun ensureLegacyContact() {
        val legacyId = prefs.getString("child_device_id", null)
        if (legacyId.isNullOrBlank()) return

        if (prefs.getString("selected_device_id", null).isNullOrBlank()) {
            prefs.edit().putString("selected_device_id", legacyId).apply()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val database = ChildWatchDatabase.getInstance(this@MainActivity)
                val childDao = database.childDao()
                val existing = childDao.getByDeviceId(legacyId)
                if (existing == null) {
                    val now = System.currentTimeMillis()
                    val child = Child(
                        deviceId = legacyId,
                        name = "Р В Р ВµР В±Р ВµР Р…Р С•Р С”",
                        role = ContactRoles.CHILD,
                        iconId = ContactIcons.CHILD,
                        allowedFeatures = ContactFeatures.ALL,
                        createdAt = now,
                        updatedAt = now
                    )
                    childDao.insert(child)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate legacy contact", e)
            }
        }
    }

    /**
     * Р С›Р В±Р Р…Р С•Р Р†Р С‘РЎвЂљРЎРЉ Р Р†РЎвЂ№Р В±РЎР‚Р В°Р Р…Р Р…Р С•Р Вµ РЎС“РЎРѓРЎвЂљРЎР‚Р С•Р в„–РЎРѓРЎвЂљР Р†Р С•
     */
    private fun updateSelectedChild(deviceId: String) {
        lifecycleScope.launch {
            try {
                val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@MainActivity)
                val childDao = database.childDao()
                val child = childDao.getByDeviceId(deviceId)

                if (child != null) {
                    // Р С›Р В±Р Р…Р С•Р Р†Р С‘РЎвЂљРЎРЉ UI
                    binding.selectedChildName.text = child.name
                    binding.selectedChildDeviceId.text = "ID: ${child.deviceId.take(12)}..."

                    // Р С›Р В±Р Р…Р С•Р Р†Р С‘РЎвЂљРЎРЉ Р В°Р Р†Р В°РЎвЂљР В°РЎР‚
                    if (child.avatarUrl != null) {
                        try {
                            val uri = android.net.Uri.parse(child.avatarUrl)
                            binding.selectedChildAvatar.setImageURI(uri)
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Avatar URI no longer accessible", e)
                            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading avatar", e)
                            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                        }
                    } else {
                        binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                    }

                    // Р РЋР С•РЎвЂ¦РЎР‚Р В°Р Р…Р С‘РЎвЂљРЎРЉ Р Р† Р Р…Р В°РЎРѓРЎвЂљРЎР‚Р С•Р в„–Р С”Р В°РЎвЂ¦
                    prefs.edit()
                        .putString("selected_device_id", deviceId)
                        .putString("child_device_id", deviceId)
                        .apply()

                    showToast(getString(R.string.main_toast_contact_selected, child.name))
                    Log.d(TAG, "Р Р€РЎРѓРЎвЂљРЎР‚Р С•Р в„–РЎРѓРЎвЂљР Р†Р С• Р Р†РЎвЂ№Р В±РЎР‚Р В°Р Р…Р С•: ${child.name} ($deviceId)")
                } else {
                    showToast(getString(R.string.main_toast_contact_not_found))
                    showDefaultChildSelection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Р С›РЎв‚¬Р С‘Р В±Р С”Р В° Р С•Р В±Р Р…Р С•Р Р†Р В»Р ВµР Р…Р С‘РЎРЏ Р С—РЎР‚Р С•РЎвЂћР С‘Р В»РЎРЏ РЎР‚Р ВµР В±Р ВµР Р…Р С”Р В°", e)
                showToast(getString(R.string.main_toast_contact_update_error))
            }
        }
    }

    /**
     * Open remote camera activity
     */
    private fun openRemoteCamera() {
        val targetDeviceId = resolveTargetDeviceId()
        if (targetDeviceId.isNullOrBlank()) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.main_toast_set_child_device_id),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        MaterialAlertDialogBuilder(this@MainActivity)
            .setTitle("Remote Camera")
            .setMessage("Choose camera mode:")
            .setPositiveButton("Capture Photo") { _, _ ->
                val intent = Intent(this@MainActivity, RemoteCameraActivity::class.java).apply {
                    putExtra(RemoteCameraActivity.EXTRA_CHILD_ID, targetDeviceId)
                    putExtra(RemoteCameraActivity.EXTRA_CHILD_NAME, binding.selectedChildName.text?.toString().orEmpty())
                }
                startActivity(intent)
            }
            .setNegativeButton("Video Stream") { _, _ ->
                openVideoStream(targetDeviceId)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /**
     * Request remote photo capture
     */
    private fun requestRemotePhoto(childId: String) {
        if (!WebSocketManager.isConnected()) {
            Toast.makeText(this, "Not connected to server", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Requesting Photo")
            .setMessage("Please wait...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        // Set timeout
        val timeoutHandler = android.os.Handler(mainLooper)
        val timeoutRunnable = Runnable {
            progressDialog.dismiss()
            Toast.makeText(this, "Photo request timeout", Toast.LENGTH_LONG).show()
        }
        timeoutHandler.postDelayed(timeoutRunnable, 30000) // 30 second timeout

        // Request photo
        WebSocketManager.requestPhoto(
            targetDevice = childId,
            onSuccess = {
                Log.d(TAG, "Photo request sent successfully")
            },
            onError = { error ->
                timeoutHandler.removeCallbacks(timeoutRunnable)
                progressDialog.dismiss()
                Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
            }
        )

        // Set one-time callback for photo response
        var photoReceived = false
        WebSocketManager.setPhotoReceivedCallback { photoBase64, requestId, timestamp ->
            if (!photoReceived) {
                photoReceived = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                runOnUiThread {
                    progressDialog.dismiss()
                    openPhotoPreview(photoBase64, timestamp, childId)
                }
            }
        }

        WebSocketManager.setPhotoErrorCallback { requestId, error ->
            if (!photoReceived) {
                photoReceived = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Camera error: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Open photo preview activity
     */
    private fun openPhotoPreview(photoBase64: String, timestamp: Long, deviceId: String) {
        lifecycleScope.launch {
            var deviceName = deviceId
            try {
                val database = ChildWatchDatabase.getInstance(this@MainActivity)
                val child = database.childDao().getByDeviceId(deviceId)
                deviceName = child?.name ?: deviceId
            } catch (e: Exception) {
                Log.e(TAG, "Error getting device name", e)
            }

            val intent = Intent(this@MainActivity, PhotoPreviewActivity::class.java).apply {
                putExtra(PhotoPreviewActivity.EXTRA_PHOTO_BASE64, photoBase64)
                putExtra(PhotoPreviewActivity.EXTRA_PHOTO_TIMESTAMP, timestamp)
                putExtra(PhotoPreviewActivity.EXTRA_DEVICE_NAME, deviceName)
            }
            startActivity(intent)
        }
    }

    /**
     * Open video stream activity
     */
    private fun openVideoStream(childId: String) {
        lifecycleScope.launch {
            var childName: String? = null
            try {
                val database = ChildWatchDatabase.getInstance(this@MainActivity)
                val child = database.childDao().getByDeviceId(childId)
                childName = child?.name
            } catch (e: Exception) {
                Log.e(TAG, "Error getting child name", e)
            }

            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(this@MainActivity, RemoteCameraActivity::class.java).apply {
                        putExtra(RemoteCameraActivity.EXTRA_CHILD_ID, childId)
                        if (childName != null) {
                            putExtra(RemoteCameraActivity.EXTRA_CHILD_NAME, childName)
                        }
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening RemoteCameraActivity", e)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.main_toast_camera_open_error, e.message ?: "unknown"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_BATTERY_PROMPT_SUPPRESSED = "battery_prompt_suppressed"
        private const val DEVICE_STATUS_STALE_MS = 10 * 60 * 1000L
    }
}

