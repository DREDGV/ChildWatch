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
import ru.example.parentwatch.session.ChildDeviceIdentity
import ru.example.parentwatch.session.ChildEffectiveContextProvider
import ru.example.parentwatch.session.ChildFamilyDirectoryRepository
import ru.example.parentwatch.session.ChildFamilyOnboardingStore
import ru.example.parentwatch.session.ChildParticipantNameResolver
import ru.example.parentwatch.session.ChildProfileRuntimeCoordinator
import ru.example.parentwatch.profile.AvatarPhotoSession
import ru.example.parentwatch.profile.FamilyAvatarRenderer
import ru.example.parentwatch.update.UpdateManager
import ru.example.parentwatch.update.UpdateResultReceiver
import ru.example.parentwatch.update.UpdateUiController
import ru.childwatch.shared.onboarding.FamilyOnboardingEntryDecision
import ru.childwatch.shared.onboarding.FamilyOnboardingEntryPolicy
import ru.childwatch.shared.family.FamilyDirectorySnapshot
import ru.childwatch.shared.family.FamilyPresenceState
import ru.childwatch.shared.family.FamilyRole
import ru.example.childwatch.designsystem.HomeFamilyStrip
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

    private fun homePickupScope(): String {
        val resolver = ru.example.parentwatch.session.ChildEffectiveContextResolver(this)
        val family = resolver.resolveFamilyId()?.takeIf { it.isNotBlank() } ?: return ""
        val member = resolver.resolveSelfMemberId()?.takeIf { it.isNotBlank() } ?: return ""
        val server = resolver.resolveServerUrl().takeIf { it.isNotBlank() } ?: return ""
        val own = resolver.resolveChildDeviceId().takeIf { it.isNotBlank() } ?: return ""
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
                    val resolver = ru.example.parentwatch.session.ChildEffectiveContextResolver(this@MainActivity)
                    startActivity(DualLocationMapActivity.createIntent(this@MainActivity, DualLocationMapActivity.ROLE_CHILD,
                        resolver.resolveChildDeviceId(), resolver.resolveParentDeviceId()).apply {
                        putExtra("open_pickups", true); putExtra("pickup_scope", expectedScope)
                    })
                }
            })
    }

    private var homeSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var homeSelectedMemberId: String? = null
    private var homeSelectedDeviceId: String? = null

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

    /**
     * Who is waiting for a picture the person picks.
     *
     * The result contract is registered here, while the screen is still being
     * created, because a contract cannot be registered once the activity is
     * STARTED — the profile dialog opens from a tap on a resumed screen, so
     * registering it there closed the application.
     */
    private var profilePhotoCallback: ((android.net.Uri?) -> Unit)? = null

    private val profilePhotoPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { picked ->
        val callback = profilePhotoCallback
        profilePhotoCallback = null
        callback?.invoke(picked)
    }
    
    
    // Photo integration for remote photo capture
    private var photoIntegration: ru.example.parentwatch.network.PhotoIntegration? = null

    // UI elements
    private lateinit var titleText: TextView
    private lateinit var activeProfileName: TextView
    private lateinit var activeProfileMeta: TextView
    private lateinit var activeProfileAvatar: com.google.android.material.imageview.ShapeableImageView
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
    private val familyOnboardingStore by lazy { ChildFamilyOnboardingStore(this) }
    private var badgeRefreshJob: Job? = null
    private var familyOnboardingCheckStarted = false

    /**
     * Updates over the air: the quiet check, the notice, the download and the request
     * to the system installer.
     *
     * It is built here rather than inside the update package because a notice needs a
     * screen to appear on and a coroutine scope tied to this screen's lifetime. The work
     * itself stays in that package — this class only says where the notice goes and
     * which server address to use.
     */
    private val updateUi by lazy {
        UpdateUiController(
            context = this,
            scope = lifecycleScope,
            noticeContainer = findViewById(R.id.updateNoticeContainer),
            // The very address the network client talks to, so the manifest and the
            // file can never be looked for on two different hosts.
            serverUrlProvider = { networkClient.resolveConfiguredServerUrl() }
        )
    }

    /**
     * Whether this screen is on top right now.
     *
     * The installer's confirmation may only be opened from a visible screen, and a
     * screen that is merely stopped is not visible — so this is a flag of its own
     * rather than a guess from the activity's state.
     */
    private var screenVisible = false

    /** True when an installation confirmation is waiting for a visible screen. */
    private var resumeConfirmationOnStart = false

    /** True while this screen is the place an installer confirmation may be opened from. */
    private var updateUiAttached = false

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

    override fun onSaveInstanceState(outState: Bundle) {
        ru.example.childwatch.designsystem.FamilyProfileEditor.saveState(this, outState)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ru.example.childwatch.designsystem.FamilyProfileEditor.restore(this, savedInstanceState) { openProfileEditor() }
        findViewById<android.view.View>(R.id.checkUpdatesButton).setOnClickListener {
            updateUi.checkAndShowNotice(force = true)
        }

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

        // Updates are the last thing this screen deals with, and deliberately so: the
        // check cannot block anything, and a child opening the application must not be
        // held up by it.
        attachUpdateUi()
        updateUi.checkAndShowNotice()
        
        // PhotoIntegration is deprecated - RemotePhotoService now handles this via WebSocketManager
        // initializePhotoIntegration()
        
        // Open chat directly when launched from a notification
        if (intent.getBooleanExtra("open_chat", false)) {
            NotificationManager.resetUnreadCount()
            updateChatBadge()
            val chatIntent = Intent(this, ChatConversationsActivity::class.java)
            startActivity(chatIntent)
        }

        maybeLaunchFamilyOnboarding()
    }

    /**
     * Existing installations keep working through their migrated relationship.
     * A genuinely new phone must join a server-side person profile before use.
     */
    private fun maybeLaunchFamilyOnboarding() {
        if (familyOnboardingCheckStarted) return
        if (familyOnboardingStore.isCompleted()) {
            familyOnboardingCheckStarted = true
            ensureRuntimePermissions()
            return
        }
        familyOnboardingCheckStarted = true

        lifecycleScope.launch {
            val membership = runCatching {
                if (!networkClient.ensureOnboardingAuthentication()) null
                else networkClient.getAuthenticatedIdentity()
                    .takeIf { it.isSuccessful }
                    ?.body()
                    ?.memberships
                    .orEmpty()
                    .firstOrNull { it.member.isActive }
            }.onFailure { error ->
                Log.w("MainActivity", "Unable to verify family onboarding", error)
            }.getOrNull()

            when (
                FamilyOnboardingEntryPolicy.decide(
                    localCompleted = familyOnboardingStore.isCompleted(),
                    hasServerMembership = membership != null,
                    hasLegacyParentLink = familyOnboardingStore.hasLegacyParentLink()
                )
            ) {
                FamilyOnboardingEntryDecision.COMPLETE_FROM_SERVER -> {
                    familyOnboardingStore.markCompleted(
                        membership!!.familyId,
                        membership.memberId
                    )
                    runCatching {
                        ChildFamilyDirectoryRepository(this@MainActivity).refresh()
                    }
                    updateQuickProfileSummary()
                    ensureRuntimePermissions()
                }
                FamilyOnboardingEntryDecision.OPEN_JOIN_WIZARD -> {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(Intent(this@MainActivity, FamilyJoinActivity::class.java).apply {
                            putExtra(FamilyJoinActivity.EXTRA_REQUIRED_ON_FIRST_RUN, true)
                        })
                    }
                }
                FamilyOnboardingEntryDecision.KEEP_COMPLETED,
                FamilyOnboardingEntryDecision.PRESERVE_LEGACY_LINK -> ensureRuntimePermissions()
            }
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
        findViewById<View>(R.id.homeUpdateButton).setOnClickListener {
            if (homeSheet?.isShowing != true) homeSheet = ru.example.childwatch.designsystem.HomeDetailSheet.show(
                findViewById(R.id.updateNoticeContainer), getString(R.string.cw_home_update_available)
            )
        }
        findViewById<View>(R.id.homeContent).viewTreeObserver.addOnGlobalLayoutListener {
            val notices = findViewById<android.widget.LinearLayout>(R.id.updateNoticeContainer)
            val hasUpdate = (0 until notices.childCount).any { notices.getChildAt(it).visibility == View.VISIBLE }
            val button = findViewById<View>(R.id.homeUpdateButton)
            val visibility = if (hasUpdate) View.VISIBLE else View.GONE
            if (button.visibility != visibility) button.visibility = visibility
        }
        // Find UI elements
    titleText = findViewById(R.id.titleText)
        activeProfileName = findViewById(R.id.activeProfileName)
        activeProfileMeta = findViewById(R.id.activeProfileMeta)
        activeProfileAvatar = findViewById(R.id.activeProfileAvatar)
        chatCard = findViewById(R.id.chatCard)
        chatBadge = findViewById(R.id.chatBadge)
        settingsCard = findViewById(R.id.settingsCard)
        lastUpdateText = findViewById(R.id.lastUpdateText)

    // Set header title: name only (no version)
    titleText.text = getString(R.string.cw_home_title)
        renderHomeFamily(ChildFamilyDirectoryRepository(this).loadCached())

        findViewById<MaterialButton>(R.id.switchProfileQuickButton)?.setOnClickListener {
            openProfileEditor()
        }
        // The card itself opens the same editor: it shows the avatar and name,
        // so tapping it is the natural way to change them.
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.activeProfileCard)
            ?.setOnClickListener { openProfileEditor() }
        
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
        val parentId = homeSelectedDeviceId.orEmpty().ifBlank { contextProvider.current()?.targetDeviceId.orEmpty() }
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
        findViewById<View>(R.id.childConnectionCard).setOnClickListener { promptSettingsAccess() }
        findViewById<View>(R.id.childHomePersonCard).setOnClickListener {
            findViewById<View>(R.id.parentLocationCard).performClick()
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
            // The build date is shown next to the version: a version string alone does
            // not tell anybody which build a phone is actually running.
            lastUpdateText.text = getString(
                R.string.child_status_version_line,
                dateLine,
                appVersion,
                BuildConfig.BUILD_STAMP
            )
        }
    }

    /**
     * Opens the profile editor in place.
     *
     * It used to jump to the settings screen, which moved the user to a different
     * part of the app for a change as small as a new picture, so it now stays on
     * this screen.
     */
    private fun openProfileEditor() {
        ru.example.parentwatch.profile.OwnProfileEditor.show(
            this,
            requestPhoto = { onPicked ->
                profilePhotoCallback = onPicked
                profilePhotoPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onStored = { updateQuickProfileSummary() }
        )
    }

    private fun showQuickProfilePicker() {
        // The edit screen, which is the only place with the avatar picker, was
        // previously reachable only through the ambiguous label "Сохранить
        // текущий", so it looked as if a profile could not be changed at all.
        val actionLabels = arrayOf(
            getString(R.string.profile_switch_edit_title),
            getString(R.string.profile_switch_apply),
            getString(R.string.profile_switch_manage)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_switch_manage_title)
            .setItems(actionLabels) { _, which ->
                when (which) {
                    0 -> openProfileEditor()
                    1 -> showQuickProfileSwitchDialog()
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
        val wasRunning = ru.example.parentwatch.service.MonitoringRecovery.isDesired(this)
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
            FamilyAvatarRenderer.bind(activeProfileAvatar, null, null)
            homeSelectedMemberId = null
            homeSelectedDeviceId = null
            renderHomeFamily(null)
            return
        }

        val displayName = participantNameResolver.resolveChildDisplayName()
        activeProfileName.text = displayName
        activeProfileMeta.text = if (parentId.isNullOrBlank()) {
            getString(R.string.home_child_profile_not_connected)
        } else {
            getString(R.string.cw_home_child_configured)
        }
        // The canonical directory already carries this profile's avatar key; it
        // was simply never read here, so the card kept showing the app icon.
        // The name is passed as well, so a profile without a picture still gets
        // a recognizable initial instead of a bare question mark.
        FamilyAvatarRenderer.bind(
            activeProfileAvatar,
            participantNameResolver.resolveChildAvatarKey(),
            displayName
        )
        findViewById<View>(R.id.activeProfileCard).contentDescription = getString(R.string.cw_home_own_profile) + ": " + displayName
        renderHomeFamily(ChildFamilyDirectoryRepository(this).loadCached())
    }

    private fun renderHomeFamily(directory: FamilyDirectorySnapshot?) {
        val strip = findViewById<HomeFamilyStrip>(R.id.familyStrip)
        val people = directory?.people.orEmpty()
        val selected = directory?.let { it.person(homeSelectedMemberId) ?: it.person(it.selfMemberId) }
        strip.render(people.map { HomeFamilyStrip.Person(it.member.id, it.member.displayName, it.member.avatarKey) },
            selected?.member?.id, { view, key, name -> FamilyAvatarRenderer.bind(view, key, name) }, { id ->
                homeSelectedMemberId = id
                renderHomeFamily(directory)
            }, {
                if (people.isEmpty()) findViewById<View>(R.id.parentLocationCard).performClick()
                else com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.cw_home_family_all)
                    .setItems(people.map { it.member.displayName }.toTypedArray()) { _, index ->
                        homeSelectedMemberId = people[index].member.id
                        renderHomeFamily(directory)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }, false)
        homeSelectedDeviceId = selected?.takeUnless { it.member.id == directory?.selfMemberId }?.primaryDevice()?.deviceId
        if (selected == null) {
            FamilyAvatarRenderer.bind(findViewById(R.id.childHomePersonAvatar), participantNameResolver.resolveChildAvatarKey(), activeProfileName.text.toString())
            return
        }
        activeProfileName.text = selected.member.displayName
        val role = getString(if (selected.member.role == FamilyRole.CHILD) R.string.cw_home_role_child else R.string.cw_home_role_adult)
        val presence = getString(when (selected.presence()) {
            FamilyPresenceState.ONLINE -> R.string.cw_home_recent
            FamilyPresenceState.RECENTLY_ACTIVE -> R.string.cw_home_seen_recently
            FamilyPresenceState.OFFLINE -> R.string.cw_home_offline
            FamilyPresenceState.UNKNOWN -> R.string.cw_home_presence_unknown
        })
        activeProfileMeta.text = "$role · $presence"
        FamilyAvatarRenderer.bind(findViewById(R.id.childHomePersonAvatar), selected.member.avatarKey, selected.member.displayName)
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
                    val label = participantNameResolver.resolveParentDisplayName(
                        parentDeviceId = parentId,
                        legacyCandidates = listOf(
                            item.optString("parentDisplayName").trim(),
                            item.optString("displayName").trim()
                        )
                    ) ?: getString(R.string.family_member_name_missing)
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
        homePickupCard.resume()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val resolver = ru.example.parentwatch.session.ChildEffectiveContextResolver(this@MainActivity)
                val server = resolver.resolveServerUrl()
                val own = resolver.resolveChildDeviceId()
                if (server.isNotBlank() && own.isNotBlank()) {
                    val snapshot = ru.example.parentwatch.utils.DeviceInfoCollector.getDeviceInfo(this@MainActivity, includeCurrentApp = false)
                    if (server == resolver.resolveServerUrl() && own == resolver.resolveChildDeviceId())
                        ru.example.parentwatch.network.NetworkHelper(this@MainActivity).uploadDeviceStatus(server, snapshot)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("CameraDiagnostics", "Status refresh postponed", error) }
        }
        prefs.edit().putBoolean("chat_open", false).apply()
        screenVisible = true
        // A notification may have opened this screen exactly to finish an installation;
        // the confirmation is opened again as soon as the screen is visible.
        if (resumeConfirmationOnStart || updateUi.wasOpenedToContinue(intent)) {
            resumeConfirmationOnStart = false
            UpdateManager.deliverPendingConfirmation()
        }
        recoverMonitoringServiceIfNeeded()
        if (LocationService.isServiceAlive) {
            LocationService.retryAudioAfterForeground(this)
        } else if (ru.example.parentwatch.service.AudioStreamingService.isStreamingDesired(this)) {
            ru.example.parentwatch.service.AudioStreamingService.resumeIfDesired(this)
        }
        ensurePhotoCaptureService()
        ensureChatBackgroundService()
        updateQuickProfileSummary()
        lifecycleScope.launch {
            if (participantNameResolver.refreshCanonicalDirectory()) {
                updateQuickProfileSummary()
            }
        }
        updateChatBadge()
        startBadgeRefreshLoop()
        // Returning to the foreground is the other moment an update is checked for. At
        // most once a day, and only after a check that succeeded: the limit is recorded
        // by the update package, never by this screen.
        updateUi.checkAndShowNotice()
    }

    /**
     * Gives the update feature what it needs from this screen.
     *
     * The screen is registered as the place an installer confirmation may be opened
     * from — while it is visible that is allowed, and while it is not the notification
     * the receiver posts takes over, which is why the sink checks [screenVisible]. The
     * note about a previous installation is read here, exactly once, so a failure that
     * happened while the application was being replaced is still explained to whoever
     * uses this phone.
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
        updateUi.showPendingFailureNote()
    }

    /**
     * Handles the notification that continues an installation.
     *
     * The screen is usually still in memory when its own notification is tapped, so
     * Android reuses it and `onResume` never runs again. Without this the confirmation
     * would be held but never shown, which looks exactly like an update that quietly did
     * nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        UpdateManager.deliverPendingConfirmation()
    }

    private fun recoverMonitoringServiceIfNeeded() {
        val desiredRunning = ru.example.parentwatch.service.MonitoringRecovery.isDesired(this)
        isServiceRunning = desiredRunning

        if (!desiredRunning) return
        if (LocationService.isMonitoringActive) return

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
        homePickupCard.pause()
        badgeRefreshJob?.cancel()
        // The screen is no longer visible, so it is no longer the place the installer's
        // confirmation may be opened from. The claim itself is kept: the receiver may
        // deliver while this screen is stopped, and it has to have somewhere to put it.
        screenVisible = false
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
                if (!LocationService.startTrackingService(this, serviceIntent)) {
                    isServiceRunning = false
                    ensureChatBackgroundService()
                    updateUI()
                    return
                }

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
        ru.example.parentwatch.service.MonitoringRecovery.disable(this)
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
        ru.example.parentwatch.service.MonitoringRecovery.disable(this)
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
        val configured = familyOnboardingStore.isCompleted()
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val serviceAlive = isLocationServiceAlive()
        findViewById<TextView>(R.id.childConnectionText).setText(when {
            !configured -> R.string.cw_home_child_unconfigured
            !locationGranted -> R.string.cw_home_child_permission
            serviceAlive -> R.string.cw_home_child_running
            else -> R.string.cw_home_child_stopped
        })
        lastUpdateText.text = "ChildDevice · $appVersion"
        lastUpdateText.contentDescription = "$appVersion · ${BuildConfig.BUILD_STAMP}"
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
        val deviceId = contextProvider.current()?.selfDeviceId.orEmpty().ifBlank {
            sessionStore.resolveCurrentChildId()
        }.ifBlank {
            prefs.getString("device_id", null).orEmpty()
        }.ifBlank {
            // The shared source is the only place allowed to invent an identifier;
            // a random value here could disagree with what registration uses.
            ChildDeviceIdentity.resolve(this)
        }

        // Keep existing ID stable to avoid breaking pairing/streaming after updates.
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
        homePickupCard.dispose()
        homeSheet?.dismiss()
        homeSheet = null
        super.onDestroy()
        badgeRefreshJob?.cancel()
        photoIntegration?.unregister()
        photoIntegration = null
        // The screen is going away for good, so it stops being the place the installer's
        // confirmation may be opened from.
        if (updateUiAttached) {
            updateUiAttached = false
            UpdateManager.attachSink(null)
            UpdateResultReceiver.detach()
        }
    }
}
