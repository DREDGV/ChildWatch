package ru.example.childwatch

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import ru.example.childwatch.alerts.CriticalAlertSyncScheduler
import ru.example.childwatch.attention.ParentAttentionSignalLauncher
import ru.example.childwatch.databinding.ActivityMainMenuBinding
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.remote.RemotePhotoCache
import ru.example.childwatch.remote.RemotePhotoErrorMessages
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.service.ParentLocationService
import ru.example.childwatch.utils.BatteryOptimizationHelper
import ru.example.childwatch.utils.ParentMonitorProfile
import ru.example.childwatch.utils.ParentMonitorProfileManager
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecurityChecker
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentLinkedChildOption
import ru.example.childwatch.profile.ParentLinkedChildOptionsProvider
import ru.example.childwatch.profile.ParentLinkedParentOption
import ru.example.childwatch.profile.ParentLinkedParentsProvider
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.profile.ParentProfileRuntimeCoordinator
import ru.example.childwatch.chat.ChatManager
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.contacts.ContactFeatures
import ru.example.childwatch.contacts.ContactRoles
import ru.example.childwatch.database.entity.Child
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity with modern menu interface
 * 
 * ChildWatch v4.4.0 - Parental Monitoring Application
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
    private lateinit var profileManager: ParentMonitorProfileManager
    private lateinit var batteryOptimizationHelper: BatteryOptimizationHelper
    private lateinit var chatManager: ChatManager
    private val effectiveContextResolver by lazy { ParentEffectiveContextResolver(this) }
    private val contextProvider by lazy { ParentEffectiveContextProvider.get(this) }
    private val activeSessionStore by lazy { ParentActiveSessionStore(this) }
    private val linkedChildOptionsProvider by lazy { ParentLinkedChildOptionsProvider(this) }
    private val linkedParentsProvider by lazy { ParentLinkedParentsProvider(this) }
    private val participantNameResolver by lazy { ParentParticipantNameResolver(this) }
    private val profileRuntimeCoordinator by lazy { ParentProfileRuntimeCoordinator(this) }
    private var hasConsent = false
    private var batteryOptimizationDialogDisplayed = false
    private val deviceInfoScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val networkClient by lazy { NetworkClient(this) }
    private val gson by lazy { Gson() }
    private var latestDeviceStatus: DeviceStatus? = null
    private var deviceStatusJob: Job? = null
    private var deviceStatusRefreshJob: Job? = null
    private var badgeRefreshJob: Job? = null
    private var lastStatusFetchTime = 0L
    
    // Permission launchers for different permission groups
    private val basicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handleBasicPermissionResults(permissions)
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        handleBackgroundLocationResult(isGranted)
    }

    // Child selection launcher
    private val childSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val deviceId = result.data?.getStringExtra(ChildSelectionActivity.EXTRA_SELECTED_DEVICE_ID)
            if (deviceId != null) {
                updateSelectedChild(deviceId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        secureSettings = SecureSettingsManager(this)
        profileManager = ParentMonitorProfileManager(this)
        batteryOptimizationHelper = BatteryOptimizationHelper(this)
        chatManager = ChatManager(this)
        hasConsent = ConsentActivity.hasConsent(this)

        // Set app version
        binding.appVersionText.text = "v${BuildConfig.VERSION_NAME}"

        setupUI()
        updateQuickProfileSummary()
        updateUIState()
        updateChatBadge()

        // Non-critical subsystems should not be able to crash first launch.
        binding.root.post {
            runStartupTask("performSecurityChecks") { performSecurityChecks() }
            runStartupTask("scheduleCriticalAlertSync") { CriticalAlertSyncScheduler.schedule(this) }
            runStartupTask("ensureChatBackgroundService") { ensureChatBackgroundService() }
            runStartupTask("ensureParentLocationService") { ensureParentLocationService() }
            runStartupTask("initializeWebSocket") { initializeWebSocket() }
        }
    }
    
    /**
     * Initialize WebSocket connection for real-time commands
     */
    private fun initializeWebSocket() {
        try {
            val serverUrl = getConfiguredServerUrl()
            val targetDeviceId = resolveTargetDeviceId()
            
            if (!targetDeviceId.isNullOrBlank() && !serverUrl.isNullOrBlank()) {
                ru.example.childwatch.network.WebSocketManager.initialize(
                    this,
                    serverUrl,
                    targetDeviceId
                )
                ru.example.childwatch.network.WebSocketManager.connect(
                    onConnected = {
                        Log.d(TAG, "WebSocket connected for target: $targetDeviceId")
                    },
                    onError = { error ->
                        Log.e(TAG, "WebSocket connection error: $error")
                    }
                )
            } else {
                Log.w(
                    TAG,
                    "WebSocket init skipped: serverUrlPresent=${!serverUrl.isNullOrBlank()}, targetDeviceId=$targetDeviceId"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebSocket", e)
        }
    }


    private fun setupUI() {
        setupBatteryOptimizationUi()

        binding.switchProfileQuickButton.setOnClickListener {
            showQuickProfilePicker()
        }

        // Unified monitoring toggle button with visual feedback
        binding.monitoringToggleBtn.setOnClickListener {
            // Prevent double-tap by disabling button during state transition
            if (!binding.monitoringToggleBtn.isEnabled) return@setOnClickListener
            
            val isMonitoring = MonitorService.isRunning
            Log.d(TAG, "Monitoring toggle clicked: current state isRunning=$isMonitoring")
            
            // Disable button temporarily to prevent rapid clicks
            binding.monitoringToggleBtn.isEnabled = false
            
            if (isMonitoring) {
                stopMonitoring()
            } else {
                startMonitoring()
            }
            
            // Re-enable after 2 seconds (enough time for service to start/stop)
            binding.monitoringToggleBtn.postDelayed({
                binding.monitoringToggleBtn.isEnabled = true
            }, 2000)
        }

        // Keep old buttons for compatibility (hidden in layout)
        binding.startMonitoringBtn.setOnClickListener {
            startMonitoring()
        }
        
        binding.stopMonitoringBtn.setOnClickListener {
            stopMonitoring()
        }

        // Emergency stop button
        binding.emergencyStopBtn.setOnClickListener {
            showEmergencyStopDialog()
        }

        // Menu card click listeners (unified emerald design)
        // locationCard hidden; using parentLocationCard (DualLocationMapActivity) only

        binding.audioStreamingCard.setOnClickListener {
            val serverUrl = getConfiguredServerUrl()
            if (serverUrl.isNullOrBlank()) {
                showToast(getString(R.string.server_url_missing))
                return@setOnClickListener
            }
            val targetDeviceId = resolveTargetDeviceId()
            if (targetDeviceId.isNullOrBlank()) {
                showDeviceIdOptions(serverUrl)
                return@setOnClickListener
            }
            activeSessionStore.updateFocusedChildId(targetDeviceId)
            val intent = Intent(this@MainActivity, AudioStreamingActivity::class.java).apply {
                putExtra(AudioStreamingActivity.EXTRA_DEVICE_ID, targetDeviceId)
                putExtra(AudioStreamingActivity.EXTRA_SERVER_URL, serverUrl)
            }
            startActivity(intent)
        }

        binding.chatCard.setOnClickListener {
            val targetDeviceId = resolveTargetDeviceId()
            if (targetDeviceId.isNullOrBlank()) {
                showToast(getString(R.string.main_toast_set_child_device_id))
                return@setOnClickListener
            }
            activeSessionStore.updateFocusedChildId(targetDeviceId)
            ru.example.childwatch.utils.NotificationManager.resetUnreadCount()
            try {
                chatManager.markAllAsRead()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark chat messages as read before opening chat", e)
            }
            updateChatBadge()
            val intent = Intent(this@MainActivity, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_TARGET_DEVICE_ID, targetDeviceId)
            }
            startActivity(intent)
        }

        binding.attentionSignalCard.setOnClickListener {
            ParentAttentionSignalLauncher.show(this)
        }
        
        // Р•РґРёРЅСЃС‚РІРµРЅРЅР°СЏ РєРЅРѕРїРєР° РєР°РјРµСЂС‹ - СѓРґР°Р»С‘РЅРЅР°СЏ СЃСЉС‘РјРєР°
        binding.remoteCameraCard.setOnClickListener {
            openRemoteCamera()
        }

        binding.deviceUsageButton.setOnClickListener {
            openDeviceUsage()
        }
        
        // Location map card (legacy mode): parent + selected child
        findViewById<View>(R.id.parentLocationCard)?.setOnClickListener {
            val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            val myId = contextProvider.current()?.selfDeviceId.orEmpty().ifBlank {
                effectiveContextResolver.resolveOwnParentId()
            }.ifBlank {
                listOf(
                    prefs.getString("device_id", null),
                    prefs.getString("parent_device_id", null)
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
            val otherId = resolveTargetDeviceId()
            if (otherId.isNullOrBlank()) {
                showToast(getString(R.string.main_toast_set_child_device_id))
                return@setOnClickListener
            }
            activeSessionStore.updateFocusedChildId(otherId)
            val intent = DualLocationMapActivity.createIntent(
                context = this,
                myRole = DualLocationMapActivity.ROLE_PARENT,
                myId = myId,
                otherId = otherId
            )
            startActivity(intent)
        }
        
        binding.settingsCard.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Child selection - use only container click handler
        try {
            binding.childSelectionContainer.setOnClickListener {
                try {
                    Log.d(TAG, "Child selection clicked - opening device selection")
                    val intent = Intent(this, ChildSelectionActivity::class.java)
                    childSelectionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching ChildSelectionActivity", e)
                    showToast(getString(R.string.main_toast_launch_error, e.message ?: "unknown"))
                }
            }
            binding.childSelectionEditIcon.setOnClickListener {
                openCurrentChildEditor()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up child selection click handler", e)
        }

        // Load and display selected child
        try {
            loadSelectedChild()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading selected child", e)
        }

        // Ensure legacy single-device setups work without manual contact creation
        ensureLegacyContact()
    }
    
    private fun setupBatteryOptimizationUi() {
        binding.disableOptimizationButton.setOnClickListener {
            batteryOptimizationHelper.requestDisableBatteryOptimization()
        }
        binding.openPowerSaverButton.setOnClickListener {
            batteryOptimizationHelper.openPowerSaverSettings()
        }
    }

    private fun showQuickProfilePicker() {
        val actionLabels = arrayOf(
            getString(R.string.profile_switch_apply),
            getString(R.string.profile_switch_save_current),
            getString(R.string.profile_switch_manage)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_switch_manage_title))
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
        lifecycleScope.launch {
            val profiles = loadProfilesAfterRelationshipSync()
            if (profiles.isEmpty()) {
                showToast(getString(R.string.profile_switch_empty))
                return@launch
            }

            val activeId = profileManager.getActiveProfile()?.id ?: profileManager.getActiveProfileId()
            val selectedIndex = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            val items = profiles.map(::formatProfilePickerItem).toTypedArray()

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.profile_switch_select_title))
                .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                    applyQuickProfile(profiles[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showProfileManagementDialog() {
        lifecycleScope.launch {
            val profiles = loadProfilesAfterRelationshipSync()
            if (profiles.isEmpty()) {
                showProfileEditorDialog(null)
                return@launch
            }

            val items = profiles.map(::formatProfilePickerItem).toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.profile_switch_manage_title))
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

        MaterialAlertDialogBuilder(this)
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

    private fun confirmDeleteProfile(profile: ParentMonitorProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_switch_delete_title)
            .setMessage(getString(R.string.profile_switch_delete_message, profile.name))
            .setPositiveButton(R.string.profile_switch_delete) { _, _ ->
                profileManager.deleteProfile(profile.id)
                updateQuickProfileSummary()
                showToast(getString(R.string.profile_switch_deleted))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileEditorDialog(existingProfile: ParentMonitorProfile?) {
        val currentOwnId = existingProfile?.ownParentDeviceId?.ifBlank { null }
            ?: effectiveContextResolver.resolveOwnParentId().ifBlank { profileManager.resolveCurrentParentId() }
        val currentChildId = existingProfile?.linkedChildDeviceId?.ifBlank { null }
            ?: effectiveContextResolver.resolveFocusedChildId().ifBlank { profileManager.resolveCurrentChildId() }
        val currentServerUrl = existingProfile?.serverUrl?.ifBlank { null }
            ?: effectiveContextResolver.resolveServerUrl().ifBlank { getConfiguredServerUrl().orEmpty() }
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

        val dialog = MaterialAlertDialogBuilder(this)
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
                    name.isBlank() -> showToast(getString(R.string.profile_switch_validation_name))
                    serverUrl.isBlank() || (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) ->
                        showToast(getString(R.string.profile_switch_validation_server))
                    ownId.isBlank() -> showToast(getString(R.string.profile_switch_validation_own_id))
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
                            applyQuickProfile(profile)
                        } else {
                            updateQuickProfileSummary()
                        }
                        showToast(
                            getString(
                                if (existingProfile == null) {
                                    R.string.profile_switch_saved
                                } else {
                                    R.string.profile_switch_updated
                                }
                            )
                        )
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
                    showToast(getString(R.string.profile_switch_pick_child_error))
                    emptyList()
                }
            if (options.isEmpty()) {
                showToast(getString(R.string.profile_switch_pick_child_empty))
                return@launch
            }

            val items = options.map(::formatLinkedChildOption).toTypedArray()
            val selectedIndex = options.indexOfFirst { it.deviceId == selectedDeviceId }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@MainActivity)
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

    private fun applyQuickProfile(profile: ParentMonitorProfile) {
        profileRuntimeCoordinator.applyProfile(
            profile = profile,
            shareParentLocation = prefs.getBoolean("share_parent_location", true)
        )
        initializeWebSocket()
        loadSelectedChild()
        updateQuickProfileSummary()
        updateUIState(skipAutoRecovery = true)
        updateChatBadge()
        showToast(getString(R.string.profile_switch_applied))
    }

    private fun updateQuickProfileSummary() {
        val activeProfile = profileManager.getActiveProfile()
        val effectiveContext = effectiveContextResolver.resolve()
        val profileName = activeProfile?.name?.takeIf { it.isNotBlank() }
            ?: activeSessionStore.getSession()?.profileName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_switch_current_name)
        val ownParentId = activeProfile?.ownParentDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext.ownParentDeviceId.takeIf { it.isNotBlank() }
        val childId = activeProfile?.linkedChildDeviceId?.takeIf { it.isNotBlank() }
            ?: effectiveContext.linkedChildDeviceId.takeIf { it.isNotBlank() }
        val serverUrl = activeProfile?.serverUrl?.takeIf { it.isNotBlank() }
            ?: effectiveContext.serverUrl.takeIf { it.isNotBlank() }

        if (ownParentId.isNullOrBlank() || serverUrl.isNullOrBlank()) {
            binding.activeProfileName.text = getString(R.string.profile_switch_title)
            binding.activeProfileMeta.text = getString(R.string.profile_switch_no_active)
            return
        }

        binding.activeProfileName.text = profileName
        val isSavedProfile = activeProfile?.id?.let { activeId ->
            profileManager.getSavedProfiles().any { it.id == activeId }
        } == true
        val childLabel = formatChildReference(
            rawChildId = childId ?: getString(R.string.profile_switch_unknown_link),
            childDisplayName = activeProfile?.linkedChildDisplayName?.ifBlank { null }
                ?: profileManager.resolveLinkedChildDisplayName(
                    childDeviceId = childId.orEmpty(),
                    serverUrl = serverUrl,
                    ownParentDeviceId = ownParentId
                )
        )
        val summary = getString(
            R.string.profile_switch_summary_format,
            profileName,
            formatProfileServer(serverUrl),
            formatProfileId(ownParentId),
            childLabel
        )
        val sourceLine = getString(
            R.string.profile_switch_source_line,
            describeProfileContextSource(effectiveContext.source)
        )
        val statusLine = getString(
            R.string.profile_switch_status_line,
            getString(
                if (isSavedProfile) {
                    R.string.profile_switch_status_saved
                } else {
                    R.string.profile_switch_status_runtime_only
                }
            )
        )
        val selfNameLine = getString(
            R.string.participant_self_name_summary_line,
            participantNameResolver.resolveOwnParentDisplayName()
        )
        val selfMarkerLine = getString(
            R.string.participant_self_marker_summary_line,
            ContactIcons.labelFor(participantNameResolver.resolveOwnParentMarkerIconId())
        )
        val linkedParentsLine = buildCachedLinkedParentsLine()
        val mismatchLine = if (isProfileContextMismatched(activeProfile, effectiveContext)) {
            "\n" + getString(R.string.profile_switch_warning_mismatch)
        } else {
            ""
        }
        binding.activeProfileMeta.text = summary + "\n" + selfNameLine + "\n" + selfMarkerLine + "\n" + sourceLine + "\n" + statusLine +
            (linkedParentsLine?.let { "\n$it" } ?: "") + mismatchLine
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
        effectiveContext: ru.example.childwatch.profile.ParentEffectiveContext
    ): Boolean {
        if (activeProfile == null) return false

        fun differs(profileValue: String, effectiveValue: String): Boolean {
            val p = profileValue.trim()
            val e = effectiveValue.trim()
            return p.isNotBlank() && e.isNotBlank() && p != e
        }

        return differs(activeProfile.serverUrl, effectiveContext.serverUrl) ||
            differs(activeProfile.ownParentDeviceId, effectiveContext.ownParentDeviceId) ||
            differs(activeProfile.linkedChildDeviceId, effectiveContext.linkedChildDeviceId)
    }

    private fun formatProfileServer(serverUrl: String): String {
        val parsedHost = runCatching { Uri.parse(serverUrl).host }.getOrNull()
        return (parsedHost ?: serverUrl).removePrefix("www.")
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
            updateQuickProfileSummary()
        }

        syncLinkedParentsContextInBackground()
    }

    private suspend fun syncLinkedParentsContextInBackground() {
        val childId = resolveTargetDeviceId().orEmpty()
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
        updateQuickProfileSummary()
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

    private fun checkBatteryOptimizationStatus() {
        val ignoringOptimizations = batteryOptimizationHelper.isIgnoringBatteryOptimizations()
        val powerSaveEnabled = batteryOptimizationHelper.isPowerSaveEnabled()

        binding.batteryOptimizationRow.isVisible = !ignoringOptimizations
        binding.powerSaverRow.isVisible = powerSaveEnabled
        binding.powerSettingsCard.isVisible = !ignoringOptimizations || powerSaveEnabled

        if (ignoringOptimizations) {
            batteryOptimizationDialogDisplayed = false
        } else {
            maybeShowBatteryOptimizationDialog()
        }
    }

    private fun maybeShowBatteryOptimizationDialog() {
        if (prefs.getBoolean(KEY_BATTERY_PROMPT_SUPPRESSED, false) || batteryOptimizationDialogDisplayed) {
            return
        }
        batteryOptimizationDialogDisplayed = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_dialog_title)
            .setMessage(R.string.battery_optimization_dialog_message)
            .setPositiveButton(R.string.battery_optimization_button) { _, _ ->
                batteryOptimizationHelper.requestDisableBatteryOptimization()
            }
            .setNegativeButton(R.string.battery_optimization_dialog_later, null)
            .setNeutralButton(R.string.battery_optimization_dialog_never) { _, _ ->
                prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SUPPRESSED, true).apply()
            }
            .setOnDismissListener {
                if (batteryOptimizationHelper.isIgnoringBatteryOptimizations()) {
                    batteryOptimizationDialogDisplayed = false
                }
            }
            .show()
    }

    private fun updateUIState(skipAutoRecovery: Boolean = false) {
        // Check consent first
        hasConsent = ConsentActivity.hasConsent(this)
        
        if (hasConsent) {
            // Use only runtime flag for UI state to avoid confusion
            val isMonitoring = MonitorService.isRunning
            
            // Auto-recover ONLY on app resume if persisted state says monitoring should be on
            // BUT skip during user actions or if runtime already matches
            if (!skipAutoRecovery && !isMonitoring) {
                val persistedEnabled = secureSettings.isMonitoringEnabled()
                if (persistedEnabled) {
                    try {
                        val intent = Intent(this, MonitorService::class.java).apply {
                            action = MonitorService.ACTION_START_MONITORING
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        android.util.Log.d(TAG, "Auto-recovered MonitorService to match persisted state")
                        // Give service time to start before updating UI
                        binding.monitoringToggleBtn.postDelayed({
                            updateUIState(skipAutoRecovery = true)
                        }, 500)
                        return
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to auto-start MonitorService", e)
                    }
                }
            }
            
            // Update status display
            updateStatusDisplay(isMonitoring)
            
            // Update unified toggle button with animation
            updateMonitoringButton(isMonitoring)
            
            // Update old button states (hidden, for compatibility)
            binding.startMonitoringBtn.isEnabled = !isMonitoring
            binding.stopMonitoringBtn.isEnabled = isMonitoring
            
        } else {
            // Show consent screen
            showConsentScreen()
        }

        updateDeviceInfoCard()
        checkBatteryOptimizationStatus()
    }
    
    private fun updateMonitoringButton(isMonitoring: Boolean) {
        binding.monitoringToggleBtn.apply {
            // Animate scale
            animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    // Update appearance
                    if (isMonitoring) {
                        // Active state - red/danger color
                        text = getString(R.string.stop_monitoring)
                        setIconResource(R.drawable.ic_stop)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(context, android.R.color.holo_red_dark)
                        )
                    } else {
                        // Inactive state - emerald/start color
                        text = getString(R.string.start_monitoring)
                        setIconResource(R.drawable.ic_play)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.emerald_primary)
                        )
                    }
                    
                    // Scale back
                    animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
    }
    
    private fun updateStatusDisplay(isMonitoring: Boolean) {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        if (isMonitoring) {
            // Active monitoring - bright green with pulsing animation
            binding.statusText.text = getString(R.string.monitoring_active_status)
            binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.statusIcon.setImageResource(android.R.drawable.presence_online)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
            
            // Add pulsing animation to icon
            binding.statusIcon.animate()
                .alpha(0.3f)
                .setDuration(800)
                .withEndAction {
                    binding.statusIcon.animate()
                        .alpha(1.0f)
                        .setDuration(800)
                        .start()
                }
                .start()
            
            // Update service running time
            val serviceStartTime = secureSettings.getServiceStartTime()
            if (serviceStartTime > 0) {
                val runningTime = System.currentTimeMillis() - serviceStartTime
                val hours = runningTime / (1000 * 60 * 60)
                val minutes = (runningTime % (1000 * 60 * 60)) / (1000 * 60)
                val timeString = getString(R.string.service_running_time_value, hours, minutes)
                binding.serviceRunningTimeText.text = getString(R.string.service_running_time, timeString)
                binding.serviceRunningTimeText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            } else {
                binding.serviceRunningTimeText.text = getString(R.string.service_running_time, getString(R.string.unknown))
            }
            
            // Update feature status
            binding.locationStatusText.text = getString(R.string.status_location_active)
            binding.audioStatusText.text = getString(R.string.status_audio_active)
            binding.photoStatusText.text = getString(R.string.status_photo_active)
            
        } else {
            // Inactive monitoring - gray
            binding.statusText.text = getString(R.string.monitoring_inactive_status)
            binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.statusIcon.setImageResource(android.R.drawable.presence_offline)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
            
            // Stop any animation
            binding.statusIcon.animate().cancel()
            binding.statusIcon.alpha = 1.0f
            
            binding.serviceRunningTimeText.text = getString(R.string.service_running_time, getString(R.string.not_working))
            binding.serviceRunningTimeText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

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
            binding.lastUpdateText.text = getString(R.string.last_location_update, getString(R.string.never))
        }

        // Audio update
        val lastAudioUpdate = secureSettings.getLastAudioUpdate()
        if (lastAudioUpdate > 0) {
            val timeString = dateFormat.format(Date(lastAudioUpdate))
            binding.lastAudioUpdateText.text = getString(R.string.last_audio_update, timeString)
        } else {
            binding.lastAudioUpdateText.text = getString(R.string.last_audio_update, getString(R.string.never))
        }

        // Photo update
        val lastPhotoUpdate = secureSettings.getLastPhotoUpdate()
        if (lastPhotoUpdate > 0) {
            val timeString = dateFormat.format(Date(lastPhotoUpdate))
            binding.lastPhotoUpdateText.text = getString(R.string.last_photo_update, timeString)
        } else {
            binding.lastPhotoUpdateText.text = getString(R.string.last_photo_update, getString(R.string.never))
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
    
    private fun updateDeviceInfoCard() {
        binding.deviceInfoCard.isVisible = true
        binding.deviceInfoProgress.isVisible = false

        val childDeviceId = resolveDeviceIdForStatus()
        if (childDeviceId.isNullOrEmpty()) {
            binding.deviceInfoDeviceId.text = getString(R.string.device_info_device_id, getString(R.string.device_info_unknown))
            latestDeviceStatus = null
            showDeviceInfoMessage(getString(R.string.device_info_needs_pairing))
            return
        }

        binding.deviceInfoDeviceId.text = getString(R.string.device_info_device_id, childDeviceId)
        val cachedStatus = latestDeviceStatus ?: loadCachedDeviceStatus()
        if (cachedStatus != null) {
            applyDeviceStatus(cachedStatus)
        } else {
            binding.deviceInfoStatusMessage.text = getString(R.string.device_info_loading)
            binding.deviceInfoStatusMessage.isVisible = true
            binding.deviceInfoContent.isVisible = false
        }

        refreshChildDeviceStatus(force = false)
    }

    private fun loadCachedDeviceStatus(): DeviceStatus? {
        val childDeviceId = resolveDeviceIdForStatus() ?: return null
        val cachedJson = secureSettings.getLastDeviceStatusForDevice(childDeviceId) ?: return null
        return runCatching { gson.fromJson(cachedJson, DeviceStatus::class.java) }.getOrNull()
    }

    private fun applyDeviceStatus(status: DeviceStatus) {
        binding.deviceInfoProgress.isVisible = false
        binding.deviceInfoStatusMessage.isVisible = false
        binding.deviceInfoContent.isVisible = true
        val childDeviceId = resolveDeviceIdForStatus()
        val cachedTimestamp = childDeviceId?.let { secureSettings.getLastDeviceStatusTimestampForDevice(it) }
        val statusTimestamp = normalizeEpochMillis(status.timestamp ?: cachedTimestamp ?: 0L)
        val isStale = statusTimestamp?.let { System.currentTimeMillis() - it > DEVICE_STATUS_STALE_MS } == true
        if (statusTimestamp == null) {
            Log.w(TAG, "Device status timestamp missing")
        } else if (isStale) {
            Log.d(TAG, "Device status is stale")
        }

        binding.deviceInfoBatteryValue.text = status.batteryLevel
            ?.takeIf { it in 0..100 }
            ?.let { "$it%" }
            ?: getString(R.string.device_info_unknown)

        binding.deviceInfoChargingValue.text = when {
            status.isCharging == true && !status.chargingType.isNullOrBlank() ->
                "${getString(R.string.device_info_charging_yes)} (${status.chargingType})"
            status.isCharging == true -> getString(R.string.device_info_charging_yes)
            status.isCharging == false -> getString(R.string.device_info_charging_no)
            else -> getString(R.string.device_info_unknown)
        }

        binding.deviceInfoTemperatureValue.text = status.temperature?.takeIf { it > 0 }?.let {
            String.format(Locale.getDefault(), "%.1f C", it)
        } ?: getString(R.string.device_info_unknown)

        val modelText = listOfNotNull(status.manufacturer, status.model)
            .joinToString(" ")
            .trim()
        binding.deviceInfoModelValue.text = if (modelText.isNotEmpty()) modelText else getString(R.string.device_info_unknown)

        binding.deviceInfoCurrentAppValue.text = status.currentAppName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.device_info_unknown)

        binding.deviceInfoUpdatedValue.text = if (statusTimestamp != null) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val base = timeFormat.format(Date(statusTimestamp))
            if (isStale) "$base ${getString(R.string.device_info_stale_suffix)}" else base
        } else {
            getString(R.string.device_info_unknown)
        }

        latestDeviceStatus = status
    }

    private fun showDeviceInfoMessage(message: String) {
        binding.deviceInfoContent.isVisible = false
        binding.deviceInfoProgress.isVisible = false
        binding.deviceInfoStatusMessage.isVisible = true
        binding.deviceInfoStatusMessage.text = message
    }

    private fun refreshChildDeviceStatus(force: Boolean = false) {
        val childDeviceId = resolveDeviceIdForStatus()
        if (childDeviceId.isNullOrEmpty()) {
            deviceStatusJob?.cancel()
            lastStatusFetchTime = 0L
            showDeviceInfoMessage(getString(R.string.device_info_needs_pairing))
            return
        }

        val serverUrl = getConfiguredServerUrl()
        if (serverUrl.isNullOrBlank()) {
            Log.w(TAG, "Device status fetch skipped: server URL not configured")
            if (latestDeviceStatus == null) {
                showDeviceInfoMessage(getString(R.string.server_url_missing))
            }
            binding.deviceInfoProgress.isVisible = false
            return
        }

        Log.d(TAG, "Fetching device status: deviceId=$childDeviceId serverUrl=$serverUrl")

        val now = System.currentTimeMillis()
        if (!force && now - lastStatusFetchTime < 60_000) {
            return
        }
        
        // Avoid parallel status fetches unless the caller explicitly forces a refresh.
        if (!force && deviceStatusJob?.isActive == true) {
            return
        }
        
        // Forced refresh cancels any in-flight job and starts a new one.
        if (force) {
            deviceStatusJob?.cancel()
        }
        
        lastStatusFetchTime = now

        binding.deviceInfoProgress.isVisible = true
        binding.deviceInfoStatusMessage.isVisible = false

        deviceStatusJob = lifecycleScope.launch {
            try {
                var attempt = 0
                while (attempt < 3) {
                    attempt++
                    val response = withContext(Dispatchers.IO) {
                        networkClient.getChildDeviceStatus(childDeviceId)
                    }
                    if (response.isSuccessful) {
                        val status = response.body()?.status
                        if (status != null) {
                            val normalizedTimestamp = normalizeEpochMillis(status.timestamp) ?: System.currentTimeMillis()
                            val normalizedStatus = status.copy(timestamp = normalizedTimestamp)
                            secureSettings.setLastDeviceStatus(gson.toJson(normalizedStatus))
                            secureSettings.setLastDeviceStatusTimestamp(normalizedTimestamp)
                            secureSettings.setLastDeviceStatusForDevice(childDeviceId, gson.toJson(normalizedStatus))
                            secureSettings.setLastDeviceStatusTimestampForDevice(childDeviceId, normalizedTimestamp)
                            applyDeviceStatus(normalizedStatus)
                            return@launch
                        }
                    }

                    if (attempt < 3) {
                        delay(500L * attempt)
                    }
                }

                if (latestDeviceStatus == null) {
                    showDeviceInfoMessage(getString(R.string.device_info_not_available))
                }
            } catch (error: CancellationException) {
                // Cancellation is expected here, so do not treat it as an error.
                Log.d(TAG, "Device status fetch cancelled")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load device status", error)
                if (latestDeviceStatus == null) {
                    showDeviceInfoMessage(getString(R.string.device_info_not_available))
                }
            } finally {
                binding.deviceInfoProgress.isVisible = false
            }
        }
    }
    
    private fun performSecurityChecks() {
        try {
            val securityReport = SecurityChecker.getSecurityReport(this)
            val securityWarnings = SecurityChecker.getSecurityWarnings(this)

            // Security events logging is disabled in current version
            // The logging was causing encoding issues with Russian text

            // Keep debug security information in logs only to avoid noisy popups and mojibake in UI.
            if (BuildConfig.DEBUG && securityWarnings.isNotEmpty()) {
                Log.w(TAG, "Security warnings: ${securityWarnings.joinToString("; ")}")
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
        
        Log.d(TAG, "User action: starting monitoring")
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START_MONITORING
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        ensureChatBackgroundService()
        showToast(getString(R.string.monitoring_started))
        // Optimistically update button state, then refresh UI
        updateMonitoringButton(true)
        // Skip auto-recovery during user action to avoid race condition
        updateUIState(skipAutoRecovery = true)
    }

    private fun stopMonitoring() {
        Log.d(TAG, "User action: stopping monitoring")
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_MONITORING
        }
        startService(intent)

        stopChatBackgroundService()
        showToast(getString(R.string.monitoring_stopped))
        // Optimistically update button state, then refresh UI
        updateMonitoringButton(false)
        // Skip auto-recovery during user action to avoid race condition
        updateUIState(skipAutoRecovery = true)
    }

    private fun showEmergencyStopDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.emergency_stop_title))
            .setMessage(getString(R.string.emergency_stop_message))
            .setPositiveButton(getString(R.string.emergency_stop_yes)) { _, _ ->
                emergencyStopAll()
            }
            .setNegativeButton(getString(R.string.emergency_stop_cancel), null)
            .show()
    }

    private fun emergencyStopAll() {
        Log.w("ChildWatch", "EMERGENCY STOP triggered")

        // Stop audio playback if running
        if (ru.example.childwatch.service.AudioPlaybackService.isPlaying) {
            ru.example.childwatch.service.AudioPlaybackService.stopPlayback(this)
            Log.d("ChildWatch", "Audio playback stopped")
        }

        // Stop monitoring
        stopMonitoring()
        Log.d("ChildWatch", "Monitoring stopped")

        showToast(getString(R.string.emergency_stop_done))
        Log.w("ChildWatch", "EMERGENCY STOP completed")
    }

    private fun requestPermissions() {
        // First request basic permissions
        if (!PermissionHelper.hasBasicPermissions(this)) {
            requestBasicPermissions()
        } else {
            // Basic permissions granted, now request background location if needed
            requestBackgroundLocationPermission()
        }
    }
    
    private fun requestBasicPermissions() {
        val basicPermissions = PermissionHelper.getBasicPermissions()
        val missingBasicPermissions = basicPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingBasicPermissions.isNotEmpty()) {
            basicPermissionLauncher.launch(missingBasicPermissions.toTypedArray())
        }
    }
    
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!PermissionHelper.hasBackgroundLocationPermission(this)) {
                // Show explanation first
                showBackgroundLocationExplanation()
            } else {
                // All permissions granted
                onAllPermissionsGranted()
            }
        } else {
            // Android 9 and below don't need background location permission
            onAllPermissionsGranted()
        }
    }
    
    private fun showBackgroundLocationExplanation() {
        val explanation = PermissionHelper.getPermissionExplanation(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val consequences = PermissionHelper.getPermissionDenialConsequences(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.main_background_location_title)
            .setMessage(
                "$explanation\n\n$consequences\n\n${getString(R.string.main_background_location_prompt_suffix)}"
            )
            .setPositiveButton(R.string.main_permission_allow) { _, _ ->
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.main_permission_skip) { _, _ ->
                showToast(getString(R.string.main_background_location_denied_limited))
                onAllPermissionsGranted()
            }
            .setNeutralButton(R.string.main_permission_settings) { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .show()
    }
    
    private fun handleBasicPermissionResults(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys

        if (deniedPermissions.isEmpty()) {
            showToast(getString(R.string.main_permissions_basic_granted))
            // Now request background location permission
            requestBackgroundLocationPermission()
        } else {
            val deniedList = deniedPermissions.joinToString(", ")
            showToast(getString(R.string.main_permissions_denied_list, deniedList))

            // Show explanation for denied permissions
            showPermissionDeniedExplanation(deniedPermissions)
        }
    }

    private fun handleBackgroundLocationResult(isGranted: Boolean) {
        if (isGranted) {
            showToast(getString(R.string.main_permissions_all_granted))
            onAllPermissionsGranted()
        } else {
            showToast(getString(R.string.main_background_location_denied))
            onAllPermissionsGranted() // Continue anyway
        }
    }

    private fun onAllPermissionsGranted() {
        showToast(getString(R.string.main_permissions_all_granted))
        // Try to start monitoring if consent is given
        if (hasConsent) {
            startMonitoring()
        }
    }
    
    private fun ensureChatBackgroundService() {
        val serverUrl = getConfiguredServerUrl()
        val childDeviceId = resolveTargetDeviceId()

        if (!childDeviceId.isNullOrEmpty() && !serverUrl.isNullOrBlank()) {
            ChatBackgroundService.start(this, serverUrl, childDeviceId)
        } else {
            Log.w(TAG, "Cannot start chat background service: serverUrl=$serverUrl child_device_id=$childDeviceId")
        }
    }

    private fun ensureParentLocationService() {
        try {
            val shareEnabled = prefs.getBoolean("share_parent_location", true)
            val serverUrl = getConfiguredServerUrl()
            val ownDeviceId = effectiveContextResolver
                .resolveOwnParentCandidates()
                .firstOrNull()

            if (shareEnabled && !serverUrl.isNullOrBlank() && !ownDeviceId.isNullOrBlank()) {
                ParentLocationService.start(this)
                Log.d(TAG, "ParentLocationService ensured (independent from WS state)")
            } else {
                ParentLocationService.stop(this)
                Log.d(
                    TAG,
                    "ParentLocationService stopped: shareEnabled=$shareEnabled serverConfigured=${!serverUrl.isNullOrBlank()} ownIdPresent=${!ownDeviceId.isNullOrBlank()}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure ParentLocationService", e)
        }
    }

    private fun stopChatBackgroundService() {
        ChatBackgroundService.stop(this)
    }

    private fun showPermissionDeniedExplanation(deniedPermissions: Set<String>) {
        val explanations = deniedPermissions.map { permission ->
            "${PermissionHelper.getPermissionExplanation(permission)}\n${PermissionHelper.getPermissionDenialConsequences(permission)}"
        }

        val message = explanations.joinToString("\n\n")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.main_permissions_required_title)
            .setMessage(message)
            .setPositiveButton(R.string.main_permission_settings) { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .setNegativeButton(R.string.main_permission_close) { _, _ -> }
            .show()
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inline fun runStartupTask(taskName: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            Log.e(TAG, "Startup task failed: $taskName", error)
        }
    }

    private fun resolveTargetDeviceId(): String? {
        contextProvider.current()?.targetDeviceId
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val resolvedFromContext = effectiveContextResolver.resolveTargetDeviceId()
        if (resolvedFromContext.isNotBlank()) {
            activeSessionStore.updateFocusedChildId(resolvedFromContext)
            if (secureSettings.getChildDeviceId().isNullOrBlank()) {
                secureSettings.setChildDeviceId(resolvedFromContext)
            }
            contextProvider.updateSelection(focusedMemberId = null, targetDeviceId = resolvedFromContext)
            return resolvedFromContext
        }
        val resolved = effectiveContextResolver
            .resolveFocusedChildCandidates()
            .firstOrNull()
            ?: return null

        activeSessionStore.updateFocusedChildId(resolved)
        if (secureSettings.getChildDeviceId().isNullOrBlank()) {
            secureSettings.setChildDeviceId(resolved)
        }
        contextProvider.updateSelection(focusedMemberId = null, targetDeviceId = resolved)

        return resolved
    }

    private fun getConfiguredServerUrl(): String? {
        val resolved = effectiveContextResolver.resolveServerUrl()
        if (resolved.isNotBlank()) {
            return normalizeServerUrl(resolved)
        }

        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val raw = listOf(
            secureSettings.getServerUrl(),
            prefs.getString("server_url", null),
            legacyPrefs.getString("server_url", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        return normalizeServerUrl(raw)
    }

    private fun normalizeEpochMillis(raw: Long?): Long? {
        if (raw == null || raw <= 0L) return null
        return when {
            raw < 10_000_000_000L -> raw * 1000L // seconds -> millis
            raw > 10_000_000_000_000L -> raw / 1000L // micros -> millis
            else -> raw
        }
    }

    private fun resolveDeviceIdForStatus(): String? {
        val preferred = effectiveContextResolver.resolveTargetDeviceId()
        if (!preferred.isNullOrBlank()) {
            return preferred
        }
        return effectiveContextResolver
            .resolveFocusedChildCandidates()
            .firstOrNull()
    }

    private fun normalizeServerUrl(raw: String): String {
        val candidate = extractUrlCandidate(raw)
        if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
            return candidate
        }

        val looksLikeLocalOrIp = candidate.startsWith("localhost", ignoreCase = true) ||
            candidate.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?$"))
        return if (looksLikeLocalOrIp) "http://$candidate" else "https://$candidate"
    }

    private fun extractUrlCandidate(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value

        // Legacy bug: two URLs could be saved in one field.
        // Prefer the last valid URL token (most recently entered by user).
        val regex = Regex("""https?://[^\s,;]+|(?:localhost|\d{1,3}(?:\.\d{1,3}){3}|[A-Za-z0-9.-]+\.[A-Za-z]{2,})(?::\d+)?""")
        val matches = regex.findAll(value).map { it.value.trim() }.toList()
        return matches.lastOrNull().orEmpty().ifBlank { value.lineSequence().lastOrNull()?.trim().orEmpty() }
    }

    private fun startDeviceStatusRefreshLoop() {
        if (deviceStatusRefreshJob?.isActive == true) return
        deviceStatusRefreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshChildDeviceStatus(force = true)
                delay(30_000)
            }
        }
    }
    
    override fun onDestroy() {
        badgeRefreshJob?.cancel()
        deviceStatusJob?.cancel()
        deviceStatusRefreshJob?.cancel()
        super.onDestroy()
    }
    
    override fun onResume() {
        super.onResume()
        prefs.edit().putBoolean("chat_open", false).apply()
        runStartupTask("updateUIState") { updateUIState() }
        runStartupTask("updateChatBadge") { updateChatBadge() }
        runStartupTask("startBadgeRefreshLoop") { startBadgeRefreshLoop() }
        runStartupTask("refreshChildDeviceStatus") { refreshChildDeviceStatus(force = true) }
        runStartupTask("startDeviceStatusRefreshLoop") { startDeviceStatusRefreshLoop() }
        runStartupTask("triggerImmediateCriticalAlertSync") { CriticalAlertSyncScheduler.triggerImmediate(this) }
        runStartupTask("ensureChatBackgroundService") { ensureChatBackgroundService() }
        runStartupTask("ensureParentLocationService") { ensureParentLocationService() }
        runStartupTask("initializeWebSocket") { initializeWebSocket() }
        runStartupTask("updateQuickProfileSummary") { updateQuickProfileSummary() }
        lifecycleScope.launch {
            syncLinkedProfilesInBackground()
        }
    }

    override fun onPause() {
        badgeRefreshJob?.cancel()
        deviceStatusRefreshJob?.cancel()
        super.onPause()
    }

    /**
     * Update chat badge with unread message count
     */
    private fun updateChatBadge() {
        lifecycleScope.launch(Dispatchers.IO) {
            val unreadFromStore = try { chatManager.getUnreadCount() } catch (_: Exception) { 0 }
            val unreadFromNotifications = try { ru.example.childwatch.utils.NotificationManager.getUnreadCount() } catch (_: Exception) { 0 }
            val unread = maxOf(unreadFromStore, unreadFromNotifications)
            withContext(Dispatchers.Main) {
                if (unread > 0) {
                    binding.chatBadge.visibility = View.VISIBLE
                    binding.chatBadge.text = if (unread > 99) "99+" else unread.toString()
                } else {
                    binding.chatBadge.visibility = View.GONE
                }
                Log.d("MainActivity", "Chat badge updated: $unread unread messages")
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

    private fun showDeviceIdOptions(serverUrl: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.main_dialog_device_id_missing_title))
            .setMessage(getString(R.string.main_dialog_device_id_missing_message))
            .setPositiveButton(getString(R.string.main_dialog_device_id_missing_test)) { _, _ ->
                // Use a dedicated test child ID for diagnostics and recovery drills.
                val testDeviceId = "test-child-device-001"
                val intent = Intent(this, AudioStreamingActivity::class.java).apply {
                    putExtra(AudioStreamingActivity.EXTRA_DEVICE_ID, testDeviceId)
                    putExtra(AudioStreamingActivity.EXTRA_SERVER_URL, serverUrl)
                }
                startActivity(intent)
                showToast(getString(R.string.main_toast_started_test_mode, testDeviceId))
            }
            .setNeutralButton(getString(R.string.main_dialog_device_id_missing_settings)) { _, _ ->
                // Open settings so the operator can configure a real child ID.
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * Load and render the currently selected child profile.
     */
    private fun loadSelectedChild() {
        lifecycleScope.launch {
            try {
                val selectedDeviceId = resolveSelectedChildIdForUi()

                if (!selectedDeviceId.isNullOrBlank()) {
                    val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@MainActivity)
                    val childDao = database.childDao()
                    val child = childDao.getByDeviceId(selectedDeviceId)

                    if (child != null) {
                        binding.selectedChildName.text = child.name
                        binding.selectedChildDeviceId.text = getString(
                            R.string.main_selected_child_id_and_edit_hint,
                            child.deviceId.take(12)
                        )

                        // Load avatar if the stored URI is still accessible.
                        if (child.avatarUrl != null) {
                            try {
                                val uri = android.net.Uri.parse(child.avatarUrl)
                                binding.selectedChildAvatar.setImageURI(uri)
                            } catch (e: SecurityException) {
                                Log.w(TAG, "Avatar URI no longer accessible", e)
                                binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading avatar", e)
                                binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                            }
                        } else {
                            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                        }

                        Log.d(TAG, "Selected child loaded: ${child.name}")
                    } else {
                        showDefaultChildSelection()
                    }
                } else {
                    showDefaultChildSelection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load selected child", e)
                showDefaultChildSelection()
            }
        }
    }

    /**
     * Show the placeholder state when no child has been selected yet.
     */
    private fun showDefaultChildSelection() {
        try {
            binding.selectedChildName.text = getString(R.string.main_select_contact_placeholder_title)
            binding.selectedChildDeviceId.text = getString(R.string.main_select_contact_placeholder_subtitle)
            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(0, "child"))
        } catch (e: Exception) {
            Log.e(TAG, "Error in showDefaultChildSelection", e)
        }
    }

    private suspend fun getSelectedContact(): Child? {
        return try {
            val selectedId = resolveSelectedChildIdForUi()
            if (selectedId.isNullOrBlank()) return null
            val database = ChildWatchDatabase.getInstance(this)
            database.childDao().getByDeviceId(selectedId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load selected contact", e)
            withContext(Dispatchers.Main) {
                showToast(getString(R.string.main_toast_contacts_db_error))
            }
            null
        }
    }

    private fun openCurrentChildEditor() {
        lifecycleScope.launch {
            val child = getSelectedContact()
            if (child == null) {
                showToast(getString(R.string.main_toast_select_child_first_to_edit))
                return@launch
            }

            try {
                val intent = Intent(this@MainActivity, ChildSelectionActivity::class.java).apply {
                    putExtra(ChildSelectionActivity.EXTRA_EDIT_CHILD_ID, child.deviceId)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching child editor", e)
                showToast(getString(R.string.main_toast_launch_error, e.message ?: "unknown"))
            }
        }
    }

    private fun ensureLegacyContact() {
        val legacyId = resolveSelectedChildIdForUi()
        if (legacyId.isNullOrBlank()) return

        persistSelectedChildCompat(legacyId)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val database = ChildWatchDatabase.getInstance(this@MainActivity)
                val childDao = database.childDao()
                val existing = childDao.getByDeviceId(legacyId)
                if (existing == null) {
                    val now = System.currentTimeMillis()
                    val linkedOption = runCatching {
                        linkedChildOptionsProvider.getOptions().firstOrNull { it.deviceId == legacyId }
                    }.getOrNull()
                    val child = Child(
                        deviceId = legacyId,
                        name = linkedOption?.displayName?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.main_default_child_name),
                        role = ContactRoles.CHILD,
                        iconId = ContactIcons.CHILD,
                        allowedFeatures = ContactFeatures.ALL,
                        createdAt = now,
                        updatedAt = now
                    )
                    childDao.insert(child)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate legacy contact", e)
            }
        }
    }

    /**
     * Apply the selected child to the UI and the active runtime context.
     */
    private fun updateSelectedChild(deviceId: String) {
        lifecycleScope.launch {
            try {
                val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@MainActivity)
                val childDao = database.childDao()
                val child = childDao.getByDeviceId(deviceId)

                if (child != null) {
                    // Update the visible child card.
                    binding.selectedChildName.text = child.name
                    binding.selectedChildDeviceId.text = getString(
                        R.string.main_selected_child_id_and_edit_hint,
                        child.deviceId.take(12)
                    )

                    // Refresh avatar from URI when available.
                    if (child.avatarUrl != null) {
                        try {
                            val uri = android.net.Uri.parse(child.avatarUrl)
                            binding.selectedChildAvatar.setImageURI(uri)
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Avatar URI no longer accessible", e)
                            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading avatar", e)
                            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                        }
                    } else {
                        binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(child.iconId, child.role))
                    }

                    // Persist and activate the selection across runtime entry points.
                    persistSelectedChildCompat(deviceId)
                    profileRuntimeCoordinator.switchFocusedChild(
                        childDeviceId = deviceId,
                        shareParentLocation = prefs.getBoolean("share_parent_location", true)
                    )
                    initializeWebSocket()
                    updateQuickProfileSummary()
                    updateChatBadge()
                    refreshChildDeviceStatus(force = true)

                    showToast(getString(R.string.main_toast_contact_selected, child.name))
                    Log.d(TAG, "Selected child updated: ${child.name} ($deviceId)")
                } else {
                    showToast(getString(R.string.main_toast_contact_not_found))
                    showDefaultChildSelection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update selected child", e)
                showToast(getString(R.string.main_toast_contact_update_error))
            }
        }
    }

    private fun resolveSelectedChildIdForUi(): String? {
        val effectiveId = effectiveContextResolver.resolveFocusedChildId()
        if (effectiveId.isNotBlank()) {
            return effectiveId
        }

        profileManager.getActiveProfile()?.linkedChildDeviceId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val currentProfileChildId = profileManager.resolveCurrentChildId()
        if (currentProfileChildId.isNotBlank()) {
            return currentProfileChildId
        }

        return null
    }

    private fun persistSelectedChildCompat(deviceId: String) {
        val normalized = deviceId.trim()
        if (normalized.isBlank()) return

        activeSessionStore.updateFocusedChildId(normalized)
        contextProvider.updateSelection(focusedMemberId = null, targetDeviceId = normalized)
        secureSettings.setChildDeviceId(normalized)
        prefs.edit()
            .putString("selected_device_id", normalized)
            .putString("child_device_id", normalized)
            .apply()
    }

    /**
     * Open remote camera activity
     */
    private fun openRemoteCamera() {
        val targetDeviceId = resolveTargetDeviceId()
        if (targetDeviceId.isNullOrBlank()) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.main_toast_set_child_device_id),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        activeSessionStore.updateFocusedChildId(targetDeviceId)
        val intent = Intent(this@MainActivity, RemoteCameraActivity::class.java).apply {
            putExtra(RemoteCameraActivity.EXTRA_CHILD_ID, targetDeviceId)
            putExtra(RemoteCameraActivity.EXTRA_CHILD_NAME, binding.selectedChildName.text?.toString().orEmpty())
        }
        startActivity(intent)
    }

    private fun openDeviceUsage() {
        val targetDeviceId = resolveTargetDeviceId()
        if (targetDeviceId.isNullOrBlank()) {
            showToast(getString(R.string.device_usage_pairing_required))
            return
        }
        activeSessionStore.updateFocusedChildId(targetDeviceId)
        runCatching {
            startActivity(
                Intent(this@MainActivity, DeviceUsageActivity::class.java).apply {
                    putExtra(DeviceUsageActivity.EXTRA_DEVICE_ID, targetDeviceId)
                }
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to open device usage screen", error)
            showToast(getString(R.string.device_usage_open_error))
        }
    }

    /**
     * Request remote photo capture
     */
    private fun requestRemotePhoto(childId: String) {
        if (!WebSocketManager.isConnected()) {
            Toast.makeText(this, getString(R.string.remote_camera_server_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_camera_progress_title)
            .setMessage(R.string.remote_camera_progress_message)
            .setCancelable(false)
            .create()

        progressDialog.show()

        // Set timeout
        val timeoutHandler = android.os.Handler(mainLooper)
        val timeoutRunnable = Runnable {
            progressDialog.dismiss()
            Toast.makeText(this, getString(R.string.remote_camera_progress_timeout), Toast.LENGTH_LONG).show()
        }
        timeoutHandler.postDelayed(timeoutRunnable, 30000) // 30 second timeout

        // Request photo
        WebSocketManager.requestPhoto(
            targetDevice = childId,
            onSuccess = {
                Log.d(TAG, "Photo request sent successfully")
            },
            onError = { error ->
                timeoutHandler.removeCallbacks(timeoutRunnable)
                progressDialog.dismiss()
                Toast.makeText(
                    this,
                    getString(R.string.remote_camera_error_format, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        )

        // Set one-time callback for photo response
        var photoReceived = false
        WebSocketManager.setPhotoReceivedCallback { photoBase64, requestId, timestamp ->
            if (!photoReceived) {
                photoReceived = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                runOnUiThread {
                    progressDialog.dismiss()
                    openPhotoPreview(photoBase64, timestamp, childId)
                }
            }
        }

        WebSocketManager.setPhotoErrorCallback { requestId, error ->
            if (!photoReceived) {
                photoReceived = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                runOnUiThread {
                    progressDialog.dismiss()
                    val uiError = RemotePhotoErrorMessages.resolve(this, error)
                    Toast.makeText(
                        this,
                        uiError.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Open photo preview activity
     */
    private fun openPhotoPreview(photoBase64: String, timestamp: Long, deviceId: String) {
        lifecycleScope.launch {
            var deviceName = deviceId
            try {
                val database = ChildWatchDatabase.getInstance(this@MainActivity)
                val child = database.childDao().getByDeviceId(deviceId)
                deviceName = child?.name ?: deviceId
            } catch (e: Exception) {
                Log.e(TAG, "Error getting device name", e)
            }

            val cachedFile = withContext(Dispatchers.IO) {
                RemotePhotoCache.saveBase64PhotoToCache(
                    this@MainActivity,
                    photoBase64,
                    timestamp
                )
            }

            if (cachedFile == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.remote_photo_preview_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val intent = Intent(this@MainActivity, PhotoPreviewActivity::class.java).apply {
                putExtra(PhotoPreviewActivity.EXTRA_PHOTO_FILE_PATH, cachedFile.absolutePath)
                putExtra(PhotoPreviewActivity.EXTRA_PHOTO_TIMESTAMP, timestamp)
                putExtra(PhotoPreviewActivity.EXTRA_DEVICE_NAME, deviceName)
            }
            startActivity(intent)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_BATTERY_PROMPT_SUPPRESSED = "battery_prompt_suppressed"
        private const val DEVICE_STATUS_STALE_MS = 10 * 60 * 1000L
        private const val KEY_LINKED_PARENT_COUNT = "linked_parent_context_count"
        private const val KEY_LINKED_PARENT_LABELS = "linked_parent_context_labels"
        private const val KEY_LINKED_PARENT_SELF_LABEL = "linked_parent_context_self_label"
    }
}
