package ru.example.childwatch.profile

import android.content.Context
import ru.childwatch.shared.family.ActiveContext
import ru.childwatch.shared.family.ActiveContextCandidate
import ru.childwatch.shared.family.ActiveContextMigration
import ru.childwatch.shared.family.ContextSource
import ru.example.childwatch.utils.SecureSettingsManager

class ParentLegacyContextMigration(context: Context) {
    private val appContext = context.applicationContext
    private val store = ParentContextStore(appContext)
    private val sessionStore = ParentActiveSessionStore(appContext)
    private val secureSettings = SecureSettingsManager(appContext)
    private val legacyPrefs = appContext.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
    private val appPrefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun migrateIfNeeded(): ActiveContext? {
        return ActiveContextMigration(store, ::legacyCandidates).migrateIfNeeded()
    }

    internal fun legacyCandidates(): List<ActiveContextCandidate> {
        val active = sessionStore.readPersistedSession()
        return listOf(
            ActiveContextCandidate(
                selfDeviceId = active?.ownParentDeviceId,
                targetDeviceId = active?.linkedChildDeviceId,
                serverUrl = active?.serverUrl,
                source = ContextSource.ACTIVE_SESSION,
                updatedAt = active?.updatedAt ?: 0L
            ),
            ActiveContextCandidate(
                selfDeviceId = secureSettings.getDeviceId(),
                targetDeviceId = secureSettings.getChildDeviceId(),
                serverUrl = secureSettings.getServerUrl(),
                source = ContextSource.SECURE_SETTINGS
            ),
            ActiveContextCandidate(
                selfDeviceId = firstNotBlank(
                    legacyPrefs.getString("device_id", null),
                    legacyPrefs.getString("parent_device_id", null),
                    appPrefs.getString("device_id", null),
                    appPrefs.getString("parent_device_id", null)
                ),
                targetDeviceId = firstNotBlank(
                    legacyPrefs.getString("child_device_id", null),
                    legacyPrefs.getString("selected_device_id", null),
                    appPrefs.getString("child_device_id", null),
                    appPrefs.getString("selected_device_id", null)
                ),
                serverUrl = firstNotBlank(
                    legacyPrefs.getString("server_url", null),
                    appPrefs.getString("server_url", null)
                ),
                source = ContextSource.LEGACY_MIGRATION
            )
        )
    }

    private fun firstNotBlank(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    }
}
