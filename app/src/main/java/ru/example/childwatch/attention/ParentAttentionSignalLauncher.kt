package ru.example.childwatch.attention

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.childwatch.shared.attention.android.AttentionSignalSheet
import ru.childwatch.shared.attention.android.AttentionSignalTarget
import ru.childwatch.shared.family.FamilyRole
import ru.childwatch.shared.family.FeatureTargetResult
import ru.example.childwatch.R
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.profile.ParentFamilyDirectoryRepository
import ru.example.childwatch.profile.ParentParticipantNameResolver

/**
 * Sends the attention signal from an adult's phone.
 *
 * The recipient used to be whoever happened to be focused, so an adult could not
 * ring a particular person — a second parent was unreachable, and the child was
 * the only practical target. When nobody is named, the adult now chooses from the
 * family first, the same way the child's phone does.
 */
object ParentAttentionSignalLauncher {

    /** One person the signal can reach, with the phone it must ring. */
    private data class SignalCandidate(
        val deviceId: String,
        val memberId: String,
        val displayName: String,
        val avatarValue: String?,
        val role: FamilyRole
    )

    fun show(
        activity: Activity,
        explicitTargetDeviceId: String? = null,
        explicitTargetName: String? = null,
        explicitTargetAvatarValue: String? = null,
        explicitTargetMemberId: String? = null,
        explicitFamilyId: String? = null
    ) {
        // A caller who named somebody — a tap on a particular person — is obeyed.
        val named = !explicitTargetDeviceId.isNullOrBlank() || !explicitTargetMemberId.isNullOrBlank()
        val component = activity as? ComponentActivity
        if (named || component == null) {
            openSheet(
                activity,
                explicitTargetDeviceId,
                explicitTargetName,
                explicitTargetAvatarValue,
                explicitTargetMemberId,
                explicitFamilyId
            )
            return
        }

        component.lifecycleScope.launch {
            val candidates = loadCandidates(activity)
            val only = candidates.singleOrNull()
            when {
                candidates.size > 1 -> chooseCandidate(activity, candidates) { chosen ->
                    openSheet(
                        activity,
                        explicitTargetDeviceId = chosen.deviceId,
                        explicitTargetName = chosen.displayName,
                        explicitTargetAvatarValue = chosen.avatarValue,
                        explicitTargetMemberId = chosen.memberId,
                        explicitFamilyId = explicitFamilyId
                    )
                }

                else -> openSheet(
                    activity,
                    explicitTargetDeviceId = only?.deviceId,
                    explicitTargetName = only?.displayName,
                    explicitTargetAvatarValue = only?.avatarValue,
                    explicitTargetMemberId = only?.memberId,
                    explicitFamilyId = explicitFamilyId
                )
            }
        }
    }

    /**
     * Everyone in the family except this phone, adults first.
     *
     * The family directory is the only source that knows both the person and the
     * phone the signal has to ring. A person without a known phone is left out
     * rather than offered and then failing.
     */
    private suspend fun loadCandidates(activity: Activity): List<SignalCandidate> =
        withContext(Dispatchers.IO) {
            val directory = runCatching {
                ParentFamilyDirectoryRepository(activity).load()
            }.getOrNull()?.directory ?: return@withContext emptyList()

            directory.people
                .asSequence()
                .filter { it.member.id != directory.selfMemberId }
                .mapNotNull { person ->
                    val device = person.primaryDevice() ?: return@mapNotNull null
                    SignalCandidate(
                        deviceId = device.deviceId,
                        memberId = person.member.id,
                        displayName = person.member.displayName.trim()
                            .ifBlank { activity.getString(R.string.attention_signal_member_name_missing) },
                        avatarValue = person.member.avatarKey,
                        role = person.member.role
                    )
                }
                .sortedWith(
                    compareBy<SignalCandidate> { if (it.role == FamilyRole.CHILD) 1 else 0 }
                        .thenBy { it.displayName.lowercase() }
                )
                .toList()
        }

    private fun chooseCandidate(
        activity: Activity,
        candidates: List<SignalCandidate>,
        onChosen: (SignalCandidate) -> Unit
    ) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val adapter = object : BaseAdapter() {
            override fun getCount() = candidates.size
            override fun getItem(position: Int) = candidates[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val candidate = getItem(position)
                val avatar = ShapeableImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    shapeAppearanceModel = ShapeAppearanceModel.builder()
                        .setAllCornerSizes(RelativeCornerSize(0.5f)).build()
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                FamilyAvatarRenderer.bind(avatar, candidate.avatarValue, candidate.displayName)
                val roleText = activity.getString(when (candidate.role) {
                    FamilyRole.CHILD -> R.string.attention_signal_role_child
                    FamilyRole.PARENT -> R.string.attention_signal_role_parent
                    FamilyRole.GUARDIAN -> R.string.attention_signal_role_guardian
                })
                val details = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = candidate.displayName
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(activity).apply {
                        text = roleText
                        textSize = 13f
                    })
                }
                return LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(72)
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(16)
                    })
                    contentDescription = "${candidate.displayName}, $roleText"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    details.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.attention_signal_choose_recipient)
            .setAdapter(adapter) { _, index ->
                candidates.getOrNull(index)?.let(onChosen)
            }
            .setNegativeButton(R.string.attention_signal_choose_cancel, null)
            .show()
    }

    private fun openSheet(
        activity: Activity,
        explicitTargetDeviceId: String?,
        explicitTargetName: String?,
        explicitTargetAvatarValue: String?,
        explicitTargetMemberId: String?,
        explicitFamilyId: String?
    ) {
        val contextProvider = ParentEffectiveContextProvider.get(activity)
        val result = contextProvider.resolveFeatureTarget(
            feature = "attention-signal",
            explicitTargetDeviceId = explicitTargetDeviceId,
            explicitFocusedMemberId = explicitTargetMemberId
        )
        val resolved = result as? FeatureTargetResult.Resolved
        val context = resolved?.context
        val targetDeviceId = resolved?.targetDeviceId.orEmpty()
        val requesterDeviceId = context?.selfDeviceId.orEmpty().trim()

        if (context == null || targetDeviceId.isBlank() || requesterDeviceId.isBlank() || context.serverUrl.isBlank()) {
            Toast.makeText(
                activity,
                R.string.attention_signal_choose_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val names = ParentParticipantNameResolver(activity)
        AttentionSignalSheet(
            context = activity,
            target = AttentionSignalTarget(
                familyId = explicitFamilyId ?: context.familyId,
                targetMemberId = explicitTargetMemberId ?: context.focusedMemberId,
                targetDeviceId = targetDeviceId,
                targetDisplayName = explicitTargetName?.trim().orEmpty()
                    .ifBlank { names.resolveFocusedChildDisplayName(targetDeviceId) },
                requesterMemberId = context.selfMemberId,
                requesterDeviceId = requesterDeviceId,
                requesterDisplayName = names.resolveOwnParentDisplayName()
            ),
            isTransportReady = WebSocketManager::isReady,
            sendRequest = WebSocketManager::sendAttentionRequest,
            sendStopRequest = WebSocketManager::sendAttentionStopRequest,
            addStatusListener = WebSocketManager::addAttentionStatusListener,
            removeStatusListener = WebSocketManager::removeAttentionStatusListener,
            bindTargetAvatar = { view ->
                FamilyAvatarRenderer.bind(view, explicitTargetAvatarValue)
            }
        ).show()
    }
}
