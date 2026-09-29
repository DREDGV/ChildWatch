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
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.childwatch.shared.onboarding.FamilyAppKind
import ru.childwatch.shared.onboarding.FamilyBootstrapRequest
import ru.childwatch.shared.onboarding.FamilyFirstRunAction
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser
import ru.childwatch.shared.onboarding.FamilyOnboardingRolePolicy
import ru.childwatch.shared.onboarding.OnboardingMemberData
import ru.childwatch.shared.onboarding.ParentFirstRunPolicy
import ru.childwatch.shared.onboarding.ParentFirstRunStep
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Parent
import ru.example.childwatch.databinding.ActivityParentSetupBinding
import ru.example.childwatch.firstrun.FamilyFirstRunState
import ru.example.childwatch.firstrun.ParentFirstRunResolver
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.profile.ParentParticipantNameResolver
import java.util.UUID

/**
 * First run of ParentMonitor, told as steps instead of one long form.
 *
 * The screen starts by stating what already exists: the server is asked whether
 * this phone is in a family and whether a phone of the child is already
 * connected. Only a phone with nothing behind it is asked to start or join a
 * family, and every screen keeps a way out to the main screen, so a skipped
 * setup is always reachable again from «Настройки» → «Семья».
 */
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
    private val firstRunResolver by lazy { ParentFirstRunResolver(this) }
    private val deferredSetupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var selectedAvatarValue = FamilyAvatarRenderer.selectableValues().first()
    private var avatarPresetViews: List<ShapeableImageView> = emptyList()

    /** What the server said about this phone, or null while it has not answered. */
    private var knownState: FamilyFirstRunState? = null

    /** The screen that is on display, so the code knows what its button does. */
    private var screen: Screen = Screen.CHECKING

    private enum class Screen {
        CHECKING,
        ALREADY_IN_FAMILY,
        OFFER_CONNECT,
        ASK_OR_JOIN,
        CONNECTION,
        OFFLINE
    }

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
        // Leaving is always allowed: the main screen is where the person asked to
        // go, and setup stays reachable from the settings. A first run that traps
        // the person is worse than an unfinished one. On the second step, back
        // first returns to the first one, which is what a step is expected to do.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == Screen.CONNECTION) {
                    renderNameStep()
                    return
                }
                leaveSetup(message = getString(R.string.first_run_later_from_home))
            }
        })
        if (isCompleted()) {
            navigateToMain()
            return
        }
        resolveWhatIsTrue()
    }

    private fun setupUi() {
        val nameLocales = LocaleList.forLanguageTags("ru-RU,en-US")
        binding.nameInput.imeHintLocales = nameLocales
        binding.familyNameInput.imeHintLocales = nameLocales
        binding.invitationInput.imeHintLocales = nameLocales
        binding.familyNameInput.setText(R.string.parent_setup_default_family)
        FamilyAvatarRenderer.bind(binding.avatarImage, selectedAvatarValue)
        setupAvatarPresetChoices()
        binding.changeAvatarButton.setOnClickListener { showAvatarSourcePicker() }
        // Which of the two ways in is chosen decides what the button does, so the
        // person never has to guess whether they are creating a family or joining one.
        binding.createFamilyRadio.setOnClickListener {
            binding.createFamilyRadio.isChecked = true
            binding.joinFamilyRadio.isChecked = false
            applyAskOrJoinFields()
        }
        binding.joinFamilyRadio.setOnClickListener {
            binding.joinFamilyRadio.isChecked = true
            binding.createFamilyRadio.isChecked = false
            applyAskOrJoinFields()
        }
        binding.continueButton.setOnClickListener { onPrimaryAction() }
        binding.connectFamilyButton.setOnClickListener { connectPhoneToVisibleFamily() }
        binding.configureLaterButton.setOnClickListener { configureLater() }
        binding.skipButton.setOnClickListener { showInvitationEntry() }
        binding.openAppButton.setOnClickListener {
            leaveSetup(message = getString(R.string.first_run_later_from_home))
        }
        renderChecking()
    }

    // ---------------------------------------------------------------- what is true

    /**
     * Asks the server before showing any form.
     *
     * The absence of a local "setup finished" flag is not evidence that no family
     * exists — that flag is lost when the application is reinstalled, while the
     * family, the person and the linked phone of the child still exist. Asking
     * here is what stops the screen from offering work that is already done.
     */
    private fun resolveWhatIsTrue() {
        renderChecking()
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                runCatching { firstRunResolver.resolve() }
                    .onFailure { Log.w(TAG, "First-run state lookup failed", it) }
                    .getOrNull()
                    ?: FamilyFirstRunState(serverReachable = false)
            }
            knownState = state
            render(
                ParentFirstRunPolicy.decide(
                    localCompleted = false,
                    serverReachable = state.serverReachable,
                    hasFamilyMembership = state.inFamily,
                    hasKnownChildPhone = state.knowsChildPhone
                )
            )
        }
    }

    private fun render(action: FamilyFirstRunAction) {
        when (action) {
            FamilyFirstRunAction.CONTINUE_TO_APP -> navigateToMain()
            FamilyFirstRunAction.SAY_ALREADY_IN_FAMILY -> renderAlreadyInFamily()
            FamilyFirstRunAction.OFFER_CONNECT_TO_LINKED -> renderOfferConnect()
            FamilyFirstRunAction.ASK_START_OR_JOIN -> renderAskOrJoin()
            FamilyFirstRunAction.OFFLINE_RETRY -> renderOffline()
        }
    }

    private fun renderChecking() {
        screen = Screen.CHECKING
        binding.progressBar.visibility = View.VISIBLE
        binding.stateCard.visibility = View.GONE
        binding.stepIndicatorText.visibility = View.GONE
        binding.titleText.setText(R.string.first_run_welcome_title)
        binding.subtitleText.setText(R.string.first_run_checking_server)
        showPersonStep(show = false)
        showFamilyStep(show = false)
        showOptionalContacts(show = false)
        binding.connectFamilyButton.visibility = View.GONE
        // Not while the check runs: leaving in the middle would show a claim the
        // screen has not verified yet.
        binding.openAppButton.visibility = View.GONE
        binding.continueButton.visibility = View.GONE
        binding.skipButton.visibility = View.GONE
        binding.configureLaterButton.visibility = View.GONE
    }

    /** The phone is in a family: say so, and ask nothing. */
    private fun renderAlreadyInFamily() {
        val state = knownState
        val person = state?.self
        screen = Screen.ALREADY_IN_FAMILY
        binding.progressBar.visibility = View.GONE
        binding.titleText.setText(R.string.first_run_already_title)
        binding.subtitleText.setText(R.string.first_run_already_done)

        binding.stateCard.visibility = View.VISIBLE
        binding.stateTitle.text = getString(
            R.string.first_run_in_family_title,
            state?.familyName ?: getString(R.string.parent_setup_default_family)
        )
        binding.stateBody.text = getString(
            R.string.first_run_in_family_body,
            person?.displayName ?: getString(R.string.parent_setup_default_name)
        )
        renderLinkedChildLines(state, childMayBeUnknown = false)

        binding.stepIndicatorText.visibility = View.GONE
        showPersonStep(show = false)
        showFamilyStep(show = false)
        showOptionalContacts(show = false)

        binding.continueButton.visibility = View.VISIBLE
        binding.continueButton.setText(R.string.first_run_continue)
        binding.connectFamilyButton.visibility = View.GONE
        binding.configureLaterButton.visibility = View.VISIBLE
        binding.skipButton.visibility = View.GONE
        binding.openAppButton.visibility = View.GONE
    }

    /** The phone knows a phone of the child but is not in the family itself. */
    private fun renderOfferConnect() {
        val state = knownState
        val child = state?.suggestedChildPhone()
        screen = Screen.OFFER_CONNECT
        binding.progressBar.visibility = View.GONE
        binding.titleText.setText(R.string.first_run_connect_title)
        binding.subtitleText.setText(R.string.first_run_connect_subtitle)

        binding.stateCard.visibility = View.VISIBLE
        binding.stateTitle.setText(R.string.first_run_known_child_title)
        binding.stateBody.text = child?.displayName?.let { childName ->
            state?.familyName?.takeIf { it.isNotBlank() }?.let { familyName ->
                getString(R.string.first_run_known_child_body, familyName, childName)
            } ?: getString(R.string.first_run_known_child_without_family_name, childName)
        } ?: getString(R.string.first_run_known_child_body_unnamed)
        renderLinkedChildLines(state, childMayBeUnknown = true)

        binding.stepIndicatorText.visibility = View.GONE
        showPersonStep(show = false)
        showFamilyStep(show = false)
        showOptionalContacts(show = false)

        binding.continueButton.visibility = View.VISIBLE
        binding.continueButton.setText(R.string.first_run_connect_action)
        binding.connectFamilyButton.visibility = View.VISIBLE
        binding.connectFamilyButton.setText(R.string.first_run_invitation_title)
        binding.configureLaterButton.visibility = View.VISIBLE
        binding.skipButton.visibility = View.GONE
        binding.openAppButton.visibility = View.GONE
    }

    /** Nothing is known about this phone: it decides, one question at a time. */
    private fun renderAskOrJoin() = renderNameStep()

    /**
     * Step one of two: who this person is.
     *
     * The name is the only thing asked here. The family question is a screen of
     * its own, because a phone with nothing behind it is exactly the case where a
     * person can still be overwhelmed by several decisions at once.
     */
    private fun renderNameStep() {
        screen = Screen.ASK_OR_JOIN
        binding.progressBar.visibility = View.GONE
        binding.titleText.setText(R.string.first_run_welcome_title)
        binding.subtitleText.setText(R.string.first_run_welcome_subtitle)

        binding.stateCard.visibility = View.VISIBLE
        binding.stateTitle.setText(R.string.first_run_empty_title)
        binding.stateBody.setText(R.string.first_run_empty_body)
        renderLinkedChildLines(null, childMayBeUnknown = false)

        binding.stepIndicatorText.visibility = View.VISIBLE
        binding.stepIndicatorText.text = getString(
            R.string.first_run_step_format,
            ParentFirstRunStep.WHO.number,
            ParentFirstRunStep.WHO.total
        )
        showPersonStep(show = true)
        showFamilyStep(show = false)
        showOptionalContacts(show = false)

        binding.continueButton.visibility = View.VISIBLE
        binding.continueButton.setText(R.string.first_run_continue)
        binding.connectFamilyButton.visibility = View.GONE
        binding.configureLaterButton.visibility = View.VISIBLE
        binding.skipButton.visibility = View.VISIBLE
        binding.skipButton.setText(R.string.first_run_have_code)
        binding.openAppButton.visibility = View.GONE
    }

    /**
     * Step two of two: what to do about the family.
     *
     * Reached only from step one, and only for a phone with nothing behind it, so
     * the choice between starting a family and joining one is a real choice here.
     */
    private fun renderConnectionStep() {
        screen = Screen.CONNECTION
        binding.progressBar.visibility = View.GONE
        binding.titleText.setText(R.string.first_run_choice_label)
        binding.subtitleText.setText(R.string.first_run_choice_subtitle)
        binding.stepIndicatorText.visibility = View.VISIBLE
        showPersonStep(show = false)
        showFamilyStep(show = true)
        showOptionalContacts(show = false)
        applyAskOrJoinFields()

        binding.continueButton.visibility = View.VISIBLE
        binding.connectFamilyButton.visibility = View.GONE
        binding.configureLaterButton.visibility = View.VISIBLE
        binding.skipButton.visibility = View.VISIBLE
        binding.openAppButton.visibility = View.GONE
    }

    /** No answer from the server: claim nothing, and offer a way forward. */
    private fun renderOffline() {
        screen = Screen.OFFLINE
        binding.progressBar.visibility = View.GONE
        binding.titleText.setText(R.string.first_run_welcome_title)
        binding.subtitleText.setText(R.string.parent_setup_later_hint)

        binding.stateCard.visibility = View.VISIBLE
        binding.stateTitle.setText(R.string.first_run_offline_title)
        binding.stateBody.setText(R.string.first_run_offline_body)
        renderLinkedChildLines(null, childMayBeUnknown = false)

        binding.stepIndicatorText.visibility = View.GONE
        showPersonStep(show = false)
        showFamilyStep(show = false)
        showOptionalContacts(show = false)

        binding.continueButton.visibility = View.VISIBLE
        binding.continueButton.setText(R.string.first_run_retry_check)
        binding.connectFamilyButton.visibility = View.GONE
        binding.configureLaterButton.visibility = View.VISIBLE
        // No code entry here: nothing is known about this phone, so a code would
        // only be a second question on a screen that failed to answer the first.
        binding.skipButton.visibility = View.GONE
        binding.openAppButton.visibility = View.VISIBLE
    }

    /**
     * The line about the phone of the child.
     *
     * [childMayBeUnknown] keeps the sentence honest when only a family name is
     * known: the screen must not invent the name of a phone it never saw.
     */
    private fun renderLinkedChildLines(
        state: FamilyFirstRunState?,
        childMayBeUnknown: Boolean
    ) {
        val phones = state?.childPhones.orEmpty()
        if (phones.isEmpty()) {
            binding.stateLinkTitle.visibility = View.GONE
            binding.stateLinkBody.visibility = View.GONE
            return
        }
        binding.stateLinkTitle.visibility = View.VISIBLE
        binding.stateLinkBody.visibility = View.VISIBLE
        binding.stateLinkBody.text = when {
            phones.size > 1 -> getString(
                R.string.first_run_child_phones_many,
                phones.joinToString(", ") { it.displayName }
            )
            childMayBeUnknown -> getString(
                R.string.first_run_linked_child_body,
                phones.first().displayName
            )
            else -> getString(R.string.first_run_linked_child_known, phones.first().displayName)
        }
    }

    // ---------------------------------------------------------------- step fields

    private fun showPersonStep(show: Boolean) {
        binding.nameInputLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.avatarCard.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showFamilyStep(show: Boolean) {
        binding.choiceGroup.visibility = if (show) View.VISIBLE else View.GONE
        binding.familyNameInputLayout.visibility =
            if (show && !binding.joinFamilyRadio.isChecked) View.VISIBLE else View.GONE
        binding.invitationInputLayout.visibility =
            if (show && binding.joinFamilyRadio.isChecked) View.VISIBLE else View.GONE
    }

    private fun showOptionalContacts(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        binding.optionalContactsHeader.visibility = visibility
        binding.optionalContactsHint.visibility = visibility
        binding.emailInputLayout.visibility = visibility
        binding.phoneInputLayout.visibility = visibility
    }

    /** One question at a time: only the field the chosen way in needs. */
    private fun applyAskOrJoinFields() {
        val joining = binding.joinFamilyRadio.isChecked
        binding.familyNameInputLayout.visibility =
            if (joining) View.GONE else View.VISIBLE
        binding.invitationInputLayout.visibility =
            if (joining) View.VISIBLE else View.GONE
        binding.continueButton.setText(
            if (joining) R.string.first_run_join_action else R.string.first_run_create_action
        )
        binding.skipButton.setText(
            if (joining) R.string.parent_setup_no_invitation else R.string.first_run_have_code
        )
        binding.stepIndicatorText.text = getString(
            R.string.first_run_step_format,
            ParentFirstRunStep.CONNECTION.number,
            ParentFirstRunStep.CONNECTION.total
        )
    }

    // ---------------------------------------------------------------- actions

    private fun onPrimaryAction() {
        when (screen) {
            Screen.CHECKING, Screen.OFFLINE -> resolveWhatIsTrue()
            Screen.ALREADY_IN_FAMILY -> finishFromKnownFamily()
            Screen.OFFER_CONNECT -> connectPhoneToVisibleFamily()
            Screen.ASK_OR_JOIN -> goToConnectionStep()
            Screen.CONNECTION -> {
                if (binding.joinFamilyRadio.isChecked) {
                    val typed = binding.invitationInput.text?.toString().orEmpty().trim()
                    if (typed.isBlank()) {
                        showInvitationEntry()
                    } else {
                        acceptInvitationValue(typed)
                    }
                } else {
                    validateAndCreateFamily()
                }
            }
        }
    }

    /**
     * Carries the name from step one into step two.
     *
     * The name is checked here rather than at the end, so a mistake is caught on
     * the step that asked for it instead of after the family has been created.
     */
    private fun goToConnectionStep() {
        val name = binding.nameInput.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            binding.nameInputLayout.error = getString(R.string.first_run_name_invalid)
            binding.nameInput.requestFocus()
            return
        }
        binding.nameInputLayout.error = null
        renderConnectionStep()
    }

    /**
     * The phone is already in a family: finish setup and say nothing was needed.
     *
     * The background synchronisation still runs, but with the name the family
     * already holds and without permission to create anything, so it can only
     * record what is true.
     */
    private fun finishFromKnownFamily() {
        val state = knownState
        val person = state?.self
        val local = localParentRecord()
        val name = person?.displayName
            ?: local?.first
            ?: getString(R.string.parent_setup_default_name)
        val familyName = state?.familyName
            ?: getString(R.string.parent_setup_default_family)
        val avatar = person?.avatarKey
            ?: local?.second
            ?: selectedAvatarValue
        showLoading(true)
        lifecycleScope.launch {
            try {
                persistCompletedProfile(
                    OnboardingMemberData(
                        id = person?.memberId,
                        familyId = state?.familyId,
                        displayName = name,
                        role = "PARENT",
                        avatarKey = avatar
                    ),
                    "",
                    ""
                )
                getSharedPreferences("childwatch_prefs", MODE_PRIVATE).edit()
                    .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, name)
                    .apply()
                toast(getString(R.string.first_run_already_done))
                navigateToMain()
            } catch (error: Exception) {
                Log.w(TAG, "Could not save the confirmed family profile locally", error)
                toast(getString(R.string.first_run_server_incomplete))
            } finally {
                showLoading(false)
            }
        }
    }

    /** A new adult phone needs an invitation from an existing adult device. */
    private fun connectPhoneToVisibleFamily() {
        showInvitationEntry()
    }

    /** Which phone to pick up next, in the words of the person reading it. */
    private fun nextPhoneHint(): String {
        val state = knownState
        val child = state?.suggestedChildPhone()
        return if (child != null) {
            getString(R.string.first_run_next_phone_child, child.displayName)
        } else {
            getString(R.string.first_run_next_phone_parent)
        }
    }

    private fun validateAndCreateFamily() {
        val name = binding.nameInput.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            binding.nameInputLayout.error = getString(R.string.first_run_name_invalid)
            binding.nameInput.requestFocus()
            return
        }
        binding.nameInputLayout.error = null
        val familyName = binding.familyNameInput.text?.toString().orEmpty().trim()
            .ifEmpty { getString(R.string.parent_setup_default_family) }
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val phone = binding.phoneInput.text?.toString().orEmpty().trim()

        binding.emailInputLayout.error =
            if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                getString(R.string.first_run_email_invalid)
            } else null
        if (binding.emailInputLayout.error != null) {
            binding.emailInput.requestFocus()
            return
        }
        binding.phoneInputLayout.error =
            if (phone.isNotEmpty() && phone.length < 10) {
                getString(R.string.first_run_phone_invalid)
            } else null
        if (binding.phoneInputLayout.error != null) {
            binding.phoneInput.requestFocus()
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            try {
                check(networkClient.ensureOnboardingAuthentication()) {
                    getString(R.string.first_run_registration_failed)
                }
                val response = networkClient.bootstrapFamily(
                    FamilyBootstrapRequest(
                        familyName = familyName,
                        displayName = name,
                        role = "PARENT",
                        avatarKey = selectedAvatarValue.takeIf { it.startsWith("preset:") }
                    )
                )
                val result = response.body()
                val errorBody = if (response.isSuccessful) null else response.errorBody()?.string()
                val errorCode = runCatching { JSONObject(errorBody.orEmpty()).optString("code") }
                    .getOrNull()
                if (response.code() == 409 && errorCode == "DEVICE_ALREADY_ONBOARDED") {
                    toast(getString(R.string.first_run_already_done))
                    resolveWhatIsTrue()
                    return@launch
                }
                check(response.isSuccessful && result?.success == true) {
                    readServerError(errorBody)
                }
                persistCompletedProfile(result!!.member, email, phone)
                getSharedPreferences("childwatch_prefs", MODE_PRIVATE).edit()
                    .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, result.member.displayName)
                    .apply()
                toast(getString(R.string.first_run_connected))
                navigateToMain()
            } catch (error: Exception) {
                Log.w(TAG, "Family creation failed", error)
                toast(error.message ?: getString(R.string.first_run_server_incomplete))
                resolveWhatIsTrue()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun configureLater() {
        binding.nameInputLayout.error = null
        binding.emailInputLayout.error = null
        binding.phoneInputLayout.error = null
        leaveSetup(message = getString(R.string.first_run_later_done))
    }

    /**
     * Leaving setup is allowed and reversible.
     *
     * The screen is reached again from the settings, so a skipped setup is a
     * postponed one. Setup used to be a trap: the back key refused to work and a
     * person who skipped had to reinstall to find this screen again.
     */
    private fun leaveSetup(message: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val alreadyCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        if (!alreadyCompleted) {
            val name = binding.nameInput.text?.toString().orEmpty().trim()
                .ifEmpty {
                    localParentRecord()?.first
                        ?: getString(R.string.parent_setup_default_name)
                }
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
            getSharedPreferences("childwatch_prefs", MODE_PRIVATE).edit()
                .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, name)
                .apply()
            toast(message)
            // Local setup only: nothing is created and no family is joined until
            // the person asks for it, so a later retry cannot choose for them.
            deferredSetupScope.launch {
                runCatching {
                    persistCompletedProfile(
                        OnboardingMemberData(
                            id = null,
                            familyId = null,
                            displayName = name,
                            role = "PARENT",
                            avatarKey = selectedAvatarValue
                        ),
                        "",
                        ""
                    )
                }.onFailure { Log.e(TAG, "Deferred first-run profile failed", it) }
            }
        } else {
            toast(message)
        }
        navigateToMain()
    }

    private fun showInvitationEntry() {
        val input = EditText(this).apply {
            hint = getString(R.string.first_run_invitation_hint)
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_invitation_title)
            .setMessage(R.string.first_run_invitation_message)
            .setView(input)
            .setPositiveButton(R.string.first_run_invitation_continue) { _, _ ->
                acceptInvitationValue(input.text?.toString())
            }
            .setNeutralButton(R.string.first_run_invitation_scan) { _, _ ->
                qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
            }
            .setNegativeButton(R.string.first_run_invitation_cancel, null)
            .show()
    }

    private fun acceptInvitationValue(rawValue: String?) {
        val token = FamilyInvitationTokenParser.parse(rawValue)
        if (token == null) {
            toast(getString(R.string.first_run_invitation_not_ours))
            return
        }
        showLoading(true)
        lifecycleScope.launch {
            try {
                check(networkClient.ensureOnboardingAuthentication()) {
                    getString(R.string.first_run_registration_failed)
                }
                val preview = networkClient.previewFamilyInvitation(token)
                val invitation = preview.body()?.invitation
                check(preview.isSuccessful && invitation != null) {
                    readServerError(preview.errorBody()?.string())
                }
                check(!invitation.isExpired && !invitation.isConsumed && !invitation.isRevoked) {
                    getString(R.string.first_run_invitation_expired)
                }
                check(
                    FamilyOnboardingRolePolicy.accepts(
                        FamilyAppKind.PARENT_MONITOR,
                        invitation.member.role
                    )
                ) {
                    getString(R.string.first_run_invitation_child_app)
                }
                showLoading(false)
                val summary = if (invitation.mode == "EXISTING_MEMBER") {
                    getString(
                        R.string.first_run_invitation_existing_summary,
                        invitation.member.displayName,
                        invitation.invitedBy
                    )
                } else {
                    getString(
                        R.string.first_run_invitation_new_summary,
                        invitation.member.displayName,
                        invitation.invitedBy
                    )
                }
                MaterialAlertDialogBuilder(this@ParentSetupActivity)
                    .setTitle(invitation.family.name)
                    .setMessage(summary)
                    .setPositiveButton(R.string.first_run_invitation_join) { _, _ ->
                        completeInvitation(token)
                    }
                    .setNegativeButton(R.string.first_run_invitation_cancel, null)
                    .show()
            } catch (error: Exception) {
                showLoading(false)
                toast(error.message ?: getString(R.string.first_run_server_incomplete))
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
                getSharedPreferences("childwatch_prefs", MODE_PRIVATE).edit()
                    .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, result.member.displayName)
                    .apply()
                toast(getString(R.string.first_run_invitation_joined))
                navigateToMain()
            } catch (error: Exception) {
                showLoading(false)
                toast(error.message ?: getString(R.string.first_run_server_incomplete))
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

    private fun isCompleted(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

    /**
     * The name and picture this phone already holds for its person.
     *
     * Read on a click, so the short blocking read is deliberate: it is one row,
     * and the alternative is handing a coroutine a screen that is already gone.
     */
    private fun localParentRecord(): Pair<String, String?>? = runCatching {
        kotlinx.coroutines.runBlocking {
            database.parentDao().getAll().firstOrNull()
        }
    }.getOrNull()?.let { parent ->
        parent.name.trim().takeIf(String::isNotBlank)?.let { name ->
            name to parent.avatarUrl?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private fun readServerError(raw: String?): String {
        val code = runCatching { JSONObject(raw.orEmpty()).optString("code") }.getOrNull()
        return when (code) {
            "INVITATION_EXPIRED", "INVITATION_REVOKED", "INVITATION_ALREADY_USED" ->
                getString(R.string.first_run_invitation_expired)
            "INVITATION_NOT_FOUND" -> getString(R.string.first_run_invitation_not_ours)
            "DEVICE_ALREADY_ONBOARDED" -> getString(R.string.first_run_already_done)
            "DEVICE_IN_ANOTHER_FAMILY" -> getString(R.string.first_run_device_other_family)
            else -> getString(R.string.first_run_server_incomplete)
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.continueButton.isEnabled = !show
        binding.connectFamilyButton.isEnabled = !show
        binding.configureLaterButton.isEnabled = !show
        binding.skipButton.isEnabled = !show
        binding.openAppButton.isEnabled = !show
        binding.changeAvatarButton.isEnabled = !show
        binding.nameInput.isEnabled = !show
        binding.familyNameInput.isEnabled = !show
        binding.emailInput.isEnabled = !show
        binding.phoneInput.isEnabled = !show
        binding.invitationInput.isEnabled = !show
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ---------------------------------------------------------------- avatars

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
        FamilyAvatarRenderer.selectableValues().drop(views.size).forEach { value ->
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
                FamilyAvatarRenderer.bind(this, value)
                binding.parentAvatarPresetContainer.addView(this)
            }
            views += view
        }
        avatarPresetViews = views
        FamilyAvatarRenderer.selectableValues().zip(views).forEachIndexed { index, (value, view) ->
            FamilyAvatarRenderer.bind(view, value)
            view.contentDescription = getString(
                R.string.family_profile_avatar_preset_description,
                index + 1
            )
            view.setOnClickListener {
                selectedAvatarValue = value
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
        FamilyAvatarRenderer.selectableValues().zip(views).forEach { (value, view) ->
            val selected = value == selectedAvatarValue
            view.strokeColor = ColorStateList.valueOf(if (selected) primary else outline)
            view.strokeWidth = (if (selected) 3f else 1f) * resources.displayMetrics.density
            view.alpha = if (selected) 1f else 0.72f
            view.scaleX = if (selected) 1f else 0.92f
            view.scaleY = if (selected) 1f else 0.92f
        }
    }

    private fun showAvatarSourcePicker() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_avatar_source_title)
            .setItems(
                arrayOf(
                    getString(R.string.first_run_avatar_source_builtin),
                    getString(R.string.first_run_avatar_source_own)
                )
            ) { _, which ->
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
        val values = FamilyAvatarRenderer.selectableValues()
        val grid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            setPadding(margin, margin, margin, margin)
        }
        values.forEachIndexed { index, value ->
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
                strokeWidth = if (value == selectedAvatarValue) 3f * density else 1f * density
                strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@ParentSetupActivity,
                        if (value == selectedAvatarValue) R.color.cw_color_primary
                        else R.color.cw_color_outline_variant
                    )
                )
                FamilyAvatarRenderer.bind(this, value)
            }
            grid.addView(avatar)
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_avatar_picker_title)
            .setView(scroll)
            .setNegativeButton(R.string.first_run_avatar_picker_cancel, null)
            .create()
        for (index in 0 until grid.childCount) {
            grid.getChildAt(index).setOnClickListener {
                selectedAvatarValue = values[index]
                FamilyAvatarRenderer.bind(binding.avatarImage, selectedAvatarValue)
                refreshAvatarPresetSelection()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
