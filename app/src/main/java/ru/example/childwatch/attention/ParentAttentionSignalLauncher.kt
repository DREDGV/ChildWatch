package ru.example.childwatch.attention

import android.app.Activity
import android.widget.Toast
import ru.childwatch.shared.attention.android.AttentionSignalSheet
import ru.childwatch.shared.attention.android.AttentionSignalTarget
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.service.ChatBackgroundService

object ParentAttentionSignalLauncher {
    fun show(
        activity: Activity,
        explicitTargetDeviceId: String? = null,
        explicitTargetName: String? = null
    ) {
        val resolver = ParentEffectiveContextResolver(activity)
        val context = resolver.resolve()
        val targetDeviceId = explicitTargetDeviceId?.trim().orEmpty()
            .ifBlank { resolver.resolveTargetDeviceId() }
        val requesterDeviceId = context.ownParentDeviceId.trim()
        if (targetDeviceId.isBlank() || requesterDeviceId.isBlank() || context.serverUrl.isBlank()) {
            Toast.makeText(activity, "Сначала выберите участника и восстановите связь", Toast.LENGTH_LONG).show()
            return
        }

        ParentActiveSessionStore(activity).updateFocusedChildId(targetDeviceId)
        ChatBackgroundService.start(activity, context.serverUrl, targetDeviceId)
        val names = ParentParticipantNameResolver(activity)
        AttentionSignalSheet(
            context = activity,
            target = AttentionSignalTarget(
                // Local profile identifiers predate the server family model and are
                // not guaranteed to match its canonical IDs. The authenticated
                // device pair is authoritative; the server resolves and verifies
                // the family/member context before routing the signal.
                familyId = null,
                targetMemberId = null,
                targetDeviceId = targetDeviceId,
                targetDisplayName = explicitTargetName?.trim().orEmpty()
                    .ifBlank { names.resolveFocusedChildDisplayName(targetDeviceId) },
                requesterMemberId = null,
                requesterDeviceId = requesterDeviceId,
                requesterDisplayName = names.resolveOwnParentDisplayName()
            ),
            isTransportReady = WebSocketManager::isReady,
            sendRequest = WebSocketManager::sendAttentionRequest,
            sendStopRequest = WebSocketManager::sendAttentionStopRequest,
            addStatusListener = WebSocketManager::addAttentionStatusListener,
            removeStatusListener = WebSocketManager::removeAttentionStatusListener
        ).show()
    }
}
