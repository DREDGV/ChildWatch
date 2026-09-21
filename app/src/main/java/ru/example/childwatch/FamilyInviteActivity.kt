package ru.example.childwatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
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
    private var selectedExistingIndex = 0
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
    private var avatarViews: List<ShapeableImageView> = emptyList()
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
        }
        setupAvatarChoices()
        binding.inviteModeGroup.setOnCheckedChangeListener { _, _ -> applyModeState() }
        binding.createInvitationButton.setOnClickListener { createInvitation() }
        binding.retryLoadButton.setOnClickListener { loadFamily() }
        binding.manageInvitationsButton.setOnClickListener { showActiveInvitations() }
        binding.transferDeviceButton.setOnClickListener { showDeviceTransferWizard() }
        binding.copyInvitationButton.setOnClickListener {
            val value = invitationUri ?: return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ChildWatch invitation", value))
            Toast.makeText(this, "Приглашение скопировано", Toast.LENGTH_SHORT).show()
        }
        // The checked state declared in the XML is not reported to the listener
        // when it is attached, so the form could show one mode while the other
        // was actually selected. The state is applied explicitly here.
        applyModeState()
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
        binding.inviteExistingLayout.visibility = if (existing) View.VISIBLE else View.GONE
        binding.inviteLegacyCandidateLayout.visibility = if (legacy) View.VISIBLE else View.GONE
        binding.inviteNameLayout.visibility = if (existing) View.GONE else View.VISIBLE
        binding.inviteRoleLayout.visibility = if (existing) View.GONE else View.VISIBLE
        binding.inviteAvatarSection.visibility = if (existing) View.GONE else View.VISIBLE
        binding.createInvitationButton.text =
            if (legacy) "Подтвердить профиль" else "Создать приглашение"
        if (legacy) applySelectedLegacyCandidate()
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
            if (labels.isNotEmpty()) binding.inviteExistingInput.setText(labels.first(), false)
            binding.inviteExistingInput.setOnItemClickListener { _, _, position, _ ->
                selectedExistingIndex = position
            }
            loadLegacyCandidates(result.directory.family.id)
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
        selectedAvatarValue = candidate.member.avatarKey
            ?.takeIf { avatar -> FamilyAvatarRenderer.presets.any { it.storageValue == avatar } }
            ?: FamilyAvatarRenderer.presets.first().storageValue
        refreshAvatarChoices()
    }

    private fun createInvitation() {
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
                if (BuildConfig.DEBUG) {
                    getSharedPreferences(DEBUG_PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(DEBUG_LAST_INVITATION_URI, invitationUri)
                        .apply()
                }
                binding.invitationResultTitle.text =
                    "Приглашение для ${invitation.member.displayName}"
                binding.invitationResultHint.text = if (invitation.member.role == "CHILD") {
                    "Откройте ChildDevice на детском телефоне и отсканируйте код. Он действует 15 минут и только один раз."
                } else {
                    "Откройте ParentMonitor на телефоне взрослого и отсканируйте код. Он действует 15 минут и только один раз."
                }
                binding.invitationQrImage.setImageBitmap(
                    generateQr(invitation.invitationUri!!, 640)
                )
                binding.invitationResultCard.visibility = View.VISIBLE
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
        val size = (56 * density).toInt()
        val spacing = (10 * density).toInt()
        avatarViews = FamilyAvatarRenderer.presets.mapIndexed { index, preset ->
            val view = ShapeableImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = spacing
                }
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(size / 2f)
                    .build()
                contentDescription = getString(
                    R.string.family_profile_avatar_preset_description,
                    index + 1
                )
                setOnClickListener {
                    selectedAvatarValue = preset.storageValue
                    refreshAvatarChoices()
                    clearResult()
                }
            }
            FamilyAvatarRenderer.bind(view, preset.storageValue)
            row.addView(view)
            view
        }
        refreshAvatarChoices()
    }

    private fun refreshAvatarChoices() {
        val primary = ContextCompat.getColor(this, R.color.cw_color_primary)
        val outline = ContextCompat.getColor(this, R.color.cw_color_outline_variant)
        FamilyAvatarRenderer.presets.zip(avatarViews).forEach { (preset, view) ->
            val selected = preset.storageValue == selectedAvatarValue
            view.strokeColor = ColorStateList.valueOf(if (selected) primary else outline)
            view.strokeWidth = (if (selected) 3f else 1f) * resources.displayMetrics.density
            view.alpha = if (selected) 1f else 0.72f
            view.scaleX = if (selected) 1f else 0.92f
            view.scaleY = if (selected) 1f else 0.92f
        }
    }

    private fun showLoading(show: Boolean) {
        binding.inviteProgress.visibility = if (show) View.VISIBLE else View.GONE
        binding.createInvitationButton.isEnabled = !show
        binding.manageInvitationsButton.isEnabled = !show
        binding.transferDeviceButton.isEnabled = !show
        binding.inviteModeGroup.isEnabled = !show
    }

    private fun generateQr(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
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
