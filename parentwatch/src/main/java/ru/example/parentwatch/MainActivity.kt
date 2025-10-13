package ru.example.parentwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import ru.example.parentwatch.BuildConfig
import ru.example.parentwatch.utils.NotificationManager
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.ChatBackgroundService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity for ParentWatch (ChildDevice)
 * 
 * ParentWatch v5.2.0 - Child Location Tracking
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

        // Создаем каналы уведомлений
        NotificationManager.createNotificationChannels(this)

        // Синхронизируем device_id с child_device_id для совместимости
        syncDeviceIds()
        ensureChatBackgroundService()

        setupUI()
        loadSettings()
        updateUI()
        
        // Проверяем, нужно ли открыть чат
        if (intent.getBooleanExtra("open_chat", false)) {
            val chatIntent = Intent(this, ChatActivity::class.java)
            startActivity(chatIntent)
        }
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
        appVersionText.text = "ChildDevice v${BuildConfig.VERSION_NAME}"
        
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


    private fun ensureChatBackgroundService() {
        val serverUrl = prefs.getString("server_url", RAILWAY_URL) ?: RAILWAY_URL
        val deviceId = prefs.getString("device_id", null)
        if (!deviceId.isNullOrEmpty()) {
            ChatBackgroundService.start(this, serverUrl, deviceId)
        }
    }


    override fun onResume() {
        super.onResume()
        ensureChatBackgroundService()
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
        try {
            if (!isServiceRunning) {
                val serviceIntent = Intent(this, LocationService::class.java)
                serviceIntent.action = LocationService.ACTION_START
                serviceIntent.putExtra("server_url", prefs.getString("server_url", RAILWAY_URL))
                serviceIntent.putExtra("device_id", getUniqueDeviceId())
                ContextCompat.startForegroundService(this, serviceIntent)
                
                ensureChatBackgroundService()

            isServiceRunning = true
            prefs.edit().putBoolean("service_running", true).apply()
            updateUI()
                Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Мониторинг уже запущен", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting location service", e)
            Toast.makeText(this, "Ошибка запуска мониторинга: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLocationService() {
        try {
            if (isServiceRunning) {
                val serviceIntent = Intent(this, LocationService::class.java)
                serviceIntent.action = LocationService.ACTION_STOP
                stopService(serviceIntent)
                
                ChatBackgroundService.stop(this)

        isServiceRunning = false
        prefs.edit().putBoolean("service_running", false).apply()
        updateUI()
                Toast.makeText(this, "Мониторинг остановлен", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Мониторинг не запущен", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping location service", e)
            Toast.makeText(this, "Ошибка остановки мониторинга: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun emergencyStopAllFunctions() {
        try {
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
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in emergency stop", e)
            Toast.makeText(this, "Ошибка экстренной остановки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI() {
        if (isServiceRunning) {
            // Состояние "Работает" - красная кнопка
            statusText.text = getString(R.string.status_running)
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            
            toggleServiceButton.text = getString(R.string.stop_service)
            toggleServiceButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            toggleServiceButton.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            
        } else {
            // Состояние "Остановлен" - зеленая кнопка
            statusText.text = getString(R.string.status_stopped)
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            
            toggleServiceButton.text = getString(R.string.start_service)
            toggleServiceButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            toggleServiceButton.setTextColor(ContextCompat.getColor(this, android.R.color.white))
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

    private fun syncDeviceIds() {
        val deviceId = prefs.getString("device_id", null)
        val childDeviceId = prefs.getString("child_device_id", null)
        
        if (deviceId != null && childDeviceId == null) {
            // Если есть device_id, но нет child_device_id - синхронизируем
            prefs.edit().putString("child_device_id", deviceId).apply()
            Log.d("MainActivity", "Синхронизирован device_id с child_device_id: $deviceId")
        } else if (deviceId == null && childDeviceId != null) {
            // Если есть child_device_id, но нет device_id - синхронизируем
            prefs.edit().putString("device_id", childDeviceId).apply()
            Log.d("MainActivity", "Синхронизирован child_device_id с device_id: $childDeviceId")
        }
    }

    private fun getUniqueDeviceId(): String {
        var deviceId = prefs.getString("device_id", null)
        val isPermanent = prefs.getBoolean("device_id_permanent", false)

        if (deviceId != null && !isPermanent) {
            deviceId = "child-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit()
                .putString("device_id", deviceId)
                .putString("child_device_id", deviceId) // Для совместимости с ChildWatch
                .putBoolean("device_id_permanent", true)
                .apply()
        } else if (deviceId == null) {
            deviceId = "child-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit()
                .putString("device_id", deviceId)
                .putString("child_device_id", deviceId) // Для совместимости с ChildWatch
                .putBoolean("device_id_permanent", true)
                .apply()
        }
        return deviceId!!
    }
}
