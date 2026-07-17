package ru.example.childwatch.profile

import android.content.Context
import org.json.JSONObject
import ru.example.childwatch.utils.SecureSettingsManager

class ParentActiveSessionStore(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "childwatch_active_session"
    private const val KEY_SESSION_JSON = "parent_active_session_json"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
    private val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val secureSettings = SecureSettingsManager(context)

    fun getSession(): ParentActiveSession? {
        readPersistedSession()?.let { return it }
        val migrated = ParentLegacyProfileMigration.buildFromLegacyState(context)
        if (migrated != null) {
            setSession(migrated)
        }
        return migrated
    }

    fun readPersistedSession(): ParentActiveSession? {
        val raw = prefs.getString(KEY_SESSION_JSON, null).orEmpty()
        if (raw.isBlank()) return null

        return runCatching {
            ParentActiveSession.fromJson(JSONObject(raw))?.let { normalizeSession(it) }
        }.getOrNull()
    }

    fun setSession(session: ParentActiveSession) {
        val normalized = normalizeSession(session)
        prefs.edit().putString(KEY_SESSION_JSON, normalized.toJson().toString()).apply()
        mirrorLegacyPrefs(normalized)
        ParentEffectiveContextProvider.get(context).updateFromActiveSession(normalized)
    }

    fun updateFocusedChildId(linkedChildDeviceId: String): ParentActiveSession? {
        val normalizedChildId = linkedChildDeviceId.trim()
        val current = getSession() ?: buildBootstrapSession(normalizedChildId)
        val updated = current.copy(
            linkedChildDeviceId = normalizedChildId,
            profileId = ParentActiveSession.buildDerivedProfileId(
                current.serverUrl,
                current.ownParentDeviceId,
                normalizedChildId
            ),
            updatedAt = System.currentTimeMillis()
        )
        setSession(updated)
        ParentEffectiveContextProvider.get(context).updateSelection(
            focusedMemberId = null,
            targetDeviceId = normalizedChildId
        )
        return updated
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION_JSON).apply()
    }

    private fun buildBootstrapSession(linkedChildDeviceId: String): ParentActiveSession {
        val serverUrl = firstNotBlank(
            secureSettings.getServerUrl(),
            legacyPrefs.getString("server_url", null),
            appPrefs.getString("server_url", null)
        )
        val ownParentDeviceId = firstNotBlank(
            secureSettings.getDeviceId(),
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            appPrefs.getString("device_id", null),
            appPrefs.getString("parent_device_id", null)
        )
        return ParentActiveSession(
            profileId = ParentActiveSession.buildDerivedProfileId(
                serverUrl,
                ownParentDeviceId,
                linkedChildDeviceId
            ),
            profileName = context.getString(ru.example.childwatch.R.string.profile_switch_current_name),
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentDeviceId,
            linkedChildDeviceId = linkedChildDeviceId,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun normalizeSession(session: ParentActiveSession): ParentActiveSession {
        val profileId = session.profileId.trim().ifBlank {
            ParentActiveSession.buildDerivedProfileId(
                session.serverUrl,
                session.ownParentDeviceId,
                session.linkedChildDeviceId
            )
        }
        val profileName = session.profileName.trim().ifBlank {
            context.getString(ru.example.childwatch.R.string.profile_switch_current_name)
        }

        return session.copy(
            profileId = profileId,
            profileName = profileName,
            serverUrl = session.serverUrl.trim(),
            ownParentDeviceId = session.ownParentDeviceId.trim(),
            linkedChildDeviceId = session.linkedChildDeviceId.trim()
        )
    }

    private fun mirrorLegacyPrefs(session: ParentActiveSession) {
        legacyPrefs.edit()
            .putString("server_url", session.serverUrl)
            .putString("device_id", session.ownParentDeviceId)
            .putString("parent_device_id", session.ownParentDeviceId)
            .apply {
                if (session.linkedChildDeviceId.isBlank()) {
                    remove("child_device_id")
                    remove("selected_device_id")
                } else {
                    putString("child_device_id", session.linkedChildDeviceId)
                    putString("selected_device_id", session.linkedChildDeviceId)
                }
                putString("active_parent_profile_id", session.profileId)
            }
            .apply()

        secureSettings.setServerUrl(session.serverUrl)
        secureSettings.setDeviceId(session.ownParentDeviceId)
        if (session.linkedChildDeviceId.isBlank()) {
            secureSettings.clearChildDeviceId()
        } else {
            secureSettings.setChildDeviceId(session.linkedChildDeviceId)
        }
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }
}
