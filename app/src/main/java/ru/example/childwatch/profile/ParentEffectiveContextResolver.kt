package ru.example.childwatch.profile

import android.content.Context
import ru.example.childwatch.utils.SecureSettingsManager

class ParentEffectiveContextResolver(context: Context) {

    private val context = context.applicationContext
    private val sessionStore = ParentActiveSessionStore(this.context)
    private val secureSettings = SecureSettingsManager(this.context)
    private val legacyPrefs = this.context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
    private val appPrefs = this.context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val provider = ParentEffectiveContextProvider.get(this.context)

    fun resolve(): ParentEffectiveContext {
        val activeSession = sessionStore.readPersistedSession()
        val canonical = provider.current()
        val serverUrl = canonical?.serverUrl ?: firstNotBlank(
            activeSession?.serverUrl,
            secureSettings.getServerUrl(),
            legacyPrefs.getString("server_url", null),
            appPrefs.getString("server_url", null)
        )
        val ownParentDeviceId = canonical?.selfDeviceId ?: firstNotBlank(
            activeSession?.ownParentDeviceId,
            secureSettings.getDeviceId(),
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            appPrefs.getString("device_id", null),
            appPrefs.getString("parent_device_id", null)
        )
        val linkedChildDeviceId = canonical?.targetDeviceId.orEmpty().ifBlank {
            firstNotBlank(
                activeSession?.linkedChildDeviceId,
                secureSettings.getChildDeviceId(),
                legacyPrefs.getString("child_device_id", null),
                legacyPrefs.getString("selected_device_id", null),
                appPrefs.getString("child_device_id", null),
                appPrefs.getString("selected_device_id", null)
            )
        }

        val source = canonical?.source?.name?.lowercase() ?: when {
            activeSession != null -> "active_session"
            serverUrl.isNotBlank() || ownParentDeviceId.isNotBlank() || linkedChildDeviceId.isNotBlank() -> "legacy_migration"
            else -> "empty"
        }

        return ParentEffectiveContext(
            activeSession = activeSession,
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentDeviceId,
            linkedChildDeviceId = linkedChildDeviceId,
            source = source
        )
    }

    fun resolveActiveSession(): ParentActiveSession? {
        return sessionStore.readPersistedSession() ?: sessionStore.getSession()
    }

    fun resolveActiveProfileId(): String {
        return resolveActiveSession()?.profileId.orEmpty()
    }

    fun resolveServerUrl(): String {
        return resolve().serverUrl
    }

    fun resolveOwnParentId(): String {
        return resolve().ownParentDeviceId
    }

    fun resolveFocusedChildId(): String {
        return provider.current()?.targetDeviceId.orEmpty().ifBlank { resolve().linkedChildDeviceId }
    }

    fun resolveTargetDeviceId(): String {
        return provider.current()?.targetDeviceId.orEmpty().ifBlank { resolveFocusedChildId() }
    }

    fun resolveFamilyId(): String? = provider.current()?.familyId

    fun resolveSelfMemberId(): String? = provider.current()?.selfMemberId

    fun resolveFocusedMemberId(): String? = provider.current()?.focusedMemberId

    fun resolveOwnParentCandidates(vararg preferred: String?): List<String> {
        val activeSession = resolveActiveSession()
        return buildList {
            addAll(preferred.asList())
            add(activeSession?.ownParentDeviceId)
            add(secureSettings.getDeviceId())
            add(legacyPrefs.getString("device_id", null))
            add(legacyPrefs.getString("parent_device_id", null))
            add(legacyPrefs.getString("linked_parent_device_id", null))
            add(appPrefs.getString("device_id", null))
            add(appPrefs.getString("parent_device_id", null))
            add(appPrefs.getString("linked_parent_device_id", null))
        }
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun resolveFocusedChildCandidates(vararg preferred: String?): List<String> {
        val activeSession = resolveActiveSession()
        val excluded = resolveOwnParentCandidates().toSet()
        return buildList {
            addAll(preferred.asList())
            add(activeSession?.linkedChildDeviceId)
            add(secureSettings.getChildDeviceId())
            add(legacyPrefs.getString("child_device_id", null))
            add(legacyPrefs.getString("selected_device_id", null))
            add(appPrefs.getString("child_device_id", null))
            add(appPrefs.getString("selected_device_id", null))
        }
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && it !in excluded }
            .distinct()
    }

    fun resolveTargetDeviceCandidates(vararg preferred: String?): List<String> {
        return resolveFocusedChildCandidates(*preferred)
    }

    fun resolveCurrentProfileName(): String {
        return resolveActiveSession()?.profileName.orEmpty()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }
}
