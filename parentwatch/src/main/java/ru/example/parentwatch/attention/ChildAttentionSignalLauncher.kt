package ru.example.parentwatch.attention

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.childwatch.shared.attention.android.AttentionSignalSheet
import ru.childwatch.shared.attention.android.AttentionSignalTarget
import ru.childwatch.shared.family.FamilyRole
import ru.example.parentwatch.R
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.session.ChildFamilyDirectoryRepository
import ru.example.parentwatch.session.ChildParticipantNameResolver

/**
 * Sends the attention signal from the child phone.
 *
 * The recipient used to be the one linked parent, so the sheet could only ever
 * offer a single name. The family directory knows every person and their phones,
 * so when no particular person was tapped the child chooses from the whole
 * family first.
 */
object ChildAttentionSignalLauncher {

    private const val TAG = "ChildAttentionSignal"

    /** One person the child may call, with the phone the signal must reach. */
    private data class SignalRecipient(
        val deviceId: String,
        val displayName: String,
        val role: FamilyRole?
    )

    fun show(
        activity: Activity,
        explicitTargetDeviceId: String? = null,
        explicitTargetName: String? = null
    ) {
        val resolver = ChildEffectiveContextResolver(activity)
        val requesterDeviceId = resolver.resolveChildDeviceId().trim()
        val serverUrl = resolver.resolveServerUrl().trim()
        if (requesterDeviceId.isBlank() || serverUrl.isBlank()) {
            Toast.makeText(activity, R.string.child_signal_profile_missing, Toast.LENGTH_LONG).show()
            return
        }

        // A caller who already knows the person — a tap on a particular name —
        // is obeyed as it comes.
        if (!explicitTargetDeviceId.isNullOrBlank()) {
            openSheet(activity, explicitTargetDeviceId, explicitTargetName.orEmpty())
            return
        }

        val recipients = familyRecipients(activity, requesterDeviceId)
        android.util.Log.d(
            TAG,
            "signal recipients: ${recipients.size} " +
                recipients.joinToString { "${it.displayName}@${it.deviceId}" }
        )
        when {
            recipients.isEmpty() -> openSheet(
                activity,
                resolver.resolveParentDeviceId().trim(),
                explicitTargetName.orEmpty()
            )

            recipients.size == 1 -> openSheet(
                activity,
                recipients.first().deviceId,
                recipients.first().displayName
            )

            else -> chooseRecipient(activity, recipients) { chosen ->
                openSheet(activity, chosen.deviceId, chosen.displayName)
            }
        }
    }

    /**
     * Everyone in the family except this phone, adults first.
     *
     * Read from the cached directory, which is a small preference file: the sheet
     * cannot open until the person has chosen, so a list that is a few seconds old
     * is a fair price for not blocking on the network. A person is only offered
     * when the directory knows a phone for them, because the signal is routed by
     * phone.
     */
    private fun familyRecipients(activity: Activity, ownDeviceId: String): List<SignalRecipient> {
        val directory = runCatching {
            ChildFamilyDirectoryRepository(activity).loadCached()
        }.getOrNull() ?: return emptyList()

        return directory.people
            .asSequence()
            .mapNotNull { person ->
                val device = person.primaryDevice() ?: return@mapNotNull null
                if (device.deviceId == ownDeviceId) return@mapNotNull null
                SignalRecipient(
                    deviceId = device.deviceId,
                    displayName = person.member.displayName.trim()
                        .ifBlank { activity.getString(R.string.family_member_name_missing) },
                    role = person.member.role
                )
            }
            .sortedWith(
                compareBy<SignalRecipient> { if (it.role == FamilyRole.CHILD) 1 else 0 }
                    .thenBy { it.displayName.lowercase() }
            )
            .toList()
    }

    private fun chooseRecipient(
        activity: Activity,
        recipients: List<SignalRecipient>,
        onChosen: (SignalRecipient) -> Unit
    ) {
        val names = recipients.map { it.displayName }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.child_signal_choose_recipient)
            .setItems(names) { _, index ->
                recipients.getOrNull(index)?.let(onChosen)
            }
            .setNegativeButton(R.string.child_signal_choose_cancel, null)
            .show()
    }

    private fun openSheet(activity: Activity, targetDeviceId: String, targetName: String) {
        val resolver = ChildEffectiveContextResolver(activity)
        val requesterDeviceId = resolver.resolveChildDeviceId().trim()
        val canonicalTarget = targetDeviceId.trim().ifBlank { resolver.resolveParentDeviceId().trim() }
        val serverUrl = resolver.resolveServerUrl().trim()
        if (requesterDeviceId.isBlank() || canonicalTarget.isBlank() || serverUrl.isBlank()) {
            Toast.makeText(activity, R.string.child_signal_profile_missing, Toast.LENGTH_LONG).show()
            return
        }

        ChatBackgroundService.start(activity, serverUrl, requesterDeviceId)
        val names = ChildParticipantNameResolver(activity)
        fun currentScope() = listOf(resolver.resolveServerUrl().trim(), resolver.resolveFamilyId().orEmpty().trim(),
            resolver.resolveSelfMemberId().orEmpty().trim(), resolver.resolveChildDeviceId().trim())
        val scope = currentScope()
        fun isCurrent() = !activity.isFinishing && !activity.isDestroyed && currentScope() == scope
        val network = ru.example.parentwatch.network.NetworkClient(activity, expectedAttentionScope = scope)
        AttentionSignalSheet(
            context = activity,
            target = AttentionSignalTarget(
                // Canonical actor context is frozen for both sending and status recovery.
                familyId = scope[1].takeIf(String::isNotBlank),
                targetMemberId = null,
                targetDeviceId = canonicalTarget,
                targetDisplayName = targetName.trim().ifBlank {
                    names.resolveParentDisplayName(canonicalTarget)
                        ?: names.resolveActiveParentDisplayName()
                },
                requesterMemberId = scope[2].takeIf(String::isNotBlank),
                requesterDeviceId = requesterDeviceId,
                requesterDisplayName = names.resolveChildDisplayName()
            ),
            isTransportReady = { isCurrent() && WebSocketManager.isReadyForServer(scope[0]) },
            sendRequest = { isCurrent() && WebSocketManager.isReadyForServer(scope[0]) && WebSocketManager.sendAttentionRequest(it) },
            sendStopRequest = { isCurrent() && WebSocketManager.isReadyForServer(scope[0]) && WebSocketManager.sendAttentionStopRequest(it) },
            addStatusListener = WebSocketManager::addAttentionStatusListener,
            removeStatusListener = WebSocketManager::removeAttentionStatusListener,
            isContextCurrent = ::isCurrent,
            recoverStatus = network::getAttentionSignalStatus,
            requestScopeKey = org.json.JSONArray(scope).toString()
        ).show()
    }
}
