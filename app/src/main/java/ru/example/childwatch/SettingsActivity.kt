package ru.example.childwatch

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.databinding.ActivitySettingsBinding
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentActiveSession
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentContextDiagnostics
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentLinkedChildOption
import ru.example.childwatch.profile.ParentLinkedChildOptionsProvider
import ru.example.childwatch.profile.ParentLinkedParentOption
import ru.example.childwatch.profile.ParentLinkedParentsProvider
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.profile.ParentProfileRuntimeCoordinator
import ru.example.childwatch.utils.NotificationManager as ChatNotificationManager
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.ParentMonitorProfile
import ru.example.childwatch.utils.ParentMonitorProfileManager
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.service.ParentLocationService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings Activity for monitoring configuration
 * 
 * Features:
 * - Configure monitoring intervals and durations
 * - Toggle monitoring features on/off
 * - Manage privacy settings
 * - Revoke consent
 */
class SettingsActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "childwatch_prefs"
        private const val PARENT_ONBOARDING_PREFS = "parent_onboarding"
        private const val KEY_PARENT_ID = "parent_id"

        // Default values
        private const val DEFAULT_LOCATION_INTERVAL = 30
        private const val DEFAULT_AUDIO_DURATION = 20
        private const val DEFAULT_SERVER_URL = "http://31.28.27.96:3000"
        private const val KEY_LINKED_PARENT_COUNT = "linked_parent_context_count"
        private const val KEY_LINKED_PARENT_LABELS = "linked_parent_context_labels"
        private const val KEY_LINKED_PARENT_SELF_LABEL = "linked_parent_context_self_label"

        // Server URL presets
        private const val LOCALHOST_URL = "http://10.0.2.2:3000"
        private const val VPS_URL = "http://31.28.27.96:3000"
        
        // Keys
        private const val KEY_LOCATION_INTERVAL = "location_interval"
        private const val KEY_AUDIO_DURATION = "audio_duration"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CHILD_DEVICE_ID = "child_device_id"
        private const val KEY_LOCATION_ENABLED = "location_enabled"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_PHOTO_ENABLED = "photo_enabled"
        private const val KEY_SHARE_PARENT_LOCATION = "share_parent_location"
        private const val NOTIFICATION_PREFS_NAME = "notification_prefs"
        private const val DEFAULT_QUIET_HOURS_START = "22:00"
        private const val DEFAULT_QUIET_HOURS_END = "07:00"
    }
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var secureSettings: SecureSettingsManager
    private lateinit var profileManager: ParentMonitorProfileManager
    private val database by lazy { ChildWatchDatabase.getInstance(this) }
    private val activeSessionStore by lazy { ParentActiveSessionStore(this) }
    private val effectiveContextResolver by lazy { ParentEffectiveContextResolver(this) }
    private val linkedChildOptionsProvider by lazy { ParentLinkedChildOptionsProvider(this) }
    private val linkedParentsProvider by lazy { ParentLinkedParentsProvider(this) }
    private val participantNameResolver by lazy { ParentParticipantNameResolver(this) }
    private val profileRuntimeCoordinator by lazy { ParentProfileRuntimeCoordinator(this) }
    private val networkClient by lazy { NetworkClient(this) }
    private var quietHoursStart = DEFAULT_QUIET_HOURS_START
    private var quietHoursEnd = DEFAULT_QUIET_HOURS_END

    // QR Scanner result launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedCode = result.data?.getStringExtra("SCANNED_QR_CODE")
            if (!scannedCode.isNullOrEmpty()) {
                binding.childDeviceIdInput.setText(scannedCode)
                Toast.makeText(
                    this,
                    getString(R.string.settings_scanned_qr, scannedCode),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        secureSettings = SecureSettingsManager(this)
        profileManager = ParentMonitorProfileManager(this)

        setupUI()
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            syncLinkedProfilesInBackground()
        }
    }
    
    private fun setupUI() {
        binding.notificationDurationSlider.addOnChangeListener { _, value, _ ->
            binding.durationValueText.text = formatNotificationDuration(value.toInt())
        }

        binding.notificationPrioritySlider.addOnChangeListener { _, value, _ ->
            binding.priorityValueText.text = notificationPriorityLabel(value.toInt())
        }

        binding.notificationQuietHoursSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateQuietHoursState(isChecked)
        }

        binding.quietHoursStartButton.setOnClickListener {
            showTimePicker(quietHoursStart) { selectedTime ->
                quietHoursStart = selectedTime
                updateQuietHoursButtons()
            }
        }

        binding.quietHoursEndButton.setOnClickListener {
            showTimePicker(quietHoursEnd) { selectedTime ->
                quietHoursEnd = selectedTime
                updateQuietHoursButtons()
            }
        }

        binding.saveSettingsBtn.setOnClickListener {
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

        binding.scanQrButton.text = getString(R.string.parent_pairing_invite_family_button)
        binding.showQrCodeBtn.text = getString(R.string.parent_pairing_invite_device_button)

        binding.scanQrButton.setOnClickListener {
            startActivity(Intent(this, FamilyInviteActivity::class.java))
        }

        binding.selectChildButton.setOnClickListener {
            showLinkedChildPicker(binding.childDeviceIdInput.text?.toString()?.trim().orEmpty()) { option ->
                binding.childDeviceIdInput.setText(option.deviceId)
            }
        }

        binding.editChildCardButton.setOnClickListener {
            openSelectedChildEditor()
        }

        binding.showQrCodeBtn.setOnClickListener {
            startActivity(Intent(this, FamilyInviteActivity::class.java))
        }

        binding.useVpsBtn.setOnClickListener {
            binding.serverUrlInput.setText(VPS_URL)
            Toast.makeText(this, R.string.settings_toast_vps_set, Toast.LENGTH_SHORT).show()
        }

        binding.useLocalhostBtn.setOnClickListener {
            binding.serverUrlInput.setText(LOCALHOST_URL)
            Toast.makeText(this, R.string.settings_toast_local_set, Toast.LENGTH_SHORT).show()
        }

        binding.resetSettingsBtn.setOnClickListener {
            showResetConfirmation()
        }

        binding.revokeConsentBtn.setOnClickListener {
            showRevokeConsentConfirmation()
        }

        binding.aboutBtn.setOnClickListener {
            openAboutScreen()
        }

        binding.testNotificationButton.setOnClickListener {
            ChatNotificationManager.showPreviewNotification(this, buildNotificationSettings())
            Toast.makeText(this, getString(R.string.notification_preview_sent), Toast.LENGTH_SHORT)
                .show()
        }

        binding.openNotificationSettingsButton.setOnClickListener {
            openSystemNotificationSettings()
        }

        binding.shareParentLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndRequestBackgroundLocationPermission()
            }
        }
    }

    private fun loadSettings() {
        val locationInterval = prefs.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
        val audioDuration = prefs.getInt(KEY_AUDIO_DURATION, DEFAULT_AUDIO_DURATION)
        val serverUrl = effectiveContextResolver.resolveServerUrl().ifBlank {
            secureSettings.getServerUrl()
        }
        val childDeviceId = effectiveContextResolver.resolveFocusedChildId().ifBlank {
            prefs.getString(KEY_CHILD_DEVICE_ID, "")
        }

        binding.locationIntervalInput.setText(locationInterval.toString())
        binding.audioDurationInput.setText(audioDuration.toString())
        binding.serverUrlInput.setText(serverUrl)
        binding.childDeviceIdInput.setText(childDeviceId)

        binding.locationMonitoringSwitch.isChecked = prefs.getBoolean(KEY_LOCATION_ENABLED, true)
        binding.audioMonitoringSwitch.isChecked = prefs.getBoolean(KEY_AUDIO_ENABLED, true)
        binding.photoMonitoringSwitch.isChecked = prefs.getBoolean(KEY_PHOTO_ENABLED, false)
        binding.shareParentLocationSwitch.isChecked = prefs.getBoolean(KEY_SHARE_PARENT_LOCATION, true)

        val notificationPrefs = getSharedPreferences(NOTIFICATION_PREFS_NAME, MODE_PRIVATE)
        val notificationDuration = notificationPrefs.getInt("notification_duration", 10000) / 1000
        val notificationSize = notificationPrefs.getString("notification_size", "expanded") ?: "expanded"
        val notificationPriority = notificationPrefs.getInt("notification_priority", 2)
        val notificationSound = notificationPrefs.getBoolean("notification_sound", true)
        val notificationVibration = notificationPrefs.getBoolean("notification_vibration", true)
        val notificationBadge = notificationPrefs.getBoolean("notification_badge", true)
        val notificationPreview = notificationPrefs.getString("notification_preview", "public") ?: "public"
        val notificationQuietHoursEnabled =
            notificationPrefs.getBoolean("notification_quiet_hours_enabled", false)
        quietHoursStart =
            notificationPrefs.getString(
                "notification_quiet_hours_start",
                DEFAULT_QUIET_HOURS_START
            ) ?: DEFAULT_QUIET_HOURS_START
        quietHoursEnd =
            notificationPrefs.getString(
                "notification_quiet_hours_end",
                DEFAULT_QUIET_HOURS_END
            ) ?: DEFAULT_QUIET_HOURS_END

        binding.notificationDurationSlider.value = notificationDuration.toFloat()
        binding.durationValueText.text = formatNotificationDuration(notificationDuration)
        binding.notificationPrioritySlider.value = notificationPriority.toFloat()
        binding.priorityValueText.text = notificationPriorityLabel(notificationPriority)
        binding.notificationSoundSwitch.isChecked = notificationSound
        binding.notificationVibrationSwitch.isChecked = notificationVibration
        binding.notificationBadgeSwitch.isChecked = notificationBadge
        binding.notificationQuietHoursSwitch.isChecked = notificationQuietHoursEnabled
        updateQuietHoursButtons()
        updateQuietHoursState(notificationQuietHoursEnabled)
        updateProfileSummary()

        if (notificationSize == "compact") {
            binding.notificationSizeCompact.isChecked = true
        } else {
            binding.notificationSizeExpanded.isChecked = true
        }

        if (notificationPreview == "private") {
            binding.notificationPreviewPrivate.isChecked = true
        } else {
            binding.notificationPreviewPublic.isChecked = true
        }

        Log.d(TAG, "Settings loaded")
    }

    private fun openSelectedChildEditor() {
        val childDeviceId = binding.childDeviceIdInput.text?.toString()?.trim().orEmpty()
        if (childDeviceId.isBlank()) {
            Toast.makeText(this, R.string.main_toast_select_child_first_to_edit, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(this, ChildSelectionActivity::class.java).apply {
                putExtra(ChildSelectionActivity.EXTRA_EDIT_CHILD_ID, childDeviceId)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open child card editor", e)
            Toast.makeText(
                this,
                getString(R.string.main_toast_launch_error, e.message ?: "unknown"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveSettings() {
        try {
            val locationInterval = binding.locationIntervalInput.text.toString().toIntOrNull()
            val audioDuration = binding.audioDurationInput.text.toString().toIntOrNull()
            val serverUrl = binding.serverUrlInput.text.toString().trim()
            val childDeviceId = binding.childDeviceIdInput.text.toString().trim()

            if (locationInterval == null || locationInterval < 10 || locationInterval > 300) {
                Toast.makeText(this, R.string.settings_validation_location_interval, Toast.LENGTH_LONG).show()
                return
            }

            if (audioDuration == null || audioDuration < 5 || audioDuration > 60) {
                Toast.makeText(this, R.string.settings_validation_audio_duration, Toast.LENGTH_LONG).show()
                return
            }

            if (serverUrl.isEmpty() || (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://"))) {
                Toast.makeText(this, R.string.settings_validation_server_url, Toast.LENGTH_LONG).show()
                return
            }

            prefs.edit()
                .putInt(KEY_LOCATION_INTERVAL, locationInterval)
                .putInt(KEY_AUDIO_DURATION, audioDuration)
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_CHILD_DEVICE_ID, childDeviceId)
                .putBoolean(KEY_LOCATION_ENABLED, binding.locationMonitoringSwitch.isChecked)
                .putBoolean(KEY_AUDIO_ENABLED, binding.audioMonitoringSwitch.isChecked)
                .putBoolean(KEY_PHOTO_ENABLED, binding.photoMonitoringSwitch.isChecked)
                .putBoolean(KEY_SHARE_PARENT_LOCATION, binding.shareParentLocationSwitch.isChecked)
                .apply()

            val notificationPrefs = getSharedPreferences(NOTIFICATION_PREFS_NAME, MODE_PRIVATE)
            val notificationSettings = buildNotificationSettings()
            notificationPrefs.edit()
                .putInt("notification_duration", notificationSettings.durationMs)
                .putString("notification_size", notificationSettings.size)
                .putInt("notification_priority", notificationSettings.priority)
                .putBoolean("notification_sound", notificationSettings.enableSound)
                .putBoolean("notification_vibration", notificationSettings.enableVibration)
                .putBoolean("notification_badge", notificationSettings.showBadge)
                .putString("notification_preview", notificationSettings.previewMode)
                .putBoolean("notification_quiet_hours_enabled", notificationSettings.quietHoursEnabled)
                .putString("notification_quiet_hours_start", notificationSettings.quietHoursStart)
                .putString("notification_quiet_hours_end", notificationSettings.quietHoursEnd)
                .apply()

            ChatNotificationManager.createNotificationChannels(this, notificationSettings)
            secureSettings.setServerUrl(serverUrl)
            syncActiveSessionFromSettings(serverUrlOverride = serverUrl, childIdOverride = childDeviceId)

            Log.d(
                TAG,
                "Settings saved: interval=$locationInterval, audio=$audioDuration, url=$serverUrl, " +
                    "notif=${notificationSettings.durationMs}, size=${notificationSettings.size}, " +
                    "priority=${notificationSettings.priority}, sound=${notificationSettings.enableSound}, " +
                    "vibration=${notificationSettings.enableVibration}, badge=${notificationSettings.showBadge}, " +
                    "preview=${notificationSettings.previewMode}"
            )
            Toast.makeText(this, getString(R.string.notification_settings_saved), Toast.LENGTH_SHORT)
                .show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings", e)
            Toast.makeText(this, R.string.settings_save_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setPositiveButton(R.string.settings_reset_confirm) { _, _ ->
                resetToDefaults()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSaveProfileDialog() {
        showProfileEditorDialog(profileManager.getActiveProfile())
    }

    private fun showProfilePicker() {
        showProfileManagementDialog()
    }

    private fun showProfileManagementDialog() {
        lifecycleScope.launch {
            val profiles = loadProfilesAfterRelationshipSync()
            if (profiles.isEmpty()) {
                showProfileEditorDialog(null)
                return@launch
            }

            val items = profiles.map(::formatProfilePickerItem).toTypedArray()
            AlertDialog.Builder(this@SettingsActivity)
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
    }

    private fun showProfileActionsDialog(profile: ParentMonitorProfile) {
        val activeProfileId = profileManager.getActiveProfile()?.id ?: profileManager.getActiveProfileId()
        val labels = mutableListOf(
            getString(R.string.profile_switch_apply),
            getString(R.string.profile_switch_edit_profile)
        )
        val allowDelete = profile.id != activeProfileId
        if (allowDelete) {
            labels += getString(R.string.profile_switch_delete)
        }

        AlertDialog.Builder(this)
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

    private fun confirmDeleteProfile(profile: ParentMonitorProfile) {
        AlertDialog.Builder(this)
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

    private fun showProfileEditorDialog(existingProfile: ParentMonitorProfile?) {
        val currentOwnId = existingProfile?.ownParentDeviceId?.ifBlank { null }
            ?: effectiveContextResolver.resolveOwnParentId().ifBlank { profileManager.resolveCurrentParentId() }
        val currentChildId = existingProfile?.linkedChildDeviceId?.ifBlank { null }
            ?: binding.childDeviceIdInput.text?.toString()?.trim().orEmpty()
                .ifBlank { effectiveContextResolver.resolveFocusedChildId().ifBlank { profileManager.resolveCurrentChildId() } }
        val currentServerUrl = existingProfile?.serverUrl?.ifBlank { null }
            ?: binding.serverUrlInput.text?.toString()?.trim().orEmpty()
                .ifBlank { effectiveContextResolver.resolveServerUrl().ifBlank { profileManager.resolveCurrentServerUrl() } }
        val currentChildName = existingProfile?.linkedChildDisplayName?.ifBlank { null }
            ?: profileManager.resolveLinkedChildDisplayName(
                childDeviceId = currentChildId,
                serverUrl = currentServerUrl,
                ownParentDeviceId = currentOwnId
            )
        val suggestedName = existingProfile?.name
            ?.takeUnless { it == getString(R.string.profile_switch_current_name) }
            ?: profileManager.buildSuggestedProfileName(currentChildName, currentChildId)

        val nameInput = createProfileInput(getString(R.string.profile_switch_name_hint), suggestedName)
        val serverInput = createProfileInput(getString(R.string.profile_switch_server_hint), currentServerUrl)
        val ownIdInput = createProfileInput(getString(R.string.profile_switch_own_parent_id_hint), currentOwnId)
        val childIdInput = createProfileInput(getString(R.string.profile_switch_linked_child_id_hint), currentChildId)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingProfile == null) R.string.profile_switch_name_title else R.string.profile_switch_edit_title)
            .setView(createProfileDialogLayout(nameInput, serverInput, ownIdInput, childIdInput))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.profile_switch_pick_child, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val previousChildId = childIdInput.text?.toString()?.trim().orEmpty()
                showLinkedChildPicker(childIdInput.text?.toString()?.trim().orEmpty()) { option ->
                    childIdInput.setText(option.deviceId)
                    maybeApplySuggestedProfileName(
                        nameInput = nameInput,
                        selectedChild = option,
                        previousChildId = previousChildId,
                        existingProfile = existingProfile
                    )
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val serverUrl = serverInput.text?.toString()?.trim().orEmpty()
                val ownId = ownIdInput.text?.toString()?.trim().orEmpty()
                val childId = childIdInput.text?.toString()?.trim().orEmpty()
                val childDisplayName = profileManager.resolveLinkedChildDisplayName(
                    childDeviceId = childId,
                    serverUrl = serverUrl,
                    ownParentDeviceId = ownId
                )

                when {
                    name.isBlank() -> Toast.makeText(this, R.string.profile_switch_validation_name, Toast.LENGTH_SHORT).show()
                    serverUrl.isBlank() || (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) ->
                        Toast.makeText(this, R.string.profile_switch_validation_server, Toast.LENGTH_SHORT).show()
                    ownId.isBlank() -> Toast.makeText(this, R.string.profile_switch_validation_own_id, Toast.LENGTH_SHORT).show()
                    else -> {
                        val profile = existingProfile?.copy(
                            name = name,
                            serverUrl = serverUrl,
                            ownParentDeviceId = ownId,
                            linkedChildDeviceId = childId,
                            linkedChildDisplayName = childDisplayName,
                            updatedAt = System.currentTimeMillis()
                        ) ?: profileManager.buildProfile(name, serverUrl, ownId, childId, childDisplayName)
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

    private fun showLinkedChildPicker(
        selectedDeviceId: String,
        onSelected: (ParentLinkedChildOption) -> Unit
    ) {
        lifecycleScope.launch {
            val options = runCatching { linkedChildOptionsProvider.getOptions() }
                .getOrElse { error ->
                    Log.e(TAG, "Failed to load linked child options", error)
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.profile_switch_pick_child_error,
                        Toast.LENGTH_SHORT
                    ).show()
                    emptyList()
                }
            if (options.isEmpty()) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.profile_switch_pick_child_empty,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val items = options.map(::formatLinkedChildOption).toTypedArray()
            val selectedIndex = options.indexOfFirst { it.deviceId == selectedDeviceId }.coerceAtLeast(0)
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.profile_switch_pick_child)
                .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                    onSelected(options[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun formatLinkedChildOption(option: ParentLinkedChildOption): String {
        return buildString {
            append(option.displayName)
            append('\n')
            append(formatProfileId(option.deviceId))
            append(" • ")
            append(
                getString(
                    if (option.source == "linked") {
                        R.string.profile_switch_source_linked
                    } else {
                        R.string.profile_switch_source_local
                    }
                )
            )
        }
    }

    private fun applyProfile(profile: ParentMonitorProfile) {
        val appliedContext = profileRuntimeCoordinator.applyProfile(
            profile = profile,
            shareParentLocation = binding.shareParentLocationSwitch.isChecked
        )
        val appliedServerUrl = appliedContext.serverUrl.ifBlank { profile.serverUrl }
        val appliedChildId = appliedContext.linkedChildDeviceId.ifBlank { profile.linkedChildDeviceId }
        binding.serverUrlInput.setText(appliedServerUrl)
        binding.childDeviceIdInput.setText(appliedChildId)
        updateProfileSummary()

        Toast.makeText(this, R.string.profile_switch_applied, Toast.LENGTH_SHORT).show()
    }

    private fun updateProfileSummary() {
        val activeProfile = profileManager.getActiveProfile()
        val effectiveContext = effectiveContextResolver.resolve()
        val ownParentId = activeProfile?.ownParentDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext.ownParentDeviceId.takeIf { it.isNotBlank() }
        val childId = activeProfile?.linkedChildDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext.linkedChildDeviceId.takeIf { it.isNotBlank() }
        val serverUrl = activeProfile?.serverUrl?.takeIf { it.isNotBlank() }
            ?: effectiveContext.serverUrl.takeIf { it.isNotBlank() }
        val profileName = activeProfile?.name?.takeIf { it.isNotBlank() }
            ?: activeSessionStore.getSession()?.profileName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_switch_current_name)
        val selfNameLine = getString(
            R.string.participant_self_name_summary_line,
            participantNameResolver.resolveOwnParentDisplayName()
        )
        val selfMarkerLine = getString(
            R.string.participant_self_marker_summary_line,
            ContactIcons.labelFor(participantNameResolver.resolveOwnParentMarkerIconId())
        )

        if (ownParentId.isNullOrBlank() || serverUrl.isNullOrBlank()) {
            binding.profileSummaryText.text = getString(R.string.profile_switch_no_active)
            return
        }

        binding.profileSummaryText.text = getString(
            R.string.profile_switch_summary_format,
            profileName,
            formatProfileServer(serverUrl),
            formatProfileId(ownParentId),
            formatChildReference(
                rawChildId = childId?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.profile_switch_unknown_link),
                childDisplayName = activeProfile?.linkedChildDisplayName?.ifBlank { null }
                    ?: profileManager.resolveLinkedChildDisplayName(
                        childDeviceId = childId.orEmpty(),
                        serverUrl = serverUrl,
                        ownParentDeviceId = ownParentId
                    )
            )
        ) + "\n" + selfNameLine + "\n" + selfMarkerLine + "\n" + getString(
            R.string.profile_switch_source_line,
            describeProfileContextSource(effectiveContext.source)
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
        ) + (buildCachedLinkedParentsLine()?.let { "\n$it" } ?: "") + if (isProfileContextMismatched(activeProfile, effectiveContext.serverUrl, effectiveContext.ownParentDeviceId, effectiveContext.linkedChildDeviceId)) {
            "\n" + getString(R.string.profile_switch_warning_mismatch)
        } else {
            ""
        }
    }

    private fun buildCanonicalContextDiagnosticsLine(): String {
        val snapshot = ParentContextDiagnostics(this).snapshot()
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
            getString(R.string.participant_self_name_hint_parent),
            participantNameResolver.resolveOwnParentDisplayName()
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.participant_self_name_title_parent)
            .setView(createProfileDialogLayout(input))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    input.error = getString(R.string.participant_self_name_error_empty)
                    return@setOnClickListener
                }
                input.error = null
                dialog.dismiss()
                saveOwnParentDisplayName(newName)
            }
        }

        dialog.show()
    }

    private fun showEditOwnMarkerDialog() {
        val options = ContactIcons.options()
        var selectedIndex = options.indexOfFirst {
            it.id == participantNameResolver.resolveOwnParentMarkerIconId()
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.participant_self_marker_title_parent)
            .setSingleChoiceItems(
                options.map { it.label }.toTypedArray(),
                selectedIndex
            ) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveOwnParentMarkerIcon(options[selectedIndex].id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveOwnParentDisplayName(newName: String) {
        lifecycleScope.launch {
            prefs.edit()
                .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, newName)
                .putString(KEY_LINKED_PARENT_SELF_LABEL, newName)
                .apply()

            updateLocalParentName(newName)
            val synced = syncOwnParentNameToServer(newName)
            syncLinkedProfilesInBackground()
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

    private fun saveOwnParentMarkerIcon(iconId: Int) {
        lifecycleScope.launch {
            prefs.edit()
                .putInt(ParentParticipantNameResolver.KEY_SELF_MARKER_ICON_ID, iconId)
                .apply()

            val synced = syncOwnParentMarkerToServer(iconId)
            syncLinkedProfilesInBackground()
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

    private suspend fun updateLocalParentName(newName: String) {
        withContext(Dispatchers.IO) {
            val onboardingPrefs = getSharedPreferences(PARENT_ONBOARDING_PREFS, MODE_PRIVATE)
            val storedParentId = onboardingPrefs.getLong(KEY_PARENT_ID, 0L)
            val existingParent = if (storedParentId > 0) {
                database.parentDao().getById(storedParentId)
            } else {
                database.parentDao().getAll().firstOrNull()
            } ?: return@withContext

            database.parentDao().update(
                existingParent.copy(
                    name = newName,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun syncOwnParentNameToServer(newName: String): Boolean {
        val ownParentId = effectiveContextResolver.resolveOwnParentId().ifBlank {
            profileManager.resolveCurrentParentId()
        }.trim()
        if (ownParentId.isBlank()) return true

        val linkedChildrenResponse = runCatching {
            networkClient.getLinkedChildren(ownParentId)
        }.getOrNull() ?: return false
        if (!linkedChildrenResponse.isSuccessful) return false

        val childIds = linkedChildrenResponse.body()
            ?.children
            .orEmpty()
            .map { it.childDeviceId.trim() }
            .filter { it.isNotBlank() }
            .toMutableSet()

        binding.childDeviceIdInput.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(childIds::add)

        if (childIds.isEmpty()) return true

        var allSuccessful = true
        childIds.forEach { childId ->
            val response = runCatching {
                networkClient.linkParentChild(
                    parentDeviceId = ownParentId,
                    childDeviceId = childId,
                    parentDisplayName = newName
                )
            }.getOrNull()
            if (response?.isSuccessful != true) {
                allSuccessful = false
            }
        }

        return allSuccessful
    }

    private suspend fun syncOwnParentMarkerToServer(iconId: Int): Boolean {
        val ownParentId = effectiveContextResolver.resolveOwnParentId().ifBlank {
            profileManager.resolveCurrentParentId()
        }.trim()
        if (ownParentId.isBlank()) return true

        val linkedChildrenResponse = runCatching {
            networkClient.getLinkedChildren(ownParentId)
        }.getOrNull()
        val linkedChildren = linkedChildrenResponse?.body()?.children.orEmpty()
        if (linkedChildren.isEmpty()) return true

        var allSuccessful = true
        linkedChildren.forEach { child ->
            val response = runCatching {
                networkClient.linkParentChild(
                    parentDeviceId = ownParentId,
                    childDeviceId = child.childDeviceId,
                    parentMarkerIconId = iconId
                )
            }.getOrNull()
            if (response?.isSuccessful != true) {
                allSuccessful = false
            }
        }
        return allSuccessful
    }

    private fun describeProfileContextSource(source: String): String {
        return when (source.trim().lowercase(Locale.ROOT)) {
            "session" -> getString(R.string.profile_switch_source_session)
            "legacy" -> getString(R.string.profile_switch_source_legacy)
            "empty" -> getString(R.string.profile_switch_source_unknown)
            else -> getString(R.string.profile_switch_source_current)
        }
    }

    private fun isProfileContextMismatched(
        activeProfile: ParentMonitorProfile?,
        effectiveServerUrl: String,
        effectiveOwnParentId: String,
        effectiveChildId: String
    ): Boolean {
        if (activeProfile == null) return false
        return activeProfile.serverUrl != effectiveServerUrl ||
            activeProfile.ownParentDeviceId != effectiveOwnParentId ||
            activeProfile.linkedChildDeviceId != effectiveChildId
    }

    private fun syncActiveSessionFromSettings(
        serverUrlOverride: String? = null,
        childIdOverride: String? = null
    ) {
        val serverUrl = serverUrlOverride?.trim().orEmpty().ifBlank {
            effectiveContextResolver.resolveServerUrl().ifBlank { secureSettings.getServerUrl() }
        }
        val ownParentId = effectiveContextResolver.resolveOwnParentId().ifBlank {
            profileManager.resolveCurrentParentId()
        }
        if (serverUrl.isBlank() || ownParentId.isBlank()) return

        val linkedChildId = childIdOverride?.trim().orEmpty().ifBlank {
            effectiveContextResolver.resolveFocusedChildId().ifBlank {
                profileManager.resolveCurrentChildId()
            }
        }
        val currentSession = activeSessionStore.getSession()
        activeSessionStore.setSession(
            ParentActiveSession(
                profileId = currentSession?.profileId
                    ?: ParentActiveSession.buildDerivedProfileId(serverUrl, ownParentId, linkedChildId),
                profileName = currentSession?.profileName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.profile_switch_current_name),
                serverUrl = serverUrl,
                ownParentDeviceId = ownParentId,
                linkedChildDeviceId = linkedChildId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun formatProfileServer(serverUrl: String): String {
        val parsedHost = runCatching { Uri.parse(serverUrl).host }.getOrNull()
        return (parsedHost ?: serverUrl).removePrefix("www.")
    }

    private fun formatProfilePickerItem(profile: ParentMonitorProfile): String {
        val linkedChild = profile.linkedChildDeviceId.ifBlank {
            getString(R.string.profile_switch_unknown_link)
        }
        return buildString {
            append(profile.name)
            append('\n')
            append(formatProfileServer(profile.serverUrl))
            append(" | ")
            append(formatProfileId(profile.ownParentDeviceId))
            append(" -> ")
            append(formatChildReference(linkedChild, profile.linkedChildDisplayName))
        }
    }

    private fun formatProfileId(rawId: String): String {
        return if (rawId.length <= 16) rawId else "${rawId.take(8)}...${rawId.takeLast(4)}"
    }

    private suspend fun loadProfilesAfterRelationshipSync(): List<ParentMonitorProfile> {
        syncLinkedProfilesInBackground()
        return profileManager.getSavedProfiles()
    }

    private suspend fun syncLinkedProfilesInBackground() {
        val options = runCatching { linkedChildOptionsProvider.getOptions() }
            .getOrElse { error ->
                Log.w(TAG, "Unable to sync relationship-backed profiles", error)
                return
            }

        if (options.isNotEmpty()) {
            linkedChildOptionsProvider.syncLocalChildren(options)
        }
        if (options.isNotEmpty() && profileManager.syncLinkedChildProfiles(options) > 0) {
            updateProfileSummary()
        }

        syncLinkedParentsContextInBackground()
    }

    private suspend fun syncLinkedParentsContextInBackground() {
        val childId = effectiveContextResolver.resolveFocusedChildId().ifBlank {
            profileManager.resolveCurrentChildId()
        }
        val ownParentId = effectiveContextResolver.resolveOwnParentId().ifBlank {
            profileManager.resolveCurrentParentId()
        }
        if (childId.isBlank() || ownParentId.isBlank()) return

        val linkedParents = runCatching { linkedParentsProvider.getOptions(childId) }
            .getOrElse { error ->
                Log.w(TAG, "Unable to sync linked parents for child context", error)
                return
            }
        if (linkedParents.isEmpty()) return

        cacheLinkedParentsSnapshot(linkedParents, ownParentId)
        updateProfileSummary()
    }

    private fun cacheLinkedParentsSnapshot(
        linkedParents: List<ParentLinkedParentOption>,
        ownParentId: String
    ) {
        val labels = linkedParents
            .map { it.displayName }
            .filter { it.isNotBlank() }
            .distinct()

        val preview = when {
            labels.isEmpty() -> ""
            labels.size <= 3 -> labels.joinToString(", ")
            else -> labels.take(3).joinToString(", ") + " +${labels.size - 3}"
        }

        val selfLabel = linkedParents.firstOrNull { it.parentDeviceId == ownParentId }?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: ownParentId

        prefs.edit()
            .putInt(KEY_LINKED_PARENT_COUNT, linkedParents.size)
            .putString(KEY_LINKED_PARENT_LABELS, preview)
            .putString(KEY_LINKED_PARENT_SELF_LABEL, selfLabel)
            .apply()
    }

    private fun buildCachedLinkedParentsLine(): String? {
        val count = prefs.getInt(KEY_LINKED_PARENT_COUNT, 0)
        if (count <= 0) return null

        val labels = prefs.getString(KEY_LINKED_PARENT_LABELS, null).orEmpty()
        val selfLabel = prefs.getString(KEY_LINKED_PARENT_SELF_LABEL, null).orEmpty()
        val familyLine = if (labels.isNotBlank()) {
            getString(R.string.profile_family_parents_named, count, labels)
        } else {
            getString(R.string.profile_family_parents_count, count)
        }

        return if (selfLabel.isNotBlank()) {
            familyLine + "\n" + getString(R.string.profile_family_current_parent, selfLabel)
        } else {
            familyLine
        }
    }

    private fun maybeApplySuggestedProfileName(
        nameInput: EditText,
        selectedChild: ParentLinkedChildOption,
        previousChildId: String,
        existingProfile: ParentMonitorProfile?
    ) {
        val currentName = nameInput.text?.toString()?.trim().orEmpty()
        val previousLabel = existingProfile?.linkedChildDisplayName
            ?.takeIf { it.isNotBlank() }
            ?: profileManager.resolveLinkedChildDisplayName(previousChildId)
        val previousSuggestedName = profileManager.buildSuggestedProfileName(previousLabel.orEmpty(), previousChildId)
        val newSuggestedName = profileManager.buildSuggestedProfileName(
            linkedChildDisplayName = selectedChild.displayName,
            linkedChildDeviceId = selectedChild.deviceId
        )

        if (currentName.isBlank() || currentName == previousSuggestedName) {
            nameInput.setText(newSuggestedName)
        }
    }

    private fun formatChildReference(rawChildId: String, childDisplayName: String?): String {
        val normalizedChildId = rawChildId.trim()
        val normalizedDisplayName = childDisplayName?.trim().orEmpty()
        if (normalizedDisplayName.isBlank() || normalizedDisplayName == normalizedChildId) {
            return formatProfileId(normalizedChildId)
        }
        return "$normalizedDisplayName (${formatProfileId(normalizedChildId)})"
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
    
    private fun resetToDefaults() {
        // Reset to default values
        binding.locationIntervalInput.setText(DEFAULT_LOCATION_INTERVAL.toString())
        binding.audioDurationInput.setText(DEFAULT_AUDIO_DURATION.toString())
        binding.serverUrlInput.setText(DEFAULT_SERVER_URL)
        
        binding.locationMonitoringSwitch.isChecked = true
        binding.audioMonitoringSwitch.isChecked = true
        binding.photoMonitoringSwitch.isChecked = false
        
        Log.d(TAG, "Settings reset to defaults")
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
    }
    
    private fun showRevokeConsentConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_revoke_title)
            .setMessage(R.string.settings_revoke_message)
            .setPositiveButton(R.string.settings_revoke_confirm) { _, _ ->
                revokeConsent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    
    private fun revokeConsent() {
        // Revoke consent
        ConsentActivity.revokeConsent(this)
        
        // Stop monitoring service if running
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_MONITORING
        }
        startService(intent)
        
        Log.d(TAG, "Consent revoked")
        Toast.makeText(this, R.string.settings_revoke_done, Toast.LENGTH_LONG).show()
        
        // Return to consent screen
        val consentIntent = Intent(this, ConsentActivity::class.java)
        consentIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(consentIntent)
        finish()
    }
    
    private fun openAboutScreen() {
        val intent = Intent(this, AboutActivity::class.java)
        startActivity(intent)
    }

    private fun buildNotificationSettings(): ChatNotificationManager.ChatNotificationSettings {
        return ChatNotificationManager.ChatNotificationSettings(
            durationMs = binding.notificationDurationSlider.value.toInt() * 1000,
            priority = binding.notificationPrioritySlider.value.toInt(),
            size = if (binding.notificationSizeCompact.isChecked) "compact" else "expanded",
            enableSound = binding.notificationSoundSwitch.isChecked,
            enableVibration = binding.notificationVibrationSwitch.isChecked,
            showBadge = binding.notificationBadgeSwitch.isChecked,
            previewMode = if (binding.notificationPreviewPrivate.isChecked) "private" else "public",
            quietHoursEnabled = binding.notificationQuietHoursSwitch.isChecked,
            quietHoursStart = quietHoursStart,
            quietHoursEnd = quietHoursEnd
        )
    }

    private fun updateQuietHoursState(enabled: Boolean) {
        binding.notificationQuietHoursButtons.isVisible = enabled
    }

    private fun updateQuietHoursButtons() {
        binding.quietHoursStartButton.text =
            getString(R.string.notification_quiet_hours_start, quietHoursStart)
        binding.quietHoursEndButton.text =
            getString(R.string.notification_quiet_hours_end, quietHoursEnd)
    }

    private fun showTimePicker(initialValue: String, onSelected: (String) -> Unit) {
        val (hour, minute) = parseQuietHoursTime(initialValue)
        TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                onSelected(formatQuietHoursTime(selectedHour, selectedMinute))
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun parseQuietHoursTime(value: String): Pair<Int, Int> {
        val parts = value.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour to minute
    }

    private fun formatQuietHoursTime(hour: Int, minute: Int): String {
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun formatNotificationDuration(seconds: Int): String {
        return getString(R.string.notification_duration_value, seconds)
    }

    private fun notificationPriorityLabel(priority: Int): String {
        return when (priority) {
            0 -> getString(R.string.notification_priority_low)
            1 -> getString(R.string.notification_priority_medium)
            else -> getString(R.string.notification_priority_high)
        }
    }

    private fun openSystemNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.notification_settings_open_error), Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onBackPressed() {
        // Check if settings were modified
        val currentLocationInterval = binding.locationIntervalInput.text.toString().toIntOrNull()
        val currentAudioDuration = binding.audioDurationInput.text.toString().toIntOrNull()
        val currentServerUrl = binding.serverUrlInput.text.toString().trim()
        
        val savedLocationInterval = prefs.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
        val savedAudioDuration = prefs.getInt(KEY_AUDIO_DURATION, DEFAULT_AUDIO_DURATION)
        val savedServerUrl = secureSettings.getServerUrl()
        
        val settingsChanged = (currentLocationInterval != savedLocationInterval ||
                currentAudioDuration != savedAudioDuration ||
                currentServerUrl != savedServerUrl ||
                binding.locationMonitoringSwitch.isChecked != prefs.getBoolean(KEY_LOCATION_ENABLED, true) ||
                binding.audioMonitoringSwitch.isChecked != prefs.getBoolean(KEY_AUDIO_ENABLED, true) ||
                binding.photoMonitoringSwitch.isChecked != prefs.getBoolean(KEY_PHOTO_ENABLED, false))
        
        if (settingsChanged) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_unsaved_title)
                .setMessage(R.string.settings_unsaved_message)
                .setPositiveButton(R.string.settings_unsaved_save) { _, _ ->
                    saveSettings()
                }
                .setNegativeButton(R.string.settings_unsaved_discard) { _, _ ->
                    super.onBackPressed()
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
    
    /**
     * Check and request background location permission for Android 10+
     */
    private fun checkAndRequestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!PermissionHelper.hasBackgroundLocationPermission(this)) {
                // Show explanation dialog first
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_background_permission_title)
                    .setMessage(R.string.settings_background_permission_message)
                    .setPositiveButton(R.string.settings_background_permission_grant) { _, _ ->
                        requestBackgroundLocationPermission()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        // Disable switch if permission denied
                        binding.shareParentLocationSwitch.isChecked = false
                    }
                    .show()
            }
        }
        // For Android 9 and below, background location is included in fine/coarse location
    }
    
    /**
     * Request background location permission
     */
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                PermissionHelper.REQUEST_CODE_BACKGROUND_LOCATION
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PermissionHelper.REQUEST_CODE_BACKGROUND_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    R.string.settings_background_permission_granted,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Permission denied
                binding.shareParentLocationSwitch.isChecked = false
                Toast.makeText(
                    this,
                    R.string.settings_background_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
