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
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import ru.example.parentwatch.BuildConfig
import ru.example.parentwatch.chat.ChatManagerAdapter
import ru.example.parentwatch.utils.NotificationManager
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.service.PhotoCaptureService
import ru.example.parentwatch.network.PhotoIntegration
import ru.example.parentwatch.utils.ServerUrlResolver
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        const val VPS_URL = "http://31.28.27.96:3000"
    }

    private lateinit var prefs: SharedPreferences
    private var isServiceRunning = false
    private val appVersion: String by lazy { BuildConfig.VERSION_NAME.replace("-debug", "") }
    
    
    // Photo integration for remote photo capture
    private var photoIntegration: ru.example.parentwatch.network.PhotoIntegration? = null

    // UI elements
    private lateinit var titleText: TextView
    private lateinit var chatCard: MaterialCardView
    private lateinit var chatBadge: TextView
    private lateinit var settingsCard: MaterialCardView
    // Removed extra cards/buttons from main screen for a minimal menu
    private lateinit var lastUpdateText: TextView
    private var chatManagerAdapter: ChatManagerAdapter? = null
    private var badgeRefreshJob: Job? = null

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
            Toast.makeText(this, "Фоновая геолокация отключена. Некоторые функции могут работать нестабильно.", Toast.LENGTH_LONG).show()
            startLocationService() // Still start, but with limited location updates
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)

        // РЎРѕР·РґР°РµРј РєР°РЅР°Р»С‹ СѓРІРµРґРѕРјР»РµРЅРёР№
        NotificationManager.createNotificationChannels(this)

        // РЎРёРЅС…СЂРѕРЅРёР·РёСЂСѓРµРј device_id СЃ child_device_id РґР»СЏ СЃРѕРІРјРµСЃС‚РёРјРѕСЃС‚Рё
        syncDeviceIds()
        val ensuredDeviceId = getUniqueDeviceId()
        chatManagerAdapter = ChatManagerAdapter(this, ensuredDeviceId)
        ensureChatBackgroundService()

        setupUI()
        loadSettings()
        updateUI()
        updateChatBadge()
        ensureRuntimePermissions()
        
        // PhotoIntegration is deprecated - RemotePhotoService now handles this via WebSocketManager
        // initializePhotoIntegration()
        
        // РџСЂРѕРІРµСЂСЏРµРј, РЅСѓР¶РЅРѕ Р»Рё РѕС‚РєСЂС‹С‚СЊ С‡Р°С‚
        if (intent.getBooleanExtra("open_chat", false)) {
            NotificationManager.resetUnreadCount()
            updateChatBadge()
            val chatIntent = Intent(this, ChatActivity::class.java)
            startActivity(chatIntent)
        }
    }

    private fun ensureRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            locationPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun setupUI() {
        // Find UI elements
    titleText = findViewById(R.id.titleText)
        chatCard = findViewById(R.id.chatCard)
        chatBadge = findViewById(R.id.chatBadge)
        settingsCard = findViewById(R.id.settingsCard)
        lastUpdateText = findViewById(R.id.lastUpdateText)

    // Set header title: name only (no version)
    titleText.text = "ChildDevice"
        
        // Menu card click listeners
        chatCard.setOnClickListener {
            NotificationManager.resetUnreadCount()
            try {
                chatManagerAdapter?.markAllAsRead()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to mark child chat as read", e)
            }
            updateChatBadge()
            val intent = Intent(this, ChatActivity::class.java)
            startActivity(intent)
        }
        
        // Parent location map card (always open, limited mode if not paired)
        findViewById<MaterialCardView>(R.id.parentLocationCard)?.setOnClickListener {
            val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            val myDeviceId = prefs.getString("device_id", "unknown") ?: "unknown"
            val parentId = resolvePairedParentId(prefs, myDeviceId)

            val myId = if (myDeviceId != "unknown") myDeviceId else ""
            val otherId = parentId

            if (otherId.isEmpty() || myId.isEmpty()) {
                Toast.makeText(
                    this,
                    "Открыт режим просмотра карты. Чтобы видеть второе устройство, свяжите устройства в настройках.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            val intent = DualLocationMapActivity.createIntent(
                context = this,
                myRole = DualLocationMapActivity.ROLE_CHILD,
                myId = myId,
                otherId = otherId
            )
            startActivity(intent)
        }
        
        settingsCard.setOnClickListener {
            promptSettingsAccess()
        }

        // Remote Camera card is not present on ChildDevice

        // Add subtle press animation to cards
        applyPressAnimation(chatCard)
        findViewById<MaterialCardView>(R.id.parentLocationCard)?.let { applyPressAnimation(it) }
    // Remote Camera card was removed
        applyPressAnimation(settingsCard)
        
        // About, Stats, and Service controls moved to Settings
    }

    // ==== Settings access with PIN ====
    private fun promptSettingsAccess() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val pinHash = prefs.getString("settings_pin_hash", null)

        if (pinHash.isNullOrEmpty()) {
            // First-time setup: ask to create PIN, then confirm
            promptCreatePin { success ->
                if (success) openSettings() else Toast.makeText(this, "PIN не установлен", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Ask to enter existing PIN
            promptEnterPin { ok ->
                if (ok) openSettings() else Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    // openRemoteCamera() removed: remote camera is a ParentMonitor feature

    private fun applyPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun promptCreatePin(onResult: (Boolean) -> Unit) {
        // Step 1: enter PIN
        val input1 = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Введите PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Создание PIN для настроек")
            .setView(input1)
            .setPositiveButton("Далее") { _, _ ->
                val pin1 = input1.text?.toString()?.trim().orEmpty()
                if (pin1.length < 4) {
                    Toast.makeText(this, "Минимум 4 цифры", Toast.LENGTH_SHORT).show()
                    onResult(false)
                    return@setPositiveButton
                }
                // Step 2: confirm PIN
                val input2 = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    hint = "Повторите PIN"
                }
                AlertDialog.Builder(this)
                    .setTitle("Подтверждение PIN")
                    .setView(input2)
                    .setPositiveButton("Сохранить") { _, _ ->
                        val pin2 = input2.text?.toString()?.trim().orEmpty()
                        if (pin1 == pin2) {
                            savePin(pin1)
                            Toast.makeText(this, "PIN сохранен", Toast.LENGTH_SHORT).show()
                            onResult(true)
                        } else {
                            Toast.makeText(this, "PIN не совпадает", Toast.LENGTH_SHORT).show()
                            onResult(false)
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun promptEnterPin(onResult: (Boolean) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Введите PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Введите PIN для доступа к настройкам")
            .setView(input)
            .setPositiveButton("ОК") { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                onResult(verifyPin(pin))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun savePin(pin: String) {
        val hash = sha256(pin)
        prefs.edit().putString("settings_pin_hash", hash).apply()
    }

    private fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString("settings_pin_hash", null) ?: return false
        return stored == sha256(pin)
    }

    private fun sha256(input: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input // fallback (shouldn't happen)
        }
    }

    private fun loadSettings() {
        isServiceRunning = prefs.getBoolean("service_running", false)

        val lastUpdate = prefs.getLong("last_update", 0)
        if (lastUpdate > 0) {
            val dateLine = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(lastUpdate))
            lastUpdateText.text = "$dateLine - ChildDevice v$appVersion\nРаботает в фоновом режиме"
        }
    }


    private fun ensureChatBackgroundService() {
        val serverUrl = ServerUrlResolver.getServerUrl(this)
        val deviceId = prefs.getString("device_id", null)
        if (!deviceId.isNullOrEmpty() && !serverUrl.isNullOrBlank()) {
            ChatBackgroundService.start(this, serverUrl, deviceId)
        } else if (serverUrl.isNullOrBlank()) {
            Log.w("MainActivity", "ChatBackgroundService not started: server URL missing")
        }
    }

    private fun ensurePhotoCaptureService() {
        val serverUrl = ServerUrlResolver.getServerUrl(this)
        val deviceId = prefs.getString("device_id", null)
        if (!deviceId.isNullOrEmpty() && !serverUrl.isNullOrBlank()) {
            PhotoCaptureService.start(this, serverUrl, deviceId)
        } else if (serverUrl.isNullOrBlank()) {
            Log.w("MainActivity", "PhotoCaptureService not started: server URL missing")
        }
    }


    override fun onResume() {
        super.onResume()
        prefs.edit().putBoolean("chat_open", false).apply()
        ensureChatBackgroundService()
        ensurePhotoCaptureService()
        updateChatBadge()
        startBadgeRefreshLoop()
    }

    override fun onPause() {
        badgeRefreshJob?.cancel()
        super.onPause()
    }

    private fun requestPermissionsAndStart() {
        // First request foreground location, audio, and camera permissions
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
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
                    .setTitle("Разрешение на фоновую геолокацию")
                    .setMessage("Для непрерывного отслеживания местоположения ChildDevice нужно разрешение на доступ к геолокации в фоне. В следующем окне выберите «Разрешить всегда».")
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
                val serverUrl = ServerUrlResolver.getServerUrl(this)
                if (serverUrl.isNullOrBlank()) {
                                        Toast.makeText(this, getString(R.string.server_url_not_configured), Toast.LENGTH_LONG).show()
                    Log.w("MainActivity", "LocationService not started: server URL missing")
                    return
                }
                val serviceIntent = Intent(this, LocationService::class.java)
                serviceIntent.action = LocationService.ACTION_START
                serviceIntent.putExtra("server_url", serverUrl)
                serviceIntent.putExtra("device_id", getUniqueDeviceId())
                ContextCompat.startForegroundService(this, serviceIntent)

                ensureChatBackgroundService()
                ensurePhotoCaptureService()
                
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
                PhotoCaptureService.stop(this)
                
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

        Toast.makeText(this, "Экстренная остановка выполнена", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in emergency stop", e)
            Toast.makeText(this, "Ошибка экстренной остановки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI() {
        // Status badge removed from UI; keep service state internally only.
        
        // Update last update text
        val lastUpdate = prefs.getLong("last_update", 0)
        val dateLine = if (lastUpdate > 0) {
            SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(lastUpdate))
        } else {
            SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date())
        }
    lastUpdateText.text = "$dateLine - ChildDevice v$appVersion\nРаботает в фоновом режиме"
    }

    private fun updateChatBadge() {
        val adapter = chatManagerAdapter
        if (adapter == null || !::chatBadge.isInitialized) return

        lifecycleScope.launch(Dispatchers.IO) {
            val unreadFromDb = try {
                adapter.getUnreadCount()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load unread chat count", e)
                0
            }
            val unread = maxOf(unreadFromDb, NotificationManager.getUnreadCount())

            withContext(Dispatchers.Main) {
                if (unread > 0) {
                    chatBadge.visibility = View.VISIBLE
                    chatBadge.text = if (unread > 99) "99+" else unread.toString()
                } else {
                    chatBadge.visibility = View.GONE
                }
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

    private fun syncDeviceIds() {
        val deviceId = prefs.getString("device_id", null)
        val childDeviceId = prefs.getString("child_device_id", null)
        
        if (deviceId != null && childDeviceId == null) {
            // Р•СЃР»Рё РµСЃС‚СЊ device_id, РЅРѕ РЅРµС‚ child_device_id - СЃРёРЅС…СЂРѕРЅРёР·РёСЂСѓРµРј
            prefs.edit().putString("child_device_id", deviceId).apply()
            Log.d("MainActivity", "РЎРёРЅС…СЂРѕРЅРёР·РёСЂРѕРІР°РЅ device_id СЃ child_device_id: $deviceId")
        } else if (deviceId == null && childDeviceId != null) {
            // Р•СЃР»Рё РµСЃС‚СЊ child_device_id, РЅРѕ РЅРµС‚ device_id - СЃРёРЅС…СЂРѕРЅРёР·РёСЂСѓРµРј
            prefs.edit().putString("device_id", childDeviceId).apply()
            Log.d("MainActivity", "РЎРёРЅС…СЂРѕРЅРёР·РёСЂРѕРІР°РЅ child_device_id СЃ device_id: $childDeviceId")
        } else if (!deviceId.isNullOrBlank() && !childDeviceId.isNullOrBlank() && deviceId != childDeviceId) {
            // Recover from broken pairing migration where child_device_id was overwritten by parent ID.
            prefs.edit().putString("child_device_id", deviceId).apply()
            Log.w("MainActivity", "Исправлен child_device_id: восстановлен из device_id ($deviceId)")
        }
    }
    private fun resolvePairedParentId(prefs: SharedPreferences, myDeviceId: String): String {
        val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val resolved = listOf(
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            prefs.getString("selected_device_id", null),
            prefs.getString("child_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("selected_device_id", null),
            legacyPrefs.getString("child_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && it != myDeviceId }
            .orEmpty()

        if (resolved.isNotEmpty() && prefs.getString("parent_device_id", null) != resolved) {
            prefs.edit().putString("parent_device_id", resolved).apply()
        }
        return resolved
    }
    private fun getUniqueDeviceId(): String {
        var deviceId = prefs.getString("device_id", null)

        // Keep existing ID stable to avoid breaking pairing/streaming after updates.
        if (deviceId.isNullOrBlank()) {
            deviceId = "child-" + UUID.randomUUID().toString().substring(0, 8)
        }

        prefs.edit()
            .putString("device_id", deviceId)
            .putString("child_device_id", deviceId)
            .putBoolean("device_id_permanent", true)
            .apply()

        return deviceId
    }
    
    override fun onDestroy() {
        super.onDestroy()
        badgeRefreshJob?.cancel()
        photoIntegration?.unregister()
        photoIntegration = null
    }
}

