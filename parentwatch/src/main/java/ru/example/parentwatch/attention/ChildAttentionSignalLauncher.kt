package ru.example.parentwatch.attention

import android.app.Activity
import android.widget.Toast
import ru.childwatch.shared.attention.android.AttentionSignalSheet
import ru.childwatch.shared.attention.android.AttentionSignalTarget
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.session.ChildParticipantNameResolver

object ChildAttentionSignalLauncher {
    fun show(
        activity: Activity,
        explicitTargetDeviceId: String? = null,
        explicitTargetName: String? = null
    ) {
        val resolver = ChildEffectiveContextResolver(activity)
        val context = resolver.resolveEffectiveContext()
        val requesterDeviceId = resolver.resolveChildDeviceId().trim()
        val canonicalTarget = resolver.resolveParentDeviceId().trim()
        val targetDeviceId = explicitTargetDeviceId?.trim().orEmpty().ifBlank { canonicalTarget }
        val serverUrl = resolver.resolveServerUrl().trim()
        if (requesterDeviceId.isBlank() || targetDeviceId.isBlank() || serverUrl.isBlank()) {
            Toast.makeText(activity, "Сначала подключите профиль родителя", Toast.LENGTH_LONG).show()
            return
        }

        ChatBackgroundService.start(activity, serverUrl, requesterDeviceId)
        val names = ChildParticipantNameResolver(activity)
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
                    .ifBlank { names.resolveActiveParentDisplayName() },
                requesterMemberId = null,
                requesterDeviceId = requesterDeviceId,
                requesterDisplayName = names.resolveChildDisplayName()
            ),
            isTransportReady = WebSocketManager::isReady,
            sendRequest = WebSocketManager::sendAttentionRequest,
            sendStopRequest = WebSocketManager::sendAttentionStopRequest,
            addStatusListener = WebSocketManager::addAttentionStatusListener,
            removeStatusListener = WebSocketManager::removeAttentionStatusListener
        ).show()
    }
}
