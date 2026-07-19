package ru.example.childwatch.attention

import android.app.Activity
import android.widget.Toast
import ru.childwatch.shared.attention.android.AttentionSignalSheet
import ru.childwatch.shared.attention.android.AttentionSignalTarget
import ru.childwatch.shared.family.FeatureTargetResult
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.profile.ParentParticipantNameResolver

object ParentAttentionSignalLauncher {
    fun show(
        activity: Activity,
        explicitTargetDeviceId: String? = null,
        explicitTargetName: String? = null,
        explicitTargetAvatarValue: String? = null,
        explicitTargetMemberId: String? = null,
        explicitFamilyId: String? = null
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
                "Сначала выберите участника и восстановите связь",
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
