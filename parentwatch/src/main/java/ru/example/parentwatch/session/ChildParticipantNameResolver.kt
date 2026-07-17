package ru.example.parentwatch.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import ru.example.parentwatch.contacts.ContactIcons
import ru.example.parentwatch.database.ParentWatchDatabase

class ChildParticipantNameResolver(context: Context) {

    companion object {
        private const val PREFS_NAME = "parentwatch_prefs"
        const val KEY_SELF_DISPLAY_NAME = "participant_self_display_name"
        const val KEY_SELF_MARKER_ICON_ID = "participant_self_marker_icon_id"
        private const val KEY_LINKED_PARENTS_JSON = "linked_parents_json"
        private const val KEY_ACTIVE_PARENT_LABEL = "active_parent_label"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionStore = ChildActiveSessionStore(appContext)
    private val database by lazy { ParentWatchDatabase.getInstance(appContext) }

    fun resolveChildDisplayName(): String {
        val explicitSelfName = prefs.getString(KEY_SELF_DISPLAY_NAME, null).orEmpty().trim()
        if (explicitSelfName.isNotBlank()) return explicitSelfName

        val ownChildId = sessionStore.resolveCurrentChildId().trim()
        if (ownChildId.isNotBlank()) {
            runBlocking(Dispatchers.IO) {
                database.childDao().getByDeviceId(ownChildId)
            }?.name?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val currentName = sessionStore.getActiveSession()?.name?.trim().orEmpty()
        if (currentName.isNotBlank() && currentName != "Текущий профиль") {
            return currentName
        }

        return ownChildId.takeIf { it.isNotBlank() }?.let(::formatShortId)
            ?: "Ребенок"
    }

    fun resolveActiveParentDisplayName(): String {
        val cachedLabel = prefs.getString(KEY_ACTIVE_PARENT_LABEL, null).orEmpty().trim()
        if (cachedLabel.isNotBlank()) return cachedLabel

        val parentId = sessionStore.resolveCurrentParentId().trim()
        if (parentId.isBlank()) {
            return "Родитель"
        }

        val linkedParentsJson = prefs.getString(KEY_LINKED_PARENTS_JSON, null).orEmpty()
        if (linkedParentsJson.isNotBlank()) {
            runCatching {
                val array = JSONArray(linkedParentsJson)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    if (item.optString("parentDeviceId").trim() != parentId) continue
                    val explicitName = item.optString("parentDisplayName").trim()
                    if (explicitName.isNotBlank()) return explicitName
                    val displayName = item.optString("displayName").trim()
                    if (displayName.isNotBlank()) return displayName
                    val parentDeviceName = item.optString("parentDeviceName").trim()
                    if (parentDeviceName.isNotBlank()) return parentDeviceName
                }
            }
        }

        return formatShortId(parentId)
    }

    fun resolveChildMarkerIconId(): Int {
        val explicitIconId = prefs.getInt(KEY_SELF_MARKER_ICON_ID, ContactIcons.DEFAULT)
        if (ContactIcons.isKnown(explicitIconId) && explicitIconId != ContactIcons.DEFAULT) {
            return explicitIconId
        }

        val ownChildId = sessionStore.resolveCurrentChildId().trim()
        if (ownChildId.isNotBlank()) {
            runBlocking(Dispatchers.IO) {
                database.childDao().getByDeviceId(ownChildId)
            }?.iconId?.takeIf(ContactIcons::isKnown)?.let { return it }
        }

        return ContactIcons.CHILD
    }

    fun resolveLinkedParentMarkerIconId(parentId: String?): Int? {
        val normalizedParentId = parentId?.trim().orEmpty()
        if (normalizedParentId.isBlank()) return null

        val linkedParentsJson = prefs.getString(KEY_LINKED_PARENTS_JSON, null).orEmpty()
        if (linkedParentsJson.isBlank()) return null

        return runCatching {
            val array = JSONArray(linkedParentsJson)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("parentDeviceId").trim() != normalizedParentId) continue
                val iconId = item.optInt("parentMarkerIconId", ContactIcons.DEFAULT)
                if (ContactIcons.isKnown(iconId) && iconId != ContactIcons.DEFAULT) {
                    return iconId
                }
            }
            null
        }.getOrNull()
    }

    private fun formatShortId(rawId: String): String {
        return if (rawId.length <= 16) rawId else "${rawId.take(8)}...${rawId.takeLast(4)}"
    }
}
