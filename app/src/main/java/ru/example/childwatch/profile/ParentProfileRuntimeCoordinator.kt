package ru.example.childwatch.profile

import android.content.Context
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.service.AudioPlaybackService
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.service.ParentLocationService
import ru.example.childwatch.utils.ParentMonitorProfile
import ru.example.childwatch.utils.ParentMonitorProfileManager

class ParentProfileRuntimeCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val sessionStore = ParentActiveSessionStore(appContext)
    private val profileManager = ParentMonitorProfileManager(appContext)
    private val effectiveContextResolver = ParentEffectiveContextResolver(appContext)
    private val networkClient = NetworkClient(appContext)

    fun applyProfile(
        profile: ParentMonitorProfile,
        shareParentLocation: Boolean
    ): ParentEffectiveContext {
        profileManager.applyProfile(profile)
        return refreshRuntime(shareParentLocation)
    }

    fun switchFocusedChild(
        childDeviceId: String,
        focusedMemberId: String? = null,
        familyId: String? = null,
        shareParentLocation: Boolean
    ): ParentEffectiveContext {
        val normalized = childDeviceId.trim()
        if (normalized.isNotBlank()) {
            sessionStore.updateFocusedChildId(normalized)
        }
        val effective = refreshRuntime(shareParentLocation)
        val contextProvider = ParentEffectiveContextProvider.get(appContext)
        contextProvider.updateSelection(
            focusedMemberId = focusedMemberId,
            targetDeviceId = normalized.takeIf(String::isNotBlank)
        )
        if (!familyId.isNullOrBlank()) {
            contextProvider.updateFamilyIdentity(
                familyId = familyId,
                selfMemberId = contextProvider.current()?.selfMemberId,
                focusedMemberId = focusedMemberId
            )
        }
        return effective
    }

    fun clearFocusedChild(shareParentLocation: Boolean): ParentEffectiveContext {
        sessionStore.updateFocusedChildId("")
        return refreshRuntime(shareParentLocation)
    }

    fun refreshRuntime(shareParentLocation: Boolean): ParentEffectiveContext {
        profileManager.reconcileCurrentState()
        val effectiveContext = effectiveContextResolver.resolve()

        if (effectiveContext.ownParentDeviceId.isNotBlank()) {
            networkClient.replaceDeviceIdentity(effectiveContext.ownParentDeviceId)
        }

        AudioPlaybackService.stopPlayback(appContext)
        WebSocketManager.cleanup()
        ChatBackgroundService.stop(appContext)
        if (effectiveContext.serverUrl.isNotBlank() && effectiveContext.linkedChildDeviceId.isNotBlank()) {
            ChatBackgroundService.start(
                appContext,
                effectiveContext.serverUrl,
                effectiveContext.linkedChildDeviceId
            )
        }

        ParentLocationService.stop(appContext)
        if (shareParentLocation && effectiveContext.serverUrl.isNotBlank() && effectiveContext.ownParentDeviceId.isNotBlank()) {
            ParentLocationService.start(appContext)
        }

        return effectiveContext
    }
}
