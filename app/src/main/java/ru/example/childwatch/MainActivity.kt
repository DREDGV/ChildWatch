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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import ru.example.childwatch.alerts.CriticalAlertSyncScheduler
import ru.example.childwatch.attention.ParentAttentionSignalLauncher
import ru.example.childwatch.databinding.ActivityMainMenuBinding
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.location.FamilyLocationSummary
import ru.example.childwatch.remote.RemotePhotoCache
import ru.example.childwatch.remote.RemotePhotoErrorMessages
import ru.example.childwatch.update.UpdateManager
import ru.example.childwatch.update.UpdateResultReceiver
import ru.example.childwatch.update.UpdateUiController
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.service.ParentLocationService
import ru.example.childwatch.utils.BatteryOptimizationHelper
import ru.example.childwatch.utils.ParentMonitorProfile
import ru.example.childwatch.utils.ParentMonitorProfileManager
import ru.example.childwatch.utils.ParentMonitorProfileNameRules
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecurityChecker
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentFamilyDirectoryRepository
import ru.childwatch.shared.family.FamilyDirectorySnapshot
import ru.childwatch.shared.family.ServerAddressValidator
import ru.example.childwatch.profile.ProfileImagePicker
import ru.example.childwatch.profile.FamilyAvatarRenderer
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
import ru.childwatch.shared.family.FamilyPresenceState
import ru.childwatch.shared.family.FamilyRole
import ru.childwatch.shared.family.FeatureTargetResult
import ru.example.childwatch.designsystem.HomeFamilyStrip
import ru.example.childwatch.designsystem.HomeDetailSheet
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

    private fun homePickupScope(): String {
        val resolver = ru.example.childwatch.profile.ParentEffectiveContextResolver(this)
        val family = resolver.resolveFamilyId()?.takeIf { it.isNotBlank() } ?: return ""
        val member = resolver.resolveSelfMemberId()?.takeIf { it.isNotBlank() } ?: return ""
        val server = resolver.resolveServerUrl().takeIf { it.isNotBlank() } ?: return ""
        val own = resolver.resolveOwnParentId().takeIf { it.isNotBlank() } ?: return ""
        return org.json.JSONArray(listOf(server, family, member, own)).toString()
    }
    private val homePickupCard by lazy {
        ru.example.childwatch.designsystem.HomePickupCard(this,
            findViewById<android.view.View>(R.id.pickupHomeRoot) ?: findViewById<android.view.ViewStub>(R.id.pickupHomeStub).inflate(),
            object : ru.example.childwatch.designsystem.HomePickupCard.Host {
                override fun scope(): String = homePickupScope()
                override fun fetch(expectedScope: String, callback: ru.example.childwatch.designsystem.FamilyPickupController.Callback) {
                    lifecycleScope.launch {
                        try {
                            if (expectedScope != homePickupScope()) throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
                            val family = org.json.JSONArray(expectedScope).getString(1)
                            val response = networkClient.pickupRequest(family, expectedScope = expectedScope)
                            if (expectedScope != homePickupScope()) throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
                            callback.complete(response, null)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            callback.complete(null, "PICKUP_CANCELLED"); throw cancelled
                        } catch (failure: Exception) {
                            callback.complete(null, failure.message ?: "PICKUP_UNAVAILABLE")
                        }
                    }
                }
                override fun open(expectedScope: String) {
                    if (expectedScope != homePickupScope()) return
                    val resolver = ru.example.childwatch.profile.ParentEffectiveContextResolver(this@MainActivity)
                    startActivity(DualLocationMapActivity.createIntent(this@MainActivity, DualLocationMapActivity.ROLE_PARENT,
                        resolver.resolveOwnParentId(), resolver.resolveFocusedChildId()).apply {
                        putExtra("open_pickups", true); putExtra("pickup_scope", expectedScope)
                    })
                }
            })
    }

    private var homeSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var homeDirectory: FamilyDirectorySnapshot? = null
    private var homeDirectoryScope: String? = null
    private var homeDirectoryIsCanonical = false
    private var selectedHomeMetaScope: String? = null
    private var selectedHomeRoleLabel: String? = null

    private lateinit var binding: ActivityMainMenuBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var secureSettings: SecureSettingsManager
    private lateinit var profileManager: ParentMonitorProfileManager
    private lateinit var batteryOptimizationHelper: BatteryOptimizationHelper
    private lateinit var chatManager: ChatManager
    private val effectiveContextResolver by lazy { ParentEffectiveContextResolver(this) }
    private val contextProvider by lazy { ParentEffectiveContextProvider.get(this) }
    private val activeSessionStore by lazy { ParentActiveSessionStore(this) }
    private val familyDirectoryRepository by lazy { ParentFamilyDirectoryRepository(this) }
    private val linkedChildOptionsProvider by lazy { ParentLinkedChildOptionsProvider(this) }
    private val linkedParentsProvider by lazy { ParentLinkedParentsProvider(this) }
    private val participantNameResolver by lazy { ParentParticipantNameResolver(this) }
    private val profileRuntimeCoordinator by lazy { ParentProfileRuntimeCoordinator(this) }
    private var hasConsent = false
    private var batteryOptimizationDialogDisplayed = false
    private val deviceInfoScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val networkClient by lazy { NetworkClient(this) }
    private val familyLocationSummary by lazy { FamilyLocationSummary(this, networkClient) }
    private var selectedLocationJob: Job? = null
    private val gson by lazy { Gson() }

    /**
     * Updates over the air: the quiet check, the notice, the download and the request
     * to the system installer.
     *
     * It is built here rather than inside the update package because a notice needs a
     * screen to appear on and a coroutine scope tied to this screen's lifetime. The
     * work itself stays in that package — this class only says where the notice goes
     * and which server address to use.
     */
    private val updateUi by lazy {
        UpdateUiController(
            context = this,
            scope = lifecycleScope,
            noticeContainer = binding.updateNoticeContainer,
            // The very address the network client talks to, so the manifest and the
            // file can never be looked for on two different hosts.
            serverUrlProvider = { networkClient.resolveConfiguredServerUrl() }
        )
    }
    private var latestDeviceStatus: DeviceStatus? = null
    private var deviceStatusJob: Job? = null
    private var deviceStatusRefreshJob: Job? = null
    private var badgeRefreshJob: Job? = null
    private var badgeReadGeneration = 0
    private var badgeDisplayedScope = ""
    private var familySummaryJob: Job? = null
    private var ownHomeAvatarIdentity: List<String?>? = null
    private var selectedHomeAvatarIdentity: List<String?>? = null
    private var homeFamilyRenderIdentity: String? = null
    private var lastStatusFetchTime = 0L
    private var selectedLocationScope: String? = null
    private var selectedPersonAvatarValue: String? = null
    private var selectedPersonCanBeListenedTo = false
    private var statusDeviceId: String? = null
    private var statusRequestGeneration = 0L

    /**
     * True while the update feature has claimed this screen as the place an installer
     * confirmation may be opened from.
     *
     * Claimed once, when the screen is created, and held for the screen's whole life —
     * whether the confirmation is actually opened is decided by [screenVisible] at the
     * moment it arrives. Taking the claim away on pause and giving it back on resume
     * left nothing to give it back from, because the receiver may deliver while this
     * screen is stopped.
     */
    private var updateUiAttached = false
    
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
                updateSelectedChild(
                    deviceId = deviceId,
                    memberId = result.data?.getStringExtra(ChildSelectionActivity.EXTRA_SELECTED_MEMBER_ID),
                    familyId = result.data?.getStringExtra(ChildSelectionActivity.EXTRA_SELECTED_FAMILY_ID)
                )
            }
        }
    }

    /**
     * The chooser for a person's own profile picture.
     *
     * It is the system photo picker: it needs no permission to read the phone's
     * pictures on any supported Android version, and it hands over only the picture
     * the person picks. It is registered here because only an activity may register
     * a result launcher, and the profile editor is a dialog inside this one.
     */
    private val profilePhotoPickerLauncher = ProfileImagePicker.registerLauncher(this)

    override fun onSaveInstanceState(outState: Bundle) {
        ru.example.childwatch.designsystem.FamilyProfileEditor.saveState(this, outState)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch { ru.example.childwatch.location.FamilyPlaceSync.sync(this@MainActivity) }
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ru.example.childwatch.designsystem.FamilyProfileEditor.restore(this, savedInstanceState) { showProfileIdentityEditor(null) }
        
        prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        secureSettings = SecureSettingsManager(this)
        profileManager = ParentMonitorProfileManager(this)
        batteryOptimizationHelper = BatteryOptimizationHelper(this)
        chatManager = ChatManager(this)
        hasConsent = ConsentActivity.hasConsent(this)

        // The version and the date of the build, so anybody can tell at a glance
        // which build a phone is running. The line sits at the bottom of the
        // screen, under the diagnostics heading, not at the top: it is read when
        // a problem has to be reported, not when the application is opened.
        binding.checkUpdatesButton.setOnClickListener { updateUi.checkAndShowNotice(force = true) }
        binding.appVersionText.text =
            "ParentMonitor · ${BuildConfig.VERSION_NAME}"
        binding.appVersionText.contentDescription = getString(R.string.home_version_line, BuildConfig.VERSION_NAME, BuildConfig.BUILD_STAMP)

        setupUI()
        updateQuickProfileSummary()
        updateUIState()
        updateChatBadge()

        // Updates are the last thing this screen deals with, and deliberately so: the
        // check cannot block anything, and a person opening the application to see
        // where their child is must not be held up by it.
        attachUpdateUi()
        runStartupTask("checkForUpdate") { updateUi.checkAndShowNotice() }

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
            val targetDeviceId = resolveFeatureTargetDeviceId("audio-listening")
            
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
        binding.homeUpdateButton.setOnClickListener {
            if (homeSheet?.isShowing != true) homeSheet = HomeDetailSheet.show(
                binding.updateNoticeContainer, getString(R.string.cw_home_update_available)
            )
        }
        binding.selectedChildLocation.doAfterTextChanged {
            binding.deviceInfoDistance.text = it?.toString().orEmpty()
            binding.deviceInfoDistance.isVisible = binding.selectedChildLocation.isVisible
        }
        binding.diagnosticsToggleButton.setOnClickListener {
            latestDeviceStatus?.let { updateFeatureDiagnostics(it) }
            if (homeSheet?.isShowing != true) homeSheet = HomeDetailSheet.show(
                binding.diagnosticsPanel, binding.deviceInfoTitle.text.toString()
            )
        }
        binding.homeAttentionButton.setOnClickListener {
            if (homeSheet?.isShowing != true) homeSheet = HomeDetailSheet.show(
                binding.homeAttentionPanel, getString(R.string.cw_home_attention)
            )
        }
        binding.homeContent.viewTreeObserver.addOnGlobalLayoutListener {
            val needsAttention = binding.setupNoticeCard.visibility == View.VISIBLE ||
                binding.powerSettingsCard.visibility == View.VISIBLE
            if (binding.homeAttentionButton.isVisible != needsAttention) binding.homeAttentionButton.isVisible = needsAttention
            val hasUpdate = (0 until binding.updateNoticeContainer.childCount).any {
                binding.updateNoticeContainer.getChildAt(it).visibility == View.VISIBLE
            }
            if (binding.homeUpdateButton.isVisible != hasUpdate) binding.homeUpdateButton.isVisible = hasUpdate
        }
        binding.deviceUsageCard.setOnClickListener { binding.deviceUsageButton.performClick() }
        binding.familyStrip.render(emptyList(), null,
            { view, key, name -> FamilyAvatarRenderer.bind(view, key, name) },
            { }, { binding.childSelectionContainer.performClick() }, true)
        setupBatteryOptimizationUi()

        // The card is named "Мой семейный профиль", so tapping it must open that
        // profile. Its button used to open the child picker instead, which is why
        // the person's own name and picture could not be found anywhere.
        val openOwnProfile = android.view.View.OnClickListener {
            showProfileIdentityEditor(profileManager.getActiveProfile())
        }
        binding.activeProfileCard.setOnClickListener(openOwnProfile)
        binding.switchProfileQuickButton.setOnClickListener(openOwnProfile)

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
            if (!selectedPersonCanBeListenedTo) {
                showToast(getString(R.string.listen_child_only))
                return@setOnClickListener
            }
            val serverUrl = getConfiguredServerUrl()
            if (serverUrl.isNullOrBlank()) {
                showToast(getString(R.string.server_url_missing))
                return@setOnClickListener
            }
            val targetDeviceId = resolveFeatureTargetDeviceId("audio-listening")
            if (targetDeviceId.isNullOrBlank()) {
                showToast(getString(R.string.listen_child_only))
                return@setOnClickListener
            }
            val intent = Intent(this@MainActivity, AudioStreamingActivity::class.java).apply {
                putExtra(AudioStreamingActivity.EXTRA_DEVICE_ID, targetDeviceId)
                putExtra(AudioStreamingActivity.EXTRA_SERVER_URL, serverUrl)
            }
            startActivity(intent)
        }

        binding.chatCard.setOnClickListener {
            val targetDeviceId = resolveFeatureTargetDeviceId("chat")
            if (targetDeviceId.isNullOrBlank()) {
                showToast(getString(R.string.main_toast_set_child_device_id))
                return@setOnClickListener
            }
            ru.example.childwatch.utils.NotificationManager.resetUnreadCount()
            try {
                chatManager.markAllAsRead()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark chat messages as read before opening chat", e)
            }
            updateChatBadge()
            val intent = Intent(this@MainActivity, ChatConversationsActivity::class.java)
            startActivity(intent)
        }

        binding.attentionSignalCard.setOnClickListener {
            ParentAttentionSignalLauncher.show(
                activity = this,
                explicitTargetName = binding.selectedChildName.text?.toString(),
                explicitTargetAvatarValue = selectedPersonAvatarValue
            )
        }
        
        // Единственная кнопка камеры — удалённая съёмка.
        binding.remoteCameraCard.setOnClickListener {
            openRemoteCamera()
        }

        binding.deviceUsageButton.setOnClickListener {
            openDeviceUsage()
        }
        binding.deviceInfoRefreshButton.setOnClickListener { refreshChildDeviceStatus(force = true) }
        
        // Shared family map: every member who currently shares a location.
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
            val otherId = resolveFeatureTargetDeviceId("map").orEmpty()
            val intent = DualLocationMapActivity.createIntent(
                context = this,
                myRole = DualLocationMapActivity.ROLE_PARENT,
                myId = myId,
                otherId = otherId
            )
            intent.putExtra(DualLocationMapActivity.EXTRA_SHOW_ALL, true)
            startActivity(intent)
        }
        
        binding.settingsCard.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // The way back into a first run that was postponed.
        binding.setupNoticeButton.setOnClickListener { openFirstRunSetup() }

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
                    1 -> showProfileIdentityEditor(profileManager.getActiveProfile())
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
                showProfileIdentityEditor(null)
                return@launch
            }

            val items = profiles.map(::formatProfilePickerItem).toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.profile_switch_manage_title))
                .setItems(items) { _, which ->
                    showProfileActionsDialog(profiles[which])
                }
                .setPositiveButton(R.string.profile_switch_save_current) { _, _ ->
                    showProfileIdentityEditor(profileManager.getActiveProfile())
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

    /**
     * Opens the profile editor as a dialog on this screen.
     *
     * Only the name and the picture are offered: the address of the server and the
     * device identifiers were part of this dialog before, which is noise for anyone
     * who is not diagnosing a connection. Choosing which child to monitor has moved
     * to the advanced editor in settings, so nothing is lost.
     *
     * The change is also published to the family, because the name and picture live
     * there too and would otherwise be reverted by the next synchronisation.
     */
    private fun showProfileIdentityEditor(existingProfile: ParentMonitorProfile?) {
        ru.example.childwatch.profile.OwnProfileEditor.show(this) { updateQuickProfileSummary() }
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
        // Keep the last displayed identity while a background refresh is pending.
        // A failed refresh must not temporarily rename an already loaded person.
        if (binding.activeProfileName.text.isNullOrBlank()) {
            binding.activeProfileName.setText(R.string.profile_card_name_fallback)
        }
        familySummaryJob?.cancel()
        val requestedScope = homePickupScope()
        familySummaryJob = lifecycleScope.launch {
            val result = runCatching { familyDirectoryRepository.load() }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Unable to refresh family summary", it)
                }
                .getOrNull() ?: return@launch
            if (requestedScope != homePickupScope()) return@launch
            val directory = result.directory
            homeDirectory = directory
            homeDirectoryScope = requestedScope
            homeDirectoryIsCanonical = result.source == ru.example.childwatch.profile.ParentFamilyDirectorySource.SERVER
            refreshSelectedHomePresence()
            renderHomeFamily(directory)
            // Name, picture and role all come from this one member record, so the card
            // cannot mix one person's name with another person's face or role.
            val ownPerson = familyDirectoryRepository.ownPerson(
                directory,
                familyDirectoryRepository.ownMemberId(),
                null
            )
            val canonicalOwnName = ownPerson?.member?.displayName
            val avatarIdentity = listOf(
                org.json.JSONArray(listOf(effectiveContextResolver.resolveServerUrl(),
                    effectiveContextResolver.resolveFamilyId(), effectiveContextResolver.resolveOwnParentId())).toString(),
                ownPerson?.member?.id,
                ownPerson?.member?.avatarKey, canonicalOwnName
            )
            if (ownHomeAvatarIdentity != avatarIdentity) {
                FamilyAvatarRenderer.bind(binding.activeProfileAvatar, ownPerson?.member?.avatarKey, canonicalOwnName)
                ownHomeAvatarIdentity = avatarIdentity
            }
            val card = profileManager.resolveOwnProfileCard(
                canonicalName = canonicalOwnName,
                familyName = directory.family.name
            )
            binding.activeProfileName.text = if (
                card.name == ParentMonitorProfileNameRules.FALLBACK
            ) {
                getString(R.string.profile_card_name_fallback)
            } else {
                card.name
            }
            binding.activeProfileMeta.text = getString(
                R.string.profile_card_role,
                roleLabel(ownPerson?.member?.role)
            )
            updateSetupNotice(directory)
            binding.activeProfileCard.contentDescription = getString(R.string.cw_home_own_profile) + ": " + binding.activeProfileName.text
        }
    }

    /**
     * Says on the main screen when the first run was left unfinished.
     *
     * Setup is reachable from the settings, but a person who skipped it must not
     * have to hunt for it: the card appears only while no phone of the child is
     * reached, and it opens the same screen the first run shows.
     */
    private fun updateSetupNotice(directory: FamilyDirectorySnapshot) {
        val hasChildPhone = directory.targetPeople().any { it.primaryDevice() != null }
        binding.setupNoticeCard.visibility = if (hasChildPhone) View.GONE else View.VISIBLE
    }

    /**
     * Opens the first-run screen because the person asked for it.
     *
     * The "setup finished" flag is cleared first: setup is being continued, not
     * repeated, and without clearing it the first-run screen would see a finished
     * installation and close itself again. Nothing else is reset — the first-run
     * screen asks the server what already exists before it asks anything.
     */
    private fun openFirstRunSetup() {
        getSharedPreferences(ParentSetupActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(ParentSetupActivity.KEY_ONBOARDING_COMPLETED, false)
            .apply()
        startActivity(Intent(this, ParentSetupActivity::class.java))
    }

    /** How a family role is named in the interface. */
    private fun roleLabel(role: FamilyRole?): String = when (role) {
        FamilyRole.CHILD -> getString(R.string.family_role_child)
        FamilyRole.GUARDIAN -> getString(R.string.family_role_guardian)
        else -> getString(R.string.family_role_parent)
    }

    private fun renderHomeFamily(directory: FamilyDirectorySnapshot) {
        val selectedId = directory.personByDeviceId(resolveSelectedChildIdForUi())?.member?.id
        val people = directory.people.filter { it.member.id != directory.selfMemberId }
        val renderIdentity = org.json.JSONArray()
            .put(effectiveContextResolver.resolveServerUrl()).put(directory.family.id)
            .put(directory.selfMemberId).put(selectedId)
            .put(org.json.JSONArray(people.map {
                org.json.JSONArray(listOf(it.member.id, it.member.displayName, it.member.avatarKey))
            })).toString()
        // The strip rebuilds its children. Leave them and their focus intact
        // when only device telemetry changed during a background directory refresh.
        if (homeFamilyRenderIdentity == renderIdentity) return
        homeFamilyRenderIdentity = renderIdentity
        binding.familyStrip.render(people.map {
            HomeFamilyStrip.Person(it.member.id, it.member.displayName, it.member.avatarKey)
        }, selectedId, { view, key, name -> FamilyAvatarRenderer.bind(view, key, name) }, select@{ memberId ->
            // A retained strip must act on the latest directory, not its render snapshot.
            val currentDirectory = homeDirectory ?: return@select
            val person = currentDirectory.person(memberId) ?: return@select
            val device = person.primaryDevice(resolveSelectedChildIdForUi())
            if (device == null) {
                binding.childSelectionContainer.performClick()
            } else {
                lifecycleScope.launch {
                    val contact = ChildWatchDatabase.getInstance(this@MainActivity).childDao().getByDeviceId(device.deviceId)
                    if (contact == null) {
                        val options = runCatching { linkedChildOptionsProvider.getOptions() }.getOrNull()
                        if (options == null || options.none { it.deviceId == device.deviceId }) {
                            binding.childSelectionContainer.performClick()
                            return@launch
                        }
                        linkedChildOptionsProvider.syncLocalChildren(options)
                    }
                    updateSelectedChild(device.deviceId, memberId, currentDirectory.family.id)
                }
            }
        }, { binding.childSelectionContainer.performClick() }, true)
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
                        text = ""
                        contentDescription = getString(R.string.parent_service_stop)
                        setIconResource(R.drawable.cw_home_toggle_on)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.cw_color_surface)
                        )
                    } else {
                        // Inactive state - emerald/start color
                        text = ""
                        contentDescription = getString(R.string.parent_service_start)
                        setIconResource(R.drawable.cw_home_toggle_off)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.cw_color_surface)
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
            binding.statusText.text = getString(R.string.parent_service_active)
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
                binding.serviceRunningTimeText.text = getString(R.string.parent_service_uptime, timeString)
                binding.serviceRunningTimeText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            } else {
                binding.serviceRunningTimeText.text = getString(R.string.parent_service_uptime, getString(R.string.unknown))
            }
            
            // Update feature status
            binding.locationStatusText.text = getString(R.string.status_location_active)
            binding.audioStatusText.text = getString(R.string.status_audio_active)
            binding.photoStatusText.text = getString(R.string.status_photo_active)
            
        } else {
            // Inactive monitoring - gray
            binding.statusText.text = getString(R.string.parent_service_inactive)
            binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.statusIcon.setImageResource(android.R.drawable.presence_offline)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
            
            // Stop any animation
            binding.statusIcon.animate().cancel()
            binding.statusIcon.alpha = 1.0f
            
            binding.serviceRunningTimeText.text = getString(R.string.parent_service_uptime, getString(R.string.not_working))
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
            // Nothing is chosen on this device yet. That is not the same as having
            // no child: the family already knows who they are, and asking a person
            // for a device identifier is asking them to do the application's work.
            // The family is consulted before giving up.
            discoverChildFromFamily()
            return
        }

        binding.deviceInfoDeviceId.text = getString(R.string.device_info_device_id, childDeviceId)
        if (statusDeviceId != childDeviceId) {
            statusRequestGeneration++
            deviceStatusJob?.cancel()
            latestDeviceStatus = null
            resetSelectedPhoneSummary()
            lastStatusFetchTime = 0L
            statusDeviceId = childDeviceId
        }
        val cachedStatus = latestDeviceStatus ?: loadCachedDeviceStatus()
        if (cachedStatus != null) {
            applyDeviceStatus(cachedStatus)
        } else {
            resetSelectedPhoneSummary()
            binding.deviceInfoStatusMessage.text = getString(R.string.device_info_loading)
            binding.deviceInfoStatusMessage.isVisible = true
            binding.deviceInfoContent.isVisible = false
        }

        refreshChildDeviceStatus(force = false)
    }

    /**
     * Finds the child to report on by asking the family, not the person.
     *
     * A phone that has been set up again has nothing stored locally, so this screen
     * used to demand an identifier that the family already holds. The child is taken
     * from the family when the answer is unambiguous — one child with one phone —
     * and the person is asked only when it genuinely is not.
     */
    private fun discoverChildFromFamily() {
        binding.deviceInfoStatusMessage.text = getString(R.string.device_info_loading)
        binding.deviceInfoStatusMessage.isVisible = true
        binding.deviceInfoContent.isVisible = false
        binding.deviceInfoDeviceId.text =
            getString(R.string.device_info_device_id, getString(R.string.device_info_unknown))

        lifecycleScope.launch {
            val directory = runCatching { familyDirectoryRepository.load().directory }
                .onFailure { Log.w(TAG, "Unable to look up the child in the family", it) }
                .getOrNull()
            if (directory == null) {
                showDeviceInfoMessage(getString(R.string.device_info_needs_pairing))
                return@launch
            }

            val childDevices = directory.people
                .filter { it.member.role == FamilyRole.CHILD }
                .flatMap { person -> person.activeDevices.map { person to it } }

            // Exactly one answer is used without asking; more than one is a real
            // choice and belongs to the person.
            val only = childDevices.singleOrNull()
            if (only == null) {
                showDeviceInfoMessage(
                    if (childDevices.isEmpty()) {
                        getString(R.string.device_info_needs_pairing)
                    } else {
                        getString(R.string.device_info_choose_child)
                    }
                )
                return@launch
            }

            val (person, device) = only
            Log.i(TAG, "Using ${device.deviceId} of ${person.member.displayName} from the family")
            rememberChildDevice(device.deviceId)
            updateDeviceInfoCard()
        }
    }

    /** Remembers the discovered child so the choice is not made again on every visit. */
    private fun rememberChildDevice(childDeviceId: String) {
        runCatching {
            val profile = profileManager.getActiveProfile()
            if (profile != null && profile.linkedChildDeviceId.isBlank()) {
                profileManager.saveProfile(profile.copy(linkedChildDeviceId = childDeviceId))
                updateQuickProfileSummary()
            }
        }.onFailure { Log.w(TAG, "Could not remember the child device", it) }
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
        val statusTimestamp = normalizeEpochMillis(status.timestamp)
        val isStale = statusTimestamp?.let { System.currentTimeMillis() - it > DEVICE_STATUS_STALE_MS } == true
        if (statusTimestamp == null) {
            Log.w(TAG, "Device status timestamp missing")
        } else if (isStale) {
            Log.d(TAG, "Device status is stale")
        }

        val batterySummary = status.batteryLevel
            ?.takeIf { it in 0..100 }
            ?.let { "$it%" }
            ?: getString(R.string.device_info_unknown)

        binding.deviceInfoBatteryValue.text = getString(R.string.device_summary_battery, batterySummary)

        binding.deviceInfoChargingValue.text = when {
            status.isCharging == true && !status.chargingType.isNullOrBlank() ->
                "${getString(R.string.device_info_charging_yes)} (${status.chargingType})"
            status.isCharging == true -> getString(R.string.device_info_charging_yes)
            status.isCharging == false -> getString(R.string.device_info_charging_no)
            else -> getString(R.string.device_info_unknown)
        }

        val temperatureSummary = status.temperature?.takeIf { it > 0 }?.let {
            String.format(Locale.getDefault(), "%.1f °C", it)
        } ?: getString(R.string.device_info_unknown)
        binding.deviceInfoTemperatureValue.text = getString(R.string.device_summary_temperature, temperatureSummary)

        val modelText = listOfNotNull(status.manufacturer, status.model)
            .joinToString(" ")
            .trim()
        binding.deviceInfoModelValue.text = getString(R.string.device_summary_model,
            modelText.ifEmpty { getString(R.string.device_info_unknown) })

        binding.deviceInfoCurrentAppValue.isVisible = selectedPersonCanBeListenedTo
        binding.deviceInfoCurrentAppValue.text = getString(R.string.home_selected_last_app,
            status.currentAppName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.device_usage_current_unknown))

        binding.deviceInfoCameraValue.isVisible = selectedPersonCanBeListenedTo
        updateFeatureDiagnostics(status)
        binding.deviceInfoCameraValue.text = getString(R.string.photo_camera_diagnostics_line,
            ru.example.childwatch.remote.PhotoReadinessSummary.fromStatus(this, status).summary)
        binding.deviceInfoCameraValue.setOnClickListener {
            if (selectedPersonCanBeListenedTo && latestDeviceStatus === status) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.photo_camera_details_title)
                    .setMessage(ru.example.childwatch.remote.PhotoReadinessSummary.fromStatus(this, status).details)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }

        binding.deviceInfoUpdatedValue.text = if (statusTimestamp != null) {
            val timeFormat = SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault())
            val base = timeFormat.format(Date(statusTimestamp))
            getString(if (isStale) R.string.device_summary_stale else R.string.device_summary_updated, base)
        } else {
            getString(R.string.home_selected_time_unknown)
        }
        binding.selectedPhoneBatteryText.text = if (status.batteryLevel?.let { it in 0..100 } == true) {
            getString(if (status.isCharging == true) R.string.home_phone_charging else R.string.home_phone_battery,
                batterySummary)
        } else getString(R.string.home_phone_battery_unknown)
        binding.selectedPhoneBatteryGauge.setState(status.batteryLevel, status.isCharging == true,
            statusTimestamp != null && !isStale && statusTimestamp <= System.currentTimeMillis())
        binding.selectedPhoneUpdatedText.text = binding.deviceInfoUpdatedValue.text
        updateSelectedPhoneDescription()

        latestDeviceStatus = status
    }

    private fun resetSelectedPhoneSummary(messageRes: Int = R.string.home_phone_data_loading) {
        binding.selectedPhoneBatteryText.setText(R.string.home_phone_battery_unknown)
        binding.selectedPhoneBatteryGauge.setState(null, false, false)
        binding.selectedPhoneUpdatedText.setText(messageRes)
        updateSelectedPhoneDescription()
    }

    private fun updateSelectedPhoneDescription() {
        binding.diagnosticsToggleButton.contentDescription = getString(
            R.string.home_phone_details_accessibility, binding.selectedChildName.text,
            binding.selectedPhoneBatteryText.text, binding.selectedPhoneUpdatedText.text)
    }

    private fun showDeviceInfoMessage(message: String) {
        resetSelectedPhoneSummary(if (message == getString(R.string.home_selected_access_denied))
            R.string.home_phone_data_denied else R.string.home_phone_data_unavailable)
        binding.deviceInfoContent.isVisible = false
        binding.deviceInfoProgress.isVisible = false
        binding.deviceInfoStatusMessage.isVisible = true
        binding.deviceInfoStatusMessage.text = message
    }

    private fun updateFeatureDiagnostics(status: DeviceStatus) {
        val person = binding.selectedChildName.text.toString()
        binding.deviceInfoUsageReadiness.isVisible = selectedPersonCanBeListenedTo
        binding.deviceInfoUsageReadiness.text = ru.example.childwatch.remote.DeviceFeatureDiagnostics.usage(this, status, person)
        binding.deviceInfoLocationReadiness.text = ru.example.childwatch.remote.DeviceFeatureDiagnostics.location(this, status, person)
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
        if (statusDeviceId != childDeviceId) {
            statusRequestGeneration++
            deviceStatusJob?.cancel()
            latestDeviceStatus = null
            resetSelectedPhoneSummary()
            statusDeviceId = childDeviceId
            lastStatusFetchTime = 0L
            binding.deviceInfoContent.isVisible = false
        }

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
        val generation = ++statusRequestGeneration

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
                    if (generation != statusRequestGeneration || childDeviceId != resolveDeviceIdForStatus()) return@launch
                    if (response.code() == 403) {
                        latestDeviceStatus = null
                        showDeviceInfoMessage(getString(R.string.home_selected_access_denied))
                        return@launch
                    }
                    if (response.isSuccessful) {
                        val status = response.body()?.status
                        if (status != null) {
                            val normalizedTimestamp = normalizeEpochMillis(status.timestamp)
                            val normalizedStatus = status.copy(timestamp = normalizedTimestamp)
                            secureSettings.setLastDeviceStatus(gson.toJson(normalizedStatus))
                            if (normalizedTimestamp != null) secureSettings.setLastDeviceStatusTimestamp(normalizedTimestamp)
                            secureSettings.setLastDeviceStatusForDevice(childDeviceId, gson.toJson(normalizedStatus))
                            if (normalizedTimestamp != null) secureSettings.setLastDeviceStatusTimestampForDevice(childDeviceId, normalizedTimestamp)
                            applyDeviceStatus(normalizedStatus)
                            return@launch
                        }
                    }

                    if (attempt < 3) {
                        delay(500L * attempt)
                    }
                }

                showStatusRefreshFailure()
            } catch (error: CancellationException) {
                // Cancellation is expected here, so do not treat it as an error.
                Log.d(TAG, "Device status fetch cancelled")
            } catch (error: Exception) {
                if (generation != statusRequestGeneration || childDeviceId != resolveDeviceIdForStatus()) return@launch
                Log.e(TAG, "Failed to load device status", error)
                showStatusRefreshFailure()
            } finally {
                if (generation == statusRequestGeneration) binding.deviceInfoProgress.isVisible = false
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

    private fun showStatusRefreshFailure() {
        if (latestDeviceStatus == null) {
            showDeviceInfoMessage(getString(R.string.home_selected_no_data))
        } else {
            binding.deviceInfoStatusMessage.isVisible = true
            binding.deviceInfoStatusMessage.setText(R.string.home_selected_refresh_failed)
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
            return resolvedFromContext
        }
        return effectiveContextResolver
            .resolveFocusedChildCandidates()
            .firstOrNull()
    }

    private fun resolveFeatureTargetDeviceId(feature: String): String? {
        return (contextProvider.resolveFeatureTarget(feature) as? FeatureTargetResult.Resolved)
            ?.targetDeviceId
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
        resolveSelectedChildIdForUi()?.takeIf { it.isNotBlank() }?.let { return it }
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
        val normalized = if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)) {
            candidate
        } else {
            val looksLikeLocalOrIp = candidate.startsWith("localhost", ignoreCase = true) ||
                candidate.matches(Regex("^\\d+\\.\\d+\\.\\d+(:\\d+)?$"))
            if (looksLikeLocalOrIp) "http://$candidate" else "https://$candidate"
        }
        return normalized.takeIf(ServerAddressValidator::isValid).orEmpty()
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
                if (screenVisible) {
                    refreshSelectedHomePresence()
                    updateQuickProfileSummary()
                }
                refreshChildDeviceStatus(force = true)
                ru.example.childwatch.remote.ParentDeviceStatusReporter.report(this@MainActivity)
                delay(30_000)
            }
        }
    }
    
    override fun onDestroy() {
        homePickupCard.dispose()
        homeSheet?.dismiss()
        homeSheet = null
        badgeRefreshJob?.cancel()
        deviceStatusJob?.cancel()
        deviceStatusRefreshJob?.cancel()
        familySummaryJob?.cancel()
        // The screen is going away for good, so it stops being the place the installer's
        // confirmation may be opened from.
        if (updateUiAttached) {
            updateUiAttached = false
            UpdateManager.attachSink(null)
            UpdateResultReceiver.detach()
        }
        super.onDestroy()
    }

    /**
     * Handles the notification that continues an installation.
     *
     * The screen is usually still in memory when its own notification is tapped, so
     * Android reuses it and `onResume` never runs again. Without this the confirmation
     * would be held but never shown, which looks exactly like an update that quietly
     * did nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runStartupTask("resumeInstallConfirmation") {
            UpdateManager.deliverPendingConfirmation()
        }
    }
    
    /**
     * Whether this screen is on top right now.
     *
     * The installer's confirmation may only be opened from a visible screen, and a
     * screen that is merely stopped is not visible — so this is a flag of its own
     * rather than a guess from the activity's state.
     */
    private var screenVisible = false

    /** True when the screen was opened by the notification that continues an install. */
    private var resumeConfirmationOnStart = false

    override fun onResume() {
        super.onResume()
        homePickupCard.resume()
        prefs.edit().putBoolean("chat_open", false).apply()
        screenVisible = true
        // A notification may have opened this screen exactly to finish an
        // installation; the confirmation is opened again as soon as it is visible.
        if (resumeConfirmationOnStart || updateUi.wasOpenedToContinue(intent)) {
            resumeConfirmationOnStart = false
            runStartupTask("resumeInstallConfirmation") {
                UpdateManager.deliverPendingConfirmation()
            }
        }
        runStartupTask("updateUIState") { updateUIState() }
        runStartupTask("updateChatBadge") { updateChatBadge() }
        runStartupTask("startBadgeRefreshLoop") { startBadgeRefreshLoop() }
        runStartupTask("refreshChildDeviceStatus") { refreshChildDeviceStatus(force = true) }
        runStartupTask("startDeviceStatusRefreshLoop") { startDeviceStatusRefreshLoop() }
        runStartupTask("triggerImmediateCriticalAlertSync") { CriticalAlertSyncScheduler.triggerImmediate(this) }
        runStartupTask("ensureChatBackgroundService") { ensureChatBackgroundService() }
        runStartupTask("ensureParentLocationService") { ensureParentLocationService() }
        runStartupTask("initializeWebSocket") { initializeWebSocket() }
        runStartupTask("loadSelectedChild") { loadSelectedChild() }
        runStartupTask("startSelectedLocationUpdates") { startSelectedLocationUpdates() }
        runStartupTask("updateQuickProfileSummary") { updateQuickProfileSummary() }
        // Returning to the foreground is the other moment an update is checked for.
        // At most once a day, and only after a check that succeeded: the limit is
        // recorded by the update package, never by this screen.
        runStartupTask("checkForUpdateOnResume") { updateUi.checkAndShowNotice() }
        lifecycleScope.launch {
            syncLinkedProfilesInBackground()
        }
    }

    /**
     * Gives the update feature what it needs from this screen.
     *
     * The screen is registered as the place an installer confirmation may be opened
     * from — while it is visible that is allowed, and while it is not the notification
     * the receiver posts takes over, which is why the sink checks [screenVisible]. The
     * note about a previous installation is read here, exactly once, so a failure that
     * happened while the application was being replaced is still explained.
     */
    private fun attachUpdateUi() {
        updateUiAttached = true
        UpdateManager.attachSink { confirmation ->
            if (screenVisible) {
                updateUi.openConfirmation(confirmation)
            } else {
                // Kept for the next resume instead of being dropped: opening a window
                // from a stopped screen is refused by the system and would lose it.
                resumeConfirmationOnStart = true
                UpdateManager.publishPendingConfirmation(confirmation)
            }
        }
        UpdateResultReceiver.attach(this)
        runStartupTask("showPendingUpdateNote") { updateUi.showPendingFailureNote() }
    }

    override fun onPause() {
        homePickupCard.pause()
        selectedLocationJob?.cancel()
        selectedLocationJob = null
        badgeRefreshJob?.cancel()
        deviceStatusRefreshJob?.cancel()
        // The screen is no longer visible, so it is no longer the place the installer's
        // confirmation may be opened from. The claim itself is kept: the receiver may
        // deliver while this screen is stopped, and it has to have somewhere to put it.
        screenVisible = false
        super.onPause()
    }

    /**
     * Update chat badge with unread message count
     */
    private fun updateChatBadge() {
        val scope = homePickupScope()
        val generation = ++badgeReadGeneration
        if (scope != badgeDisplayedScope) {
            binding.chatBadge.visibility = View.GONE
            badgeDisplayedScope = scope
        }
        if (scope.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val unread = try {
                val context = org.json.JSONArray(scope)
                val family = context.getString(1)
                ru.example.childwatch.chat.v2.ChatV2Repository.create(applicationContext, context.getString(0))
                    .getCachedConversations()
                    .filter { it.familyId == family }
                    .sumOf { it.unreadCount.coerceAtLeast(0L) }
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MainActivity", "Unable to read current conversation badge", error)
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (generation != badgeReadGeneration || scope != homePickupScope()) return@withContext
                // Same v2 conversations as the chat screen. Legacy notification counters
                // must not resurrect a badge after those conversations have been read.
                binding.chatBadge.visibility = if (unread > 0) View.VISIBLE else View.GONE
                binding.chatBadge.text = if (unread > 99) "99+" else unread.toString()
                Log.d("MainActivity", "Chat badge updated from current family conversations: $unread")
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
            val requestedScope = selectedLocationScopeKey()
            try {
                val selectedDeviceId = resolveSelectedChildIdForUi()

                if (!selectedDeviceId.isNullOrBlank()) {
                    val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@MainActivity)
                    val childDao = database.childDao()
                    val child = childDao.getByDeviceId(selectedDeviceId)
                    if (selectedLocationScopeKey() != requestedScope) return@launch

                    if (child != null) {
                        // Render local data immediately, then enrich it from the
                        // canonical family directory without delaying the screen.
                        renderSelectedChild(child, null)
                        val canonical = runCatching {
                            linkedChildOptionsProvider.getOptions()
                                .firstOrNull { it.deviceId == selectedDeviceId }
                        }.getOrNull()
                        if (selectedLocationScopeKey() != requestedScope) return@launch
                        if (canonical != null) renderSelectedChild(child, canonical)

                        Log.d(TAG, "Selected child loaded: ${child.name}")
                    } else {
                        showDefaultChildSelection()
                    }
                } else {
                    showDefaultChildSelection()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (selectedLocationScopeKey() != requestedScope) return@launch
                Log.e(TAG, "Failed to load selected child", e)
                showDefaultChildSelection()
            }
        }
    }

    /**
     * Show the placeholder state when no child has been selected yet.
     */
    private fun showDefaultChildSelection() {
        selectedPersonCanBeListenedTo = false
        statusRequestGeneration++
        deviceStatusJob?.cancel()
        statusDeviceId = null
        latestDeviceStatus = null
        lastStatusFetchTime = 0L
        binding.deviceInfoTitle.setText(R.string.home_device_summary)
        showDeviceInfoMessage(getString(R.string.home_selected_choose_person))
        applySelectedPersonActions()
        binding.selectedPersonActionsHint.setText(R.string.home_selected_choose_person)
        try {
            binding.selectedChildLocation.visibility = View.INVISIBLE
            binding.deviceInfoDistance.isVisible = false
            binding.selectedChildName.text = getString(R.string.main_select_contact_placeholder_title)
            binding.selectedChildDeviceId.text = getString(R.string.main_select_contact_placeholder_subtitle)
            resetSelectedPhoneSummary(R.string.home_phone_choose_person)
            binding.selectedChildAvatar.setImageResource(ContactIcons.resolve(0, "child"))
            selectedHomeAvatarIdentity = null
            selectedPersonAvatarValue = null
            binding.childSelectionContainer.contentDescription = getString(R.string.family_profiles_add_description)
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
    private fun updateSelectedChild(
        deviceId: String,
        memberId: String? = null,
        familyId: String? = null
    ) {
        lifecycleScope.launch {
            try {
                val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@MainActivity)
                val childDao = database.childDao()
                val child = childDao.getByDeviceId(deviceId)

                if (child != null) {
                    val canonical = runCatching {
                        linkedChildOptionsProvider.getOptions()
                            .firstOrNull { it.deviceId == deviceId }
                    }.getOrNull()
                    // Persist and activate the selection across runtime entry points.
                    persistSelectedChildCompat(deviceId, memberId, familyId)
                    renderSelectedChild(child, canonical)
                    startSelectedLocationUpdates()
                    profileRuntimeCoordinator.switchFocusedChild(
                        childDeviceId = deviceId,
                        focusedMemberId = memberId,
                        familyId = familyId,
                        shareParentLocation = prefs.getBoolean("share_parent_location", true)
                    )
                    initializeWebSocket()
                    updateQuickProfileSummary()
                    updateChatBadge()
                    refreshChildDeviceStatus(force = true)

                    val selectedName = canonical?.displayName?.takeIf { it.isNotBlank() } ?: child.name
                    showToast(getString(R.string.main_toast_contact_selected, selectedName))
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

    private fun renderSelectedChild(child: Child, option: ParentLinkedChildOption?) {
        selectedPersonCanBeListenedTo = if (option != null) option.role == FamilyRole.CHILD
            else child.role == ru.example.childwatch.contacts.ContactRoles.CHILD
        binding.audioStreamingCard.isEnabled = selectedPersonCanBeListenedTo
        binding.audioStreamingCard.alpha = if (selectedPersonCanBeListenedTo) 1f else 0.45f
        binding.audioStreamingCard.contentDescription = if (selectedPersonCanBeListenedTo)
            getString(R.string.home_action_listen) else getString(R.string.listen_child_only)
        applySelectedPersonActions()
        binding.selectedChildLocation.isVisible = true
        val locationScope = selectedLocationScopeKey()
        if (locationScope != selectedLocationScope || binding.selectedChildLocation.text.isNullOrBlank()) {
            binding.selectedChildLocation.setText(ru.example.childwatch.designsystem.R.string.cw_distance_loading)
        }
        val displayName = option?.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: child.name.trim().ifBlank { getString(R.string.main_default_child_name) }
        binding.selectedChildName.text = displayName
        binding.deviceInfoTitle.text = getString(R.string.home_selected_device_title, displayName)
        binding.selectedChildDeviceId.text = selectedChildMeta(child, option)
        binding.childSelectionContainer.contentDescription = getString(
            R.string.family_profile_edit_named,
            displayName
        )

        val avatar = option?.avatarKey?.trim()?.takeIf { it.isNotBlank() }
            ?: child.avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        selectedPersonAvatarValue = avatar
        // The person's name drives the letter avatar when no picture is stored.
        val avatarIdentity = listOf(locationScope, avatar, displayName)
        if (selectedHomeAvatarIdentity != avatarIdentity) {
            FamilyAvatarRenderer.bind(binding.selectedChildAvatar, avatar, displayName)
            selectedHomeAvatarIdentity = avatarIdentity
        }
        startSelectedLocationUpdates()
        updateDeviceInfoCard()
    }

    private fun applySelectedPersonActions() {
        homeDirectory?.let(::renderHomeFamily)
        listOf<View>(binding.audioStreamingCard, binding.remoteCameraCard, binding.deviceUsageButton, binding.deviceUsageCard).forEach { view ->
            view.isEnabled = selectedPersonCanBeListenedTo
            view.alpha = if (selectedPersonCanBeListenedTo) 1f else 0.45f
        }
        binding.remoteCameraCard.contentDescription = if (selectedPersonCanBeListenedTo)
            getString(R.string.home_action_photo) else getString(R.string.home_child_action_only)
        binding.deviceUsageButton.contentDescription = if (selectedPersonCanBeListenedTo)
            getString(R.string.home_action_activity) else getString(R.string.home_child_action_only)
        binding.deviceUsageCard.contentDescription = binding.deviceUsageButton.contentDescription
        binding.deviceInfoCameraValue.isVisible = selectedPersonCanBeListenedTo
        binding.deviceInfoCurrentAppValue.isVisible = selectedPersonCanBeListenedTo
        binding.selectedPersonActionsHint.text = getString(if (selectedPersonCanBeListenedTo)
            R.string.home_selected_child_actions else R.string.home_selected_adult_actions)
    }

    private fun selectedLocationScopeKey(): String = org.json.JSONArray()
        .put(effectiveContextResolver.resolveServerUrl())
        .put(effectiveContextResolver.resolveFamilyId())
        .put(effectiveContextResolver.resolveOwnParentId())
        .put(resolveSelectedChildIdForUi()).toString()

    private fun startSelectedLocationUpdates() {
        if (!screenVisible) return
        val scope = selectedLocationScopeKey()
        if (selectedLocationScope == scope && selectedLocationJob?.isActive == true) return
        selectedLocationJob?.cancel()
        selectedLocationScope = scope
        selectedLocationJob = lifecycleScope.launch {
            while (isActive && selectedLocationScopeKey() == scope) {
                val requestedId = resolveSelectedChildIdForUi()
                if (!requestedId.isNullOrBlank()) {
                    val ownId = effectiveContextResolver.resolveOwnParentId()
                    val summary = familyLocationSummary.forPerson(requestedId, ownId)
                    if (selectedLocationScopeKey() == scope) {
                        if (binding.selectedChildLocation.text.toString() != summary) binding.selectedChildLocation.text = summary
                        binding.selectedChildLocation.isVisible = true
                    }
                }
                delay(30_000L)
            }
        }
    }

    private fun selectedChildMeta(child: Child, option: ParentLinkedChildOption?): String {
        val role = when (option?.role) {
            FamilyRole.PARENT -> getString(R.string.family_role_parent)
            FamilyRole.GUARDIAN -> getString(R.string.family_role_relative)
            FamilyRole.CHILD -> getString(R.string.family_role_child)
            null -> ContactRoles.label(child.role)
        }
        selectedHomeMetaScope = selectedLocationScopeKey()
        selectedHomeRoleLabel = role
        return getString(R.string.family_profile_role_and_status, role, selectedHomePresenceText())
    }

    private fun refreshSelectedHomePresence() {
        if (selectedHomeMetaScope != selectedLocationScopeKey()) return
        val role = selectedHomeRoleLabel ?: return
        binding.selectedChildDeviceId.text = getString(
            R.string.family_profile_role_and_status, role, selectedHomePresenceText())
    }

    private fun selectedHomePresenceText(): String {
        val deviceId = resolveSelectedChildIdForUi()
        val directory = homeDirectory?.takeIf { homeDirectoryScope == homePickupScope() }
        // Use the chosen endpoint, never another phone's heartbeat for this person.
        val device = directory?.personByDeviceId(deviceId)?.activeDevices
            ?.firstOrNull { it.deviceId == deviceId }
        val lastSeen = normalizeEpochMillis(device?.lastSeenAt)
        val presence = ru.childwatch.shared.family.FamilyDeviceHeartbeatPolicy.presence(
            lastSeen, homeDirectoryIsCanonical && directory != null,
            System.currentTimeMillis())
        val lastSeenText = lastSeen?.let { SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(Date(it)) }.orEmpty()
        val status = when (presence) {
            FamilyPresenceState.ONLINE -> getString(R.string.home_presence_recent_heartbeat)
            FamilyPresenceState.RECENTLY_ACTIVE -> getString(R.string.home_presence_last_heartbeat, lastSeenText)
            FamilyPresenceState.OFFLINE -> getString(R.string.home_presence_old_heartbeat, lastSeenText)
            FamilyPresenceState.UNKNOWN -> getString(R.string.family_profile_presence_unknown)
        }
        return status
    }

    private fun persistSelectedChildCompat(
        deviceId: String,
        memberId: String? = null,
        familyId: String? = null
    ) {
        val normalized = deviceId.trim()
        if (normalized.isBlank()) return

        activeSessionStore.updateFocusedChildId(normalized)
        contextProvider.updateSelection(focusedMemberId = memberId, targetDeviceId = normalized)
        if (!familyId.isNullOrBlank()) {
            contextProvider.updateFamilyIdentity(
                familyId = familyId,
                selfMemberId = contextProvider.current()?.selfMemberId,
                focusedMemberId = memberId
            )
        }
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
        if (!selectedPersonCanBeListenedTo) {
            showToast(getString(R.string.home_child_action_only))
            return
        }
        val targetDeviceId = resolveFeatureTargetDeviceId("remote-photo")
        if (targetDeviceId.isNullOrBlank()) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.main_toast_set_child_device_id),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(this@MainActivity, RemoteCameraActivity::class.java).apply {
            putExtra(RemoteCameraActivity.EXTRA_CHILD_ID, targetDeviceId)
            putExtra(RemoteCameraActivity.EXTRA_CHILD_NAME, binding.selectedChildName.text?.toString().orEmpty())
        }
        startActivity(intent)
    }

    private fun openDeviceUsage() {
        if (!selectedPersonCanBeListenedTo) {
            showToast(getString(R.string.home_child_action_only))
            return
        }
        val targetDeviceId = resolveFeatureTargetDeviceId("activity")
        if (targetDeviceId.isNullOrBlank()) {
            showToast(getString(R.string.device_usage_pairing_required))
            return
        }
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
                    timestamp,
                    targetDeviceId = deviceId
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
