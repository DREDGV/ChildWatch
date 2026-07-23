package ru.example.parentwatch

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.family.FamilyRole
import ru.childwatch.shared.onboarding.FamilyAppKind
import ru.childwatch.shared.onboarding.FamilyInvitationData
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser
import ru.childwatch.shared.onboarding.FamilyOnboardingRolePolicy
import ru.example.parentwatch.databinding.ActivityFamilyJoinBinding
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.session.ChildFamilyDirectoryRepository
import ru.example.parentwatch.session.ChildFamilyOnboardingStore

/**
 * Joins this physical phone to a person profile chosen by a trusted family member.
 * The screen never accepts or displays raw device identifiers as human identity.
 */
class FamilyJoinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyJoinBinding
    private val networkClient by lazy { NetworkClient(this) }
    private val onboardingStore by lazy { ChildFamilyOnboardingStore(this) }
    private var verifiedToken: String? = null
    private var requiredOnFirstRun = false

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val value = result.data?.getStringExtra("SCANNED_QR_CODE")
            binding.codeInput.setText(value.orEmpty())
            previewInvitation(value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyJoinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requiredOnFirstRun = intent.getBooleanExtra(EXTRA_REQUIRED_ON_FIRST_RUN, false)

        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.scanButton.setOnClickListener {
            qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
        }
        binding.previewButton.setOnClickListener {
            previewInvitation(binding.codeInput.text?.toString())
        }
        binding.acceptButton.setOnClickListener {
            verifiedToken?.let(::acceptInvitation)
        }
        binding.codeInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) clearVerifiedPreview()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        intent.getStringExtra(EXTRA_INVITATION)?.takeIf(String::isNotBlank)?.let { raw ->
            binding.codeInput.setText(raw)
            previewInvitation(raw)
        }
    }

    private fun previewInvitation(rawValue: String?) {
        val token = FamilyInvitationTokenParser.parse(rawValue)
        if (token == null) {
            showError("Это не приглашение ChildWatch. Попросите взрослого создать новый QR-код семьи.")
            return
        }
        showLoading(true)
        lifecycleScope.launch {
            try {
                check(networkClient.ensureOnboardingAuthentication()) {
                    "Не удалось зарегистрировать телефон на сервере"
                }
                val response = networkClient.previewFamilyInvitation(token)
                val invitation = response.body()?.invitation
                check(response.isSuccessful && invitation != null) {
                    readServerError(response.errorBody()?.string())
                }
                checkInvitationState(invitation)
                showPreview(token, invitation)
            } catch (error: Exception) {
                showError(error.message ?: "Не удалось проверить приглашение")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun checkInvitationState(invitation: FamilyInvitationData) {
        check(!invitation.isExpired) { "Срок приглашения истёк. Попросите создать новое." }
        check(!invitation.isConsumed) { "Это приглашение уже использовано." }
        check(!invitation.isRevoked) { "Это приглашение отменено." }
        check(
            FamilyOnboardingRolePolicy.accepts(
                FamilyAppKind.CHILD_DEVICE,
                invitation.member.role
            )
        ) {
            "Это приглашение для взрослого приложения ParentMonitor. На детском телефоне нужно приглашение с ролью «Ребёнок»."
        }
    }

    private fun showPreview(token: String, invitation: FamilyInvitationData) {
        verifiedToken = token
        binding.codeInputLayout.error = null
        binding.statusText.visibility = View.GONE
        binding.previewCard.visibility = View.VISIBLE
        binding.memberNameText.text = invitation.member.displayName
        binding.familyNameText.text = invitation.family.name
        binding.roleText.text = "Роль: ${roleLabel(invitation.member.role)}"
        binding.invitedByText.text = "Приглашение создал(а): ${invitation.invitedBy}"
    }

    private fun acceptInvitation(token: String) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = networkClient.acceptFamilyInvitation(token)
                val result = response.body()
                check(response.isSuccessful && result?.success == true) {
                    readServerError(response.errorBody()?.string())
                }
                onboardingStore.markCompleted(result!!.family.id, result.member.id.orEmpty())
                runCatching { synchronizeFamilyContext() }
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "Invitation was accepted, but family context refresh is deferred",
                            error
                        )
                    }
                Toast.makeText(
                    this@FamilyJoinActivity,
                    "Телефон добавлен: ${result.member.displayName}",
                    Toast.LENGTH_LONG
                ).show()
                openMain()
            } catch (error: Exception) {
                showError(error.message ?: "Не удалось добавить телефон в семью")
                showLoading(false)
            }
        }
    }

    private suspend fun synchronizeFamilyContext() {
        val directory = ChildFamilyDirectoryRepository(this).refresh() ?: return
        val selfMemberId = directory.selfMemberId
        val targetDeviceId = directory.people
            .asSequence()
            .filter { person ->
                person.member.id != selfMemberId &&
                    person.member.role in setOf(FamilyRole.PARENT, FamilyRole.GUARDIAN)
            }
            .flatMap { it.activeDevices.asSequence() }
            .maxByOrNull { it.lastSeenAt ?: 0L }
            ?.deviceId
            ?: return
        sequenceOf("parentwatch_prefs", "childwatch_prefs").forEach { prefsName ->
            getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                .putString("selected_parent_device_id", targetDeviceId)
                .putString("parent_device_id", targetDeviceId)
                .putString("linked_parent_device_id", targetDeviceId)
                .apply()
        }
    }

    private fun clearVerifiedPreview() {
        verifiedToken = null
        binding.previewCard.visibility = View.GONE
    }

    private fun showError(message: String) {
        clearVerifiedPreview()
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
        binding.codeInputLayout.error = message
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.scanButton.isEnabled = !show
        binding.previewButton.isEnabled = !show
        binding.acceptButton.isEnabled = !show
        binding.codeInput.isEnabled = !show
    }

    private fun readServerError(raw: String?): String = runCatching {
        val root = JSONObject(raw.orEmpty())
        root.optString("error").ifBlank { root.optString("message") }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: "Сервер не завершил подключение. Попробуйте ещё раз."

    private fun roleLabel(role: String): String = when (role.trim().uppercase()) {
        "PARENT" -> "родитель"
        "GUARDIAN", "RELATIVE" -> "взрослый"
        else -> "ребёнок"
    }

    private fun handleBack() {
        if (requiredOnFirstRun && !onboardingStore.isCompleted()) {
            Toast.makeText(
                this,
                "Для начала работы добавьте телефон в семью",
                Toast.LENGTH_SHORT
            ).show()
            moveTaskToBack(true)
        } else {
            finish()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        private const val TAG = "FamilyJoinActivity"
        const val EXTRA_REQUIRED_ON_FIRST_RUN = "required_on_first_run"
        const val EXTRA_INVITATION = "family_invitation"
    }
}
