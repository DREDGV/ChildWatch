package ru.example.childwatch.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.example.childwatch.utils.SecureSettingsManager

object ParentLegacyProfileMigration {

    fun buildFromLegacyState(context: Context): ParentActiveSession? {
        val secureSettings = SecureSettingsManager(context)
        val legacyPrefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedProfile = readActiveSavedProfile(context)

        val serverUrl = firstNotBlank(
            secureSettings.getServerUrl(),
            savedProfile?.serverUrl,
            legacyPrefs.getString("server_url", null),
            appPrefs.getString("server_url", null)
        )

        val ownParentId = firstNotBlank(
            secureSettings.getDeviceId(),
            savedProfile?.ownParentDeviceId,
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            appPrefs.getString("device_id", null),
            appPrefs.getString("parent_device_id", null)
        )

        val linkedChildId = firstNotBlank(
            secureSettings.getChildDeviceId(),
            savedProfile?.linkedChildDeviceId,
            legacyPrefs.getString("child_device_id", null),
            legacyPrefs.getString("selected_device_id", null),
            appPrefs.getString("child_device_id", null),
            appPrefs.getString("selected_device_id", null)
        )

        if (serverUrl.isBlank() && ownParentId.isBlank() && linkedChildId.isBlank()) {
            return null
        }

        val profileId = firstNotBlank(
            savedProfile?.profileId,
            legacyPrefs.getString("active_parent_profile_id", null),
            appPrefs.getString("active_parent_profile_id", null)
        ).ifBlank {
            ParentActiveSession.buildDerivedProfileId(serverUrl, ownParentId, linkedChildId)
        }

        return ParentActiveSession(
            profileId = profileId,
            profileName = savedProfile?.profileName?.takeIf(String::isNotBlank)
                ?: context.getString(ru.example.childwatch.R.string.profile_switch_current_name),
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentId,
            linkedChildDeviceId = linkedChildId,
            updatedAt = savedProfile?.updatedAt ?: System.currentTimeMillis()
        )
    }

    internal fun readActiveSavedProfile(context: Context): ParentActiveSession? {
        val prefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        val rawProfiles = prefs.getString("saved_parent_profiles_json", null).orEmpty()
        if (rawProfiles.isBlank()) return null

        val activeProfileId = prefs.getString("active_parent_profile_id", null)?.trim().orEmpty()
        return runCatching {
            val array = JSONArray(rawProfiles)
            val profiles = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    item.toLegacySession()?.let(::add)
                }
            }
            when {
                activeProfileId.isNotBlank() -> profiles.firstOrNull { it.profileId == activeProfileId }
                profiles.size == 1 -> profiles.single()
                else -> null
            }
        }.getOrNull()
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

    private fun JSONObject.toLegacySession(): ParentActiveSession? {
        val profileId = firstJsonString("id", "profileId", "profile_id")
        val profileName = firstJsonString("name", "profileName", "profile_name")
        val serverUrl = firstJsonString("serverUrl", "server_url")
        val ownParentDeviceId = firstJsonString(
            "ownParentDeviceId",
            "own_parent_device_id",
            "parentDeviceId",
            "parent_device_id"
        )
        val linkedChildDeviceId = firstJsonString(
            "linkedChildDeviceId",
            "linked_child_device_id",
            "childDeviceId",
            "child_device_id",
            "selected_device_id"
        )
        if (serverUrl.isBlank() && ownParentDeviceId.isBlank() && linkedChildDeviceId.isBlank()) {
            return null
        }
        return ParentActiveSession(
            profileId = profileId.ifBlank {
                ParentActiveSession.buildDerivedProfileId(serverUrl, ownParentDeviceId, linkedChildDeviceId)
            },
            profileName = profileName,
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentDeviceId,
            linkedChildDeviceId = linkedChildDeviceId,
            updatedAt = optLong("updatedAt", optLong("updated_at", 0L))
        )
    }

    private fun JSONObject.firstJsonString(vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key ->
            optString(key, "").trim().takeIf(String::isNotEmpty)
        }.orEmpty()
    }
}
