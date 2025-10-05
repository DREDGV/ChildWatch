package ru.example.parentwatch

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.example.parentwatch.databinding.ActivityMainBinding
import ru.example.parentwatch.service.LocationService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity for ParentWatch
 * Simple UI to start/stop location tracking service
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isServiceRunning = false

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startLocationService()
        } else {
            Toast.makeText(
                this,
                "Необходимы разрешения для работы приложения",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)

        setupUI()
        loadSettings()
        updateUI()
    }

    private fun setupUI() {
        // Load saved server URL
        val savedUrl = prefs.getString("server_url", "http://10.0.2.2:3000")
        binding.serverUrlInput.setText(savedUrl)

        // Toggle service button
        binding.toggleServiceButton.setOnClickListener {
            saveSettings()
            if (isServiceRunning) {
                stopLocationService()
            } else {
                requestPermissionsAndStart()
            }
        }

        // Display device ID
        val deviceId = getUniqueDeviceId()
        binding.deviceIdText.text = deviceId
    }

    private fun loadSettings() {
        isServiceRunning = prefs.getBoolean("service_running", false)

        val lastUpdate = prefs.getLong("last_update", 0)
        if (lastUpdate > 0) {
            val format = SimpleDateFormat("HH:mm:ss, dd.MM.yyyy", Locale.getDefault())
            binding.lastUpdateText.text = "Последнее обновление: ${format.format(Date(lastUpdate))}"
        }
    }

    private fun saveSettings() {
        val serverUrl = binding.serverUrlInput.text.toString().trim()
        prefs.edit()
            .putString("server_url", serverUrl)
            .apply()
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsPermissions = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermissions) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startLocationService()
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        prefs.edit().putBoolean("service_running", true).apply()
        updateUI()

        Toast.makeText(this, "Сервис запущен", Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationService() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        startService(intent)

        isServiceRunning = false
        prefs.edit().putBoolean("service_running", false).apply()
        updateUI()

        Toast.makeText(this, "Сервис остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isServiceRunning) {
            binding.statusText.text = getString(R.string.status_running)
            binding.statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.toggleServiceButton.text = getString(R.string.stop_service)
            binding.toggleServiceButton.setIconResource(android.R.drawable.ic_media_pause)
        } else {
            binding.statusText.text = getString(R.string.status_stopped)
            binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
            binding.toggleServiceButton.text = getString(R.string.start_service)
            binding.toggleServiceButton.setIconResource(android.R.drawable.ic_media_play)
        }
    }

    private fun getUniqueDeviceId(): String {
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            // Generate unique device ID
            deviceId = "child-${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)}"
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateUI()
    }
}
