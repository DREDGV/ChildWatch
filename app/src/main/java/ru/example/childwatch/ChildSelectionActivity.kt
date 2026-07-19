package ru.example.childwatch

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import ru.example.childwatch.adapter.ChildrenAdapter
import ru.example.childwatch.attention.ParentAttentionSignalLauncher
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.database.repository.ChildRepository
import ru.example.childwatch.contacts.ContactFeatures
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.contacts.ContactRoles
import ru.example.childwatch.databinding.ActivityChildSelectionBinding
import ru.example.childwatch.network.LinkedChildLink
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentFamilyDirectoryRepository
import ru.example.childwatch.profile.ParentLinkedChildOption
import ru.example.childwatch.profile.ParentLinkedChildOptionsProvider
import ru.example.childwatch.profile.ParentProfileRuntimeCoordinator
import ru.example.childwatch.profile.FamilyAvatarRenderer
import android.util.Log

/**
 * Activity для выбора и управления детскими устройствами
 *
 * Показывает список всех зарегистрированных детских устройств,
 * позволяет добавлять новые и выбирать текущее для мониторинга.
 */
class ChildSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildSelectionBinding
    private lateinit var childrenAdapter: ChildrenAdapter
    private lateinit var database: ChildWatchDatabase
    private lateinit var childRepository: ChildRepository
    private lateinit var activeSessionStore: ParentActiveSessionStore
    private lateinit var profileRuntimeCoordinator: ParentProfileRuntimeCoordinator
    private lateinit var effectiveContextResolver: ParentEffectiveContextResolver
    private lateinit var networkClient: NetworkClient
    private lateinit var linkedChildOptionsProvider: ParentLinkedChildOptionsProvider
    private lateinit var familyDirectoryRepository: ParentFamilyDirectoryRepository
    private var familyOptionsByDevice: Map<String, ParentLinkedChildOption> = emptyMap()
    private var pendingEditChildId: String? = null
    private var selectedAvatarValue: String? = null
    private var currentAvatarPresetViews: List<ShapeableImageView> = emptyList()

    // Launcher for avatar selection
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                // Запросить постоянный доступ к URI
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                selectedAvatarValue = it.toString()
                currentAvatarImageView?.let { view ->
                    FamilyAvatarRenderer.bind(view, selectedAvatarValue)
                }
                refreshAvatarPresetSelection()
                Log.d(TAG, "Avatar selected with persistent permission: $it")
            } catch (e: Exception) {
                // Если не получилось получить постоянный доступ, все равно используем URI
                Log.w(TAG, "Could not take persistable URI permission, using temporary: ${e.message}")
                selectedAvatarValue = it.toString()
                currentAvatarImageView?.let { view ->
                    FamilyAvatarRenderer.bind(view, selectedAvatarValue)
                }
                refreshAvatarPresetSelection()
            }
        }
    }

    // Keep reference to current avatar ImageView in dialog
    private var currentAvatarImageView: ImageView? = null

    // Keep references to current dialog inputs for contact picker
    private var currentNameInput: TextInputEditText? = null
    private var currentPhoneInput: TextInputEditText? = null
    private var currentDeviceIdInput: TextInputEditText? = null
    private var currentRoleInput: MaterialAutoCompleteTextView? = null
    private var currentIconInput: MaterialAutoCompleteTextView? = null
    private var currentChatCheck: MaterialCheckBox? = null
    private var currentMapCheck: MaterialCheckBox? = null
    private var currentAudioCheck: MaterialCheckBox? = null
    private var currentPhotoCheck: MaterialCheckBox? = null

    // Launcher for contact selection
    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { contactUri ->
        contactUri?.let {
            loadContactData(it)
        }
    }

    // Launcher for single phone number selection
    private val pickPhoneLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { contactUri ->
        contactUri?.let {
            loadContactPhone(it)
        }
    }

    // Launcher for QR code scanning
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            currentDeviceIdInput?.setText(result.contents)
            Log.d(TAG, "QR code scanned: ${result.contents}")
            Toast.makeText(this, "Device ID отсканирован", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "QR scan cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d(TAG, "onCreate started")
            binding = ActivityChildSelectionBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Log.d(TAG, "View binding successful")

            activeSessionStore = ParentActiveSessionStore(this)
            profileRuntimeCoordinator = ParentProfileRuntimeCoordinator(this)
            effectiveContextResolver = ParentEffectiveContextResolver(this)
            networkClient = NetworkClient(this)
            linkedChildOptionsProvider = ParentLinkedChildOptionsProvider(this)
            familyDirectoryRepository = ParentFamilyDirectoryRepository(this)
            database = ChildWatchDatabase.getInstance(this)
            childRepository = ChildRepository(database.childDao())
            pendingEditChildId = intent?.getStringExtra(EXTRA_EDIT_CHILD_ID)?.trim()?.takeIf { it.isNotBlank() }
            Log.d(TAG, "Database initialized")

            setupToolbar()
            setupRecyclerView()
            setupFab()
            loadChildren()
            Log.d(TAG, "onCreate completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: onCreate failed", e)
            showError("Ошибка инициализации: ${e.message}")
            finish()
        }
    }

    /**
     * Настройка Toolbar
     */
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    /**
     * Настройка RecyclerView
     */
    private fun setupRecyclerView() {
        childrenAdapter = ChildrenAdapter(
            onChildClick = { child -> onChildSelected(child) },
            onChildEdit = { child -> showEditChildDialog(child) },
            onChildAttention = { child ->
                val option = familyOptionsByDevice[child.deviceId]
                ParentAttentionSignalLauncher.show(
                    activity = this,
                    explicitTargetDeviceId = child.deviceId,
                    explicitTargetName = option?.displayName ?: child.name,
                    explicitTargetAvatarValue = option?.avatarKey ?: child.avatarUrl,
                    explicitTargetMemberId = option?.memberId,
                    explicitFamilyId = option?.familyId
                )
            }
        )

        binding.childrenRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChildSelectionActivity)
            adapter = childrenAdapter
        }
        childrenAdapter.updatePresentation(
            options = emptyList(),
            selectedDeviceId = effectiveContextResolver.resolveTargetDeviceId()
        )
    }

    /**
     * Настройка FAB для добавления устройства
     */
    private fun setupFab() {
        binding.addChildFab.setOnClickListener {
            showAddChildDialog()
        }
    }

    /**
     * Загрузка списка детей
     */
    private fun loadChildren() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val children = childRepository.getAllChildren()

                renderChildren(children)
                maybeOpenRequestedChildEditor(children)

                binding.progressBar.visibility = View.GONE
                syncLinkedChildrenFromServer()
                Log.d(TAG, "Загружено устройств: ${children.size}")

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки устройств", e)
                binding.progressBar.visibility = View.GONE
                showError("Ошибка загрузки: ${e.message}")
            }
        }
    }

    /**
     * Показать список детей
     */
    private fun showChildrenList(children: List<Child>) {
        binding.childrenRecyclerView.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        childrenAdapter.submitList(children)
        childrenAdapter.updatePresentation(
            options = familyOptionsByDevice.values.toList(),
            selectedDeviceId = effectiveContextResolver.resolveTargetDeviceId()
        )
    }

    /**
     * Показать пустое состояние
     */
    private fun showEmptyState() {
        binding.childrenRecyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.VISIBLE
    }

    private fun renderChildren(children: List<Child>) {
        if (children.isEmpty()) {
            showEmptyState()
        } else {
            showChildrenList(children)
        }
    }

    private fun maybeOpenRequestedChildEditor(children: List<Child>) {
        val requestedDeviceId = pendingEditChildId ?: return
        val child = children.firstOrNull { it.deviceId == requestedDeviceId } ?: return
        pendingEditChildId = null
        binding.root.post {
            showEditChildDialog(child)
        }
    }

    private suspend fun syncLinkedChildrenFromServer() {
        val parentDeviceId = effectiveContextResolver.resolveOwnParentId().trim()
        val serverUrl = effectiveContextResolver.resolveServerUrl().trim()
        if (parentDeviceId.isBlank() || serverUrl.isBlank()) {
            Log.d(TAG, "Skipping linked child sync: missing parent/server context")
            return
        }

        runCatching {
            val beforeIds = childRepository.getAllChildren().mapTo(mutableSetOf(), Child::deviceId)
            val options = linkedChildOptionsProvider.getOptions()
            familyOptionsByDevice = options.associateBy { it.deviceId }
            linkedChildOptionsProvider.syncLocalChildren(options)
            val refreshedChildren = childRepository.getAllChildren()
            val importedCount = refreshedChildren.count { it.deviceId !in beforeIds }
            renderChildren(refreshedChildren)
            childrenAdapter.updatePresentation(
                options = options,
                selectedDeviceId = effectiveContextResolver.resolveTargetDeviceId()
            )
            if (importedCount > 0) {
                Toast.makeText(
                    this@ChildSelectionActivity,
                    getString(R.string.relationship_sync_imported, importedCount),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to sync linked children from server", error)
        }
    }

    private suspend fun mergeLinkedChildren(links: List<LinkedChildLink>): Int {
        var importedCount = 0

        links.forEach { link ->
            val childDeviceId = link.childDeviceId.trim()
            if (childDeviceId.isBlank()) return@forEach

            val existingChild = childRepository.getChildByDeviceId(childDeviceId)
            val resolvedName = link.displayName?.trim().takeUnless { it.isNullOrBlank() }
                ?: link.childDeviceName?.trim().takeUnless { it.isNullOrBlank() }
                ?: childDeviceId

            if (existingChild == null) {
                childRepository.insertOrUpdateChild(
                    Child(
                        deviceId = childDeviceId,
                        name = resolvedName,
                        role = ContactRoles.CHILD,
                        iconId = ContactIcons.DEFAULT,
                        allowedFeatures = ContactFeatures.CHAT or
                            ContactFeatures.MAP or
                            ContactFeatures.AUDIO or
                            ContactFeatures.PHOTO,
                        isActive = true
                    )
                )
                importedCount += 1
                return@forEach
            }

            val shouldRefreshName = existingChild.name.isBlank() ||
                existingChild.name == existingChild.deviceId
            if (shouldRefreshName && existingChild.name != resolvedName) {
                childRepository.insertOrUpdateChild(
                    existingChild.copy(
                        name = resolvedName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        return importedCount
    }

    private suspend fun linkChildOnServer(
        childDeviceId: String,
        childName: String,
        childIconId: Int? = null
    ): Boolean {
        val parentDeviceId = effectiveContextResolver.resolveOwnParentId().trim()
        val serverUrl = effectiveContextResolver.resolveServerUrl().trim()
        if (parentDeviceId.isBlank() || serverUrl.isBlank() || childDeviceId.isBlank()) {
            Log.d(TAG, "Skipping link sync: missing parent/server/child context")
            return false
        }

        return runCatching {
            networkClient.linkParentChild(
                parentDeviceId = parentDeviceId,
                childDeviceId = childDeviceId,
                childDisplayName = childName.ifBlank { null },
                childMarkerIconId = childIconId
            ).isSuccessful
        }.getOrElse { error ->
            Log.w(TAG, "Unable to link child on server: $childDeviceId", error)
            false
        }
    }

    private suspend fun unlinkChildOnServer(childDeviceId: String): Boolean {
        val parentDeviceId = effectiveContextResolver.resolveOwnParentId().trim()
        val serverUrl = effectiveContextResolver.resolveServerUrl().trim()
        if (parentDeviceId.isBlank() || serverUrl.isBlank() || childDeviceId.isBlank()) {
            Log.d(TAG, "Skipping unlink sync: missing parent/server/child context")
            return false
        }

        return runCatching {
            networkClient.unlinkParentChild(
                parentDeviceId = parentDeviceId,
                childDeviceId = childDeviceId
            ).isSuccessful
        }.getOrElse { error ->
            Log.w(TAG, "Unable to unlink child on server: $childDeviceId", error)
            false
        }
    }

    /**
     * Обработка выбора ребенка
     */
    private fun onChildSelected(child: Child) {
        Log.d(TAG, "Выбран ребенок: ${child.name} (${child.deviceId})")
        val familyOption = familyOptionsByDevice[child.deviceId]

        // Сохранить выбранное устройство
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        profileRuntimeCoordinator.switchFocusedChild(
            childDeviceId = child.deviceId,
            focusedMemberId = familyOption?.memberId,
            familyId = familyOption?.familyId,
            shareParentLocation = prefs.getBoolean("share_parent_location", true)
        )

        // Вернуть результат
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SELECTED_DEVICE_ID, child.deviceId)
            putExtra(EXTRA_SELECTED_MEMBER_ID, familyOption?.memberId)
            putExtra(EXTRA_SELECTED_FAMILY_ID, familyOption?.familyId)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Показать диалог добавления нового устройства
     */
    private fun setupAvatarPresetChoices(dialogView: View, preview: ImageView) {
        val viewIds = intArrayOf(
            R.id.avatarPreset1,
            R.id.avatarPreset2,
            R.id.avatarPreset3,
            R.id.avatarPreset4,
            R.id.avatarPreset5,
            R.id.avatarPreset6
        )
        currentAvatarPresetViews = viewIds.map { dialogView.findViewById<ShapeableImageView>(it) }
        FamilyAvatarRenderer.presets.zip(currentAvatarPresetViews).forEachIndexed { index, (preset, view) ->
            view.apply {
                contentDescription = getString(
                    R.string.family_profile_avatar_preset_description,
                    index + 1
                )
                setOnClickListener {
                    selectedAvatarValue = preset.storageValue
                    FamilyAvatarRenderer.bind(preview, preset.storageValue)
                    refreshAvatarPresetSelection()
                }
            }
        }
        refreshAvatarPresetSelection()
    }

    private fun refreshAvatarPresetSelection() {
        if (currentAvatarPresetViews.isEmpty()) return
        val primary = ContextCompat.getColor(this, R.color.cw_color_primary)
        val outline = ContextCompat.getColor(this, R.color.cw_color_outline_variant)
        val density = resources.displayMetrics.density
        FamilyAvatarRenderer.presets.zip(currentAvatarPresetViews).forEach { (preset, view) ->
            val selected = preset.storageValue == selectedAvatarValue
            view.strokeColor = ColorStateList.valueOf(if (selected) primary else outline)
            view.strokeWidth = if (selected) 3f * density else density
            view.alpha = if (selected) 1f else 0.72f
            view.scaleX = if (selected) 1f else 0.92f
            view.scaleY = if (selected) 1f else 0.92f
        }
    }

    private fun showAddChildDialog() {
        selectedAvatarValue = FamilyAvatarRenderer.presets[1].storageValue
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_child, null)

        // Find views
        val avatarImage = dialogView.findViewById<ImageView>(R.id.childAvatarImage)
        val changeAvatarButton = dialogView.findViewById<MaterialButton>(R.id.changeAvatarButton)
        val selectContactButton = dialogView.findViewById<MaterialButton>(R.id.selectContactButton)
        val scanQrButton = dialogView.findViewById<MaterialButton>(R.id.scanQrButton)
        val deviceIdInputLayout = dialogView.findViewById<TextInputLayout>(R.id.deviceIdInputLayout)
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInputLayout = dialogView.findViewById<TextInputLayout>(R.id.childNameInputLayout)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)
        val childAgeInput = dialogView.findViewById<TextInputEditText>(R.id.childAgeInput)
        val childPhoneInputLayout = dialogView.findViewById<TextInputLayout>(R.id.childPhoneInputLayout)
        val childPhoneInput = dialogView.findViewById<TextInputEditText>(R.id.childPhoneInput)
        val roleInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.roleInput)
        val iconInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.iconInput)
        val featureChatCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featureChatCheck)
        val featureMapCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featureMapCheck)
        val featureAudioCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featureAudioCheck)
        val featurePhotoCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featurePhotoCheck)
        setupAdvancedOptions(dialogView)

        // Set up avatar selection
        currentAvatarImageView = avatarImage
        setupAvatarPresetChoices(dialogView, avatarImage)
        FamilyAvatarRenderer.bind(avatarImage, selectedAvatarValue)
        changeAvatarButton.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        // Set up contact picker
        currentNameInput = childNameInput
        currentPhoneInput = childPhoneInput
        currentDeviceIdInput = deviceIdInput
        currentRoleInput = roleInput
        currentIconInput = iconInput
        currentChatCheck = featureChatCheck
        currentMapCheck = featureMapCheck
        currentAudioCheck = featureAudioCheck
        currentPhotoCheck = featurePhotoCheck
        Log.d(TAG, "Setting up contact picker button")
        selectContactButton.setOnClickListener {
            Log.d(TAG, "Contact picker button clicked!")
            requestContactsPermission {
                Log.d(TAG, "Contacts permission granted, launching picker")
                pickContactLauncher.launch(null)
            }
        }

        // Set up QR scanner button
        scanQrButton.setOnClickListener {
            Log.d(TAG, "QR scanner button clicked")
            requestCameraPermission {
                Log.d(TAG, "Camera permission granted, launching QR scanner")
                val options = ScanOptions()
                options.setPrompt("Наведите камеру на QR-код")
                options.setBeepEnabled(true)
                options.setBarcodeImageEnabled(false)
                options.setOrientationLocked(false)
                qrScannerLauncher.launch(options)
            }
        }

        // Set up Device ID paste button
        deviceIdInputLayout.setEndIconOnClickListener {
            pasteDeviceIdFromClipboard(deviceIdInput)
        }

        // Set up phone picker button
        childPhoneInputLayout.setEndIconOnClickListener {
            requestContactsPermission {
                pickPhoneLauncher.launch(null)
            }
        }

        // Setup role/icon dropdowns
        val roleOptions = arrayOf("Ребенок", "Родитель", "Родственник")
        roleInput.setSimpleItems(roleOptions)
        roleInput.setText(ContactRoles.label(ContactRoles.CHILD), false)

        val iconOptions = ContactIcons.options()
        iconInput.setSimpleItems(iconOptions.map { it.label }.toTypedArray())
        iconInput.setText(iconOptions.first().label, false)

        // Default permissions: all enabled
        featureChatCheck.isChecked = true
        featureMapCheck.isChecked = true
        featureAudioCheck.isChecked = true
        featurePhotoCheck.isChecked = true

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.family_profile_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.family_profile_add_action, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
                currentRoleInput = null
                currentIconInput = null
                currentChatCheck = null
                currentMapCheck = null
                currentAudioCheck = null
                currentPhotoCheck = null
            }
            .setOnDismissListener {
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
                currentRoleInput = null
                currentIconInput = null
                currentChatCheck = null
                currentMapCheck = null
                currentAudioCheck = null
                currentPhotoCheck = null
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val deviceId = deviceIdInput.text.toString().trim()
                val childName = childNameInput.text.toString().trim()
                val ageText = childAgeInput.text.toString().trim()
                val phoneNumber = childPhoneInput.text.toString().trim()
                val roleValue = ContactRoles.fromLabel(roleInput.text?.toString().orEmpty())
                val iconId = resolveIconId(iconInput.text?.toString().orEmpty())
                val allowed = buildAllowedFeatures(
                    featureChatCheck.isChecked,
                    featureMapCheck.isChecked,
                    featureAudioCheck.isChecked,
                    featurePhotoCheck.isChecked
                )

                childNameInputLayout.error = if (childName.isEmpty()) {
                    getString(R.string.family_profile_name_required)
                } else null
                deviceIdInputLayout.error = if (deviceId.isEmpty()) {
                    getString(R.string.family_profile_device_code_required)
                } else null
                val age = ageText.toIntOrNull()
                val ageValid = ageText.isEmpty() || age?.let { it in 0..120 } == true
                dialogView.findViewById<TextInputLayout>(R.id.childAgeInputLayout).error =
                    if (ageValid) null else getString(R.string.family_profile_age_invalid)

                if (deviceId.isEmpty() || childName.isEmpty() || !ageValid) {
                    return@setOnClickListener
                }

                addChild(
                    deviceId = deviceId,
                    name = childName,
                    role = roleValue,
                    iconId = iconId,
                    allowedFeatures = allowed,
                    age = age,
                    phoneNumber = phoneNumber.ifEmpty { null },
                    avatarUrl = selectedAvatarValue
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Добавить новое устройство
     */
    private fun addChild(
        deviceId: String,
        name: String,
        role: String,
        iconId: Int,
        allowedFeatures: Int,
        age: Int? = null,
        phoneNumber: String? = null,
        avatarUrl: String? = null
    ) {
        lifecycleScope.launch {
            try {
                // Проверить, существует ли устройство
                val existingChild = childRepository.getChildByDeviceId(deviceId)

                if (existingChild != null) {
                    showError("Устройство с таким ID уже добавлено")
                    return@launch
                }

                // Создать новый профиль
                val child = Child(
                    deviceId = deviceId,
                    name = name,
                    role = role,
                    iconId = iconId,
                    allowedFeatures = allowedFeatures,
                    age = age,
                    phoneNumber = phoneNumber,
                    avatarUrl = avatarUrl,
                    lastSeenAt = null,
                    isActive = true
                )

                childRepository.insertOrUpdateChild(child)
                linkChildOnServer(deviceId, name, iconId)
                familyDirectoryRepository.updateProfileForDevice(
                    deviceId = deviceId,
                    displayName = name,
                    avatarValue = avatarUrl
                )
                Log.d(TAG, "Устройство добавлено: $name ($deviceId)")

                // Обновить список
                loadChildren()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка добавления устройства", e)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Показать диалог редактирования устройства
     */
    private fun showEditChildDialog(child: Child) {
        selectedAvatarValue = child.avatarUrl ?: FamilyAvatarRenderer.presets[1].storageValue
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_child, null)

        // Find views
        val avatarImage = dialogView.findViewById<ImageView>(R.id.childAvatarImage)
        val changeAvatarButton = dialogView.findViewById<MaterialButton>(R.id.changeAvatarButton)
        val selectContactButton = dialogView.findViewById<MaterialButton>(R.id.selectContactButton)
        val scanQrButton = dialogView.findViewById<MaterialButton>(R.id.scanQrButton)
        val deviceIdInputLayout = dialogView.findViewById<TextInputLayout>(R.id.deviceIdInputLayout)
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInputLayout = dialogView.findViewById<TextInputLayout>(R.id.childNameInputLayout)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)
        val childAgeInput = dialogView.findViewById<TextInputEditText>(R.id.childAgeInput)
        val childPhoneInputLayout = dialogView.findViewById<TextInputLayout>(R.id.childPhoneInputLayout)
        val childPhoneInput = dialogView.findViewById<TextInputEditText>(R.id.childPhoneInput)
        val roleInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.roleInput)
        val iconInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.iconInput)
        val featureChatCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featureChatCheck)
        val featureMapCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featureMapCheck)
        val featureAudioCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featureAudioCheck)
        val featurePhotoCheck = dialogView.findViewById<MaterialCheckBox>(R.id.featurePhotoCheck)
        setupAdvancedOptions(dialogView)

        // Заполнить текущие данные
        deviceIdInput.setText(child.deviceId)
        deviceIdInput.isEnabled = false // Device ID нельзя изменить
        deviceIdInputLayout.isEnabled = false // Также отключить layout
        deviceIdInputLayout.helperText = getString(R.string.child_card_locked_id_hint)
        deviceIdInputLayout.isEndIconVisible = false
        scanQrButton.visibility = View.GONE
        childNameInput.setText(child.name)
        childAgeInput.setText(child.age?.toString() ?: "")
        childPhoneInput.setText(child.phoneNumber ?: "")

        val roleOptions = arrayOf("Ребенок", "Родитель", "Родственник")
        roleInput.setSimpleItems(roleOptions)
        roleInput.setText(ContactRoles.label(child.role), false)

        val iconOptions = ContactIcons.options()
        iconInput.setSimpleItems(iconOptions.map { it.label }.toTypedArray())
        iconInput.setText(
            iconOptions.firstOrNull { it.id == child.iconId }?.label ?: iconOptions.first().label,
            false
        )

        featureChatCheck.isChecked = ContactFeatures.isAllowed(child.allowedFeatures, ContactFeatures.CHAT)
        featureMapCheck.isChecked = ContactFeatures.isAllowed(child.allowedFeatures, ContactFeatures.MAP)
        featureAudioCheck.isChecked = ContactFeatures.isAllowed(child.allowedFeatures, ContactFeatures.AUDIO)
        featurePhotoCheck.isChecked = ContactFeatures.isAllowed(child.allowedFeatures, ContactFeatures.PHOTO)

        FamilyAvatarRenderer.bind(avatarImage, selectedAvatarValue)

        // Set up avatar selection
        currentAvatarImageView = avatarImage
        setupAvatarPresetChoices(dialogView, avatarImage)
        currentRoleInput = roleInput
        currentIconInput = iconInput
        currentChatCheck = featureChatCheck
        currentMapCheck = featureMapCheck
        currentAudioCheck = featureAudioCheck
        currentPhotoCheck = featurePhotoCheck
        changeAvatarButton.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        // Set up contact picker
        currentNameInput = childNameInput
        currentPhoneInput = childPhoneInput
        Log.d(TAG, "Setting up contact picker button")
        selectContactButton.setOnClickListener {
            Log.d(TAG, "Contact picker button clicked!")
            requestContactsPermission {
                Log.d(TAG, "Contacts permission granted, launching picker")
                pickContactLauncher.launch(null)
            }
        }

        // Set up phone picker button
        childPhoneInputLayout.setEndIconOnClickListener {
            requestContactsPermission {
                pickPhoneLauncher.launch(null)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.family_profile_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.family_profile_save_action, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
                currentRoleInput = null
                currentIconInput = null
                currentChatCheck = null
                currentMapCheck = null
                currentAudioCheck = null
                currentPhotoCheck = null
            }
            .setNeutralButton(R.string.family_profile_delete_action) { _, _ ->
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
                currentRoleInput = null
                currentIconInput = null
                currentChatCheck = null
                currentMapCheck = null
                currentAudioCheck = null
                currentPhotoCheck = null
                showDeleteConfirmDialog(child)
            }
            .setOnDismissListener {
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
                currentRoleInput = null
                currentIconInput = null
                currentChatCheck = null
                currentMapCheck = null
                currentAudioCheck = null
                currentPhotoCheck = null
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val newName = childNameInput.text.toString().trim()
                val ageText = childAgeInput.text.toString().trim()
                val phoneNumber = childPhoneInput.text.toString().trim()
                val roleValue = ContactRoles.fromLabel(roleInput.text?.toString().orEmpty())
                val iconId = resolveIconId(iconInput.text?.toString().orEmpty())
                val allowed = buildAllowedFeatures(
                    featureChatCheck.isChecked,
                    featureMapCheck.isChecked,
                    featureAudioCheck.isChecked,
                    featurePhotoCheck.isChecked
                )

                childNameInputLayout.error = if (newName.isEmpty()) {
                    getString(R.string.family_profile_name_required)
                } else null
                val age = ageText.toIntOrNull()
                val ageValid = ageText.isEmpty() || age?.let { it in 0..120 } == true
                dialogView.findViewById<TextInputLayout>(R.id.childAgeInputLayout).error =
                    if (ageValid) null else getString(R.string.family_profile_age_invalid)
                if (newName.isEmpty() || !ageValid) {
                    return@setOnClickListener
                }

                updateChild(
                    child = child,
                    newName = newName,
                    newRole = roleValue,
                    newIconId = iconId,
                    newAllowedFeatures = allowed,
                    newAge = age,
                    newPhoneNumber = phoneNumber.ifEmpty { null },
                    newAvatarUrl = selectedAvatarValue
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Обновить данные устройства
     */
    private fun updateChild(
        child: Child,
        newName: String,
        newRole: String,
        newIconId: Int,
        newAllowedFeatures: Int,
        newAge: Int? = null,
        newPhoneNumber: String? = null,
        newAvatarUrl: String? = null
    ) {
        lifecycleScope.launch {
            try {
                val updatedChild = child.copy(
                    name = newName,
                    role = newRole,
                    iconId = newIconId,
                    allowedFeatures = newAllowedFeatures,
                    age = newAge,
                    phoneNumber = newPhoneNumber,
                    avatarUrl = newAvatarUrl ?: child.avatarUrl,  // Keep old avatar if no new one selected
                    updatedAt = System.currentTimeMillis()
                )
                childRepository.insertOrUpdateChild(updatedChild)
                linkChildOnServer(child.deviceId, newName, newIconId)
                val profileSynced = familyDirectoryRepository.updateProfileForDevice(
                    deviceId = child.deviceId,
                    displayName = newName,
                    avatarValue = updatedChild.avatarUrl
                )
                Log.d(TAG, "Устройство обновлено: $newName (${child.deviceId})")
                if (!profileSynced) {
                    Log.w(TAG, "Canonical profile sync postponed for ${child.deviceId}")
                }

                // Обновить список
                loadChildren()

                // Показать уведомление
                android.widget.Toast.makeText(
                    this@ChildSelectionActivity,
                    "Данные обновлены",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления устройства", e)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    private fun buildAllowedFeatures(chat: Boolean, map: Boolean, audio: Boolean, photo: Boolean): Int {
        var mask = 0
        if (chat) mask = mask or ContactFeatures.CHAT
        if (map) mask = mask or ContactFeatures.MAP
        if (audio) mask = mask or ContactFeatures.AUDIO
        if (photo) mask = mask or ContactFeatures.PHOTO
        return mask
    }

    private fun setupAdvancedOptions(dialogView: View) {
        val button = dialogView.findViewById<MaterialButton>(R.id.advancedOptionsButton)
        val container = dialogView.findViewById<View>(R.id.advancedOptionsContainer)
        button.setOnClickListener {
            val expanded = container.visibility != View.VISIBLE
            container.visibility = if (expanded) View.VISIBLE else View.GONE
            button.setText(
                if (expanded) R.string.family_profile_hide_settings
                else R.string.family_profile_more_settings
            )
            button.setIconResource(
                if (expanded) android.R.drawable.arrow_up_float
                else android.R.drawable.arrow_down_float
            )
        }
    }

    private fun resolveIconId(label: String): Int {
        return ContactIcons.options().firstOrNull { it.label == label }?.id ?: ContactIcons.DEFAULT
    }

    /**
     * Показать диалог подтверждения удаления
     */
    private fun showDeleteConfirmDialog(child: Child) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить устройство?")
            .setMessage("Вы уверены, что хотите удалить устройство \"${child.name}\"?\n\nЭто действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteChild(child)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Удалить устройство
     */
    private fun deleteChild(child: Child) {
        lifecycleScope.launch {
            try {
                unlinkChildOnServer(child.deviceId)
                childRepository.deleteChild(child)
                Log.d(TAG, "Устройство удалено: ${child.name} (${child.deviceId})")

                // Обновить список
                loadChildren()

                // Показать уведомление
                android.widget.Toast.makeText(
                    this@ChildSelectionActivity,
                    "Устройство удалено",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                // Если удалённое устройство было выбрано, очистить выбор
                val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
                val selectedDeviceId = prefs.getString("selected_device_id", null)
                if (selectedDeviceId == child.deviceId) {
                    prefs.edit()
                        .remove("selected_device_id")
                        .remove("child_device_id")
                        .apply()
                    profileRuntimeCoordinator.clearFocusedChild(
                        shareParentLocation = prefs.getBoolean("share_parent_location", true)
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка удаления устройства", e)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Показать ошибку
     */
    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ошибка")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Загрузить данные контакта (имя и телефон) для автозаполнения
     */
    private fun loadContactData(contactUri: Uri) {
        Log.d(TAG, "Loading contact data from URI: $contactUri")
        Log.d(TAG, "currentNameInput is null: ${currentNameInput == null}")
        Log.d(TAG, "currentPhoneInput is null: ${currentPhoneInput == null}")

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        try {
            val cursor = contentResolver.query(contactUri, projection, null, null, null)
            if (cursor == null) {
                Log.e(TAG, "Contact query returned null")
                return
            }

            cursor.use {
                if (!it.moveToFirst()) {
                    Log.w(TAG, "Contact cursor is empty")
                    return@use
                }

                // Загрузить имя
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val contactName: String? = if (nameIndex >= 0) it.getString(nameIndex) else null

                contactName?.let { name ->
                    currentNameInput?.setText(name)
                    Log.d(TAG, "Contact name loaded: $name")
                }

                // Проверить наличие телефона
                val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                val hasPhoneNumber: Int = if (hasPhoneIndex >= 0) it.getInt(hasPhoneIndex) else 0
                val hasPhone = hasPhoneNumber > 0
                Log.d(TAG, "Contact has phone: $hasPhone")

                if (!hasPhone) {
                    Log.w(TAG, "Contact has no phone number")
                    Toast.makeText(this, "У контакта нет телефона", Toast.LENGTH_SHORT).show()
                    return@use
                }

                // Загрузить телефон
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val contactId: String? = if (idIndex >= 0) it.getString(idIndex) else null

                if (contactId != null) {
                    Log.d(TAG, "Loading phone for contact ID: $contactId")
                    loadPhoneForContact(contactId)
                } else {
                    Log.w(TAG, "Contact ID is null")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact data", e)
            Toast.makeText(this, "Ошибка загрузки контакта: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Загрузить телефон контакта по ID
     */
    private fun loadPhoneForContact(contactId: String) {
        Log.d(TAG, "loadPhoneForContact called with ID: $contactId")
        val phoneProjection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val phoneSelection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val phoneSelectionArgs = arrayOf(contactId)

        try {
            val phoneCursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                phoneSelection,
                phoneSelectionArgs,
                null
            )
            if (phoneCursor == null) {
                Log.e(TAG, "Phone query returned null")
                return
            }

            phoneCursor.use {
                Log.d(TAG, "Phone cursor count: ${it.count}")
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    Log.d(TAG, "Phone number column index: $numberIndex")
                    val phoneNumber = if (numberIndex >= 0) it.getString(numberIndex) else null

                    Log.d(TAG, "Phone number retrieved: $phoneNumber")
                    if (phoneNumber != null) {
                        currentPhoneInput?.setText(phoneNumber)
                        Log.d(TAG, "Contact phone loaded and set: $phoneNumber")
                        Toast.makeText(this, "Телефон загружен: $phoneNumber", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.w(TAG, "Phone number is null")
                    }
                } else {
                    Log.w(TAG, "Phone cursor is empty")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact phone", e)
            Toast.makeText(this, "Ошибка загрузки телефона: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Загрузить только телефон из контакта
     */
    private fun loadContactPhone(contactUri: Uri) {
        val projection = arrayOf(ContactsContract.Contacts._ID)

        try {
            val cursor = contentResolver.query(contactUri, projection, null, null, null)
            if (cursor == null) {
                Log.e(TAG, "Contact query returned null for phone")
                return
            }

            cursor.use {
                if (it.moveToFirst()) {
                    val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                    if (idIndex >= 0) {
                        val contactId = it.getString(idIndex)
                        loadPhoneForContact(contactId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact phone", e)
            Toast.makeText(this, "Ошибка загрузки телефона: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Вставить Device ID из буфера обмена
     */
    private fun pasteDeviceIdFromClipboard(input: TextInputEditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                input.setText(text)
                Toast.makeText(this, "Device ID вставлен", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Device ID pasted from clipboard: $text")
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Проверить разрешение на чтение контактов
     */
    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Запросить разрешение на чтение контактов
     */
    private fun requestContactsPermission(onGranted: () -> Unit) {
        Log.d(TAG, "requestContactsPermission called")
        when {
            checkContactsPermission() -> {
                Log.d(TAG, "Permission already granted")
                onGranted()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) -> {
                Log.d(TAG, "Showing permission rationale")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Разрешение на контакты")
                    .setMessage("Приложению нужен доступ к контактам для автозаполнения данных ребенка")
                    .setPositiveButton("Разрешить") { _, _ ->
                        Log.d(TAG, "User clicked allow in rationale dialog")
                        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS_PERMISSION)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            else -> {
                Log.d(TAG, "Requesting permission directly")
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS_PERMISSION)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CONTACTS_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Доступ к контактам разрешен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Доступ к контактам отклонен", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Доступ к камере разрешен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Доступ к камере отклонен", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Запросить разрешение на использование камеры
     */
    private fun requestCameraPermission(onGranted: () -> Unit) {
        Log.d(TAG, "requestCameraPermission called")
        when {
            checkCameraPermission() -> {
                Log.d(TAG, "Camera permission already granted")
                onGranted()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(TAG, "Showing camera permission rationale")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Разрешение на камеру")
                    .setMessage("Приложению нужен доступ к камере для сканирования QR-кода")
                    .setPositiveButton("Разрешить") { _, _ ->
                        Log.d(TAG, "User clicked allow in camera rationale dialog")
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            else -> {
                Log.d(TAG, "Requesting camera permission directly")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            }
        }
    }

    /**
     * Проверить наличие разрешения на камеру
     */
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "ChildSelectionActivity"
        const val EXTRA_SELECTED_DEVICE_ID = "selected_device_id"
        const val EXTRA_SELECTED_MEMBER_ID = "selected_member_id"
        const val EXTRA_SELECTED_FAMILY_ID = "selected_family_id"
        const val EXTRA_EDIT_CHILD_ID = "edit_child_device_id"
        private const val REQUEST_CONTACTS_PERMISSION = 100
        private const val REQUEST_CAMERA_PERMISSION = 101
    }
}
