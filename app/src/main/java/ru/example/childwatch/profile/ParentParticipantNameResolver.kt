package ru.example.childwatch.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.example.childwatch.R
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.utils.ParentMonitorProfileManager

class ParentParticipantNameResolver(context: Context) {

    companion object {
        private const val PREFS_NAME = "childwatch_prefs"
        private const val PARENT_ONBOARDING_PREFS = "parent_onboarding"
        private const val KEY_PARENT_ID = "parent_id"
        const val KEY_SELF_DISPLAY_NAME = "participant_self_display_name"
        const val KEY_SELF_MARKER_ICON_ID = "participant_self_marker_icon_id"
        private const val KEY_LINKED_PARENT_SELF_LABEL = "linked_parent_context_self_label"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val onboardingPrefs =
        appContext.getSharedPreferences(PARENT_ONBOARDING_PREFS, Context.MODE_PRIVATE)
    private val effectiveContextResolver = ParentEffectiveContextResolver(appContext)
    private val profileManager = ParentMonitorProfileManager(appContext)
    private val database by lazy { ChildWatchDatabase.getInstance(appContext) }

    fun resolveOwnParentDisplayName(): String {
        val explicitSelfName = prefs.getString(KEY_SELF_DISPLAY_NAME, null).orEmpty().trim()
        if (explicitSelfName.isNotBlank()) return explicitSelfName

        resolveLocalParentName()?.let { return it }

        val cached = prefs.getString(KEY_LINKED_PARENT_SELF_LABEL, null).orEmpty().trim()
        if (cached.isNotBlank()) return cached

        val ownParentId = effectiveContextResolver.resolveOwnParentId()
            .ifBlank { profileManager.resolveCurrentParentId() }
            .trim()

        return ownParentId.takeIf { it.isNotBlank() }?.let(::formatShortId)
            ?: "Родитель"
    }

    fun resolveFocusedChildDisplayName(childDeviceId: String? = null): String {
        val resolvedChildId = childDeviceId?.trim().orEmpty().ifBlank {
            effectiveContextResolver.resolveFocusedChildId()
                .ifBlank { profileManager.resolveCurrentChildId() }
                .trim()
        }

        if (resolvedChildId.isBlank()) {
            return appContext.getString(R.string.chat_partner_child)
        }

        resolveLocalChildName(resolvedChildId)?.let { return it }

        val serverUrl = effectiveContextResolver.resolveServerUrl()
            .ifBlank { profileManager.resolveCurrentServerUrl() }
        val ownParentId = effectiveContextResolver.resolveOwnParentId()
            .ifBlank { profileManager.resolveCurrentParentId() }
        val savedDisplayName = profileManager.resolveLinkedChildDisplayName(
            childDeviceId = resolvedChildId,
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentId
        ).trim()

        return savedDisplayName.ifBlank { formatShortId(resolvedChildId) }
    }

    fun resolveOwnParentMarkerIconId(): Int {
        val stored = prefs.getInt(KEY_SELF_MARKER_ICON_ID, ContactIcons.PARENT)
        return stored.takeIf(ContactIcons::isKnown) ?: ContactIcons.PARENT
    }

    private fun formatShortId(rawId: String): String {
        return if (rawId.length <= 16) rawId else "${rawId.take(8)}...${rawId.takeLast(4)}"
    }

    private fun resolveLocalParentName(): String? {
        val storedParentId = onboardingPrefs.getLong(KEY_PARENT_ID, 0L)
        if (storedParentId > 0) {
            runBlocking(Dispatchers.IO) {
                database.parentDao().getById(storedParentId)
            }?.name?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return runBlocking(Dispatchers.IO) {
            database.parentDao().getAll().firstOrNull()
        }?.name?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun resolveLocalChildName(childDeviceId: String): String? {
        return runBlocking(Dispatchers.IO) {
            database.childDao().getByDeviceId(childDeviceId)
        }?.name?.trim()?.takeIf { it.isNotBlank() }
    }
}
