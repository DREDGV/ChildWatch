package ru.example.childwatch

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.LocaleList
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.onboarding.FamilyAppKind
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser
import ru.childwatch.shared.onboarding.FamilyOnboardingRolePolicy
import ru.childwatch.shared.onboarding.OnboardingMemberData
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Parent
import ru.example.childwatch.databinding.ActivityParentSetupBinding
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.onboarding.ParentOnboardingSyncWorker
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.profile.ParentParticipantNameResolver
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
    private val deferredSetupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var selectedAvatarValue = FamilyAvatarRenderer.presets.first().storageValue
    private var avatarPresetViews: List<ShapeableImageView> = emptyList()

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
        val nameLocales = LocaleList.forLanguageTags("ru-RU,en-US")
        binding.nameInput.imeHintLocales = nameLocales
        binding.familyNameInput.imeHintLocales = nameLocales
        FamilyAvatarRenderer.bind(binding.avatarImage, selectedAvatarValue)
        setupAvatarPresetChoices()
        binding.changeAvatarButton.setOnClickListener {
            showAvatarSourcePicker()
        }
        binding.continueButton.setOnClickListener { validateAndCreateFamily() }
        binding.configureLaterButton.setOnClickListener { configureLater() }
        binding.skipButton.setOnClickListener { showInvitationEntry() }
    }

    private fun setupAvatarPresetChoices() {
        val density = resources.displayMetrics.density
        val views = mutableListOf(
            binding.parentAvatarPreset1,
            binding.parentAvatarPreset2,
            binding.parentAvatarPreset3,
            binding.parentAvatarPreset4,
            binding.parentAvatarPreset5,
            binding.parentAvatarPreset6
        )
        FamilyAvatarRenderer.presets.drop(views.size).forEach { preset ->
            val view = ShapeableImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (56 * density).toInt(),
                    (56 * density).toInt()
                ).apply { marginStart = (10 * density).toInt() }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                isClickable = true
                isFocusable = true
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(28 * density)
                    .build()
                FamilyAvatarRenderer.bind(this, preset.storageValue)
                binding.parentAvatarPresetContainer.addView(this)
            }
            views += view
        }
        avatarPresetViews = views
        FamilyAvatarRenderer.presets.zip(views).forEachIndexed { index, (preset, view) ->
            FamilyAvatarRenderer.bind(view, preset.storageValue)
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
        val views = avatarPresetViews
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

    private fun showAvatarSourcePicker() {
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf("Выбрать аватар", "Выбрать своё фото")) { _, which ->
                if (which == 0) showPresetAvatarPicker()
                else pickImageLauncher.launch(arrayOf("image/*"))
            }
            .show()
    }

    /** Full preset sheet. New avatar keys are stored, not device-local image paths. */
    private fun showPresetAvatarPicker() {
        val density = resources.displayMetrics.density
        val avatarSize = (64 * density).toInt()
        val margin = (6 * density).toInt()
        val grid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            setPadding(margin, margin, margin, margin)
        }
        FamilyAvatarRenderer.presets.forEachIndexed { index, preset ->
            val avatar = ShapeableImageView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = avatarSize
                    height = avatarSize
                    setMargins(margin, margin, margin, margin)
                }
                contentDescription = getString(
                    R.string.family_profile_avatar_preset_description,
                    index + 1
                )
                isClickable = true
                isFocusable = true
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(avatarSize / 2f)
                    .build()
                strokeWidth = if (preset.storageValue == selectedAvatarValue) 3f * density else 1f * density
                strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@ParentSetupActivity,
                        if (preset.storageValue == selectedAvatarValue) R.color.cw_color_primary
                        else R.color.cw_color_outline_variant
                    )
                )
                FamilyAvatarRenderer.bind(this, preset.storageValue)
            }
            grid.addView(avatar)
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Выберите аватар")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .create()
        for (index in 0 until grid.childCount) {
            grid.getChildAt(index).setOnClickListener {
                selectedAvatarValue = FamilyAvatarRenderer.presets[index].storageValue
                FamilyAvatarRenderer.bind(binding.avatarImage, selectedAvatarValue)
                refreshAvatarPresetSelection()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun validateAndCreateFamily() {
        val name = binding.nameInput.text?.toString().orEmpty().trim()
            .ifEmpty { getString(R.string.parent_setup_default_name) }
        val familyName = binding.familyNameInput.text?.toString().orEmpty().trim()
            .ifEmpty { getString(R.string.parent_setup_default_family) }
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val phone = binding.phoneInput.text?.toString().orEmpty().trim()

        binding.nameInputLayout.error = null
        binding.familyNameInputLayout.error = null
        binding.emailInputLayout.error =
            if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                "Неверный формат email"
            } else null
        if (binding.emailInputLayout.error != null) return
        binding.phoneInputLayout.error =
            if (phone.isNotEmpty() && phone.length < 10) "Неверный формат телефона" else null
        if (binding.phoneInputLayout.error != null) return

        completeLocallyAndScheduleSync(familyName, name, email, phone)
    }

    private fun configureLater() {
        binding.nameInputLayout.error = null
        binding.familyNameInputLayout.error = null
        binding.emailInputLayout.error = null
        binding.phoneInputLayout.error = null
        completeLocallyAndScheduleSync(
            familyName = getString(R.string.parent_setup_default_family),
            name = getString(R.string.parent_setup_default_name),
            email = "",
            phone = "",
            completionMessage = getString(R.string.parent_setup_later_done)
        )
    }

    private fun completeLocallyAndScheduleSync(
        familyName: String,
        name: String,
        email: String,
        phone: String,
        completionMessage: String? = null
    ) {
        val avatarValue = selectedAvatarValue
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
        getSharedPreferences("childwatch_prefs", MODE_PRIVATE).edit()
            .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, name)
            .apply()

        Toast.makeText(
            this,
            completionMessage ?: "Семья настроена",
            Toast.LENGTH_SHORT
        ).show()

        /*
         * A fresh Room/WorkManager initialization can be slow on an emulator.
         * Keep it outside the click handler so Android never sees a frozen UI.
         */
        deferredSetupScope.launch {
            try {
                persistCompletedProfile(
                    OnboardingMemberData(
                        id = null,
                        familyId = null,
                        displayName = name,
                        role = "PARENT",
                        avatarKey = avatarValue
                    ),
                    email,
                    phone
                )
                ParentOnboardingSyncWorker.enqueue(
                    context = applicationContext,
                    familyName = familyName,
                    displayName = name,
                    avatarValue = avatarValue
                )
            } catch (error: Exception) {
                Log.e(TAG, "Deferred family setup failed", error)
            }
        }
        navigateToMain()
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
        binding.configureLaterButton.isEnabled = !show
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
