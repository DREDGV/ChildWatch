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
import ru.example.childwatch.databinding.ActivityMainBinding
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.utils.PermissionHelper

/**
 * Main Activity with onboarding screen and monitoring controls
 * 
 * Features:
 * - Consent screen with clear explanation of monitoring
 * - Permission requests with rationale
 * - Start/Stop monitoring controls
 * - Settings for intervals and server URL
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        hasConsent = prefs.getBoolean("consent_given", false)
        
        setupUI()
        updateUIState()
    }
    
    private fun setupUI() {
        // Consent buttons
        binding.btnAgreeConsent.setOnClickListener {
            giveConsent()
        }
        
        binding.btnDeclineConsent.setOnClickListener {
            revokeConsent()
        }
        
        // Monitoring controls
        binding.btnStartMonitoring.setOnClickListener {
            startMonitoring()
        }
        
        binding.btnStopMonitoring.setOnClickListener {
            stopMonitoring()
        }
        
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startMonitoring()
            } else {
                stopMonitoring()
            }
        }
        
        // Load settings from preferences
        loadSettings()
    }
    
    private fun updateUIState() {
        val isMonitoring = MonitorService.isRunning
        
        if (hasConsent) {
            // Show main interface
            binding.consentCard.visibility = View.GONE
            binding.statusCard.visibility = View.VISIBLE
            binding.settingsCard.visibility = View.VISIBLE
            
            // Update monitoring status
            binding.tvStatus.text = if (isMonitoring) {
                getString(R.string.monitoring_active)
            } else {
                getString(R.string.monitoring_inactive)
            }
            
            binding.switchMonitoring.isChecked = isMonitoring
            binding.btnStartMonitoring.isEnabled = !isMonitoring
            binding.btnStopMonitoring.isEnabled = isMonitoring
        } else {
            // Show consent screen
            binding.consentCard.visibility = View.VISIBLE
            binding.statusCard.visibility = View.GONE
            binding.settingsCard.visibility = View.GONE
        }
    }
    
    private fun giveConsent() {
        hasConsent = true
        prefs.edit().putBoolean("consent_given", true).apply()
        
        // Request necessary permissions
        requestPermissions()
        
        updateUIState()
        
        Toast.makeText(this, getString(R.string.consent_given), Toast.LENGTH_SHORT).show()
    }
    
    private fun revokeConsent() {
        hasConsent = false
        prefs.edit().putBoolean("consent_given", false).apply()
        
        // Stop monitoring if it's running
        if (MonitorService.isRunning) {
            stopMonitoring()
        }
        
        updateUIState()
        
        Toast.makeText(this, getString(R.string.consent_revoked), Toast.LENGTH_SHORT).show()
    }
    
    private fun requestPermissions() {
        val permissionsToRequest = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            // Show rationale for sensitive permissions
            showPermissionRationale {
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        } else {
            // All permissions already granted
            Toast.makeText(this, getString(R.string.all_permissions_granted), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showPermissionRationale(onContinue: () -> Unit) {
        // TODO: Show a proper dialog explaining why permissions are needed
        // For now, just show toast and continue
        val message = getString(R.string.permission_location_rationale) + "\n" +
                     getString(R.string.permission_audio_rationale)
        
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        
        // Wait a bit and then request permissions
        binding.root.postDelayed({
            onContinue()
        }, 2000)
    }
    
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys
        
        if (deniedPermissions.isEmpty()) {
            Toast.makeText(this, getString(R.string.all_permissions_granted), Toast.LENGTH_SHORT).show()
        } else {
            val message = getString(R.string.permissions_denied, deniedPermissions.joinToString(", "))
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            
            // Note: For background location on Android 10+, user needs to manually grant in settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                deniedPermissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Toast.makeText(
                    this, 
                    getString(R.string.background_location_manual),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun startMonitoring() {
        if (!hasConsent) {
            Toast.makeText(this, getString(R.string.consent_required), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }
        
        // Save settings before starting
        saveSettings()
        
        // Start the monitoring service
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START_MONITORING
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        updateUIState()
        Toast.makeText(this, getString(R.string.monitoring_started), Toast.LENGTH_SHORT).show()
    }
    
    private fun stopMonitoring() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_MONITORING
        }
        startService(intent)
        
        updateUIState()
        Toast.makeText(this, getString(R.string.monitoring_stopped), Toast.LENGTH_SHORT).show()
    }
    
    private fun loadSettings() {
        binding.etLocationInterval.setText(prefs.getInt("location_interval", 30).toString())
        binding.etAudioDuration.setText(prefs.getInt("audio_duration", 20).toString())
        binding.etServerUrl.setText(prefs.getString("server_url", "https://your-server.com"))
    }
    
    private fun saveSettings() {
        val locationInterval = binding.etLocationInterval.text.toString().toIntOrNull() ?: 30
        val audioDuration = binding.etAudioDuration.text.toString().toIntOrNull() ?: 20
        val serverUrl = binding.etServerUrl.text.toString().ifEmpty { "https://your-server.com" }
        
        prefs.edit().apply {
            putInt("location_interval", locationInterval)
            putInt("audio_duration", audioDuration)
            putString("server_url", serverUrl)
            apply()
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateUIState()
    }
}
