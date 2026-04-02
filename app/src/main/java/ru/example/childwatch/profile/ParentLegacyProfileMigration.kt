package ru.example.childwatch.profile

import android.content.Context
import ru.example.childwatch.utils.SecureSettingsManager

object ParentLegacyProfileMigration {

    fun buildFromLegacyState(context: Context): ParentActiveSession? {
        val secureSettings = SecureSettingsManager(context)
        val legacyPrefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val serverUrl = firstNotBlank(
            secureSettings.getServerUrl(),
            legacyPrefs.getString("server_url", null),
            appPrefs.getString("server_url", null)
        )

        val ownParentId = firstNotBlank(
            secureSettings.getDeviceId(),
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            appPrefs.getString("device_id", null),
            appPrefs.getString("parent_device_id", null)
        )

        val linkedChildId = firstNotBlank(
            legacyPrefs.getString("child_device_id", null),
            legacyPrefs.getString("selected_device_id", null),
            appPrefs.getString("child_device_id", null),
            appPrefs.getString("selected_device_id", null),
            secureSettings.getChildDeviceId()
        )

        if (serverUrl.isBlank() && ownParentId.isBlank() && linkedChildId.isBlank()) {
            return null
        }

        val profileId = firstNotBlank(
            legacyPrefs.getString("active_parent_profile_id", null),
            appPrefs.getString("active_parent_profile_id", null)
        ).ifBlank {
            ParentActiveSession.buildDerivedProfileId(serverUrl, ownParentId, linkedChildId)
        }

        return ParentActiveSession(
            profileId = profileId,
            profileName = context.getString(ru.example.childwatch.R.string.profile_switch_current_name),
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentId,
            linkedChildDeviceId = linkedChildId
        )
    }

    fun migrateIfNeeded(context: Context, store: ParentActiveSessionStore): ParentActiveSession? {
        store.readPersistedSession()?.let { return it }
        val legacySession = buildFromLegacyState(context) ?: return null
        store.setSession(legacySession)
        return legacySession
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }
}
