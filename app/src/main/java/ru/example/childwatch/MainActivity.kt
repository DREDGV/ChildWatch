package ru.example.childwatch

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.example.childwatch.databinding.ActivityMainMenuBinding
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecurityChecker
import ru.example.childwatch.utils.SecureSettingsManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity with modern menu interface
 * 
 * ChildWatch v2.0.0 - Parental Monitoring Application
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
    private var hasConsent = false
    
    // Required permissions for the app
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        // Add background location permission for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // Permission launcher for requesting multiple permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResults(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        secureSettings = SecureSettingsManager(this)
        hasConsent = ConsentActivity.hasConsent(this) // Используем правильный метод

        // Set app version
        binding.appVersionText.text = "v${BuildConfig.VERSION_NAME}"

        setupUI()
        updateUIState()
        
        // Perform security checks
        performSecurityChecks()
    }
    
    private fun setupUI() {
        // Quick action buttons
        binding.startMonitoringBtn.setOnClickListener {
            startMonitoring()
        }
        
        binding.stopMonitoringBtn.setOnClickListener {
            stopMonitoring()
        }
        
        // Menu card click listeners
        binding.homeCard.setOnClickListener {
            showToast("Главная - уже здесь!")
        }
        
        binding.locationCard.setOnClickListener {
            // Use Google Maps
            val intent = Intent(this, LocationMapActivity::class.java)
            startActivity(intent)
        }

        binding.audioStreamingCard.setOnClickListener {
            val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"
            val childDeviceId = prefs.getString("child_device_id", "")

            if (childDeviceId.isNullOrEmpty()) {
                showToast("Сначала укажите Device ID ребёнка в Настройках")
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
        
        binding.settingsCard.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        
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
                binding.serviceRunningTimeText.text = getString(R.string.service_running_time, "неизвестно")
            }
            
            // Update feature status
            binding.locationStatusText.text = getString(R.string.status_location_active)
            binding.audioStatusText.text = getString(R.string.status_audio_active)
            binding.photoStatusText.text = getString(R.string.status_photo_active)
            
        } else {
            binding.statusText.text = getString(R.string.monitoring_inactive_status)
            binding.statusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
            
            binding.serviceRunningTimeText.text = getString(R.string.service_running_time, "не работает")
            
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
            binding.lastUpdateText.text = getString(R.string.last_location_update, "никогда")
        }
        
        // Audio update
        val lastAudioUpdate = secureSettings.getLastAudioUpdate()
        if (lastAudioUpdate > 0) {
            val timeString = dateFormat.format(Date(lastAudioUpdate))
            binding.lastAudioUpdateText.text = getString(R.string.last_audio_update, timeString)
        } else {
            binding.lastAudioUpdateText.text = getString(R.string.last_audio_update, "никогда")
        }
        
        // Photo update
        val lastPhotoUpdate = secureSettings.getLastPhotoUpdate()
        if (lastPhotoUpdate > 0) {
            val timeString = dateFormat.format(Date(lastPhotoUpdate))
            binding.lastPhotoUpdateText.text = getString(R.string.last_photo_update, timeString)
        } else {
            binding.lastPhotoUpdateText.text = getString(R.string.last_photo_update, "никогда")
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
    
    private fun performSecurityChecks() {
        try {
            val securityReport = SecurityChecker.getSecurityReport(this)
            val securityWarnings = SecurityChecker.getSecurityWarnings(this)
            
            // Log security events
            if (securityReport.isDebugBuild) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.DEBUG_BUILD_DETECTED,
                //     "Приложение собрано в debug режиме",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            if (securityReport.isDeveloperOptionsEnabled) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.DEVELOPER_OPTIONS_ENABLED,
                //     "Включены опции разработчика",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            if (securityReport.isUsbDebuggingEnabled) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.USB_DEBUGGING_ENABLED,
                //     "Включена отладка по USB",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            if (securityReport.isDeviceRooted) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.ROOT_DETECTED,
                //     "Устройство имеет root права",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            if (securityReport.isEmulator) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.EMULATOR_DETECTED,
                //     "Приложение запущено в эмуляторе",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            if (securityReport.isDebuggerAttached) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.DEBUGGER_ATTACHED,
                //     "Подключен отладчик",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            if (securityReport.isAppDebuggable) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.APP_DEBUGGABLE,
                //     "Приложение доступно для отладки",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            if (securityReport.isMockLocationEnabled) {
                // SecurityLogger.logSecurityEvent(this, SecurityEvent(
                //     SecurityEventType.MOCK_LOCATION_ENABLED,
                //     "Включены mock-локации",
                //     true,
                //     System.currentTimeMillis()
                // ))
            }
            
            // Show security warnings if any
            if (securityWarnings.isNotEmpty()) {
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
        
        showToast(getString(R.string.monitoring_started))
        updateUIState()
    }
    
    private fun stopMonitoring() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_MONITORING
        }
        startService(intent)
        
        showToast(getString(R.string.monitoring_stopped))
        updateUIState()
    }
    
    private fun requestPermissions() {
        val permissionsToRequest = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys
        
        if (deniedPermissions.isEmpty()) {
            showToast(getString(R.string.all_permissions_granted))
            // Try to start monitoring if consent is given
            if (hasConsent) {
                startMonitoring()
            }
        } else {
            val deniedList = deniedPermissions.joinToString(", ")
            showToast(getString(R.string.permissions_denied, deniedList))
            
            // Check if background location permission was denied
            if (deniedPermissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                showToast(getString(R.string.background_location_manual))
            }
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onResume() {
        super.onResume()
        updateUIState()
    }
}