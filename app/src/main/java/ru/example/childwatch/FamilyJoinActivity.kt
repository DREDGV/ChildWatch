package ru.example.childwatch

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.onboarding.FamilyAppKind
import ru.childwatch.shared.onboarding.FamilyInvitationData
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser
import ru.childwatch.shared.onboarding.FamilyOnboardingRolePolicy
import ru.example.childwatch.databinding.ActivityFamilyJoinBinding
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentInvitationCompletion

/** Scan/paste, inspect the actual person, then confirm once. */
class FamilyJoinActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFamilyJoinBinding
    private val network by lazy { NetworkClient(this) }
    private var verified: FamilyInvitationData? = null
    private var token: String? = null
    private var busy = false
    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getStringExtra("SCANNED_QR_CODE")?.let {
            binding.codeInput.setText(it)
            preview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyJoinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.scanButton.setOnClickListener { scan.launch(Intent(this, QrScannerActivity::class.java).putExtra("invitation_only", true)) }
        binding.previewButton.setOnClickListener { preview() }
        binding.codeInput.doAfterTextChanged {
            verified = null; token = null
            binding.previewCard.isVisible = false
            binding.acceptButton.isEnabled = false
        }
        binding.acceptButton.setOnClickListener { accept() }
        binding.openAppButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
        val raw = savedInstanceState?.getString("entered_invitation") ?: intent.dataString ?: intent.getStringExtra("invitation")
        val completed = savedInstanceState?.getString("completed_summary")
        if (!completed.isNullOrBlank()) {
            binding.entryCard.isVisible = false
            binding.doneText.text = completed
            binding.doneCard.isVisible = true
        } else if (!raw.isNullOrBlank()) { binding.codeInput.setText(raw); preview() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("entered_invitation", binding.codeInput.text?.toString())
        if (binding.doneCard.isVisible) outState.putString("completed_summary", binding.doneText.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun preview() {
        if (busy) return
        val parsed = FamilyInvitationTokenParser.parse(binding.codeInput.text?.toString())
        if (parsed == null) { error(getString(R.string.family_receive_invalid)); return }
        verified = null; token = null
        binding.previewCard.isVisible = false
        loading(true)
        lifecycleScope.launch {
            try {
                check(network.ensureOnboardingAuthentication()) { getString(R.string.family_receive_network) }
                val response = network.previewFamilyInvitation(parsed)
                val invitation = response.body()?.invitation
                check(response.isSuccessful && invitation != null) { serverError(response.errorBody()?.string()) }
                check(!invitation.isExpired && !invitation.isConsumed && !invitation.isRevoked &&
                    invitation.expiresAt > System.currentTimeMillis()) { getString(R.string.family_receive_expired) }
                check(FamilyOnboardingRolePolicy.accepts(FamilyAppKind.PARENT_MONITOR, invitation.member.role)) {
                    getString(R.string.family_receive_wrong_app)
                }
                verified = invitation; token = parsed
                binding.memberNameText.text = invitation.member.displayName
                ru.example.childwatch.profile.FamilyAvatarRenderer.bind(binding.memberAvatar, invitation.member.avatarKey)
                binding.familyNameText.text = invitation.family.name
                binding.roleText.text = getString(if (invitation.member.role == "GUARDIAN")
                    R.string.family_receive_relative else R.string.family_receive_parent)
                val expiry = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT, java.util.Locale.forLanguageTag("ru-RU")).format(java.util.Date(invitation.expiresAt))
                binding.invitedByText.text = getString(R.string.family_receive_details, invitation.invitedBy, expiry) +
                    "\n\n" + getString(if (invitation.mode == "EXISTING_MEMBER")
                        R.string.family_receive_existing else R.string.family_receive_new)
                binding.previewCard.isVisible = true
                binding.statusText.isVisible = false
                binding.joinScroll.post { binding.joinScroll.smoothScrollTo(0, binding.previewCard.top) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure.message ?: getString(R.string.family_receive_network)) }
            finally { loading(false) }
        }
    }

    private fun accept() {
        if (busy) return
        val invitation = verified ?: return
        val confirmedToken = token ?: return
        if (invitation.expiresAt <= System.currentTimeMillis()) { error(getString(R.string.family_receive_expired)); return }
        loading(true)
        lifecycleScope.launch {
            try {
                val response = network.acceptFamilyInvitation(confirmedToken)
                val result = response.body()
                check(response.isSuccessful && result?.success == true) { serverError(response.errorBody()?.string()) }
                ParentInvitationCompletion.persist(this@FamilyJoinActivity, result!!.member)
                verified = null; token = null
                binding.previewCard.isVisible = false
                binding.entryCard.isVisible = false
                binding.statusText.isVisible = false
                binding.doneText.text = getString(R.string.family_receive_done, result.member.displayName, result.family.name)
                binding.doneCard.isVisible = true
                binding.joinScroll.post { binding.joinScroll.smoothScrollTo(0, binding.doneCard.top) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure.message ?: getString(R.string.family_receive_network)) }
            finally { loading(false) }
        }
    }

    private fun error(message: String) {
        verified = null; token = null
        binding.previewCard.isVisible = false
        binding.statusText.text = message
        binding.statusText.isVisible = true
    }
    private fun loading(value: Boolean) {
        busy = value
        binding.progressBar.isVisible = value
        binding.codeInput.isEnabled = !value
        binding.scanButton.isEnabled = !value
        binding.previewButton.isEnabled = !value
        binding.acceptButton.isEnabled = !value && verified != null
    }
    private fun serverError(raw: String?): String {
        val code = runCatching { JSONObject(raw.orEmpty()).optString("code") }.getOrNull()
        return getString(when (code) {
            "INVITATION_EXPIRED", "INVITATION_REVOKED", "INVITATION_ALREADY_USED" -> R.string.family_receive_expired
            "APP_ROLE_MISMATCH" -> R.string.family_receive_wrong_app
            "INVITATION_NOT_FOUND" -> R.string.family_receive_invalid
            "DEVICE_IN_ANOTHER_FAMILY", "DEVICE_ALREADY_ONBOARDED" -> R.string.family_receive_already_linked
            else -> R.string.family_receive_network
        })
    }
}
