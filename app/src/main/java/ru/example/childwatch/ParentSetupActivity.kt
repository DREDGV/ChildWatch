package ru.example.childwatch

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.onboarding.FamilyBootstrapRequest
import ru.childwatch.shared.onboarding.FamilyAppKind
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser
import ru.childwatch.shared.onboarding.FamilyOnboardingRolePolicy
import ru.childwatch.shared.onboarding.FamilyProfileConfirmationRequest
import ru.childwatch.shared.onboarding.OnboardingMemberData
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Parent
import ru.example.childwatch.databinding.ActivityParentSetupBinding
import ru.example.childwatch.network.AuthenticatedMembershipData
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.profile.ParentFamilyDirectoryRepository
import java.util.UUID

/** First-run wizard. A person profile and a phone binding are separate records. */
class ParentSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ParentSetupActivity"
        const val PREFS_NAME = "parent_onboarding"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_PARENT_ID = "parent_id"
    }

    private lateinit var binding: ActivityParentSetupBinding
    private val database by lazy { ChildWatchDatabase.getInstance(this) }
    private val networkClient by lazy { NetworkClient(this) }
    private var selectedAvatarValue = FamilyAvatarRenderer.presets.first().storageValue

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        selectedAvatarValue = uri.toString()
        FamilyAvatarRenderer.bind(binding.avatarImage, selectedAvatarValue)
        refreshAvatarPresetSelection()
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            acceptInvitationValue(result.data?.getStringExtra("SCANNED_QR_CODE"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUi()
        if (isCompleted()) navigateToMain()
    }

    private fun setupUi() {
        FamilyAvatarRenderer.bind(binding.avatarImage, selectedAvatarValue)
        setupAvatarPresetChoices()
        binding.changeAvatarButton.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }
        binding.continueButton.setOnClickListener { validateAndCreateFamily() }
        binding.skipButton.setOnClickListener { showInvitationEntry() }
    }

    private fun setupAvatarPresetChoices() {
        val views = listOf(
            binding.parentAvatarPreset1,
            binding.parentAvatarPreset2,
            binding.parentAvatarPreset3,
            binding.parentAvatarPreset4,
            binding.parentAvatarPreset5,
            binding.parentAvatarPreset6
        )
        FamilyAvatarRenderer.presets.zip(views).forEachIndexed { index, (preset, view) ->
            view.contentDescription = getString(
                R.string.family_profile_avatar_preset_description,
                index + 1
            )
            view.setOnClickListener {
                selectedAvatarValue = preset.storageValue
                FamilyAvatarRenderer.bind(binding.avatarImage, selectedAvatarValue)
                refreshAvatarPresetSelection()
            }
        }
        refreshAvatarPresetSelection()
    }

    private fun refreshAvatarPresetSelection() {
        val views = listOf(
            binding.parentAvatarPreset1,
            binding.parentAvatarPreset2,
            binding.parentAvatarPreset3,
            binding.parentAvatarPreset4,
            binding.parentAvatarPreset5,
            binding.parentAvatarPreset6
        )
        val primary = ContextCompat.getColor(this, R.color.cw_color_primary)
        val outline = ContextCompat.getColor(this, R.color.cw_color_outline_variant)
        FamilyAvatarRenderer.presets.zip(views).forEach { (preset, view) ->
            val selected = preset.storageValue == selectedAvatarValue
            view.strokeColor = ColorStateList.valueOf(if (selected) primary else outline)
            view.strokeWidth = (if (selected) 3f else 1f) * resources.displayMetrics.density
            view.alpha = if (selected) 1f else 0.72f
            view.scaleX = if (selected) 1f else 0.92f
            view.scaleY = if (selected) 1f else 0.92f
        }
    }

    private fun validateAndCreateFamily() {
        val name = binding.nameInput.text?.toString().orEmpty().trim()
        val familyName = binding.familyNameInput.text?.toString().orEmpty().trim()
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val phone = binding.phoneInput.text?.toString().orEmpty().trim()

        binding.nameInputLayout.error = when {
            name.isEmpty() -> "Введите ваше имя"
            name.length < 2 -> "Имя слишком короткое"
            else -> null
        }
        if (binding.nameInputLayout.error != null) {
            binding.nameInput.requestFocus()
            return
        }
        binding.familyNameInputLayout.error =
            if (familyName.length < 2) "Введите название семьи" else null
        if (binding.familyNameInputLayout.error != null) {
            binding.familyNameInput.requestFocus()
            return
        }
        binding.emailInputLayout.error =
            if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                "Неверный формат email"
            } else null
        if (binding.emailInputLayout.error != null) return
        binding.phoneInputLayout.error =
            if (phone.isNotEmpty() && phone.length < 10) "Неверный формат телефона" else null
        if (binding.phoneInputLayout.error != null) return

        createOrConfirmFamily(familyName, name, email, phone)
    }

    private fun createOrConfirmFamily(
        familyName: String,
        name: String,
        email: String,
        phone: String
    ) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                check(networkClient.ensureOnboardingAuthentication()) {
                    "Не удалось зарегистрировать телефон на сервере"
                }
                val identityResponse = networkClient.getAuthenticatedIdentity()
                val identity = identityResponse.body()
                    ?.takeIf { identityResponse.isSuccessful && it.success }
                check(identity != null) {
                    readServerError(identityResponse.errorBody()?.string())
                }
                val existing = identity.memberships
                    .sortedWith(
                        compareByDescending<AuthenticatedMembershipData> {
                            it.binding.memberBindingSource == "EXPLICIT"
                        }.thenByDescending { it.binding.updatedAt }
                    )
                    .firstOrNull()
                val member = if (existing != null) {
                    val avatarKey = selectedAvatarValue.takeIf { it.startsWith("preset:") }
                    if (existing.binding.memberBindingSource != "EXPLICIT") {
                        val response = networkClient.confirmOwnFamilyProfile(
                            existing.familyId,
                            FamilyProfileConfirmationRequest(
                                displayName = name,
                                avatarKey = avatarKey
                            )
                        )
                        check(response.isSuccessful && response.body()?.success == true) {
                            readServerError(response.errorBody()?.string())
                        }
                        response.body()!!.member
                    } else {
                        check(
                            ParentFamilyDirectoryRepository(this@ParentSetupActivity)
                                .updateOwnProfile(name, selectedAvatarValue)
                        ) { "Не удалось сохранить профиль семьи" }
                        OnboardingMemberData(
                            id = existing.member.id,
                            familyId = existing.familyId,
                            displayName = name,
                            role = existing.member.role,
                            avatarKey = avatarKey
                        )
                    }
                } else {
                    val response = networkClient.bootstrapFamily(
                        FamilyBootstrapRequest(
                            familyName = familyName,
                            displayName = name,
                            role = "PARENT",
                            avatarKey = selectedAvatarValue.takeIf { it.startsWith("preset:") }
                        )
                    )
                    check(response.isSuccessful && response.body()?.success == true) {
                        readServerError(response.errorBody()?.string())
                    }
                    response.body()!!.member
                }
                persistCompletedProfile(member, email, phone)
                Toast.makeText(this@ParentSetupActivity, "Семья настроена", Toast.LENGTH_SHORT).show()
                navigateToMain()
            } catch (error: Exception) {
                Log.e(TAG, "Family setup failed", error)
                showLoading(false)
                Toast.makeText(this@ParentSetupActivity, error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showInvitationEntry() {
        val input = EditText(this).apply {
            hint = "Код или ссылка приглашения"
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Вступить в семью")
            .setMessage("Отсканируйте QR-код на телефоне члена семьи или вставьте одноразовую ссылку.")
            .setView(input)
            .setPositiveButton("Продолжить") { _, _ ->
                acceptInvitationValue(input.text?.toString())
            }
            .setNeutralButton("Сканировать QR") { _, _ ->
                qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun acceptInvitationValue(rawValue: String?) {
        val token = FamilyInvitationTokenParser.parse(rawValue)
        if (token == null) {
            Toast.makeText(this, "Это не приглашение ChildWatch", Toast.LENGTH_LONG).show()
            return
        }
        showLoading(true)
        lifecycleScope.launch {
            try {
                check(networkClient.ensureOnboardingAuthentication()) {
                    "Не удалось зарегистрировать телефон на сервере"
                }
                val preview = networkClient.previewFamilyInvitation(token)
                val invitation = preview.body()?.invitation
                check(preview.isSuccessful && invitation != null) {
                    readServerError(preview.errorBody()?.string())
                }
                check(!invitation.isExpired && !invitation.isConsumed && !invitation.isRevoked) {
                    "Приглашение уже недействительно. Попросите создать новое."
                }
                check(
                    FamilyOnboardingRolePolicy.accepts(
                        FamilyAppKind.PARENT_MONITOR,
                        invitation.member.role
                    )
                ) {
                    "Это приглашение для детского приложения ChildDevice. Здесь можно добавить только взрослого члена семьи."
                }
                showLoading(false)
                MaterialAlertDialogBuilder(this@ParentSetupActivity)
                    .setTitle(invitation.family.name)
                    .setMessage(
                        "Вы присоединитесь как ${invitation.member.displayName}. " +
                            "Приглашение создал(а) ${invitation.invitedBy}."
                    )
                    .setPositiveButton("Присоединиться") { _, _ -> completeInvitation(token) }
                    .setNegativeButton("Отмена", null)
                    .show()
            } catch (error: Exception) {
                showLoading(false)
                Toast.makeText(this@ParentSetupActivity, error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun completeInvitation(token: String) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = networkClient.acceptFamilyInvitation(token)
                val result = response.body()
                check(response.isSuccessful && result?.success == true) {
                    readServerError(response.errorBody()?.string())
                }
                persistCompletedProfile(result!!.member, "", "")
                Toast.makeText(
                    this@ParentSetupActivity,
                    "Телефон добавлен в семью",
                    Toast.LENGTH_SHORT
                ).show()
                navigateToMain()
            } catch (error: Exception) {
                showLoading(false)
                Toast.makeText(this@ParentSetupActivity, error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun persistCompletedProfile(
        member: OnboardingMemberData,
        email: String,
        phone: String
    ) {
        val memberAccountId = member.id
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        val existing = database.parentDao().getByAccountId(memberAccountId)
            ?: database.parentDao().getAll().firstOrNull()
        val parentId = database.parentDao().insert(
            Parent(
                id = existing?.id ?: 0L,
                accountId = memberAccountId,
                name = member.displayName,
                email = email.ifEmpty {
                    existing?.email?.takeIf(String::isNotBlank)
                        ?: "parent@childwatch.local"
                },
                phoneNumber = phone.ifEmpty { existing?.phoneNumber },
                avatarUrl = member.avatarKey ?: selectedAvatarValue,
                passwordHash = existing?.passwordHash,
                isVerified = true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .putLong(KEY_PARENT_ID, parentId)
            .apply()
    }

    private fun readServerError(raw: String?): String =
        runCatching { JSONObject(raw.orEmpty()).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Сервер не завершил настройку. Попробуйте ещё раз."

    private fun isCompleted(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.continueButton.isEnabled = !show
        binding.skipButton.isEnabled = !show
        binding.changeAvatarButton.isEnabled = !show
        binding.nameInput.isEnabled = !show
        binding.familyNameInput.isEnabled = !show
        binding.emailInput.isEnabled = !show
        binding.phoneInput.isEnabled = !show
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Toast.makeText(this, "Завершите настройку или примите приглашение", Toast.LENGTH_SHORT).show()
    }
}
