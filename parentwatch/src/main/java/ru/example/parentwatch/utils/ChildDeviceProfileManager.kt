package ru.example.parentwatch.utils

import android.content.Context
import ru.example.parentwatch.session.ChildActiveSession
import ru.example.parentwatch.session.ChildActiveSessionStore
import java.util.UUID

data class ChildDeviceProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val ownChildDeviceId: String,
    val linkedParentDeviceId: String,
    val updatedAt: Long = System.currentTimeMillis()
)

class ChildDeviceProfileManager(private val context: Context) {

    private val sessionStore = ChildActiveSessionStore(context)

    fun getSavedProfiles(): List<ChildDeviceProfile> {
        return sessionStore.getSavedSessions().map { it.toProfile() }
    }

    fun getProfiles(): List<ChildDeviceProfile> {
        reconcileCurrentState()
        return sessionStore.getSessions().map { it.toProfile() }
    }

    fun getActiveProfileId(): String? = sessionStore.getActiveSessionId()

    fun getActiveProfile(): ChildDeviceProfile? {
        reconcileCurrentState()
        return sessionStore.getActiveSession()?.toProfile()
    }

    fun reconcileCurrentState(): ChildDeviceProfile? {
        val effectiveContext = sessionStore.resolveEffectiveContext() ?: return null
        val normalizedSession = ChildActiveSession(
            id = effectiveContext.activeSessionId?.takeIf { it.isNotBlank() }
                ?: sessionStore.buildSession(
                    name = context.getString(ru.example.parentwatch.R.string.profile_switch_current_name),
                    serverUrl = effectiveContext.serverUrl,
                    ownChildDeviceId = effectiveContext.ownChildDeviceId,
                    linkedParentDeviceId = effectiveContext.linkedParentDeviceId
                ).id,
            name = sessionStore.getActiveSession()?.name?.takeIf { it.isNotBlank() }
                ?: context.getString(ru.example.parentwatch.R.string.profile_switch_current_name),
            serverUrl = effectiveContext.serverUrl,
            ownChildDeviceId = effectiveContext.ownChildDeviceId,
            linkedParentDeviceId = effectiveContext.linkedParentDeviceId,
            updatedAt = System.currentTimeMillis()
        )
        sessionStore.syncRuntimeIdentity(normalizedSession)
        return normalizedSession.toProfile()
    }

    fun saveProfile(profile: ChildDeviceProfile) {
        sessionStore.saveSession(profile.toSession())
    }

    fun applyProfile(profile: ChildDeviceProfile) {
        sessionStore.applySession(profile.toSession())
    }

    fun deleteProfile(profileId: String) {
        sessionStore.removeSession(profileId)
    }

    fun buildProfile(
        name: String,
        serverUrl: String,
        ownChildDeviceId: String,
        linkedParentDeviceId: String
    ): ChildDeviceProfile {
        return sessionStore.buildSession(
            name = name,
            serverUrl = serverUrl,
            ownChildDeviceId = ownChildDeviceId,
            linkedParentDeviceId = linkedParentDeviceId
        ).toProfile()
    }

    fun resolveCurrentServerUrl(): String = sessionStore.resolveCurrentServerUrl()

    fun resolveCurrentChildId(): String = sessionStore.resolveCurrentChildId()

    fun resolveCurrentParentId(): String = sessionStore.resolveCurrentParentId()

    private fun ChildActiveSession.toProfile(): ChildDeviceProfile {
        return ChildDeviceProfile(
            id = id,
            name = name,
            serverUrl = serverUrl,
            ownChildDeviceId = ownChildDeviceId,
            linkedParentDeviceId = linkedParentDeviceId,
            updatedAt = updatedAt
        )
    }

    private fun ChildDeviceProfile.toSession(): ChildActiveSession {
        return ChildActiveSession(
            id = id,
            name = name,
            serverUrl = serverUrl,
            ownChildDeviceId = ownChildDeviceId,
            linkedParentDeviceId = linkedParentDeviceId,
            updatedAt = updatedAt
        )
    }
}
