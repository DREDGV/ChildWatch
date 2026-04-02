package ru.example.childwatch.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.example.childwatch.R
import ru.example.childwatch.profile.ParentActiveSession
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ParentMonitorProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val ownParentDeviceId: String,
    val linkedChildDeviceId: String,
    val linkedChildDisplayName: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

class ParentMonitorProfileManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "childwatch_prefs"
        private const val KEY_PROFILES_JSON = "saved_parent_profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_parent_profile_id"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureSettings = SecureSettingsManager(context)
    private val sessionStore = ParentActiveSessionStore(context)
    private val effectiveContextResolver = ParentEffectiveContextResolver(context)

    fun getSavedProfiles(): List<ParentMonitorProfile> {
        return loadSavedProfiles().sortedBy { it.name.lowercase() }
    }

    fun getProfiles(): List<ParentMonitorProfile> {
        val savedProfiles = getSavedProfiles()
        val currentProfile = buildCurrentProfileCandidate()
        if (currentProfile == null) return savedProfiles

        val alreadySaved = savedProfiles.any { sameIdentity(it, currentProfile) }
        if (alreadySaved) return savedProfiles

        return (savedProfiles + currentProfile).sortedBy { it.name.lowercase() }
    }

    fun getActiveProfileId(): String? {
        return sessionStore.getSession()?.profileId?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
    }

    fun getActiveProfile(): ParentMonitorProfile? {
        val profiles = getProfiles()
        if (profiles.isEmpty()) return null

        val activeId = getActiveProfileId().orEmpty()
        if (activeId.isNotBlank()) {
            profiles.firstOrNull { it.id == activeId }?.let { return it }
        }

        return buildCurrentProfileCandidate() ?: profiles.singleOrNull()
    }

    fun reconcileCurrentState(): ParentMonitorProfile? {
        val currentProfile = buildCurrentProfileCandidate() ?: return null
        val savedProfiles = getSavedProfiles()
        val existing = savedProfiles.firstOrNull {
            it.id == currentProfile.id || sameIdentity(it, currentProfile)
        }
        val normalizedProfile = currentProfile.copy(
            id = existing?.id ?: currentProfile.id,
            name = existing?.name?.takeIf { it.isNotBlank() } ?: currentProfile.name,
            updatedAt = System.currentTimeMillis()
        )

        val activeProfileId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (activeProfileId != normalizedProfile.id) {
            setActiveProfile(normalizedProfile.id)
        }

        val currentSession = sessionStore.getSession()
        val normalizedSession = normalizedProfile.toActiveSession().copy(
            updatedAt = currentSession?.updatedAt ?: normalizedProfile.updatedAt
        )
        if (currentSession != normalizedSession) {
            sessionStore.setSession(normalizedSession)
        }

        return normalizedProfile
    }

    fun saveProfile(profile: ParentMonitorProfile) {
        val profiles = loadSavedProfiles().toMutableList()
        val existingIndex = profiles.indexOfFirst {
            it.id == profile.id ||
                it.name.equals(profile.name, ignoreCase = true) ||
                sameIdentity(it, profile)
        }

        if (existingIndex >= 0) {
            profiles[existingIndex] = profile.copy(id = profiles[existingIndex].id, updatedAt = System.currentTimeMillis())
        } else {
            profiles.add(profile.copy(updatedAt = System.currentTimeMillis()))
        }

        persistProfiles(profiles)
        setActiveProfile(
            profiles.firstOrNull {
                it.id == profile.id ||
                    it.name.equals(profile.name, ignoreCase = true) ||
                    sameIdentity(it, profile)
            }?.id
        )
    }

    fun applyProfile(profile: ParentMonitorProfile) {
        sessionStore.setSession(profile.toActiveSession())
        setActiveProfile(profile.id)
    }

    fun deleteProfile(profileId: String) {
        val profiles = loadSavedProfiles().filterNot { it.id == profileId }
        persistProfiles(profiles)
        if (getActiveProfileId() == profileId) {
            setActiveProfile(null)
        }
    }

    fun buildProfile(
        name: String,
        serverUrl: String,
        ownParentDeviceId: String,
        linkedChildDeviceId: String,
        linkedChildDisplayName: String = ""
    ): ParentMonitorProfile {
        val normalizedName = name.trim()
        val normalizedServer = serverUrl.trim()
        val normalizedOwnId = ownParentDeviceId.trim()
        val normalizedChildId = linkedChildDeviceId.trim()
        val normalizedChildName = linkedChildDisplayName.trim()
        val existingProfiles = getSavedProfiles() + listOfNotNull(buildCurrentProfileCandidate())
        return ParentMonitorProfile(
            id = existingProfiles.firstOrNull {
                it.name.equals(normalizedName, ignoreCase = true) ||
                    (it.serverUrl.equals(normalizedServer, ignoreCase = true) &&
                        it.ownParentDeviceId == normalizedOwnId &&
                        it.linkedChildDeviceId == normalizedChildId)
            }?.id ?: UUID.randomUUID().toString(),
            name = normalizedName,
            serverUrl = normalizedServer,
            ownParentDeviceId = normalizedOwnId,
            linkedChildDeviceId = normalizedChildId,
            linkedChildDisplayName = normalizedChildName,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun buildSuggestedProfileName(
        linkedChildDisplayName: String,
        linkedChildDeviceId: String
    ): String {
        val childLabel = linkedChildDisplayName.trim().ifBlank {
            linkedChildDeviceId.trim().ifBlank {
                context.getString(R.string.profile_switch_no_link_short)
            }
        }
        return context.getString(R.string.profile_switch_child_profile_name_format, childLabel)
    }

    fun resolveLinkedChildDisplayName(
        childDeviceId: String,
        serverUrl: String? = null,
        ownParentDeviceId: String? = null
    ): String {
        val normalizedChildId = childDeviceId.trim()
        if (normalizedChildId.isBlank()) return ""

        val normalizedServerUrl = serverUrl?.trim().orEmpty()
        val normalizedOwnParentId = ownParentDeviceId?.trim().orEmpty()
        val identityMatch = getSavedProfiles().firstOrNull {
            it.linkedChildDeviceId == normalizedChildId &&
                (normalizedServerUrl.isBlank() || it.serverUrl.equals(normalizedServerUrl, ignoreCase = true)) &&
                (normalizedOwnParentId.isBlank() || it.ownParentDeviceId == normalizedOwnParentId)
        }
        return identityMatch?.linkedChildDisplayName?.trim().orEmpty()
    }

    fun syncLinkedChildProfiles(
        linkedChildren: List<ru.example.childwatch.profile.ParentLinkedChildOption>
    ): Int {
        val normalizedOptions = linkedChildren
            .filter { it.source == "linked" }
            .mapNotNull { option ->
                val childId = option.deviceId.trim()
                if (childId.isBlank()) return@mapNotNull null
                option.copy(
                    deviceId = childId,
                    displayName = option.displayName.trim().ifBlank { childId }
                )
            }
            .distinctBy { it.deviceId }

        if (normalizedOptions.isEmpty()) return 0

        val serverUrl = effectiveContextResolver.resolveServerUrl().ifBlank { resolveCurrentServerUrl() }
        val ownParentId = effectiveContextResolver.resolveOwnParentId().ifBlank { resolveCurrentParentId() }
        if (serverUrl.isBlank() || ownParentId.isBlank()) return 0

        val profiles = loadSavedProfiles().toMutableList()
        var updatedProfiles = 0

        normalizedOptions.forEach { option ->
            val profileIndex = profiles.indexOfFirst {
                it.serverUrl.equals(serverUrl, ignoreCase = true) &&
                    it.ownParentDeviceId == ownParentId &&
                    it.linkedChildDeviceId == option.deviceId
            }

            if (profileIndex >= 0) {
                val existing = profiles[profileIndex]
                val updated = existing.copy(
                    name = if (shouldRefreshGeneratedProfileName(existing, option.displayName)) {
                        buildSuggestedProfileName(option.displayName, option.deviceId)
                    } else {
                        existing.name
                    },
                    linkedChildDisplayName = option.displayName,
                    updatedAt = if (
                        existing.linkedChildDisplayName != option.displayName ||
                        shouldRefreshGeneratedProfileName(existing, option.displayName)
                    ) {
                        System.currentTimeMillis()
                    } else {
                        existing.updatedAt
                    }
                )
                if (updated != existing) {
                    profiles[profileIndex] = updated
                    updatedProfiles += 1
                }
                return@forEach
            }

            profiles += ParentMonitorProfile(
                name = buildSuggestedProfileName(option.displayName, option.deviceId),
                serverUrl = serverUrl,
                ownParentDeviceId = ownParentId,
                linkedChildDeviceId = option.deviceId,
                linkedChildDisplayName = option.displayName,
                updatedAt = System.currentTimeMillis()
            )
            updatedProfiles += 1
        }

        if (updatedProfiles > 0) {
            persistProfiles(profiles)
        }

        return updatedProfiles
    }

    fun resolveCurrentServerUrl(): String {
        val sessionServer = effectiveContextResolver.resolveServerUrl()
        if (sessionServer.isNotBlank()) return sessionServer
        val fromSecure = secureSettings.getServerUrl().trim()
        if (fromSecure.isNotBlank()) return fromSecure
        return prefs.getString("server_url", null)?.trim().orEmpty()
    }

    fun resolveCurrentParentId(): String {
        val sessionParent = effectiveContextResolver.resolveOwnParentId()
        if (sessionParent.isNotBlank()) return sessionParent
        return listOf(
            secureSettings.getDeviceId(),
            prefs.getString("device_id", null),
            prefs.getString("parent_device_id", null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    fun resolveCurrentChildId(): String {
        val sessionChild = effectiveContextResolver.resolveFocusedChildId()
        if (sessionChild.isNotBlank()) return sessionChild
        getSavedProfiles().firstOrNull { it.id == getActiveProfileId() }
            ?.linkedChildDeviceId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return listOf(
            secureSettings.getChildDeviceId(),
            prefs.getString("child_device_id", null),
            prefs.getString("selected_device_id", null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun loadSavedProfiles(): List<ParentMonitorProfile> {
        val raw = prefs.getString(KEY_PROFILES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(item.toProfile())
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun buildCurrentProfileCandidate(): ParentMonitorProfile? {
        val effectiveContext = effectiveContextResolver.resolve()
        val serverUrl = effectiveContext.serverUrl
        val ownParentId = effectiveContext.ownParentDeviceId
        if (serverUrl.isBlank() || ownParentId.isBlank()) return null

        val linkedChildId = effectiveContext.linkedChildDeviceId
        val session = effectiveContext.activeSession
        val savedChildName = getSavedProfiles().firstOrNull {
            it.serverUrl.equals(serverUrl, ignoreCase = true) &&
                it.ownParentDeviceId == ownParentId &&
                it.linkedChildDeviceId == linkedChildId
        }?.linkedChildDisplayName.orEmpty()
        return ParentMonitorProfile(
            id = session?.profileId?.takeIf { it.isNotBlank() }
                ?: buildDerivedProfileId(serverUrl, ownParentId, linkedChildId),
            name = session?.profileName?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.profile_switch_current_name),
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentId,
            linkedChildDeviceId = linkedChildId,
            linkedChildDisplayName = savedChildName,
            updatedAt = session?.updatedAt ?: System.currentTimeMillis()
        )
    }

    private fun buildDerivedProfileId(serverUrl: String, ownParentId: String, linkedChildId: String): String {
        val source = "${serverUrl.trim().lowercase()}|${ownParentId.trim()}|${linkedChildId.trim()}"
        return UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun sameIdentity(first: ParentMonitorProfile, second: ParentMonitorProfile): Boolean {
        return first.serverUrl.equals(second.serverUrl, ignoreCase = true) &&
            first.ownParentDeviceId == second.ownParentDeviceId &&
            first.linkedChildDeviceId == second.linkedChildDeviceId
    }

    private fun persistProfiles(profiles: List<ParentMonitorProfile>) {
        val array = JSONArray()
        profiles.sortedBy { it.name.lowercase() }.forEach { profile ->
            array.put(profile.toJson())
        }
        prefs.edit().putString(KEY_PROFILES_JSON, array.toString()).apply()
    }

    private fun setActiveProfile(profileId: String?) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
    }

    private fun JSONObject.toProfile(): ParentMonitorProfile {
        return ParentMonitorProfile(
            id = optString("id"),
            name = optString("name"),
            serverUrl = optString("serverUrl"),
            ownParentDeviceId = optString("ownParentDeviceId"),
            linkedChildDeviceId = optString("linkedChildDeviceId"),
            linkedChildDisplayName = optString("linkedChildDisplayName"),
            updatedAt = optLong("updatedAt", 0L)
        )
    }

    private fun ParentMonitorProfile.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("serverUrl", serverUrl)
            put("ownParentDeviceId", ownParentDeviceId)
            put("linkedChildDeviceId", linkedChildDeviceId)
            put("linkedChildDisplayName", linkedChildDisplayName)
            put("updatedAt", updatedAt)
        }
    }

    private fun ParentMonitorProfile.toActiveSession(): ParentActiveSession {
        return ParentActiveSession(
            profileId = id,
            profileName = name,
            serverUrl = serverUrl,
            ownParentDeviceId = ownParentDeviceId,
            linkedChildDeviceId = linkedChildDeviceId,
            updatedAt = updatedAt
        )
    }

    private fun shouldRefreshGeneratedProfileName(
        profile: ParentMonitorProfile,
        newChildDisplayName: String
    ): Boolean {
        val currentName = profile.name.trim()
        if (currentName.isBlank()) return true

        val generatedCandidates = buildSet {
            add(buildSuggestedProfileName(profile.linkedChildDisplayName, profile.linkedChildDeviceId))
            add(buildSuggestedProfileName(newChildDisplayName, profile.linkedChildDeviceId))
            add(context.getString(R.string.profile_switch_default_name_format,
                truncateId(profile.ownParentDeviceId),
                truncateId(profile.linkedChildDeviceId.ifBlank {
                    context.getString(R.string.profile_switch_no_link_short)
                })
            ))
        }

        return currentName in generatedCandidates
    }

    private fun truncateId(rawId: String): String {
        val normalized = rawId.trim()
        return if (normalized.length <= 16) normalized else "${normalized.take(8)}...${normalized.takeLast(4)}"
    }
}
