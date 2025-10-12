package ru.example.parentwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import ru.example.parentwatch.service.LocationService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity for ParentWatch (ChildDevice)
 *
 * ParentWatch v5.0.0 - Child Location Tracking
 * New UI with menu cards for navigation.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val LOCALHOST_URL = "http://10.0.2.2:3000"
        const val RAILWAY_URL = "https://childwatch-production.up.railway.app"
    }

    private lateinit var prefs: SharedPreferences
    private var isServiceRunning = false

    // UI elements
    private lateinit var appVersionText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: android.view.View
    private lateinit var chatCard: MaterialCardView
    private lateinit var settingsCard: MaterialCardView
    private lateinit var aboutCard: MaterialCardView
    private lateinit var statsCard: MaterialCardView
    private lateinit var toggleServiceButton: MaterialButton
    private lateinit var emergencyStopButton: MaterialButton
    private lateinit var lastUpdateText: TextView

    // Permission launchers
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (fineLocationGranted && coarseLocationGranted && recordAudioGranted && postNotificationsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationPermission()
            } else {
                startLocationService()
            }
        } else {
            Toast.makeText(this, "Необходимы разрешения для работы приложения", Toast.LENGTH_LONG).show()
            updateUI()
        }
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startLocationService()
        } else {
            Toast.makeText(this, "Фоновое отслеживание местоположения отключено. Некоторые функции могут работать некорректно.", Toast.LENGTH_LONG).show()
            startLocationService() // Still start, but with limited location updates
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)

        setupUI()
        loadSettings()
        updateUI()
    }

    private fun setupUI() {
        // Find UI elements
        appVersionText = findViewById(R.id.appVersionText)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        chatCard = findViewById(R.id.chatCard)
        settingsCard = findViewById(R.id.settingsCard)
        aboutCard = findViewById(R.id.aboutCard)
        statsCard = findViewById(R.id.statsCard)
        toggleServiceButton = findViewById(R.id.toggleServiceButton)
        emergencyStopButton = findViewById(R.id.emergencyStopButton)
        lastUpdateText = findViewById(R.id.lastUpdateText)

        // Set app version
        appVersionText.text = "ChildDevice v5.1.0"
        
        // Menu card click listeners
        chatCard.setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java)
            startActivity(intent)
        }
        
        settingsCard.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        aboutCard.setOnClickListener {
            val intent = Intent(this, AboutActivity::class.java)
            startActivity(intent)
        }

        statsCard.setOnClickListener {
            val intent = Intent(this, StatsActivity::class.java)
            startActivity(intent)
        }

        // Toggle service button
        toggleServiceButton.setOnClickListener {
            if (isServiceRunning) {
                stopLocationService()
            } else {
                requestPermissionsAndStart()
            }
        }

        // Emergency stop button
        emergencyStopButton.setOnClickListener {
            // Show confirmation dialog
            AlertDialog.Builder(this)
                .setTitle("🚨 Экстренная остановка")
                .setMessage("Это немедленно остановит ВСЕ функции:\n• Прослушку\n• Отслеживание геолокации\n• Все фоновые процессы\n\nВы уверены?")
                .setPositiveButton("Да, остановить всё") { _, _ ->
                    emergencyStopAllFunctions()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun loadSettings() {
        isServiceRunning = prefs.getBoolean("service_running", false)

        val lastUpdate = prefs.getLong("last_update", 0)
        if (lastUpdate > 0) {
            val format = SimpleDateFormat("HH:mm:ss, dd.MM.yyyy", Locale.getDefault())
            lastUpdateText.text = "Последнее обновление: ${format.format(Date(lastUpdate))}"
        }
    }

    private fun requestPermissionsAndStart() {
        // First request foreground location and audio permissions
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsForegroundPermissions = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsForegroundPermissions) {
            locationPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            // Foreground permissions already granted, check background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationPermission()
            } else {
                startLocationService()
            }
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this)
                    .setTitle("Разрешение на фоновое местоположение")
                    .setMessage("Для непрерывного отслеживания местоположения ChildDevice требуется разрешение на доступ к местоположению в фоновом режиме. Пожалуйста, выберите 'Разрешить всегда' в следующем окне.")
                    .setPositiveButton("Продолжить") { _, _ ->
                        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("Отмена") { _, _ ->
                        startLocationService() // Start service even if denied, but with limited background location
                    }
                    .show()
            } else {
                startLocationService()
            }
        } else {
            startLocationService()
        }
    }

    private fun startLocationService() {
        if (!isServiceRunning) {
            val serviceIntent = Intent(this, LocationService::class.java)
            serviceIntent.putExtra("server_url", prefs.getString("server_url", RAILWAY_URL))
            serviceIntent.putExtra("device_id", getUniqueDeviceId())
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceRunning = true
            prefs.edit().putBoolean("service_running", true).apply()
            updateUI()
            Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Мониторинг уже запущен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLocationService() {
        if (isServiceRunning) {
            val serviceIntent = Intent(this, LocationService::class.java)
            stopService(serviceIntent)
            isServiceRunning = false
            prefs.edit().putBoolean("service_running", false).apply()
            updateUI()
            Toast.makeText(this, "Мониторинг остановлен", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Мониторинг не запущен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun emergencyStopAllFunctions() {
        // Send EMERGENCY_STOP action to service
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_EMERGENCY_STOP
        }
        startService(intent)

        // Update local state
        isServiceRunning = false
        prefs.edit().putBoolean("service_running", false).apply()
        updateUI()

        Toast.makeText(this, "🚨 Экстренная остановка выполнена", Toast.LENGTH_LONG).show()
    }

    private fun updateUI() {
        if (isServiceRunning) {
            statusText.text = getString(R.string.status_running)
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            toggleServiceButton.text = getString(R.string.stop_service)
        } else {
            statusText.text = getString(R.string.status_stopped)
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            toggleServiceButton.text = getString(R.string.start_service)
        }
        
        // Update last update text
        val lastUpdate = prefs.getLong("last_update", 0)
        if (lastUpdate > 0) {
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            lastUpdateText.text = "Последнее обновление: ${format.format(Date(lastUpdate))}"
        } else {
            lastUpdateText.text = "Последнее обновление: нет данных"
        }
    }

    private fun getUniqueDeviceId(): String {
        var deviceId = prefs.getString("device_id", null)
        val isPermanent = prefs.getBoolean("device_id_permanent", false)

        if (deviceId != null && !isPermanent) {
            deviceId = "child-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit()
                .putString("device_id", deviceId)
                .putBoolean("device_id_permanent", true)
                .apply()
        } else if (deviceId == null) {
            deviceId = "child-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit()
                .putString("device_id", deviceId)
                .putBoolean("device_id_permanent", true)
                .apply()
        }
        return deviceId!!
    }
}