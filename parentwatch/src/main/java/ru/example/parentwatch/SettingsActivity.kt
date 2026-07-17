package ru.example.parentwatch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import org.json.JSONArray
import org.json.JSONObject
import ru.example.parentwatch.contacts.ContactIcons
import ru.example.parentwatch.databinding.ActivitySettingsBinding
import ru.example.parentwatch.database.ParentWatchDatabase
import ru.example.parentwatch.database.entity.Child
import ru.example.parentwatch.network.LinkedParentLink
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.service.AppUsageTracker
import ru.example.parentwatch.service.AudioStreamingService
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.session.ChildEffectiveContext
import ru.example.parentwatch.utils.ChildDeviceProfile
import ru.example.parentwatch.utils.ChildDeviceProfileManager
import ru.example.parentwatch.utils.ServerUrlResolver
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.session.ChildParticipantNameResolver
import ru.example.parentwatch.session.ChildProfileRuntimeCoordinator
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Settings Activity for ParentWatch
 * 
 * Features:
 * - Server URL configuration
 * - Device ID display and management
 * - Monitoring intervals
 * - About information
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "parentwatch_prefs"
        private const val KEY_LINKED_PARENT_COUNT = "linked_parent_count"
        private const val KEY_LINKED_PARENT_LABELS = "linked_parent_labels"
        private const val KEY_LINKED_PARENTS_JSON = "linked_parents_json"
        private const val KEY_ACTIVE_PARENT_LABEL = "active_parent_label"

        // Server URL presets
        private const val LOCALHOST_URL = "http://10.0.2.2:3000"
        private const val VPS_URL = "http://31.28.27.96:3000"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var profileManager: ChildDeviceProfileManager
    private val database by lazy { ParentWatchDatabase.getInstance(this) }
    private val sessionStore by lazy { ChildActiveSessionStore(this) }
    private val participantNameResolver by lazy { ChildParticipantNameResolver(this) }
    private val profileRuntimeCoordinator by lazy { ChildProfileRuntimeCoordinator(this) }
    private val networkClient by lazy { NetworkClient(this) }
    
    // QR Scanner result launcher
    private val qrScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedCode = result.data?.getStringExtra("SCANNED_QR_CODE")
            if (scannedCode != null) {
                saveParentDeviceId(scannedCode)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"
        profileManager = ChildDeviceProfileManager(this)
        
        setupUI()
        loadSettings()
    }
    
    private fun setupUI() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val notificationPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)

        // Load current settings
        val serverUrl = sessionStore.resolveCurrentServerUrl().ifBlank {
            ServerUrlResolver.getServerUrl(this) ?: ""
        }
        
        // Generate device_id if not exists
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrBlank()) {
            deviceId = sessionStore.resolveCurrentChildId()
        }
        if (deviceId.isNullOrBlank()) {
            deviceId = "child-" + java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs.edit()
                .putString("device_id", deviceId)
                .putString("child_device_id", deviceId)
                .putBoolean("device_id_permanent", true)
                .apply()
            syncActiveSession(
                serverUrl = serverUrl,
                ownChildId = deviceId,
                linkedParentId = sessionStore.resolveCurrentParentId()
            )
        }

        binding.serverUrlInput.setText(serverUrl)
        binding.deviceIdText.setText(deviceId)
        updateProfileSummary()

        // Update usage permission status
        updateUsagePermissionStatus()

        // Load notification settings
        val notificationDuration = notificationPrefs.getInt("notification_duration", 10000) / 1000 // Convert ms to seconds
        val notificationSound = notificationPrefs.getBoolean("notification_sound", true)
        val notificationVibration = notificationPrefs.getBoolean("notification_vibration", true)

        binding.notificationDurationSlider.value = notificationDuration.toFloat()
        binding.durationValueText.text = "$notificationDuration секунд"
        binding.notificationSoundSwitch.isChecked = notificationSound
        binding.notificationVibrationSwitch.isChecked = notificationVibration

        // Notification duration slider listener
        binding.notificationDurationSlider.addOnChangeListener { _, value, _ ->
            binding.durationValueText.text = "${value.toInt()} секунд"
        }

        // Save button
        binding.saveButton.setOnClickListener {
            saveSettings()
        }

        binding.saveProfileButton.setOnClickListener {
            showSaveProfileDialog()
        }

        binding.switchProfileButton.setOnClickListener {
            showProfilePicker()
        }

        binding.editSelfNameButton.setOnClickListener {
            showEditOwnNameDialog()
        }

        binding.editSelfMarkerButton.setOnClickListener {
            showEditOwnMarkerDialog()
        }

        binding.showQrButton.text = getString(R.string.child_pairing_show_child_qr_button)
        binding.scanParentQrButton.text = getString(R.string.child_pairing_scan_parent_backup_button)

        // Server URL preset buttons
        binding.useVpsBtn.setOnClickListener {
            binding.serverUrlInput.setText(VPS_URL)
            Toast.makeText(this, "VPS URL установлен", Toast.LENGTH_SHORT).show()
        }

        binding.useLocalhostBtn.setOnClickListener {
            binding.serverUrlInput.setText(LOCALHOST_URL)
            Toast.makeText(this, "Localhost URL установлен", Toast.LENGTH_SHORT).show()
        }

        // Copy Device ID button
        binding.copyIdButton.setOnClickListener {
            copyDeviceId()
        }

        // Show QR Code button
        binding.showQrButton.setOnClickListener {
            showQRCode()
        }

        // Scan Parent QR button
        binding.scanParentQrButton.setOnClickListener {
            showBackupParentScanDialog()
        }

        binding.selectActiveParentButton.setOnClickListener {
            showActiveParentPicker()
        }

        binding.releaseListeningLineButton.setOnClickListener {
            showForceReleaseListeningDialog()
        }

        // Update parent connection status
        updateParentConnectionStatus()

        // Request usage stats permission button
        binding.requestUsagePermissionButton.setOnClickListener {
            requestUsageStatsPermission()
        }

        // Service controls
        val isRunning = prefs.getBoolean("service_running", false)
        updateServiceButtons(isRunning)
        binding.startStopServiceButton.setOnClickListener {
            val currentlyRunning = prefs.getBoolean("service_running", false)
            if (currentlyRunning) stopMonitoring() else startMonitoring()
        }
        binding.emergencyStopButtonSettings.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🚨 Экстренная остановка")
                .setMessage("Это немедленно остановит ВСЕ функции: прослушку, геолокацию, фоновые процессы. Продолжить?")
                .setPositiveButton("Остановить всё") { _, _ -> emergencyStopAll() }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // About & Stats
        binding.openAboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.openStatsButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUsagePermissionStatus()
        // Refresh service button state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        updateServiceButtons(prefs.getBoolean("service_running", false))
    }

    private fun updateUsagePermissionStatus() {
        val appUsageTracker = AppUsageTracker(this)
        val hasPermission = appUsageTracker.hasUsageStatsPermission()

        if (hasPermission) {
            binding.usagePermissionStatus.isVisible = true
            binding.requestUsagePermissionButton.text = "✅ Разрешение предоставлено"
            binding.requestUsagePermissionButton.isEnabled = false
        } else {
            binding.usagePermissionStatus.isVisible = false
            binding.requestUsagePermissionButton.text = "🔓 Предоставить разрешение"
            binding.requestUsagePermissionButton.isEnabled = true
        }
    }

    private fun requestUsageStatsPermission() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Найдите ParentWatch в списке и включите разрешение",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Не удалось открыть настройки разрешений",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun loadSettings() {
        // Settings are loaded in setupUI
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val notificationPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
        val serverUrl = binding.serverUrlInput.text.toString().trim()

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Введите URL сервера", Toast.LENGTH_SHORT).show()
            return
        }

        val normalizedServerUrl = ServerUrlResolver.normalizeServerUrl(serverUrl)

        // Save server URL
        prefs.edit()
            .putString("server_url", normalizedServerUrl)
            .apply()
        syncActiveSession(
            serverUrl = normalizedServerUrl,
            ownChildId = sessionStore.resolveCurrentChildId().ifBlank {
                prefs.getString("device_id", null).orEmpty()
            },
            linkedParentId = sessionStore.resolveCurrentParentId()
        )

        // Save notification settings
        val notificationDurationSec = binding.notificationDurationSlider.value.toInt()
        val notificationSound = binding.notificationSoundSwitch.isChecked
        val notificationVibration = binding.notificationVibrationSwitch.isChecked

        notificationPrefs.edit()
            .putInt("notification_duration", notificationDurationSec * 1000) // Convert to ms
            .putBoolean("notification_sound", notificationSound)
            .putBoolean("notification_vibration", notificationVibration)
            .apply()

        ru.example.parentwatch.utils.NotificationManager.createNotificationChannels(this)

        Toast.makeText(this, "✅ Настройки сохранены", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showSaveProfileDialog() {
        showProfileEditorDialog(profileManager.getActiveProfile())
    }

    private fun showProfilePicker() {
        showProfileManagementDialog()
    }

    private fun showProfileManagementDialog() {
        val profiles = profileManager.getSavedProfiles()
        if (profiles.isEmpty()) {
            showProfileEditorDialog(null)
            return
        }

        val items = profiles.map(::formatProfilePickerItem).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
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

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> applyProfile(profile)
                    which == 1 -> showProfileEditorDialog(profile)
                    allowDelete && which == 2 -> confirmDeleteProfile(profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteProfile(profile: ChildDeviceProfile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.profile_switch_delete_title)
            .setMessage(getString(R.string.profile_switch_delete_message, profile.name))
            .setPositiveButton(R.string.profile_switch_delete) { _, _ ->
                profileManager.deleteProfile(profile.id)
                updateProfileSummary()
                Toast.makeText(this, R.string.profile_switch_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileEditorDialog(existingProfile: ChildDeviceProfile?) {
        val effectiveContext = sessionStore.resolveEffectiveContext()
        val currentOwnId = existingProfile?.ownChildDeviceId?.ifBlank { null }
            ?: effectiveContext?.ownChildDeviceId?.takeIf { it.isNotBlank() }
            ?: profileManager.resolveCurrentChildId()
        val currentParentId = existingProfile?.linkedParentDeviceId?.ifBlank { null }
            ?: effectiveContext?.linkedParentDeviceId?.takeIf { it.isNotBlank() }
            ?: profileManager.resolveCurrentParentId()
        val currentServerUrl = existingProfile?.serverUrl?.ifBlank { null }
            ?: binding.serverUrlInput.text?.toString()?.trim().orEmpty()
                .ifBlank { profileManager.resolveCurrentServerUrl() }
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

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (existingProfile == null) R.string.profile_switch_name_title else R.string.profile_switch_edit_title)
            .setView(createProfileDialogLayout(nameInput, serverInput, ownIdInput, parentIdInput))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
                            applyProfile(profile)
                        } else {
                            updateProfileSummary()
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

    private fun applyProfile(profile: ChildDeviceProfile) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val wasRunning = prefs.getBoolean("service_running", false)

        val appliedContext = profileRuntimeCoordinator.applyProfile(profile, wasRunning)
        val appliedServerUrl = appliedContext?.serverUrl.orEmpty().ifBlank { profile.serverUrl }
        val appliedChildId = appliedContext?.ownChildDeviceId.orEmpty().ifBlank { profile.ownChildDeviceId }
        binding.serverUrlInput.setText(appliedServerUrl)
        binding.deviceIdText.setText(appliedChildId)
        updateParentConnectionStatus()
        updateProfileSummary()

        Toast.makeText(this, R.string.profile_switch_applied, Toast.LENGTH_SHORT).show()
    }

    private fun updateProfileSummary() {
        val activeProfile = profileManager.getActiveProfile()
        val effectiveContext = sessionStore.resolveEffectiveContext()
        val ownChildId = activeProfile?.ownChildDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.ownChildDeviceId?.takeIf { it.isNotBlank() }
        val parentId = activeProfile?.linkedParentDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.linkedParentDeviceId?.takeIf { it.isNotBlank() }
        val serverUrl = activeProfile?.serverUrl?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.serverUrl?.takeIf { it.isNotBlank() }
        val profileName = activeProfile?.name?.takeIf { it.isNotBlank() }
            ?: sessionStore.getActiveSession()?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_switch_current_name)
        val selfNameLine = getString(
            R.string.participant_self_name_summary_line,
            participantNameResolver.resolveChildDisplayName()
        )
        val selfMarkerLine = getString(
            R.string.participant_self_marker_summary_line,
            ContactIcons.labelFor(participantNameResolver.resolveChildMarkerIconId())
        )

        if (ownChildId.isNullOrBlank() || serverUrl.isNullOrBlank()) {
            binding.profileSummaryText.text = getString(R.string.profile_switch_no_active)
            return
        }

        binding.profileSummaryText.text = getString(
            R.string.profile_switch_summary_format,
            profileName,
            formatProfileServer(serverUrl),
            formatProfileId(ownChildId),
            formatProfileId(
                parentId?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.profile_switch_unknown_link)
            )
        ) + "\n" + selfNameLine + "\n" + selfMarkerLine + "\n" + getString(
            R.string.profile_switch_source_line,
            describeProfileContextSource(effectiveContext?.source)
        ) + "\n" + buildCanonicalContextDiagnosticsLine() + "\n" + getString(
            R.string.profile_switch_status_line,
            getString(
                if (activeProfile?.id?.let { activeId ->
                    profileManager.getSavedProfiles().any { it.id == activeId }
                } == true) {
                    R.string.profile_switch_status_saved
                } else {
                    R.string.profile_switch_status_runtime_only
                }
            )
        ) + (buildCachedLinkedParentsLine()?.let { "\n$it" } ?: "") +
            (buildCachedActiveParentLine()?.let { "\n$it" } ?: "") +
            if (isProfileContextMismatched(activeProfile, effectiveContext)) {
            "\n" + getString(R.string.profile_switch_warning_mismatch)
        } else {
            ""
        }
    }

    private fun buildCanonicalContextDiagnosticsLine(): String {
        val snapshot = ru.example.parentwatch.session.ChildContextDiagnostics(this).snapshot()
        val unknown = getString(R.string.profile_switch_context_diagnostics_unknown)
        return getString(
            R.string.profile_switch_context_diagnostics_line,
            snapshot.version?.toString() ?: unknown,
            snapshot.familyId ?: unknown,
            snapshot.selfMemberId ?: unknown,
            snapshot.focusedMemberId ?: unknown
        )
    }

    private fun showEditOwnNameDialog() {
        val input = createProfileInput(
            getString(R.string.participant_self_name_hint_child),
            participantNameResolver.resolveChildDisplayName()
        )

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.participant_self_name_title_child)
            .setView(createProfileDialogLayout(input))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                ?.setOnClickListener {
                    val newName = input.text?.toString()?.trim().orEmpty()
                    if (newName.isBlank()) {
                        input.error = getString(R.string.participant_self_name_error_empty)
                        return@setOnClickListener
                    }
                    input.error = null
                    dialog.dismiss()
                    saveOwnChildDisplayName(newName)
                }
        }

        dialog.show()
    }

    private fun showEditOwnMarkerDialog() {
        val options = ContactIcons.options()
        var selectedIndex = options.indexOfFirst {
            it.id == participantNameResolver.resolveChildMarkerIconId()
        }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.participant_self_marker_title_child)
            .setSingleChoiceItems(
                options.map { it.label }.toTypedArray(),
                selectedIndex
            ) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveOwnChildMarkerIcon(options[selectedIndex].id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveOwnChildDisplayName(newName: String) {
        lifecycleScope.launch {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ChildParticipantNameResolver.KEY_SELF_DISPLAY_NAME, newName)
                .apply()

            updateLocalChildName(newName)
            val synced = syncOwnChildNameToServer(newName)
            updateParentConnectionStatus()
            updateProfileSummary()

            Toast.makeText(
                this@SettingsActivity,
                if (synced) {
                    R.string.participant_self_name_saved
                } else {
                    R.string.participant_self_name_sync_partial
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveOwnChildMarkerIcon(iconId: Int) {
        lifecycleScope.launch {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(ChildParticipantNameResolver.KEY_SELF_MARKER_ICON_ID, iconId)
                .apply()

            updateLocalChildMarkerIcon(iconId)
            val synced = syncOwnChildMarkerToServer(iconId)
            updateParentConnectionStatus()
            updateProfileSummary()

            Toast.makeText(
                this@SettingsActivity,
                if (synced) {
                    R.string.participant_self_marker_saved
                } else {
                    R.string.participant_self_marker_sync_partial
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun updateLocalChildName(newName: String) {
        val ownChildId = sessionStore.resolveCurrentChildId().trim()
        if (ownChildId.isBlank()) return

        withContext(Dispatchers.IO) {
            val existing = database.childDao().getByDeviceId(ownChildId)
            if (existing != null) {
                database.childDao().update(
                    existing.copy(
                        name = newName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                database.childDao().insert(
                    Child(
                        deviceId = ownChildId,
                        name = newName
                    )
                )
            }
        }
    }

    private suspend fun updateLocalChildMarkerIcon(iconId: Int) {
        val ownChildId = sessionStore.resolveCurrentChildId().trim()
        if (ownChildId.isBlank()) return

        withContext(Dispatchers.IO) {
            val existing = database.childDao().getByDeviceId(ownChildId)
            if (existing != null) {
                database.childDao().update(
                    existing.copy(
                        iconId = iconId,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                database.childDao().insert(
                    Child(
                        deviceId = ownChildId,
                        name = participantNameResolver.resolveChildDisplayName(),
                        iconId = iconId
                    )
                )
            }
        }
    }

    private suspend fun syncOwnChildNameToServer(newName: String): Boolean {
        val ownChildId = sessionStore.resolveCurrentChildId().trim()
        if (ownChildId.isBlank()) return true

        val linkedParents = fetchLinkedParents(ownChildId)
        if (linkedParents.isEmpty()) return true

        var allSuccessful = true
        linkedParents.forEach { parent ->
            val response = runCatching {
                networkClient.linkParentChild(
                    parentDeviceId = parent.parentDeviceId,
                    childDeviceId = ownChildId,
                    childDisplayName = newName
                )
            }.getOrNull()
            if (response?.isSuccessful != true) {
                allSuccessful = false
            }
        }
        if (allSuccessful) {
            cacheLinkedParentsSnapshot(
                localParentId = sessionStore.resolveCurrentParentId(),
                linkedParents = fetchLinkedParents(ownChildId)
            )
        }
        return allSuccessful
    }

    private suspend fun syncOwnChildMarkerToServer(iconId: Int): Boolean {
        val ownChildId = sessionStore.resolveCurrentChildId().trim()
        if (ownChildId.isBlank()) return true

        val linkedParents = fetchLinkedParents(ownChildId)
        if (linkedParents.isEmpty()) return true

        var allSuccessful = true
        linkedParents.forEach { parent ->
            val response = runCatching {
                networkClient.linkParentChild(
                    parentDeviceId = parent.parentDeviceId,
                    childDeviceId = ownChildId,
                    childMarkerIconId = iconId
                )
            }.getOrNull()
            if (response?.isSuccessful != true) {
                allSuccessful = false
            }
        }
        if (allSuccessful) {
            cacheLinkedParentsSnapshot(
                localParentId = sessionStore.resolveCurrentParentId(),
                linkedParents = fetchLinkedParents(ownChildId)
            )
        }
        return allSuccessful
    }

    private fun buildCachedLinkedParentsLine(): String? {
        val settingsPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val count = settingsPrefs.getInt(KEY_LINKED_PARENT_COUNT, 0)
        if (count <= 0) return null

        val labels = settingsPrefs.getString(KEY_LINKED_PARENT_LABELS, null).orEmpty()
        return if (labels.isNotBlank()) {
            getString(R.string.child_parent_link_status_connected_named, count, labels)
        } else {
            getString(R.string.child_parent_link_status_connected_count, count)
        }
    }

    private fun buildCachedActiveParentLine(): String? {
        val label = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_ACTIVE_PARENT_LABEL, null)
            .orEmpty()
        return label.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.child_parent_link_status_active_parent, it) }
    }

    private fun describeProfileContextSource(source: ChildEffectiveContext.Source?): String {
        return when (source) {
            ChildEffectiveContext.Source.ACTIVE_SESSION ->
                getString(R.string.profile_switch_source_session)
            ChildEffectiveContext.Source.CURRENT_SESSION ->
                getString(R.string.profile_switch_source_current)
            ChildEffectiveContext.Source.LEGACY_PREFS ->
                getString(R.string.profile_switch_source_legacy)
            else -> getString(R.string.profile_switch_source_unknown)
        }
    }

    private fun isProfileContextMismatched(
        activeProfile: ChildDeviceProfile?,
        effectiveContext: ChildEffectiveContext?
    ): Boolean {
        if (activeProfile == null || effectiveContext == null) return false
        return activeProfile.serverUrl != effectiveContext.serverUrl ||
            activeProfile.ownChildDeviceId != effectiveContext.ownChildDeviceId ||
            activeProfile.linkedParentDeviceId != effectiveContext.linkedParentDeviceId
    }

    private fun syncActiveSession(
        serverUrl: String,
        ownChildId: String,
        linkedParentId: String,
        preferredName: String? = null
    ) {
        val normalizedServerUrl = ServerUrlResolver.normalizeServerUrl(serverUrl.trim())
        val normalizedOwnChildId = ownChildId.trim()
        if (normalizedServerUrl.isBlank() || normalizedOwnChildId.isBlank()) return

        val profileName = preferredName?.takeIf { it.isNotBlank() }
            ?: profileManager.getActiveProfile()?.name?.takeIf { it.isNotBlank() }
            ?: sessionStore.getActiveSession()?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_switch_current_name)

        sessionStore.applySession(
            sessionStore.buildSession(
                name = profileName,
                serverUrl = normalizedServerUrl,
                ownChildDeviceId = normalizedOwnChildId,
                linkedParentDeviceId = linkedParentId.trim()
            )
        )
    }

    private fun formatProfileServer(serverUrl: String): String {
        val parsedHost = runCatching { Uri.parse(serverUrl).host }.getOrNull()
        return (parsedHost ?: serverUrl).removePrefix("www.")
    }

    private fun formatProfilePickerItem(profile: ChildDeviceProfile): String {
        val linkedParent = profile.linkedParentDeviceId.ifBlank {
            getString(R.string.profile_switch_unknown_link)
        }
        val linkedParentLabel = loadCachedLinkedParentsSnapshot()
            .firstOrNull { it.parentDeviceId == linkedParent }
            ?.let(::resolveParentDisplayName)
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

    private fun formatProfileId(rawId: String): String {
        return if (rawId.length <= 16) rawId else "${rawId.take(8)}...${rawId.takeLast(4)}"
    }

    private fun createProfileInput(hint: String, value: String): EditText {
        return EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
            this.hint = hint
            setText(value)
            setSingleLine()
        }
    }

    private fun createProfileDialogLayout(vararg inputs: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                0,
                (20 * resources.displayMetrics.density).toInt(),
                0
            )
            inputs.forEach(::addView)
        }
    }

    private fun startMonitoring() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val serverUrl = sessionStore.resolveCurrentServerUrl().ifBlank { ServerUrlResolver.getServerUrl(this) ?: "" }
        val deviceId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        if (serverUrl.isBlank()) {
            Toast.makeText(this, "Введите URL сервера в настройках", Toast.LENGTH_SHORT).show()
            return
        }
        if (deviceId.isBlank()) {
            Toast.makeText(this, "Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(this, ru.example.parentwatch.service.LocationService::class.java).apply {
                action = ru.example.parentwatch.service.LocationService.ACTION_START
                putExtra("server_url", serverUrl)
                putExtra("device_id", deviceId)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            prefs.edit().putBoolean("service_running", true).apply()
            updateServiceButtons(true)
            Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка запуска: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoring() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        try {
            val intent = Intent(this, ru.example.parentwatch.service.LocationService::class.java).apply {
                action = ru.example.parentwatch.service.LocationService.ACTION_STOP
            }
            stopService(intent)
            ru.example.parentwatch.service.ChatBackgroundService.stop(this)
            ru.example.parentwatch.service.PhotoCaptureService.stop(this)
            prefs.edit().putBoolean("service_running", false).apply()
            updateServiceButtons(false)
            Toast.makeText(this, "Мониторинг остановлен", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка остановки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun emergencyStopAll() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        try {
            val intent = Intent(this, ru.example.parentwatch.service.LocationService::class.java).apply {
                action = ru.example.parentwatch.service.LocationService.ACTION_EMERGENCY_STOP
            }
            startService(intent)
            prefs.edit().putBoolean("service_running", false).apply()
            updateServiceButtons(false)
            Toast.makeText(this, "Экстренная остановка выполнена", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экстренной остановки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateServiceButtons(running: Boolean) {
        if (running) {
            binding.startStopServiceButton.text = "Остановить мониторинг"
            binding.startStopServiceButton.icon = androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.ic_media_pause)
        } else {
            binding.startStopServiceButton.text = "Запустить мониторинг"
            binding.startStopServiceButton.icon = androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.ic_media_play)
        }
    }
    
    private fun copyDeviceId() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val deviceId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        
        if (deviceId.isBlank()) {
            Toast.makeText(this, "Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "✅ Device ID скопирован", Toast.LENGTH_SHORT).show()
    }
    
    private fun showQRCode() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val deviceId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }

        if (deviceId.isBlank()) {
            Toast.makeText(this, "Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, QrCodeActivity::class.java)
        startActivity(intent)
    }

    private fun showBackupParentScanDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.child_pairing_scan_parent_backup_title)
            .setMessage(R.string.child_pairing_scan_parent_backup_message)
            .setPositiveButton(R.string.child_pairing_scan_parent_backup_continue) { _, _ ->
                val intent = Intent(this, QrScannerActivity::class.java)
                qrScannerLauncher.launch(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    
    private fun saveParentDeviceId(parentId: String) {
        val normalized = parentId.trim()
        if (normalized.isEmpty()) return

        applySelectedParent(
            parentId = normalized,
            createLinkOnServer = true,
            toastMessage = getString(R.string.settings_parent_id_saved, normalized)
        )
    }

    private suspend fun linkParentOnServer(
        parentId: String,
        childDeviceId: String,
        parentDisplayName: String? = null,
        childDisplayName: String? = null
    ) {
        runCatching {
            networkClient.linkParentChild(
                parentDeviceId = parentId,
                childDeviceId = childDeviceId,
                parentDisplayName = parentDisplayName,
                childDisplayName = childDisplayName
            )
        }.onFailure { error ->
            android.util.Log.w(TAG, "Unable to create parent-child link on server", error)
        }
    }

    private fun updateParentConnectionStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val compat = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val parentId = sessionStore.resolveCurrentParentId().ifBlank {
            prefs.getString("parent_device_id", null)
                ?: prefs.getString("linked_parent_device_id", null)
                ?: compat.getString("parent_device_id", null)
                ?: compat.getString("linked_parent_device_id", null)
                ?: ""
        }
        val childId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null)
                ?: prefs.getString("child_device_id", null)
                ?: compat.getString("device_id", null)
                ?: compat.getString("child_device_id", null)
                ?: ""
        }

        renderParentConnectionStatus(parentId, emptyList())

        if (childId.isBlank()) {
            return
        }

        lifecycleScope.launch {
            val linkedParents = fetchLinkedParents(childId)
            if (linkedParents.isNotEmpty()) {
                renderParentConnectionStatus(parentId, linkedParents)
            }
        }
    }

    private suspend fun fetchLinkedParents(childDeviceId: String): List<LinkedParentLink> {
        return try {
            val response = networkClient.getLinkedParents(childDeviceId)
            if (!response.isSuccessful) {
                emptyList()
            } else {
                response.body()
                    ?.parents
                    .orEmpty()
                    .filter { it.isActive != false }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun renderParentConnectionStatus(
        localParentId: String,
        linkedParents: List<LinkedParentLink>
    ) {
        val effectiveParents = buildManageableParents(
            localParentId = localParentId,
            linkedParents = if (linkedParents.isNotEmpty()) linkedParents else loadCachedLinkedParentsSnapshot()
        )
        binding.selectActiveParentButton.isVisible = effectiveParents.isNotEmpty()
        if (linkedParents.isNotEmpty() || localParentId.isBlank()) {
            cacheLinkedParentsSnapshot(localParentId, linkedParents)
        }

        if (linkedParents.isNotEmpty()) {
            val linkedSummary = buildLinkedParentsSummary(linkedParents)
            val activeLabel = resolveActiveParentLabel(localParentId, linkedParents)

            binding.parentIdStatus.text = buildString {
                append(linkedSummary)
                if (activeLabel != null) {
                    append('\n')
                    append(getString(R.string.child_parent_link_status_active_parent, activeLabel))
                }
            }
            binding.parentIdStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            return
        }

        if (localParentId.isNotBlank()) {
            binding.parentIdStatus.text = getString(
                R.string.child_parent_link_status_local_only,
                formatShortId(localParentId)
            )
            binding.parentIdStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.parentIdStatus.text = getString(R.string.child_parent_link_status_missing)
            binding.parentIdStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun buildLinkedParentsSummary(linkedParents: List<LinkedParentLink>): String {
        val labels = linkedParents
            .map(::resolveParentDisplayName)
            .filter { it.isNotBlank() }
            .distinct()

        if (labels.isEmpty()) {
            return getString(
                R.string.child_parent_link_status_connected_count,
                linkedParents.size
            )
        }

        val preview = if (labels.size <= 3) {
            labels.joinToString(", ")
        } else {
            labels.take(3).joinToString(", ") + " +${labels.size - 3}"
        }

        return getString(
            R.string.child_parent_link_status_connected_named,
            linkedParents.size,
            preview
        )
    }

    private fun resolveActiveParentLabel(
        localParentId: String,
        linkedParents: List<LinkedParentLink>
    ): String? {
        if (localParentId.isBlank()) return null
        val matchingParent = linkedParents.firstOrNull { it.parentDeviceId == localParentId }
        return matchingParent?.let(::resolveParentDisplayName) ?: formatShortId(localParentId)
    }

    private fun resolveParentDisplayName(link: LinkedParentLink): String {
        return link.parentDisplayName?.takeIf { it.isNotBlank() }
            ?: link.displayName?.takeIf { it.isNotBlank() }
            ?: link.parentDeviceName?.takeIf { it.isNotBlank() }
            ?: formatShortId(link.parentDeviceId)
    }

    private fun cacheLinkedParentsSnapshot(
        localParentId: String,
        linkedParents: List<LinkedParentLink>
    ) {
        val labels = linkedParents
            .map(::resolveParentDisplayName)
            .filter { it.isNotBlank() }
            .distinct()
        val preview = if (labels.isEmpty()) {
            ""
        } else if (labels.size <= 3) {
            labels.joinToString(", ")
        } else {
            labels.take(3).joinToString(", ") + " +${labels.size - 3}"
        }
        val activeLabel = resolveActiveParentLabel(localParentId, linkedParents)
            ?: localParentId.takeIf { it.isNotBlank() }?.let(::formatShortId).orEmpty()

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_LINKED_PARENT_COUNT, linkedParents.size)
            .putString(KEY_LINKED_PARENT_LABELS, preview)
            .putString(KEY_ACTIVE_PARENT_LABEL, activeLabel)
            .putString(
                KEY_LINKED_PARENTS_JSON,
                JSONArray().apply {
                    linkedParents.forEach { parent ->
                        put(
                            JSONObject().apply {
                                put("parentDeviceId", parent.parentDeviceId)
                                put("displayName", parent.displayName ?: "")
                                put("parentDisplayName", parent.parentDisplayName ?: "")
                                put("parentDeviceName", parent.parentDeviceName ?: "")
                                put("parentMarkerIconId", parent.parentMarkerIconId ?: ContactIcons.DEFAULT)
                            }
                        )
                    }
                }.toString()
            )
            .apply()
    }

    private fun showActiveParentPicker() {
        val childId = sessionStore.resolveCurrentChildId()
        lifecycleScope.launch {
            val liveParents = childId.takeIf { it.isNotBlank() }
                ?.let { fetchLinkedParents(it) }
                .orEmpty()
            val linkedParents = buildManageableParents(
                localParentId = sessionStore.resolveCurrentParentId(),
                linkedParents = if (liveParents.isNotEmpty()) liveParents else loadCachedLinkedParentsSnapshot()
            )
            if (linkedParents.isEmpty()) {
                Toast.makeText(this@SettingsActivity, R.string.child_parent_link_manage_empty, Toast.LENGTH_SHORT).show()
                updateParentConnectionStatus()
                return@launch
            }

            val activeParentId = sessionStore.resolveCurrentParentId()
            val items = linkedParents.map { parent ->
                val label = resolveParentDisplayName(parent)
                val decoratedLabel = if (parent.parentDeviceId == activeParentId) {
                    "${getString(R.string.child_parent_link_manage_item_active)}: $label"
                } else {
                    label
                }
                getString(
                    R.string.child_parent_link_manage_item_format,
                    decoratedLabel,
                    formatShortId(parent.parentDeviceId)
                )
            }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.child_parent_link_manage_title)
                .setItems(items) { _, which ->
                    showLinkedParentActionsDialog(linkedParents[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showLinkedParentActionsDialog(parent: LinkedParentLink) {
        val isActive = parent.parentDeviceId == sessionStore.resolveCurrentParentId()
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!isActive) {
            labels += getString(R.string.child_parent_link_action_set_active)
            actions += {
                applySelectedParent(
                    parentId = parent.parentDeviceId,
                    createLinkOnServer = false,
                    toastMessage = getString(R.string.child_parent_link_switch_done)
                )
            }
        }

        labels += getString(R.string.child_parent_link_action_rename)
        actions += { showRenameParentDialog(parent) }

        labels += getString(R.string.child_parent_link_action_remove)
        actions += { confirmRemoveParentLink(parent) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(resolveParentDisplayName(parent))
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showForceReleaseListeningDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentChildId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        val currentServerUrl = sessionStore.resolveCurrentServerUrl().ifBlank {
            ServerUrlResolver.getServerUrl(this) ?: ""
        }

        if (currentChildId.isBlank()) {
            Toast.makeText(this, R.string.child_parent_link_no_child_id, Toast.LENGTH_SHORT).show()
            return
        }
        if (currentServerUrl.isBlank()) {
            Toast.makeText(this, R.string.settings_error_enter_server_url_in_settings, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val status = networkClient.getStreamingStatus(currentServerUrl, currentChildId)
            if (status?.active != true) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.child_parent_link_release_listening_missing,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val ownerLabel = status.ownerDisplayName?.takeIf { it.isNotBlank() }
                ?: status.ownerParentId?.takeIf { it.isNotBlank() }
                ?: getString(R.string.child_parent_link_release_listening_unknown_owner)

            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.child_parent_link_release_listening_title)
                .setMessage(
                    getString(
                        R.string.child_parent_link_release_listening_message,
                        ownerLabel
                    )
                )
                .setPositiveButton(R.string.child_parent_link_release_listening_button) { _, _ ->
                    forceReleaseListeningLine(currentServerUrl, currentChildId)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun forceReleaseListeningLine(serverUrl: String, childId: String) {
        WebSocketManager.initialize(this, serverUrl, childId)
        WebSocketManager.ensureConnected(
            onReady = {
                runOnUiThread {
                    runCatching {
                        val releasedByDisplayName = participantNameResolver.resolveChildDisplayName()
                        val payload = JSONObject().apply {
                            put("deviceId", childId)
                            put("releasedByDisplayName", releasedByDisplayName)
                            put("timestamp", System.currentTimeMillis())
                        }
                        val client = WebSocketManager.getClient()
                        if (client == null || !client.isReady()) {
                            throw IllegalStateException("WebSocket is not ready")
                        }
                        client.emit("force_release_stream", payload)
                        LocationService.requestAudioStop(this)
                        AudioStreamingService.stopStreaming(this)
                        Toast.makeText(
                            this,
                            R.string.child_parent_link_release_listening_done,
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure {
                        Log.e(TAG, "Failed to force release listening line", it)
                        Toast.makeText(
                            this,
                            getString(
                                R.string.settings_error_stop,
                                it.message ?: getString(R.string.child_parent_link_release_listening_failed)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.settings_error_stop, error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showRenameParentDialog(parent: LinkedParentLink) {
        val input = EditText(this).apply {
            setText(parent.displayName?.takeIf { it.isNotBlank() } ?: "")
            hint = getString(R.string.child_parent_link_rename_hint)
            setSingleLine()
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.child_parent_link_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newDisplayName = input.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    renameParentLink(parent, newDisplayName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun renameParentLink(parent: LinkedParentLink, newDisplayName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentChildId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        if (currentChildId.isBlank()) {
            Toast.makeText(this, R.string.child_parent_link_no_child_id, Toast.LENGTH_SHORT).show()
            return
        }

        val response = runCatching {
            networkClient.linkParentChild(
                parentDeviceId = parent.parentDeviceId,
                childDeviceId = currentChildId,
                parentDisplayName = newDisplayName.ifBlank { null }
            )
        }.getOrNull()

        if (response?.isSuccessful != true) {
            Toast.makeText(this, R.string.child_parent_link_rename_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val refreshedParents = fetchLinkedParents(currentChildId)
        cacheLinkedParentsSnapshot(
            localParentId = sessionStore.resolveCurrentParentId(),
            linkedParents = refreshedParents
        )
        updateParentConnectionStatus()
        updateProfileSummary()
        Toast.makeText(this, R.string.child_parent_link_rename_done, Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemoveParentLink(parent: LinkedParentLink) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.child_parent_link_remove_title)
            .setMessage(
                getString(
                    R.string.child_parent_link_remove_message,
                    resolveParentDisplayName(parent)
                )
            )
            .setPositiveButton(R.string.child_parent_link_action_remove) { _, _ ->
                lifecycleScope.launch {
                    removeParentLink(parent)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySelectedParent(
        parentId: String,
        createLinkOnServer: Boolean,
        toastMessage: String
    ) {
        val normalized = parentId.trim()
        if (normalized.isBlank()) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentChildId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        val currentServerUrl = sessionStore.resolveCurrentServerUrl().ifBlank {
            ServerUrlResolver.getServerUrl(this) ?: ""
        }

        persistSelectedParent(normalized, currentServerUrl, currentChildId)

        val monitoringEnabled = prefs.getBoolean("service_running", false)
        profileRuntimeCoordinator.refreshRuntime(monitoringEnabled)
        updateProfileSummary()
        updateParentConnectionStatus()
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()

        if (createLinkOnServer && currentChildId.isNotBlank()) {
            lifecycleScope.launch {
                linkParentOnServer(parentId = normalized, childDeviceId = currentChildId)
                updateParentConnectionStatus()
            }
        }
    }

    private suspend fun removeParentLink(parent: LinkedParentLink) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentChildId = sessionStore.resolveCurrentChildId().ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }
        if (currentChildId.isBlank()) {
            Toast.makeText(this, R.string.child_parent_link_no_child_id, Toast.LENGTH_SHORT).show()
            return
        }

        val cachedServerLinks = loadCachedLinkedParentsSnapshot()
        val isServerLinked = cachedServerLinks.any { it.parentDeviceId == parent.parentDeviceId }
        if (isServerLinked) {
            val response = runCatching {
                networkClient.unlinkParentChild(
                    parentDeviceId = parent.parentDeviceId,
                    childDeviceId = currentChildId
                )
            }.getOrNull()
            if (response?.isSuccessful != true) {
                Toast.makeText(this, R.string.child_parent_link_remove_failed, Toast.LENGTH_SHORT).show()
                return
            }
        }

        val currentActiveParentId = sessionStore.resolveCurrentParentId()
        val remainingParents = buildManageableParents(
            localParentId = currentActiveParentId,
            linkedParents = cachedServerLinks.filterNot { it.parentDeviceId == parent.parentDeviceId }
        ).filterNot { it.parentDeviceId == parent.parentDeviceId }

        val replacementParentId = if (currentActiveParentId == parent.parentDeviceId) {
            remainingParents.firstOrNull()?.parentDeviceId.orEmpty()
        } else {
            currentActiveParentId
        }

        val currentServerUrl = sessionStore.resolveCurrentServerUrl().ifBlank {
            ServerUrlResolver.getServerUrl(this) ?: ""
        }
        persistSelectedParent(replacementParentId, currentServerUrl, currentChildId)

        val monitoringEnabled = prefs.getBoolean("service_running", false)
        profileRuntimeCoordinator.refreshRuntime(monitoringEnabled)
        cacheLinkedParentsSnapshot(
            localParentId = replacementParentId,
            linkedParents = cachedServerLinks.filterNot { it.parentDeviceId == parent.parentDeviceId }
        )
        updateParentConnectionStatus()
        updateProfileSummary()
        Toast.makeText(this, R.string.child_parent_link_remove_done, Toast.LENGTH_SHORT).show()
    }

    private fun persistSelectedParent(
        parentId: String,
        serverUrl: String,
        ownChildId: String
    ) {
        val normalized = parentId.trim()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString("selected_parent_device_id", normalized)
            .putString("parent_device_id", normalized)
            .putString("linked_parent_device_id", normalized)
            .apply()

        val compat = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        compat.edit()
            .putString("selected_parent_device_id", normalized)
            .putString("parent_device_id", normalized)
            .putString("linked_parent_device_id", normalized)
            .apply()

        syncActiveSession(
            serverUrl = serverUrl,
            ownChildId = ownChildId,
            linkedParentId = normalized,
            preferredName = null
        )
    }

    private fun loadCachedLinkedParentsSnapshot(): List<LinkedParentLink> {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LINKED_PARENTS_JSON, null)
            .orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val parentDeviceId = item.optString("parentDeviceId").trim()
                    if (parentDeviceId.isBlank()) continue
                    add(
                        LinkedParentLink(
                            parentDeviceId = parentDeviceId,
                            parentDisplayName = item.optString("parentDisplayName").takeIf { it.isNotBlank() },
                            displayName = item.optString("displayName").takeIf { it.isNotBlank() },
                            parentDeviceName = item.optString("parentDeviceName").takeIf { it.isNotBlank() },
                            parentMarkerIconId = item.optInt("parentMarkerIconId", ContactIcons.DEFAULT)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun buildManageableParents(
        localParentId: String,
        linkedParents: List<LinkedParentLink>
    ): List<LinkedParentLink> {
        val result = linkedParents
            .filter { it.parentDeviceId.isNotBlank() }
            .distinctBy { it.parentDeviceId }
            .toMutableList()

        if (localParentId.isNotBlank() && result.none { it.parentDeviceId == localParentId }) {
            result.add(
                0,
                LinkedParentLink(parentDeviceId = localParentId)
            )
        }

        return result
    }

    private fun formatShortId(rawId: String): String {
        return if (rawId.length <= 16) rawId else "${rawId.take(8)}...${rawId.takeLast(4)}"
    }
override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
