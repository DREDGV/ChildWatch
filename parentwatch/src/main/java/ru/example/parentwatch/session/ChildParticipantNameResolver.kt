package ru.example.parentwatch.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import ru.example.parentwatch.contacts.ContactIcons
import ru.example.parentwatch.database.ParentWatchDatabase

/**
 * Resolves person-facing names independently from technical device ids.
 * Canonical family profiles are the primary source; legacy relationship
 * labels are retained only as an offline compatibility cache.
 */
class ChildParticipantNameResolver(context: Context) {

    companion object {
        private const val PREFS_NAME = "parentwatch_prefs"
        const val KEY_SELF_DISPLAY_NAME = "participant_self_display_name"
        const val KEY_SELF_MARKER_ICON_ID = "participant_self_marker_icon_id"
        private const val KEY_LINKED_PARENTS_JSON = "linked_parents_json"
        private const val KEY_ACTIVE_PARENT_LABEL = "active_parent_label"
        private const val KEY_CANONICAL_DIRECTORY_JSON = "canonical_family_directory_json"
        private const val KEY_CANONICAL_DIRECTORY_UPDATED_AT = "canonical_family_directory_updated_at"
        private const val CANONICAL_CACHE_TTL_MS = 5 * 60 * 1000L
        private val TECHNICAL_ID_PATTERN = Regex(
            pattern = "^(device|parent|child|member|family)[_-][A-Za-z0-9_.:-]+$",
            option = RegexOption.IGNORE_CASE
        )
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionStore = ChildActiveSessionStore(appContext)
    private val database by lazy { ParentWatchDatabase.getInstance(appContext) }

    fun resolveChildDisplayName(): String {
        val ownChildId = sessionStore.resolveCurrentChildId().trim()
        canonicalProfileForDevice(ownChildId)?.displayName
            ?.takeIf { isHumanDisplayName(it, ownChildId) }
            ?.let { return it }

        val explicitSelfName = prefs.getString(KEY_SELF_DISPLAY_NAME, null).orEmpty().trim()
        if (isHumanDisplayName(explicitSelfName, ownChildId)) return explicitSelfName

        if (ownChildId.isNotBlank()) {
            runBlocking(Dispatchers.IO) {
                database.childDao().getByDeviceId(ownChildId)
            }?.name?.trim()?.takeIf { isHumanDisplayName(it, ownChildId) }?.let { return it }
        }

        val currentName = sessionStore.getActiveSession()?.name?.trim().orEmpty()
        if (isHumanDisplayName(currentName, ownChildId) && currentName != "Текущий профиль") {
            return currentName
        }
        return "Ребёнок"
    }

    fun resolveActiveParentDisplayName(): String {
        val parentId = sessionStore.resolveCurrentParentId().trim()
        resolveParentDisplayName(parentId)?.let { return it }

        val cachedLabel = prefs.getString(KEY_ACTIVE_PARENT_LABEL, null).orEmpty().trim()
        if (isHumanDisplayName(cachedLabel, parentId)) return cachedLabel

        if (parentId.isNotBlank()) {
            val linkedParentsJson = prefs.getString(KEY_LINKED_PARENTS_JSON, null).orEmpty()
            if (linkedParentsJson.isNotBlank()) {
                runCatching {
                    val array = JSONArray(linkedParentsJson)
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        if (item.optString("parentDeviceId").trim() != parentId) continue
                        val candidates = listOf(
                            item.optString("parentDisplayName").trim(),
                            item.optString("displayName").trim()
                        )
                        candidates.firstOrNull { isHumanDisplayName(it, parentId) }
                            ?.let { return it }
                    }
                }
            }
        }
        return appContext.getString(ru.example.parentwatch.R.string.family_member_name_missing)
    }

    /** Refreshes the device-to-person directory without blocking UI callers. */
    suspend fun refreshCanonicalDirectory(force: Boolean = false): Boolean {
        val cachedAt = prefs.getLong(KEY_CANONICAL_DIRECTORY_UPDATED_AT, 0L)
        if (!force && cachedAt > 0L && System.currentTimeMillis() - cachedAt < CANONICAL_CACHE_TTL_MS) {
            if (readCanonicalProfiles().isNotEmpty()) return true
        }

        val directory = ChildFamilyDirectoryRepository(appContext).refresh() ?: return false
        val payload = JSONArray().apply {
            directory.people.forEach { person ->
                put(JSONObject().apply {
                    put("familyId", person.member.familyId)
                    put("memberId", person.member.id)
                    put("displayName", person.member.displayName)
                    put("role", person.member.role.name)
                    put("avatarKey", person.member.avatarKey ?: JSONObject.NULL)
                    put("deviceIds", JSONArray(person.activeDevices.map { it.deviceId }))
                })
            }
        }
        prefs.edit()
            .putString(KEY_CANONICAL_DIRECTORY_JSON, payload.toString())
            .putLong(KEY_CANONICAL_DIRECTORY_UPDATED_AT, System.currentTimeMillis())
            .apply()

        val activeParentId = sessionStore.resolveCurrentParentId().trim()
        directory.personByDeviceId(activeParentId)
            ?.member
            ?.displayName
            ?.takeIf { isHumanDisplayName(it, activeParentId) }
            ?.let { prefs.edit().putString(KEY_ACTIVE_PARENT_LABEL, it).apply() }
        return true
    }

    fun resolveParentDisplayName(
        parentDeviceId: String?,
        legacyCandidates: List<String?> = emptyList()
    ): String? {
        val normalizedDeviceId = parentDeviceId?.trim().orEmpty()
        canonicalProfileForDevice(normalizedDeviceId)
            ?.takeIf { it.role == "PARENT" || it.role == "GUARDIAN" }
            ?.displayName
            ?.takeIf { isHumanDisplayName(it, normalizedDeviceId) }
            ?.let { return it }

        return legacyCandidates.asSequence()
            .mapNotNull { it?.trim() }
            .firstOrNull { isHumanDisplayName(it, normalizedDeviceId) }
    }

    fun resolveParentAvatarKey(parentDeviceId: String?): String? =
        canonicalProfileForDevice(parentDeviceId)?.avatarKey

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
                if (ContactIcons.isKnown(iconId) && iconId != ContactIcons.DEFAULT) return iconId
            }
            null
        }.getOrNull()
    }

    private data class CachedCanonicalProfile(
        val memberId: String,
        val displayName: String,
        val role: String,
        val avatarKey: String?,
        val deviceIds: Set<String>
    )

    private fun canonicalProfileForDevice(deviceId: String?): CachedCanonicalProfile? {
        val normalized = deviceId?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return readCanonicalProfiles().firstOrNull { normalized in it.deviceIds }
    }

    private fun readCanonicalProfiles(): List<CachedCanonicalProfile> {
        val raw = prefs.getString(KEY_CANONICAL_DIRECTORY_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val memberId = item.optString("memberId").trim()
                    val devicesJson = item.optJSONArray("deviceIds") ?: continue
                    val deviceIds = buildSet {
                        for (deviceIndex in 0 until devicesJson.length()) {
                            devicesJson.optString(deviceIndex).trim()
                                .takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                    if (memberId.isBlank() || deviceIds.isEmpty()) continue
                    add(
                        CachedCanonicalProfile(
                            memberId = memberId,
                            displayName = item.optString("displayName").trim(),
                            role = item.optString("role").trim().uppercase(),
                            avatarKey = item.optString("avatarKey").trim()
                                .takeIf { it.isNotBlank() && it != "null" },
                            deviceIds = deviceIds
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun isHumanDisplayName(value: String, deviceId: String? = null): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank() || normalized.equals(deviceId?.trim(), ignoreCase = true)) return false
        return !TECHNICAL_ID_PATTERN.matches(normalized)
    }
}
