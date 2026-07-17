package ru.example.parentwatch.session

import android.content.Context
import ru.childwatch.shared.family.ActiveContext
import ru.childwatch.shared.family.ActiveContextCandidate
import ru.childwatch.shared.family.ActiveContextMigration
import ru.childwatch.shared.family.ContextSource

class ChildLegacyContextMigration(context: Context) {
    private val appContext = context.applicationContext
    private val store = ChildContextStore(appContext)
    private val sessionStore = ChildActiveSessionStore(appContext)
    private val primaryPrefs = appContext.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)

    fun migrateIfNeeded(): ActiveContext? {
        return ActiveContextMigration(store, ::legacyCandidates).migrateIfNeeded()
    }

    internal fun legacyCandidates(): List<ActiveContextCandidate> {
        val active = sessionStore.getActiveSession()
        return listOf(
            ActiveContextCandidate(
                selfDeviceId = active?.ownChildDeviceId,
                targetDeviceId = active?.linkedParentDeviceId,
                serverUrl = active?.serverUrl,
                source = ContextSource.ACTIVE_SESSION,
                updatedAt = active?.updatedAt ?: 0L
            ),
            ActiveContextCandidate(
                selfDeviceId = firstNotBlank(
                    primaryPrefs.getString("child_device_id", null),
                    primaryPrefs.getString("device_id", null)
                ),
                targetDeviceId = firstNotBlank(
                    primaryPrefs.getString("selected_parent_device_id", null),
                    primaryPrefs.getString("parent_device_id", null),
                    primaryPrefs.getString("linked_parent_device_id", null)
                ),
                serverUrl = primaryPrefs.getString("server_url", null),
                source = ContextSource.SECURE_SETTINGS
            ),
            ActiveContextCandidate(
                selfDeviceId = firstNotBlank(
                    legacyPrefs.getString("child_device_id", null),
                    legacyPrefs.getString("device_id", null)
                ),
                targetDeviceId = firstNotBlank(
                    legacyPrefs.getString("selected_parent_device_id", null),
                    legacyPrefs.getString("parent_device_id", null),
                    legacyPrefs.getString("linked_parent_device_id", null)
                ),
                serverUrl = legacyPrefs.getString("server_url", null),
                source = ContextSource.LEGACY_MIGRATION
            )
        )
    }

    private fun firstNotBlank(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    }
}
