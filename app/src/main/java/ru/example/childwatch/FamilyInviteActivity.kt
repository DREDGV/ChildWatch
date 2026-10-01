package ru.example.childwatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import ru.childwatch.shared.family.FamilyPersonProfile
import ru.childwatch.shared.onboarding.FamilyInvitationCreateRequest
import ru.childwatch.shared.onboarding.FamilyInvitationMode
import ru.childwatch.shared.onboarding.FamilyLegacyMigrationCandidateData
import ru.childwatch.shared.onboarding.FamilyLegacyProfileConfirmRequest
import ru.example.childwatch.databinding.ActivityFamilyInviteBinding
import ru.example.childwatch.profile.ParentFamilyDirectoryRepository
import ru.example.childwatch.profile.ParentFamilyDirectorySource
import ru.example.childwatch.profile.FamilyAvatarRenderer

/** Creates one-time invitations; it never creates an unconfirmed ghost profile. */
class FamilyInviteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyInviteBinding
    private val directoryRepository by lazy { ParentFamilyDirectoryRepository(this) }
    private val networkClient by lazy { ru.example.childwatch.network.NetworkClient(this) }
    private var familyId: String? = null
    private var people: List<FamilyPersonProfile> = emptyList()
    private var legacyCandidates: List<FamilyLegacyMigrationCandidateData> = emptyList()
    private var selectedExistingIndex = -1
    private var selectedLegacyIndex = 0
    /**
     * Which role the invitation offers.
     *
     * Starts on «Родитель»: an adult inviting somebody is usually inviting another
     * adult, and the list used to start on «Ребёнок», so a second parent was easily
     * created as a child. Any role can still be chosen.
     */
    private var selectedRoleIndex = 1
    private var selectedAvatarValue = FamilyAvatarRenderer.selectableValues().first()
    private var invitationUri: String? = null
    private var invitationExpiresAt = 0L
    private var invitationInstructions = ""
    private var invitationUnavailable: String? = null
    private var expiryJob: Job? = null
    private data class AvatarChoice(val frame: FrameLayout, val badge: ImageView)
    private var avatarViews: List<AvatarChoice> = emptyList()
    private val roleLabels = listOf("Ребёнок", "Родитель", "Родственник")
    private val roleValues = listOf("CHILD", "PARENT", "GUARDIAN")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyInviteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.inviteToolbar.setNavigationOnClickListener { finish() }
        setupInputs()
        loadFamily()
    }

    private fun setupInputs() {
        binding.inviteNameInput.imeHintLocales = LocaleList.forLanguageTags("ru-RU,en-US")
        binding.inviteRoleInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, roleLabels)
        )
        binding.inviteRoleInput.setText(roleLabels[selectedRoleIndex], false)
        binding.inviteRoleInput.setOnItemClickListener { _, _, position, _ ->
            selectedRoleIndex = position
            updateRoleHelp()
            clearResult()
        }
        binding.inviteRoleInput.setOnClickListener { showRolePicker() }
        binding.inviteRoleLayout.setEndIconOnClickListener { showRolePicker() }
        binding.inviteExistingInput.setOnClickListener { showExistingPersonPicker() }
        binding.inviteExistingLayout.setEndIconOnClickListener { showExistingPersonPicker() }
        binding.inviteNameInput.doAfterTextChanged { clearResult() }
        setupAvatarChoices()
        binding.inviteModeGroup.setOnCheckedChangeListener { _, _ -> applyModeState() }
        binding.createInvitationButton.setOnClickListener { createInvitation() }
        binding.retryLoadButton.setOnClickListener { loadFamily() }
        binding.manageInvitationsButton.setOnClickListener { showActiveInvitations() }
        binding.transferDeviceButton.setOnClickListener { showDeviceTransferWizard() }
        binding.copyInvitationButton.setOnClickListener {
            if (!invitationIsUsable()) return@setOnClickListener
            val value = invitationUri ?: return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ChildWatch invitation", value))
            Toast.makeText(this, "Приглашение скопировано", Toast.LENGTH_SHORT).show()
        }
        binding.shareInvitationButton.setOnClickListener {
            if (!invitationIsUsable()) return@setOnClickListener
            val value = invitationUri ?: return@setOnClickListener
            val directory = java.io.File(cacheDir, "family-invitations").apply { mkdirs() }
            val imageFile = java.io.File(directory, "invitation-${java.util.UUID.randomUUID()}.png")
            try {
                imageFile.outputStream().use { stream ->
                    check(generateQr(value, 640).compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.family_invite_share_failed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val imageUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_TEXT, "$invitationInstructions\n\n$value")
                clipData = ClipData.newUri(contentResolver, "Приглашение в семью", imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.family_connect_share)))
        }
        // The checked state declared in the XML is not reported to the listener
        // when it is attached, so the form could show one mode while the other
        // was actually selected. The state is applied explicitly here.
        applyModeState()
        updateRoleHelp()
    }

    /**
     * The single place that decides what the mode-dependent fields show.
     *
     * Reading the live checked state instead of a callback argument keeps the
     * screen consistent even when the selection was restored by the framework
     * rather than made by the user.
     */
    private fun applyModeState() {
        val legacy = binding.inviteLegacyProfileRadio.isChecked
        val existing = binding.inviteExistingPersonRadio.isChecked
        binding.inviteModeHelp.setText(when {
            legacy -> R.string.family_invite_legacy_help
            existing -> R.string.family_invite_existing_help
            else -> R.string.family_invite_new_help
        })
        binding.inviteExistingLayout.visibility = if (existing) View.VISIBLE else View.GONE
        binding.inviteLegacyCandidateLayout.visibility = if (legacy) View.VISIBLE else View.GONE
        binding.inviteNameLayout.visibility = if (existing) View.GONE else View.VISIBLE
        binding.inviteRoleLayout.visibility = if (existing) View.GONE else View.VISIBLE
        binding.inviteAvatarSection.visibility = if (existing) View.GONE else View.VISIBLE
        binding.createInvitationButton.text =
            if (legacy) "Подтвердить профиль" else "Создать приглашение"
        if (legacy) applySelectedLegacyCandidate()
        if (existing) updateExistingHelp()
        clearResult()
    }

    private fun showActiveInvitations() {
        val currentFamilyId = familyId ?: return
        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = networkClient.getActiveFamilyInvitations(currentFamilyId)
                val invitations =
                    if (response.isSuccessful) response.body()?.invitations.orEmpty()
                    else {
                        Toast.makeText(
                            this@FamilyInviteActivity,
                            readServerError(response.errorBody()?.string()),
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                if (invitations.isEmpty()) {
                    Toast.makeText(
                        this@FamilyInviteActivity,
                        "Активных приглашений нет",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val now = System.currentTimeMillis()
                val labels = invitations.map { invitation ->
                    val minutes = ((invitation.expiresAt - now).coerceAtLeast(0L) + 59_999L) / 60_000L
                    "${invitation.member.displayName} · ${roleLabel(invitation.member.role)} · ещё $minutes мин"
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@FamilyInviteActivity)
                    .setTitle("Активные приглашения")
                    .setItems(labels) { _, index ->
                        val invitation = invitations[index]
                        confirmInvitationRevocation(currentFamilyId, invitation.id, invitation.member.displayName)
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
            } catch (error: Exception) {
                Toast.makeText(
                    this@FamilyInviteActivity,
                    error.message ?: "Не удалось загрузить приглашения",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun confirmInvitationRevocation(
        currentFamilyId: String,
        invitationId: String,
        displayName: String
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Отозвать приглашение?")
            .setMessage("$displayName больше не сможет воспользоваться этим QR-кодом.")
            .setPositiveButton("Отозвать") { _, _ ->
                lifecycleScope.launch {
                    showLoading(true)
                    try {
                        val response = networkClient.revokeFamilyInvitation(
                            currentFamilyId,
                            invitationId
                        )
                        Toast.makeText(
                            this@FamilyInviteActivity,
                            if (response.isSuccessful) "Приглашение отозвано"
                            else readServerError(response.errorBody()?.string()),
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (error: Exception) {
                        Toast.makeText(
                            this@FamilyInviteActivity,
                            error.message ?: "Не удалось отозвать приглашение",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        showLoading(false)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeviceTransferWizard() {
        val devices = people.flatMap { person ->
            person.activeDevices.map { device -> person to device }
        }
        if (devices.isEmpty()) {
            Toast.makeText(this, "В семье пока нет телефонов", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Какой телефон переназначить?")
            .setItems(
                devices.map { (person, device) ->
                    "${device.displayName} · сейчас ${person.member.displayName}"
                }.toTypedArray()
            ) { _, index ->
                val (sourcePerson, device) = devices[index]
                showTransferTargetPicker(sourcePerson, device)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showTransferTargetPicker(
        sourcePerson: FamilyPersonProfile,
        device: ru.childwatch.shared.family.FamilyDevice
    ) {
        val sourceRole = sourcePerson.member.role
        val targets = people.filter { person ->
            person.member.id != sourcePerson.member.id &&
                (person.member.role == sourceRole ||
                    (sourceRole.name in setOf("PARENT", "GUARDIAN") &&
                        person.member.role.name in setOf("PARENT", "GUARDIAN")))
        }
        if (targets.isEmpty()) {
            Toast.makeText(
                this,
                "Нет другого подходящего профиля. Сначала добавьте человека в семью.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Кому передать ${device.displayName}?")
            .setItems(targets.map { it.member.displayName }.toTypedArray()) { _, index ->
                confirmDeviceTransfer(sourcePerson, device, targets[index])
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeviceTransfer(
        sourcePerson: FamilyPersonProfile,
        device: ru.childwatch.shared.family.FamilyDevice,
        targetPerson: FamilyPersonProfile
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Подтвердите перенос")
            .setMessage(
                "Телефон «${device.displayName}» будет отвязан от профиля " +
                    "«${sourcePerson.member.displayName}» и привязан к " +
                    "«${targetPerson.member.displayName}». История не удалится."
            )
            .setPositiveButton("Переназначить") { _, _ ->
                transferDevice(device.deviceId, targetPerson)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun transferDevice(deviceId: String, targetPerson: FamilyPersonProfile) {
        val currentFamilyId = familyId ?: return
        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = networkClient.transferFamilyDevice(
                    currentFamilyId,
                    deviceId,
                    targetPerson.member.id
                )
                if (!response.isSuccessful || response.body()?.success != true) {
                    Toast.makeText(
                        this@FamilyInviteActivity,
                        readServerError(response.errorBody()?.string()),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                Toast.makeText(
                    this@FamilyInviteActivity,
                    "Телефон теперь относится к ${targetPerson.member.displayName}",
                    Toast.LENGTH_LONG
                ).show()
                loadFamily()
            } catch (error: Exception) {
                Toast.makeText(
                    this@FamilyInviteActivity,
                    error.message ?: "Не удалось переназначить телефон",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadFamily() {
        showLoading(true)
        showLoadFailure(null)
        lifecycleScope.launch {
            val result = runCatching { directoryRepository.load() }.getOrNull()
            if (result == null || result.source != ParentFamilyDirectorySource.SERVER) {
                showLoading(false)
                binding.createInvitationButton.isEnabled = false
                // A bare disabled button gave no reason and no way forward.
                showLoadFailure("Нет связи с семейным сервером. Проверьте интернет и повторите.")
                return@launch
            }
            familyId = result.directory.family.id
            people = result.directory.people
            binding.inviteFamilySummary.text = getString(
                R.string.family_invite_context,
                result.directory.family.name,
                people.size
            )
            val labels = people.map { person ->
                "${person.member.displayName} · ${roleLabel(person.member.role.name)}"
            }
            binding.inviteExistingInput.setAdapter(
                ArrayAdapter(
                    this@FamilyInviteActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    labels
                )
            )
            selectedExistingIndex = -1
            binding.inviteExistingInput.setText("", false)
            binding.inviteExistingInput.setOnItemClickListener { _, _, position, _ ->
                selectedExistingIndex = position
                updateExistingHelp()
                clearResult()
            }
            try {
                loadLegacyCandidates(result.directory.family.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional legacy migration must not block ordinary invitations.
                legacyCandidates = emptyList()
                binding.inviteLegacyProfileRadio.visibility = View.GONE
            }
            showLoading(false)
            applyModeState()
        }
    }

    /** Shows the reason a load failed, together with a way to try again. */
    private fun showLoadFailure(message: String?) {
        binding.inviteStatusText.visibility = if (message == null) View.GONE else View.VISIBLE
        binding.inviteStatusText.text = message.orEmpty()
        binding.retryLoadButton.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private suspend fun loadLegacyCandidates(currentFamilyId: String) {
        val response = networkClient.getFamilyLegacyMigrationCandidates(currentFamilyId)
        legacyCandidates =
            if (response.isSuccessful) response.body()?.candidates.orEmpty() else emptyList()
        val labels = legacyCandidates.map { candidate ->
            val devices = candidate.devices
                .mapNotNull { it.displayName?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString()
            if (devices.isBlank()) candidate.member.displayName
            else "${candidate.member.displayName} · $devices"
        }
        binding.inviteLegacyProfileRadio.visibility =
            if (labels.isEmpty()) View.GONE else View.VISIBLE
        binding.inviteLegacyCandidateInput.setAdapter(
            ArrayAdapter(
                this@FamilyInviteActivity,
                android.R.layout.simple_dropdown_item_1line,
                labels
            )
        )
        if (labels.isNotEmpty()) {
            selectedLegacyIndex = 0
            binding.inviteLegacyCandidateInput.setText(labels.first(), false)
        } else if (binding.inviteLegacyProfileRadio.isChecked) {
            binding.inviteNewPersonRadio.isChecked = true
        }
        binding.inviteLegacyCandidateInput.setOnItemClickListener { _, _, position, _ ->
            selectedLegacyIndex = position
            applySelectedLegacyCandidate()
        }
    }

    private fun applySelectedLegacyCandidate() {
        val candidate = legacyCandidates.getOrNull(selectedLegacyIndex) ?: return
        binding.inviteNameInput.setText(candidate.member.displayName)
        selectedRoleIndex = roleValues.indexOf(candidate.member.role.uppercase())
            .takeIf { it >= 0 } ?: 0
        binding.inviteRoleInput.setText(roleLabels[selectedRoleIndex], false)
        updateRoleHelp()
        selectedAvatarValue = candidate.member.avatarKey
            ?.takeIf { avatar -> FamilyAvatarRenderer.presets.any { it.storageValue == avatar } }
            ?: FamilyAvatarRenderer.presets.first().storageValue
        refreshAvatarChoices()
    }

    private fun createInvitation(allowDuplicateName: Boolean = false) {
        val currentFamilyId = familyId ?: return
        if (binding.inviteLegacyProfileRadio.isChecked) {
            confirmLegacyProfile(currentFamilyId)
            return
        }
        val existing = binding.inviteExistingPersonRadio.isChecked
        val request = if (existing) {
            val person = people.getOrNull(selectedExistingIndex)
            if (person == null) {
                Toast.makeText(this, "Выберите члена семьи", Toast.LENGTH_SHORT).show()
                return
            }
            FamilyInvitationCreateRequest(
                familyId = currentFamilyId,
                mode = FamilyInvitationMode.EXISTING_MEMBER.name,
                targetMemberId = person.member.id
            )
        } else {
            val name = binding.inviteNameInput.text?.toString().orEmpty().trim()
            binding.inviteNameLayout.error = if (name.length < 2) "Введите имя" else null
            if (binding.inviteNameLayout.error != null) return
            val duplicate = people.indexOfFirst { it.member.displayName.trim().equals(name, ignoreCase = true) }
            if (duplicate >= 0 && !allowDuplicateName) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.family_connect_duplicate_title)
                    .setMessage(R.string.family_connect_duplicate_body)
                    .setPositiveButton(R.string.family_connect_existing_action) { _, _ ->
                        selectedExistingIndex = duplicate
                        binding.inviteExistingInput.setText(
                            "${people[duplicate].member.displayName} · ${roleLabel(people[duplicate].member.role.name)}", false)
                        binding.inviteExistingPersonRadio.isChecked = true
                        updateExistingHelp()
                    }
                    .setNeutralButton(R.string.family_connect_new_action) { _, _ -> createInvitation(allowDuplicateName = true) }
                    .setNegativeButton("Отмена", null)
                    .show()
                return
            }
            FamilyInvitationCreateRequest(
                familyId = currentFamilyId,
                mode = FamilyInvitationMode.NEW_MEMBER.name,
                displayName = name,
                role = roleValues[selectedRoleIndex],
                avatarKey = selectedAvatarValue
            )
        }
        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = networkClient.createFamilyInvitation(request)
                val invitation = response.body()?.invitation
                if (!response.isSuccessful || invitation?.invitationUri.isNullOrBlank()) {
                    Toast.makeText(
                        this@FamilyInviteActivity,
                        readServerError(response.errorBody()?.string()),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                invitationUri = invitation!!.invitationUri
                invitationUnavailable = null
                invitationExpiresAt = invitation.expiresAt
                if (BuildConfig.DEBUG) {
                    getSharedPreferences(DEBUG_PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(DEBUG_LAST_INVITATION_URI, invitationUri)
                        .apply()
                }
                binding.invitationResultTitle.text =
                    "Приглашение для ${invitation.member.displayName}"
                val application = if (invitation.member.role == "CHILD") "ChildDevice" else "ParentMonitor"
                val expiry = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, java.util.Locale("ru", "RU")
                ).format(java.util.Date(invitation.expiresAt))
                val action = if (existing) "Телефон добавится к существующему человеку. Имя, аватар и роль сохранятся. Старые телефоны останутся подключёнными."
                    else "Человек появится в вашей семье после подтверждения на его телефоне."
                invitationInstructions =
                    "${invitation.member.displayName} · ${roleLabel(invitation.member.role)}\n$action\n\n" +
                    "На новом телефоне установите $application, откройте ссылку или отсканируйте QR-код в приложении. " +
                    "Подтвердите имя и семью перед подключением.\n\nПриглашение одноразовое, действует до $expiry."
                binding.invitationResultHint.text = invitationInstructions
                binding.invitationQrImage.setImageBitmap(
                    generateQr(invitation.invitationUri!!, 640)
                )
                binding.invitationResultCard.visibility = View.VISIBLE
                updateInvitationExpiry()
                binding.inviteScroll.post {
                    binding.inviteScroll.smoothScrollTo(0, binding.invitationResultCard.top)
                }
            } catch (error: Exception) {
                Toast.makeText(
                    this@FamilyInviteActivity,
                    error.message ?: "Не удалось связаться с семейным сервером",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun confirmLegacyProfile(currentFamilyId: String) {
        val candidate = legacyCandidates.getOrNull(selectedLegacyIndex)
        if (candidate == null) {
            Toast.makeText(this, "Выберите старое устройство", Toast.LENGTH_SHORT).show()
            return
        }
        val name = binding.inviteNameInput.text?.toString().orEmpty().trim()
        binding.inviteNameLayout.error = if (name.length < 2) "Введите имя" else null
        if (binding.inviteNameLayout.error != null) return

        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = networkClient.confirmFamilyLegacyProfile(
                    currentFamilyId,
                    candidate.member.id.orEmpty(),
                    FamilyLegacyProfileConfirmRequest(
                        displayName = name,
                        role = roleValues[selectedRoleIndex],
                        avatarKey = selectedAvatarValue
                    )
                )
                if (!response.isSuccessful || response.body()?.success != true) {
                    Toast.makeText(
                        this@FamilyInviteActivity,
                        readServerError(response.errorBody()?.string()),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                Toast.makeText(
                    this@FamilyInviteActivity,
                    "Профиль ${response.body()!!.member.displayName} подтверждён",
                    Toast.LENGTH_LONG
                ).show()
                loadFamily()
            } catch (error: Exception) {
                Toast.makeText(
                    this@FamilyInviteActivity,
                    error.message ?: "Не удалось подтвердить профиль",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun clearResult() {
        invitationUri = null
        invitationExpiresAt = 0L
        invitationInstructions = ""
        invitationUnavailable = null
        if (BuildConfig.DEBUG) {
            getSharedPreferences(DEBUG_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(DEBUG_LAST_INVITATION_URI)
                .apply()
        }
        binding.invitationResultCard.visibility = View.GONE
    }

    companion object {
        const val DEBUG_PREFS_NAME = "childwatch_emulator_lab"
        const val DEBUG_LAST_INVITATION_URI = "last_invitation_uri"
    }

    /**
     * Builds one avatar view per preset.
     *
     * The layout used to hold six fixed slots, so nineteen of the twenty-five
     * presets the app can render were unreachable, and the six visible ones
     * happened to be exactly the ones the server used to reject. The row is
     * filled from the preset list so the two can no longer disagree, and each
     * view gets enough spacing that the last one is fully visible.
     */
    private fun setupAvatarChoices() {
        val row = binding.inviteAvatarRow
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (68 * density).toInt()
        val imageSize = (56 * density).toInt()
        val spacing = (8 * density).toInt()
        avatarViews = FamilyAvatarRenderer.presets.mapIndexed { index, preset ->
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = spacing
                }
                isFocusable = true
                contentDescription = getString(
                    R.string.family_profile_avatar_preset_description,
                    index + 1
                )
                setOnClickListener {
                    selectedAvatarValue = preset.storageValue
                    refreshAvatarChoices()
                    clearResult()
                }
                val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                foreground = attributes.getDrawable(0)
                attributes.recycle()
            }
            val view = ShapeableImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(imageSize, imageSize, Gravity.CENTER)
                shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(imageSize / 2f).build()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            FamilyAvatarRenderer.bind(view, preset.storageValue)
            val badgeSize = (20 * density).toInt()
            val badge = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.BOTTOM or Gravity.END)
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@FamilyInviteActivity, R.color.cw_color_on_primary))
                setPadding((3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@FamilyInviteActivity, R.color.cw_color_primary))
                    setStroke((2 * density).toInt(), ContextCompat.getColor(this@FamilyInviteActivity, R.color.cw_color_surface))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(view)
            frame.addView(badge)
            ViewCompat.setAccessibilityDelegate(frame, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            })
            row.addView(frame)
            AvatarChoice(frame, badge)
        }
        refreshAvatarChoices()
    }

    private fun refreshAvatarChoices() {
        val primary = ContextCompat.getColor(this, R.color.cw_color_primary)
        val surface = ContextCompat.getColor(this, R.color.cw_color_surface)
        val selectedSurface = ContextCompat.getColor(this, R.color.cw_color_selected_surface)
        FamilyAvatarRenderer.presets.zip(avatarViews).forEach { (preset, choice) ->
            val selected = preset.storageValue == selectedAvatarValue
            choice.frame.isSelected = selected
            choice.frame.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (selected) selectedSurface else surface)
                setStroke((2 * resources.displayMetrics.density).toInt(), if (selected) primary else Color.TRANSPARENT)
            }
            choice.badge.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun showLoading(show: Boolean) {
        binding.inviteProgress.visibility = if (show) View.VISIBLE else View.GONE
        binding.createInvitationButton.isEnabled = !show
        binding.manageInvitationsButton.isEnabled = !show
        binding.transferDeviceButton.isEnabled = !show
        binding.inviteModeGroup.isEnabled = !show
        for (index in 0 until binding.inviteModeGroup.childCount) {
            binding.inviteModeGroup.getChildAt(index).isEnabled = !show
        }
        binding.inviteNameInput.isEnabled = !show
        binding.inviteRoleInput.isEnabled = !show
        binding.inviteExistingInput.isEnabled = !show
        binding.inviteLegacyCandidateInput.isEnabled = !show
        avatarViews.forEach { it.frame.isEnabled = !show }
    }

    private fun updateExistingHelp() {
        if (!binding.inviteExistingPersonRadio.isChecked) return
        val person = people.getOrNull(selectedExistingIndex) ?: return
        binding.inviteModeHelp.text = getString(R.string.family_connect_existing,
            person.member.displayName, roleLabel(person.member.role.name), person.activeDevices.size)
    }

    private fun updateRoleHelp() {
        binding.inviteRoleLayout.helperText = getString(if (selectedRoleIndex == 0)
            R.string.family_invite_child_app else R.string.family_invite_adult_app)
    }

    private fun showRolePicker() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Роль человека в семье")
            .setSingleChoiceItems(roleLabels.toTypedArray(), selectedRoleIndex) { dialog, position ->
                selectedRoleIndex = position
                binding.inviteRoleInput.setText(roleLabels[position], false)
                updateRoleHelp()
                clearResult()
                dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun showExistingPersonPicker() {
        if (people.isEmpty()) return
        val labels = people.map { "${it.member.displayName} · ${roleLabel(it.member.role.name)}" }
        MaterialAlertDialogBuilder(this).setTitle("Чей это новый телефон?")
            .setSingleChoiceItems(labels.toTypedArray(), selectedExistingIndex) { dialog, position ->
                selectedExistingIndex = position
                binding.inviteExistingInput.setText(labels[position], false)
                updateExistingHelp()
                clearResult()
                dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun invitationIsUsable(): Boolean {
        updateInvitationExpiry()
        return invitationUri != null && invitationUnavailable == null && System.currentTimeMillis() < invitationExpiresAt
    }

    private fun updateInvitationExpiry() {
        if (invitationUri == null) return
        val usable = invitationUnavailable == null && System.currentTimeMillis() < invitationExpiresAt
        binding.copyInvitationButton.isEnabled = usable
        binding.shareInvitationButton.isEnabled = usable
        binding.invitationQrImage.alpha = if (usable) 1f else 0.2f
        if (invitationUnavailable != null) binding.invitationResultHint.text = invitationUnavailable
        else if (!usable) binding.invitationResultHint.setText(R.string.family_connect_expired)
        else {
            val minutes = ((invitationExpiresAt - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
            binding.invitationResultHint.text = "$invitationInstructions\nОсталось $minutes мин."
        }
    }

    override fun onStart() {
        super.onStart()
        expiryJob = lifecycleScope.launch {
            while (isActive) {
                updateInvitationExpiry()
                delay(15_000L)
                val uri = invitationUri
                if (uri != null && invitationIsUsable()) {
                    val token = ru.childwatch.shared.onboarding.FamilyInvitationTokenParser.parse(uri)
                    if (token != null) {
                        try {
                            val invitation = networkClient.previewFamilyInvitation(token).body()?.invitation
                            if (uri == invitationUri && invitation != null) {
                                invitationUnavailable = when {
                                    invitation.isConsumed -> getString(R.string.family_invite_connected, invitation.member.displayName)
                                    invitation.isRevoked || invitation.isExpired -> getString(R.string.family_connect_expired)
                                    else -> null
                                }
                                updateInvitationExpiry()
                                if (invitation.isConsumed) {
                                    val directory = directoryRepository.load()
                                    if (uri == invitationUri && directory?.source == ParentFamilyDirectorySource.SERVER) {
                                        people = directory.directory.people
                                        binding.inviteFamilySummary.text = getString(
                                            R.string.family_invite_context,
                                            directory.directory.family.name,
                                            people.size
                                        )
                                    }
                                }
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Keep the code while an intermittent refresh is unavailable. */ }
                    }
                }
            }
        }
    }

    override fun onStop() {
        expiryJob?.cancel()
        super.onStop()
    }

    private fun generateQr(value: String, size: Int): Bitmap {
        val matrix = ru.example.childwatch.profile.FamilyInvitationQr.encode(value, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
            for (x in 0 until size) for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        "CHILD" -> "ребёнок"
        "GUARDIAN" -> "родственник"
        else -> "родитель"
    }

    private fun readServerError(raw: String?): String =
        runCatching { JSONObject(raw.orEmpty()).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Не удалось создать приглашение"
}
