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
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.family.FamilyRole
import ru.childwatch.shared.onboarding.ChildFirstRunStep
import ru.childwatch.shared.onboarding.FamilyAppKind
import ru.childwatch.shared.onboarding.FamilyInvitationData
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser
import ru.childwatch.shared.onboarding.FamilyOnboardingRolePolicy
import ru.example.parentwatch.databinding.ActivityFamilyJoinBinding
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.session.ChildFamilyDirectoryRepository
import ru.example.parentwatch.session.ChildFamilyOnboardingStore

/**
 * Joins this physical phone to a person profile chosen by a trusted family
 * member, told as two steps: take the code, then see what was decided.
 *
 * The screen never accepts or displays raw device identifiers as human
 * identity, and it always offers a way out: a phone that skips setup is
 * reachable again from «Настройки», and the application works without it.
 */
class FamilyJoinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyJoinBinding
    private val networkClient by lazy { NetworkClient(this) }
    private val onboardingStore by lazy { ChildFamilyOnboardingStore(this) }
    private var verifiedToken: String? = null
    private var verifiedExpiresAt = 0L
    private var requiredOnFirstRun = false
    private var busy = false

    /** The adult phones this family holds, used to say which phone to pick up. */
    private var parentPhones: List<String> = emptyList()

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
            qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java).putExtra("invitation_only", true))
        }
        binding.previewButton.setOnClickListener {
            previewInvitation(binding.codeInput.text?.toString())
        }
        binding.acceptButton.setOnClickListener {
            verifiedToken?.let(::acceptInvitation)
        }
        binding.acceptButton.isEnabled = false
        binding.codeInput.doAfterTextChanged { clearVerifiedPreview() }
        binding.openAppButton.setOnClickListener { openMain() }
        binding.laterButton.setOnClickListener {
            Toast.makeText(
                this,
                getString(R.string.child_join_later_done),
                Toast.LENGTH_LONG
            ).show()
            openMain()
        }
        binding.codeInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) clearVerifiedPreview()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        (intent.dataString ?: intent.getStringExtra(EXTRA_INVITATION))?.takeIf(String::isNotBlank)?.let { raw ->
            binding.codeInput.setText(raw)
            previewInvitation(raw)
        }

        showStepNumber(ChildFirstRunStep.CODE_NUMBER, ChildFirstRunStep.CODE_TOTAL)
        checkWhatIsAlreadyTrue()
    }

    /**
     * Says what already works before asking for anything.
     *
     * A phone that already reaches a grown-up of the family is not sent through
     * the code step as if nothing were connected; the screen states the link and
     * lets the person leave.
     */
    private fun checkWhatIsAlreadyTrue() {
        lifecycleScope.launch {
            val directory = runCatching { ChildFamilyDirectoryRepository(this@FamilyJoinActivity).refresh() }
                .onFailure { Log.w(TAG, "Family directory refresh failed", it) }
                .getOrNull()
            val adults = directory?.people
                .orEmpty()
                .filter { person ->
                    // Only a grown-up whose phone is really reachable counts: the
                    // sentence says the link already works, so it must be true.
                    person.activeDevices.isNotEmpty() &&
                        person.member.role in setOf(FamilyRole.PARENT, FamilyRole.GUARDIAN)
                }
                .map { it.member.displayName.trim() }
                .filter(String::isNotBlank)
                .distinct()
            if (adults.isEmpty()) {
                binding.linkedStateCard.visibility = View.GONE
                return@launch
            }
            parentPhones = adults
            binding.linkedStateCard.visibility = View.VISIBLE
            binding.linkedStateTitle.setText(R.string.child_join_already_title)
            binding.linkedStateBody.text =
                getString(R.string.child_join_already_body, adults.joinToString(", "))
        }
    }

    private fun previewInvitation(rawValue: String?) {
        if (busy) return
        val token = FamilyInvitationTokenParser.parse(rawValue)
        if (token == null) {
            showError(getString(R.string.child_join_error_not_ours))
            return
        }
        showLoading(true)
        lifecycleScope.launch {
            try {
                check(networkClient.ensureOnboardingAuthentication()) {
                    getString(R.string.child_join_error_registration)
                }
                val response = networkClient.previewFamilyInvitation(token)
                val invitation = response.body()?.invitation
                check(response.isSuccessful && invitation != null) {
                    readServerError(response.errorBody()?.string())
                }
                checkInvitationState(invitation)
                showPreview(token, invitation)
            } catch (error: Exception) {
                showError(error.message ?: getString(R.string.child_join_error_check))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun checkInvitationState(invitation: FamilyInvitationData) {
        check(!invitation.isExpired) { getString(R.string.child_join_error_expired) }
        check(!invitation.isConsumed) { getString(R.string.child_join_error_used) }
        check(!invitation.isRevoked) { getString(R.string.child_join_error_revoked) }
        check(
            FamilyOnboardingRolePolicy.accepts(
                FamilyAppKind.CHILD_DEVICE,
                invitation.member.role
            )
        ) {
            getString(R.string.child_join_error_adult_app)
        }
    }

    private fun showPreview(token: String, invitation: FamilyInvitationData) {
        verifiedToken = token
        verifiedExpiresAt = invitation.expiresAt
        binding.acceptButton.isEnabled = true
        binding.codeInputLayout.error = null
        binding.statusText.visibility = View.GONE
        binding.previewCard.visibility = View.VISIBLE
        binding.memberNameText.text = invitation.member.displayName
        binding.familyNameText.text = invitation.family.name
        binding.roleText.text = getString(
            R.string.child_join_role_label,
            roleLabel(invitation.member.role)
        )
        binding.invitedByText.text =
            getString(R.string.child_join_invited_by, invitation.invitedBy) + "\n\n" +
                getString(if (invitation.mode == "EXISTING_MEMBER")
                    R.string.child_connect_existing_result else R.string.child_connect_new_result) +
                "\n" + getString(R.string.child_connect_expires,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT, java.util.Locale.forLanguageTag("ru-RU")).format(java.util.Date(invitation.expiresAt)))
        // Step two: the code is verified, and the only thing left is to confirm.
        showStepNumber(ChildFirstRunStep.DONE_NUMBER, ChildFirstRunStep.DONE_TOTAL)
        binding.joinScroll.post { binding.joinScroll.smoothScrollTo(0, binding.previewCard.top) }
    }

    private fun acceptInvitation(token: String) {
        if (busy) return
        if (System.currentTimeMillis() >= verifiedExpiresAt) {
            showError(getString(R.string.child_join_error_expired))
            return
        }
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
                showLoading(false)
                showDone(
                    memberName = result.member.displayName,
                    familyName = result.family.name
                )
            } catch (error: Exception) {
                showLoading(false)
                showError(error.message ?: getString(R.string.child_join_error_accept))
            }
        }
    }

    /** The last screen of the first run: what is now true, and who to pick up. */
    private fun showDone(memberName: String, familyName: String) {
        binding.previewCard.visibility = View.GONE
        binding.howItWorksCard.visibility = View.GONE
        binding.scanButton.visibility = View.GONE
        binding.previewButton.visibility = View.GONE
        binding.codeInputLayout.visibility = View.GONE
        binding.orPasteText.visibility = View.GONE
        binding.laterButton.visibility = View.GONE
        binding.doneCard.visibility = View.VISIBLE
        binding.doneTitleText.setText(R.string.child_join_done_title)
        binding.joinTitleText.setText(R.string.child_join_done_title)
        binding.joinSubtitleText.setText(R.string.child_join_already_title)
        binding.stepIndicatorText.visibility = View.GONE
        val grownUp = parentPhones.firstOrNull()
        binding.doneBodyText.text = if (grownUp.isNullOrBlank()) {
            getString(R.string.child_join_done_body_without_parent, memberName, familyName)
        } else {
            getString(R.string.child_join_done_body, memberName, familyName, grownUp)
        }
        Toast.makeText(
            this,
            getString(R.string.child_join_joined, memberName),
            Toast.LENGTH_LONG
        ).show()
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

    private fun showStepNumber(number: Int, total: Int) {
        binding.stepIndicatorText.visibility = View.VISIBLE
        binding.stepIndicatorText.text =
            getString(R.string.child_join_step_format, number, total)
    }

    private fun clearVerifiedPreview() {
        verifiedToken = null
        verifiedExpiresAt = 0L
        binding.acceptButton.isEnabled = false
        binding.previewCard.visibility = View.GONE
        showStepNumber(ChildFirstRunStep.CODE_NUMBER, ChildFirstRunStep.CODE_TOTAL)
    }

    private fun showError(message: String) {
        clearVerifiedPreview()
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
        binding.codeInputLayout.error = message
    }

    private fun showLoading(show: Boolean) {
        busy = show
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.scanButton.isEnabled = !show
        binding.previewButton.isEnabled = !show
        binding.acceptButton.isEnabled = !show && verifiedToken != null
        binding.codeInput.isEnabled = !show
        binding.laterButton.isEnabled = !show
    }

    private fun readServerError(raw: String?): String = runCatching {
        val root = JSONObject(raw.orEmpty())
        when (root.optString("code")) {
            "INVITATION_EXPIRED" -> getString(R.string.child_join_error_expired)
            "INVITATION_ALREADY_USED" -> getString(R.string.child_join_error_used)
            "INVITATION_REVOKED" -> getString(R.string.child_join_error_revoked)
            "APP_ROLE_MISMATCH" -> getString(R.string.child_join_error_adult_app)
            "INVITATION_NOT_FOUND", "INVALID_INVITATION_TOKEN" -> getString(R.string.child_join_error_not_ours)
            "DEVICE_ALREADY_ONBOARDED", "DEVICE_IN_ANOTHER_FAMILY" -> getString(R.string.child_connect_already_linked)
            else -> getString(R.string.child_join_error_server)
        }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: getString(R.string.child_join_error_server)

    private fun roleLabel(role: String): String = when (role.trim().uppercase()) {
        "PARENT" -> getString(R.string.child_join_role_parent)
        "GUARDIAN", "RELATIVE" -> getString(R.string.child_join_role_guardian)
        else -> getString(R.string.child_join_role_child)
    }

    /**
     * Leaving is allowed. A first run that traps the person is worse than an
     * unfinished one, and this screen is reachable again from the settings.
     */
    private fun handleBack() {
        if (requiredOnFirstRun && !onboardingStore.isCompleted()) {
            Toast.makeText(
                this,
                getString(R.string.child_join_required_next_time),
                Toast.LENGTH_LONG
            ).show()
        }
        openMain()
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
