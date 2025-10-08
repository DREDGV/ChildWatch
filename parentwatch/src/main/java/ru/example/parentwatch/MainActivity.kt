package ru.example.parentwatch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import ru.example.parentwatch.databinding.ActivityMainBinding
import ru.example.parentwatch.service.LocationService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity for ParentWatch
 * 
 * ParentWatch v2.0.0 - Child Location Tracking
 * Simple UI to start/stop location tracking service
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val LOCALHOST_URL = "http://10.0.2.2:3000"
        private const val RAILWAY_URL = "https://childwatch-production.up.railway.app"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isServiceRunning = false

    // Permission launchers
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // After foreground location is granted, request background location separately (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationPermission()
            } else {
                startLocationService()
            }
        } else {
            Toast.makeText(
                this,
                "Необходимы разрешения для работы приложения",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Start service regardless - it will work with foreground-only permission if background was denied
        // User can always grant "Allow all the time" later from Settings
        startLocationService()

        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Just inform the user, don't block them
            Toast.makeText(
                this,
                "Совет: для работы в фоне выберите 'Разрешить всегда' в настройках приложения",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)

        // Set app version
        binding.appVersionText.text = "v${BuildConfig.VERSION_NAME}"

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
        binding.deviceIdText.setText(deviceId)
        
        // Copy ID button
        binding.copyIdButton.setOnClickListener {
            copyDeviceIdToClipboard(deviceId)
        }
        
        // Click on ID field to copy
        binding.deviceIdText.setOnClickListener {
            copyDeviceIdToClipboard(deviceId)
        }
        
        // Show QR code button
        binding.showQrButton.setOnClickListener {
            showQrCodeDialog(deviceId)
        }

        // Server URL preset buttons
        binding.useLocalhostBtn.setOnClickListener {
            binding.serverUrlInput.setText(LOCALHOST_URL)
            Toast.makeText(this, "Localhost URL установлен", Toast.LENGTH_SHORT).show()
        }

        binding.useRailwayBtn.setOnClickListener {
            binding.serverUrlInput.setText(RAILWAY_URL)
            Toast.makeText(this, "Railway URL установлен", Toast.LENGTH_SHORT).show()
        }

        // Emergency stop button
        binding.emergencyStopButton.setOnClickListener {
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
            val hasBackgroundPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            // For debugging: start service anyway, permission dialog will show if needed
            if (!hasBackgroundPermission) {
                Toast.makeText(this, "Будет запрошено фоновое разрешение", Toast.LENGTH_SHORT).show()
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            // Start service regardless of background permission
            startLocationService()
        } else {
            startLocationService()
        }
    }

    private fun startLocationService() {
        Toast.makeText(this, "Запуск сервиса...", Toast.LENGTH_LONG).show()
        android.util.Log.d("ParentWatch", "Starting LocationService...")

        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            android.util.Log.d("ParentWatch", "Service started successfully")

            isServiceRunning = true
            prefs.edit().putBoolean("service_running", true).apply()
            updateUI()

            Toast.makeText(this, "Сервис запущен", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("ParentWatch", "Failed to start service", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    private fun emergencyStopAllFunctions() {
        Log.w("ParentWatch", "🚨 EMERGENCY STOP triggered from UI")

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
        Log.w("ParentWatch", "🚨 EMERGENCY STOP completed")
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
        var needsServiceRestart = false
        val isPermanent = prefs.getBoolean("device_id_permanent", false)

        // Check if old format and regenerate with new UUID format
        // New format: child-XXXXXXXX (exactly 14 chars, UUID-based, marked as permanent)
        // Old formats:
        //   - child-XXXX (10 chars, Android ID last 4)
        //   - child-c6d2c18b3632b5ac (21 chars, full hex Android ID)
        //   - device_XXXXXXXXXXXXXXXX (old prefix)
        if (deviceId != null && !isPermanent) {
            Log.d("MainActivity", "⚠️ Old Device ID detected (not marked permanent): $deviceId - regenerating with UUID...")

            // Stop service if running to prevent using old ID
            if (isServiceRunning) {
                stopLocationService()
                needsServiceRestart = true
            }

            deviceId = null  // Force regeneration
        }

        if (deviceId == null) {
            // Generate permanent unique device ID using UUID
            // Format: child-XXXXXXXX (child- + 8 hex chars = 14 chars total)
            val uuid = java.util.UUID.randomUUID().toString().replace("-", "").takeLast(8).uppercase()
            deviceId = "child-$uuid"

            // Save permanently with flag
            prefs.edit()
                .putString("device_id", deviceId)
                .putBoolean("device_id_permanent", true)
                .putLong("device_id_created", System.currentTimeMillis())
                .apply()

            Log.d("MainActivity", "✅ Generated new permanent Device ID: $deviceId (${deviceId.length} chars)")

            // Show toast to inform user
            Toast.makeText(this, "Новый ID устройства: $deviceId", Toast.LENGTH_LONG).show()

            // Restart service if it was running
            if (needsServiceRestart) {
                Handler(Looper.getMainLooper()).postDelayed({
                    requestPermissionsAndStart()
                }, 1000)
            }
        } else {
            Log.d("MainActivity", "Using existing permanent Device ID: $deviceId (${deviceId.length} chars)")
        }
        return deviceId
    }
    
    private fun copyDeviceIdToClipboard(deviceId: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Device ID", deviceId)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "ID устройства скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
    }
    
    private fun showQrCodeDialog(deviceId: String) {
        try {
            // Generate QR code bitmap
            val qrBitmap = generateQRCode(deviceId, 400, 400)
            
            // Create custom layout for dialog
            val layout = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
            val imageView = ImageView(this).apply {
                setImageBitmap(qrBitmap)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            
            AlertDialog.Builder(this)
                .setTitle("QR-код устройства")
                .setMessage("ID: $deviceId\n\nОтсканируйте QR-код в приложении ChildWatch")
                .setView(imageView)
                .setPositiveButton("ОК") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton("Копировать") { _, _ ->
                    copyDeviceIdToClipboard(deviceId)
                }
                .show()
        } catch (e: Exception) {
            // Fallback to text dialog if QR generation fails
            val message = "ID устройства: $deviceId\n\nВведите этот ID в приложении ChildWatch вручную."
            
            AlertDialog.Builder(this)
                .setTitle("ID устройства")
                .setMessage(message)
                .setPositiveButton("ОК") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton("Копировать") { _, _ ->
                    copyDeviceIdToClipboard(deviceId)
                }
                .show()
        }
    }
    
    private fun generateQRCode(text: String, width: Int, height: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bitmap
        } catch (e: WriterException) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateUI()
    }
}
