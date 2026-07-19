package ru.example.parentwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.net.Uri
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
import ru.example.parentwatch.attention.ChildAttentionSignalLauncher
import ru.example.parentwatch.chat.ChatManagerAdapter
import ru.example.parentwatch.contacts.ContactIcons
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.utils.NotificationManager
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.service.PhotoCaptureService
import ru.example.parentwatch.network.PhotoIntegration
import ru.example.parentwatch.utils.ChildDeviceProfile
import ru.example.parentwatch.utils.ChildDeviceProfileManager
import ru.example.parentwatch.utils.ServerUrlResolver
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.session.ChildEffectiveContextProvider
import ru.example.parentwatch.session.ChildParticipantNameResolver
import ru.example.parentwatch.session.ChildProfileRuntimeCoordinator
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
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
        private const val KEY_LINKED_PARENT_COUNT = "linked_parent_count"
        private const val KEY_LINKED_PARENT_LABELS = "linked_parent_labels"
        private const val KEY_LINKED_PARENTS_JSON = "linked_parents_json"
    }

    private lateinit var prefs: SharedPreferences
    private var isServiceRunning = false
    private val appVersion: String by lazy { BuildConfig.VERSION_NAME.replace("-debug", "") }
    
    
    // Photo integration for remote photo capture
    private var photoIntegration: ru.example.parentwatch.network.PhotoIntegration? = null

    // UI elements
    private lateinit var titleText: TextView
    private lateinit var activeProfileName: TextView
    private lateinit var activeProfileMeta: TextView
    private lateinit var chatCard: MaterialCardView
    private lateinit var chatBadge: TextView
    private lateinit var settingsCard: MaterialCardView
    // Removed extra cards/buttons from main screen for a minimal menu
    private lateinit var lastUpdateText: TextView
    private lateinit var profileManager: ChildDeviceProfileManager
    private val sessionStore by lazy { ChildActiveSessionStore(this) }
    private val contextProvider by lazy { ChildEffectiveContextProvider.get(this) }
    private val participantNameResolver by lazy { ChildParticipantNameResolver(this) }
    private val profileRuntimeCoordinator by lazy { ChildProfileRuntimeCoordinator(this) }
    private var chatManagerAdapter: ChatManagerAdapter? = null
    private val networkClient by lazy { NetworkClient(this) }
    private var badgeRefreshJob: Job? = null

    // Permission launchers
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED)
        if (fineLocationGranted && coarseLocationGranted && recordAudioGranted) {
            if (!cameraGranted) {
                Toast.makeText(
                    this,
                    "Мониторинг запустится, но удалённое фото недоступно без разрешения камеры",
                    Toast.LENGTH_LONG
                ).show()
            }
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
        startLocationService(silent = true)
        } else {
            Toast.makeText(this, "Фоновая геолокация отключена. Некоторые функции могут работать нестабильно.", Toast.LENGTH_LONG).show()
            startLocationService() // Still start, but with limited location updates
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        profileManager = ChildDeviceProfileManager(this)

        // Create notification channels
        NotificationManager.createNotificationChannels(this)

        // Keep legacy device identifiers in sync
        syncDeviceIds()
        val ensuredDeviceId = getUniqueDeviceId()
        chatManagerAdapter = ChatManagerAdapter(this, ensuredDeviceId)
        ensureChatBackgroundService()

        setupUI()
        loadSettings()
        updateQuickProfileSummary()
        updateUI()
        updateChatBadge()
        ensureRuntimePermissions()
        
        // PhotoIntegration is deprecated - RemotePhotoService now handles this via WebSocketManager
        // initializePhotoIntegration()
        
        // Open chat directly when launched from a notification
        if (intent.getBooleanExtra("open_chat", false)) {
            NotificationManager.resetUnreadCount()
            updateChatBadge()
            val chatIntent = Intent(this, ChatConversationsActivity::class.java)
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
        activeProfileName = findViewById(R.id.activeProfileName)
        activeProfileMeta = findViewById(R.id.activeProfileMeta)
        chatCard = findViewById(R.id.chatCard)
        chatBadge = findViewById(R.id.chatBadge)
        settingsCard = findViewById(R.id.settingsCard)
        lastUpdateText = findViewById(R.id.lastUpdateText)

    // Set header title: name only (no version)
    titleText.text = getString(R.string.home_child_brand)

        findViewById<MaterialButton>(R.id.switchProfileQuickButton)?.setOnClickListener {
            showQuickProfilePicker()
        }
        
        // Menu card click listeners
        chatCard.setOnClickListener {
            NotificationManager.resetUnreadCount()
            try {
                chatManagerAdapter?.markAllAsRead()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to mark child chat as read", e)
            }
            updateChatBadge()
            val intent = Intent(this, ChatConversationsActivity::class.java)
            startActivity(intent)
        }

        findViewById<MaterialCardView>(R.id.attentionSignalCard)?.setOnClickListener {
            ChildAttentionSignalLauncher.show(this)
        }
        
        // Parent location map card (always open, limited mode if not paired)
        findViewById<MaterialCardView>(R.id.parentLocationCard)?.setOnClickListener {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val myDeviceId = contextProvider.current()?.selfDeviceId.orEmpty().ifBlank {
            sessionStore.resolveCurrentChildId()
        }.ifBlank {
            prefs.getString("device_id", "unknown") ?: "unknown"
        }
        val parentId = contextProvider.current()?.targetDeviceId.orEmpty()
            .ifBlank { resolvePairedParentId(prefs, myDeviceId) }

            val myId = if (myDeviceId != "unknown") myDeviceId else ""
            val otherId = parentId

            if (otherId.isEmpty() || myId.isEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.map_limited_mode_subtitle),
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
        findViewById<MaterialCardView>(R.id.attentionSignalCard)?.let { applyPressAnimation(it) }
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

    private fun showQuickProfilePicker() {
        val actionLabels = arrayOf(
            getString(R.string.profile_switch_apply),
            getString(R.string.profile_switch_save_current),
            getString(R.string.profile_switch_manage)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_switch_manage_title)
            .setItems(actionLabels) { _, which ->
                when (which) {
                    0 -> showQuickProfileSwitchDialog()
                    1 -> showProfileEditorDialog(profileManager.getActiveProfile())
                    2 -> showProfileManagementDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQuickProfileSwitchDialog() {
        val profiles = profileManager.getSavedProfiles()
        if (profiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.profile_switch_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val activeId = profileManager.getActiveProfile()?.id ?: profileManager.getActiveProfileId()
        val selectedIndex = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        val items = profiles.map(::formatProfilePickerItem).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_switch_select_title)
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                applyQuickProfile(profiles[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileManagementDialog() {
        val profiles = profileManager.getSavedProfiles()
        if (profiles.isEmpty()) {
            showProfileEditorDialog(null)
            return
        }

        val items = profiles.map(::formatProfilePickerItem).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_switch_manage_title)
            .setItems(items) { _, which ->
                showProfileActionsDialog(profiles[which])
            }
            .setPositiveButton(R.string.profile_switch_save_current) { _, _ ->
                showProfileEditorDialog(profileManager.getActiveProfile())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileActionsDialog(profile: ChildDeviceProfile) {
        val activeProfileId = profileManager.getActiveProfile()?.id ?: profileManager.getActiveProfileId()
        val labels = mutableListOf(
            getString(R.string.profile_switch_apply),
            getString(R.string.profile_switch_edit)
        )
        val allowDelete = profile.id != activeProfileId
        if (allowDelete) {
            labels += getString(R.string.profile_switch_delete)
        }

        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> applyQuickProfile(profile)
                    which == 1 -> showProfileEditorDialog(profile)
                    allowDelete && which == 2 -> confirmDeleteProfile(profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteProfile(profile: ChildDeviceProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_switch_delete_title)
            .setMessage(getString(R.string.profile_switch_delete_message, profile.name))
            .setPositiveButton(R.string.profile_switch_delete) { _, _ ->
                profileManager.deleteProfile(profile.id)
                updateQuickProfileSummary()
                Toast.makeText(this, R.string.profile_switch_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileEditorDialog(existingProfile: ChildDeviceProfile?) {
        val effectiveContext = sessionStore.resolveEffectiveContext()
        val currentOwnId = existingProfile?.ownChildDeviceId?.ifBlank { null }
            ?: effectiveContext?.ownChildDeviceId?.takeIf { it.isNotBlank() }
            ?: sessionStore.resolveCurrentChildId()
        val currentParentId = existingProfile?.linkedParentDeviceId?.ifBlank { null }
            ?: effectiveContext?.linkedParentDeviceId?.takeIf { it.isNotBlank() }
            ?: sessionStore.resolveCurrentParentId()
        val currentServerUrl = existingProfile?.serverUrl?.ifBlank { null }
            ?: sessionStore.resolveCurrentServerUrl().ifBlank { ServerUrlResolver.getServerUrl(this) ?: "" }
        val suggestedName = existingProfile?.name
            ?.takeUnless { it == getString(R.string.profile_switch_current_name) }
            ?: getString(
                R.string.profile_switch_default_name_format,
                formatProfileId(currentOwnId),
                formatProfileId(currentParentId.ifBlank { getString(R.string.profile_switch_no_link_short) })
            )

        val nameInput = createProfileInput(getString(R.string.profile_switch_name_hint), suggestedName)
        val serverInput = createProfileInput(getString(R.string.profile_switch_server_hint), currentServerUrl)
        val ownIdInput = createProfileInput(getString(R.string.profile_switch_own_child_id_hint), currentOwnId)
        val parentIdInput = createProfileInput(getString(R.string.profile_switch_linked_parent_id_hint), currentParentId)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingProfile == null) R.string.profile_switch_name_title else R.string.profile_switch_edit_title)
            .setView(createProfileDialogLayout(nameInput, serverInput, ownIdInput, parentIdInput))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val serverUrl = serverInput.text?.toString()?.trim().orEmpty()
                val ownId = ownIdInput.text?.toString()?.trim().orEmpty()
                val parentId = parentIdInput.text?.toString()?.trim().orEmpty()

                when {
                    name.isBlank() -> Toast.makeText(this, R.string.profile_switch_validation_name, Toast.LENGTH_SHORT).show()
                    serverUrl.isBlank() || (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) ->
                        Toast.makeText(this, R.string.profile_switch_validation_server, Toast.LENGTH_SHORT).show()
                    ownId.isBlank() -> Toast.makeText(this, R.string.profile_switch_validation_own_id, Toast.LENGTH_SHORT).show()
                    else -> {
                        val profile = existingProfile?.copy(
                            name = name,
                            serverUrl = serverUrl,
                            ownChildDeviceId = ownId,
                            linkedParentDeviceId = parentId,
                            updatedAt = System.currentTimeMillis()
                        ) ?: profileManager.buildProfile(name, serverUrl, ownId, parentId)
                        profileManager.saveProfile(profile)
                        if (existingProfile?.id == profileManager.getActiveProfileId()) {
                            applyQuickProfile(profile)
                        } else {
                            updateQuickProfileSummary()
                        }
                        Toast.makeText(
                            this,
                            if (existingProfile == null) R.string.profile_switch_saved else R.string.profile_switch_updated,
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun formatProfilePickerItem(profile: ChildDeviceProfile): String {
        val linkedParent = profile.linkedParentDeviceId.ifBlank {
            getString(R.string.profile_switch_unknown_link)
        }
        val linkedParentLabel = loadCachedLinkedParentLabels()[linkedParent]
        return buildString {
            append(profile.name)
            append('\n')
            append(formatProfileServer(profile.serverUrl))
            append(" | ")
            append(formatProfileId(profile.ownChildDeviceId))
            append(" -> ")
            append(linkedParentLabel ?: formatProfileId(linkedParent))
        }
    }

    private fun applyQuickProfile(profile: ChildDeviceProfile) {
        val wasRunning = prefs.getBoolean("service_running", false) || isServiceRunning
        profileRuntimeCoordinator.applyProfile(profile, wasRunning)
        syncDeviceIds()

        updateQuickProfileSummary()
        updateUI()
        Toast.makeText(this, getString(R.string.profile_switch_applied), Toast.LENGTH_SHORT).show()
    }

    private fun updateQuickProfileSummary() {
        val activeProfile = profileManager.getActiveProfile()
        val effectiveContext = sessionStore.resolveEffectiveContext()
        val ownChildId = activeProfile?.ownChildDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.ownChildDeviceId?.takeIf { it.isNotBlank() }
        val parentId = activeProfile?.linkedParentDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.linkedParentDeviceId?.takeIf { it.isNotBlank() }
        val serverUrl = activeProfile?.serverUrl?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.serverUrl?.takeIf { it.isNotBlank() }

        if (ownChildId.isNullOrBlank() || serverUrl.isNullOrBlank()) {
            activeProfileName.text = getString(R.string.profile_switch_title)
            activeProfileMeta.text = getString(R.string.profile_switch_no_active)
            return
        }

        activeProfileName.text = participantNameResolver.resolveChildDisplayName()
        activeProfileMeta.text = if (parentId.isNullOrBlank()) {
            getString(R.string.home_child_profile_not_connected)
        } else {
            getString(R.string.home_child_profile_connected)
        }
    }

    private fun formatProfileServer(serverUrl: String): String {
        val parsedHost = runCatching { Uri.parse(serverUrl).host }.getOrNull()
        return (parsedHost ?: serverUrl).removePrefix("www.")
    }

    private fun formatProfileId(rawId: String): String {
        return if (rawId.length <= 16) rawId else "${rawId.take(8)}...${rawId.takeLast(4)}"
    }

    private fun buildCachedLinkedParentsLine(): String? {
        val count = prefs.getInt(KEY_LINKED_PARENT_COUNT, 0)
        if (count <= 0) return null

        val labels = prefs.getString(KEY_LINKED_PARENT_LABELS, null).orEmpty()
        return if (labels.isNotBlank()) {
            getString(R.string.child_parent_link_status_connected_named, count, labels)
        } else {
            getString(R.string.child_parent_link_status_connected_count, count)
        }
    }

    private fun buildCachedActiveParentLine(activeParentId: String?): String? {
        if (activeParentId.isNullOrBlank()) return null

        val label = loadCachedLinkedParentLabels()[activeParentId]
            ?.takeIf { it.isNotBlank() }
            ?: formatProfileId(activeParentId)
        return getString(R.string.child_parent_link_status_active_parent, label)
    }

    private fun loadCachedLinkedParentLabels(): Map<String, String> {
        val raw = prefs.getString(KEY_LINKED_PARENTS_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyMap()

        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val parentId = item.optString("parentDeviceId").trim()
                    if (parentId.isBlank()) continue
                    val displayName = item.optString("displayName").trim()
                    val deviceName = item.optString("parentDeviceName").trim()
                    val label = when {
                        displayName.isNotBlank() -> displayName
                        deviceName.isNotBlank() -> deviceName
                        else -> formatProfileId(parentId)
                    }
                    put(parentId, label)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun createProfileInput(hint: String, value: String): EditText {
        return EditText(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
            this.hint = hint
            setText(value)
            setSingleLine()
        }
    }

    private fun createProfileDialogLayout(vararg inputs: EditText): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                0,
                (20 * resources.displayMetrics.density).toInt(),
                0
            )
            inputs.forEach(::addView)
        }
    }

    private fun describeProfileContextSource(source: ru.example.parentwatch.session.ChildEffectiveContext.Source?): String {
        return when (source) {
            ru.example.parentwatch.session.ChildEffectiveContext.Source.ACTIVE_SESSION ->
                getString(R.string.profile_switch_source_session)
            ru.example.parentwatch.session.ChildEffectiveContext.Source.CURRENT_SESSION ->
                getString(R.string.profile_switch_source_current)
            ru.example.parentwatch.session.ChildEffectiveContext.Source.LEGACY_PREFS ->
                getString(R.string.profile_switch_source_legacy)
            else -> getString(R.string.profile_switch_source_unknown)
        }
    }

    private fun isProfileContextMismatched(
        activeProfile: ChildDeviceProfile?,
        effectiveContext: ru.example.parentwatch.session.ChildEffectiveContext?
    ): Boolean {
        if (activeProfile == null || effectiveContext == null) return false

        fun differs(profileValue: String, effectiveValue: String): Boolean {
            val p = profileValue.trim()
            val e = effectiveValue.trim()
            return p.isNotBlank() && e.isNotBlank() && p != e
        }

        return differs(activeProfile.serverUrl, effectiveContext.serverUrl) ||
            differs(activeProfile.ownChildDeviceId, effectiveContext.ownChildDeviceId) ||
            differs(activeProfile.linkedParentDeviceId, effectiveContext.linkedParentDeviceId)
    }


    private fun ensureChatBackgroundService() {
        val serverUrl = sessionStore.resolveCurrentServerUrl().ifBlank {
            ServerUrlResolver.getServerUrl(this) ?: ""
        }
        val deviceId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        if (deviceId.isNotBlank() && serverUrl.isNotBlank()) {
            ChatBackgroundService.start(this, serverUrl, deviceId)
        } else if (serverUrl.isBlank()) {
            Log.w("MainActivity", "ChatBackgroundService not started: server URL missing")
        }
    }

    private fun ensurePhotoCaptureService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val serverUrl = sessionStore.resolveCurrentServerUrl().ifBlank {
            ServerUrlResolver.getServerUrl(this) ?: ""
        }
        val deviceId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        if (serverUrl.isNotBlank() && deviceId.isNotBlank()) {
            PhotoCaptureService.start(this, serverUrl, deviceId)
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.edit().putBoolean("chat_open", false).apply()
        recoverMonitoringServiceIfNeeded()
        if (LocationService.isServiceAlive) {
            LocationService.retryAudioAfterForeground(this)
        } else if (ru.example.parentwatch.service.AudioStreamingService.isStreamingDesired(this)) {
            ru.example.parentwatch.service.AudioStreamingService.resumeIfDesired(this)
        }
        ensurePhotoCaptureService()
        ensureChatBackgroundService()
        updateQuickProfileSummary()
        updateChatBadge()
        startBadgeRefreshLoop()
    }

    private fun recoverMonitoringServiceIfNeeded() {
        val desiredRunning = prefs.getBoolean("service_running", false)
        isServiceRunning = desiredRunning

        if (!desiredRunning) return
        if (isLocationServiceAlive()) return

        Log.w("MainActivity", "LocationService expected active but not running, recovering")
        startLocationService()
    }

    @Suppress("DEPRECATION")
    private fun isLocationServiceAlive(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == LocationService::class.java.name
        }
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

    private fun startLocationService(silent: Boolean = false) {
        try {
            val serviceAlive = isLocationServiceAlive()
            if (!isServiceRunning || !serviceAlive) {
                val serverUrl = sessionStore.resolveCurrentServerUrl().ifBlank {
                    ServerUrlResolver.getServerUrl(this) ?: ""
                }
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

                // ACTION_START is asynchronous. Retry once while this Activity is still visible so
                // Android can attach the CAMERA foreground-service type after permission approval.
                window.decorView.postDelayed({
                    if (!isFinishing && LocationService.isServiceAlive) {
                        LocationService.retryAudioAfterForeground(this)
                    }
                }, 600L)

                ensureChatBackgroundService()

            isServiceRunning = true
            prefs.edit().putBoolean("service_running", true).apply()
            updateUI()
                if (!silent) {
                    Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (!silent) {
                    Toast.makeText(this, "Мониторинг уже запущен", Toast.LENGTH_SHORT).show()
                }
            }
            ensurePhotoCaptureService()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting location service", e)
            if (!silent) {
                Toast.makeText(this, "Ошибка запуска мониторинга: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
        val effectiveChildId = sessionStore.resolveCurrentChildId().ifBlank {
            when {
                !deviceId.isNullOrBlank() -> deviceId
                !childDeviceId.isNullOrBlank() -> childDeviceId
                else -> ""
            }
        }
        val effectiveParentId = sessionStore.resolveCurrentParentId().ifBlank {
            prefs.getString("parent_device_id", null).orEmpty()
        }
        mirrorLegacyIdsFromSession(effectiveChildId, effectiveParentId)

        sessionStore.getActiveSession()?.let { current ->
            val normalizedChildId = effectiveChildId.ifBlank { current.ownChildDeviceId }
            val normalizedParentId = effectiveParentId.ifBlank { current.linkedParentDeviceId }
            val effectiveServerUrl = sessionStore.resolveCurrentServerUrl().ifBlank { current.serverUrl }
            sessionStore.applySession(
                current.copy(
                    serverUrl = effectiveServerUrl,
                    ownChildDeviceId = normalizedChildId,
                    linkedParentDeviceId = normalizedParentId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun mirrorLegacyIdsFromSession(childId: String, parentId: String) {
        if (childId.isBlank() && parentId.isBlank()) return

        val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val editor = prefs.edit()
        if (childId.isNotBlank()) {
            editor.putString("device_id", childId)
            editor.putString("child_device_id", childId)
            editor.putBoolean("device_id_permanent", true)
        }
        if (parentId.isNotBlank()) {
            editor.putString("selected_parent_device_id", parentId)
            editor.putString("parent_device_id", parentId)
            editor.putString("linked_parent_device_id", parentId)
        }
        editor.apply()

        val legacyEditor = legacyPrefs.edit()
        if (childId.isNotBlank()) {
            legacyEditor.putString("device_id", childId)
            legacyEditor.putString("child_device_id", childId)
        }
        if (parentId.isNotBlank()) {
            legacyEditor.putString("selected_parent_device_id", parentId)
            legacyEditor.putString("parent_device_id", parentId)
            legacyEditor.putString("linked_parent_device_id", parentId)
        }
        legacyEditor.apply()
    }
    private fun resolvePairedParentId(prefs: SharedPreferences, myDeviceId: String): String {
        contextProvider.current()?.targetDeviceId
            ?.takeIf { it.isNotBlank() && it != myDeviceId }
            ?.let { resolved ->
                mirrorLegacyIdsFromSession(
                    contextProvider.current()?.selfDeviceId.orEmpty().ifBlank { myDeviceId },
                    resolved
                )
                return resolved
            }
        val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val resolved = listOf(
            sessionStore.resolveCurrentParentId(),
            prefs.getString("selected_parent_device_id", null),
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("selected_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && it != myDeviceId }
            .orEmpty()

        if (resolved.isNotEmpty()) {
            mirrorLegacyIdsFromSession(sessionStore.resolveCurrentChildId().ifBlank { myDeviceId }, resolved)
        }
        return resolved
    }
    private fun getUniqueDeviceId(): String {
        var deviceId = contextProvider.current()?.selfDeviceId.orEmpty().ifBlank {
            sessionStore.resolveCurrentChildId()
        }.ifBlank {
            prefs.getString("device_id", null)
        }

        // Keep existing ID stable to avoid breaking pairing/streaming after updates.
        if (deviceId.isNullOrBlank()) {
            deviceId = "child-" + UUID.randomUUID().toString().substring(0, 8)
        }

        mirrorLegacyIdsFromSession(deviceId, sessionStore.resolveCurrentParentId())

        val currentSession = sessionStore.getActiveSession()
        if (currentSession != null) {
            sessionStore.applySession(
                currentSession.copy(
                    ownChildDeviceId = deviceId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            val effectiveServerUrl = sessionStore.resolveCurrentServerUrl()
            if (effectiveServerUrl.isNotBlank()) {
                sessionStore.applySession(
                    sessionStore.buildSession(
                        name = getString(R.string.profile_switch_current_name),
                        serverUrl = effectiveServerUrl,
                        ownChildDeviceId = deviceId,
                        linkedParentDeviceId = sessionStore.resolveCurrentParentId()
                    )
                )
            }
        }

        return deviceId
    }
    
    override fun onDestroy() {
        super.onDestroy()
        badgeRefreshJob?.cancel()
        photoIntegration?.unregister()
        photoIntegration = null
    }
}
