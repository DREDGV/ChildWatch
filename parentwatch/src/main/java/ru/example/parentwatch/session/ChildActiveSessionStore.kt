package ru.example.parentwatch.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.example.parentwatch.R
import ru.example.parentwatch.utils.ServerUrlResolver
import java.nio.charset.StandardCharsets
import java.util.UUID

class ChildActiveSessionStore(private val context: Context) {

    companion object {
        private const val PRIMARY_PREFS_NAME = "parentwatch_prefs"
        private const val LEGACY_PREFS_NAME = "childwatch_prefs"
        private const val KEY_SESSIONS_JSON = "saved_child_sessions_json"
        private const val KEY_ACTIVE_SESSION_ID = "active_child_session_id"
        private const val LEGACY_KEY_SESSIONS_JSON = "saved_child_profiles_json"
        private const val LEGACY_KEY_ACTIVE_SESSION_ID = "active_child_profile_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CHILD_DEVICE_ID = "child_device_id"
        private const val KEY_PARENT_DEVICE_ID = "parent_device_id"
        private const val KEY_LINKED_PARENT_DEVICE_ID = "linked_parent_device_id"
        private const val KEY_SELECTED_PARENT_DEVICE_ID = "selected_parent_device_id"
    }

    private val prefs = context.getSharedPreferences(PRIMARY_PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedSessions(): List<ChildActiveSession> {
        return readSessionsFromPrefs(prefs, KEY_SESSIONS_JSON)
            .ifEmpty { readSessionsFromPrefs(legacyPrefs, LEGACY_KEY_SESSIONS_JSON) }
            .sortedBy { it.name.lowercase() }
    }

    fun getSessions(): List<ChildActiveSession> {
        val sessions = getSavedSessions().toMutableList()

        buildCurrentSessionCandidate()?.let { current ->
            if (sessions.none { sameIdentity(it, current) }) {
                sessions.add(current)
            }
        }

        return sessions.sortedBy { it.name.lowercase() }
    }

    fun getActiveSession(): ChildActiveSession? {
        val sessions = getSessions()
        if (sessions.isEmpty()) return null

        val activeId = getActiveSessionId().orEmpty()
        if (activeId.isNotBlank()) {
            sessions.firstOrNull { it.id == activeId }?.let { return it }
        }

        val current = buildCurrentSessionCandidate()
        if (current != null) {
            sessions.firstOrNull { sameIdentity(it, current) }?.let {
                setActiveSessionId(it.id)
                return it
            }
            setActiveSessionId(current.id)
            return current
        }

        if (sessions.size == 1) {
            setActiveSessionId(sessions.first().id)
            return sessions.first()
        }

        return null
    }

    fun getActiveSessionId(): String? {
        val current = prefs.getString(KEY_ACTIVE_SESSION_ID, null)?.trim()
        if (!current.isNullOrBlank()) return current

        return legacyPrefs.getString(LEGACY_KEY_ACTIVE_SESSION_ID, null)?.trim()
            .orEmpty()
            .takeIf { it.isNotBlank() }
    }

    fun saveSession(session: ChildActiveSession) {
        val sessions = getSessions().toMutableList()
        val existingIndex = sessions.indexOfFirst {
            it.id == session.id ||
                it.name.equals(session.name, ignoreCase = true) ||
                sameIdentity(it, session)
        }

        val normalized = session.copy(updatedAt = System.currentTimeMillis())
        if (existingIndex >= 0) {
            sessions[existingIndex] = normalized.copy(id = sessions[existingIndex].id)
        } else {
            sessions.add(normalized)
        }

        persistSessions(sessions)
        setActiveSessionId(
            sessions.firstOrNull {
                it.id == session.id ||
                    it.name.equals(session.name, ignoreCase = true) ||
                    sameIdentity(it, session)
            }?.id
        )
    }

    fun applySession(session: ChildActiveSession) {
        persistRuntimeIdentity(session)
        saveSession(session)
    }

    fun syncRuntimeIdentity(session: ChildActiveSession) {
        persistRuntimeIdentity(session)
        setActiveSessionId(session.id)
    }

    fun removeSession(sessionId: String) {
        val remaining = getSessions().filterNot { it.id == sessionId }
        persistSessions(remaining)
        if (getActiveSessionId() == sessionId) {
            setActiveSessionId(null)
        }
    }

    fun buildSession(
        name: String,
        serverUrl: String,
        ownChildDeviceId: String,
        linkedParentDeviceId: String
    ): ChildActiveSession {
        val normalizedName = name.trim()
        val normalizedServer = ServerUrlResolver.normalizeServerUrl(serverUrl.trim())
        val normalizedOwnId = ownChildDeviceId.trim()
        val normalizedParentId = linkedParentDeviceId.trim()
        val existingSessions = getSessions()
        return ChildActiveSession(
            id = existingSessions.firstOrNull {
                it.name.equals(normalizedName, ignoreCase = true) ||
                    (it.serverUrl.equals(normalizedServer, ignoreCase = true) &&
                        it.ownChildDeviceId == normalizedOwnId &&
                        it.linkedParentDeviceId == normalizedParentId)
            }?.id ?: buildDerivedSessionId(normalizedServer, normalizedOwnId, normalizedParentId),
            name = normalizedName,
            serverUrl = normalizedServer,
            ownChildDeviceId = normalizedOwnId,
            linkedParentDeviceId = normalizedParentId,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun resolveCurrentServerUrl(): String {
        val active = getActiveSession()?.serverUrl?.trim()
        if (!active.isNullOrBlank()) return ServerUrlResolver.normalizeServerUrl(active)

        val current = buildCurrentSessionCandidate()?.serverUrl?.trim()
        if (!current.isNullOrBlank()) return ServerUrlResolver.normalizeServerUrl(current)

        val raw = listOf(
            prefs.getString(KEY_SERVER_URL, null),
            legacyPrefs.getString(KEY_SERVER_URL, null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }
        if (!raw.isNullOrBlank()) return ServerUrlResolver.normalizeServerUrl(raw)

        return ""
    }

    fun resolveCurrentChildId(): String {
        // child_device_id is the durable identity used by the parent-child link.
        // Prefer it over a stale/generated device_id so independently restarted
        // services cannot register the same phone under two different IDs.
        val persisted = resolveCurrentChildIdFromSources()
        if (persisted.isNotBlank()) return persisted

        val active = getActiveSession()?.ownChildDeviceId?.trim()
        if (!active.isNullOrBlank()) return active

        val current = buildCurrentSessionCandidate()?.ownChildDeviceId?.trim()
        if (!current.isNullOrBlank()) return current

        return ""
    }

    fun resolveCurrentParentId(): String {
        val active = getActiveSession()?.linkedParentDeviceId?.trim()
        if (!active.isNullOrBlank()) return active

        val current = buildCurrentSessionCandidate()?.linkedParentDeviceId?.trim()
        if (!current.isNullOrBlank()) return current

        return listOf(
            prefs.getString(KEY_SELECTED_PARENT_DEVICE_ID, null),
            prefs.getString(KEY_PARENT_DEVICE_ID, null),
            prefs.getString(KEY_LINKED_PARENT_DEVICE_ID, null),
            legacyPrefs.getString(KEY_SELECTED_PARENT_DEVICE_ID, null),
            legacyPrefs.getString(KEY_PARENT_DEVICE_ID, null),
            legacyPrefs.getString(KEY_LINKED_PARENT_DEVICE_ID, null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    fun resolveEffectiveContext(): ChildEffectiveContext? {
        getActiveSession()?.let { active ->
            return ChildEffectiveContext(
                serverUrl = ServerUrlResolver.normalizeServerUrl(active.serverUrl),
                ownChildDeviceId = active.ownChildDeviceId,
                linkedParentDeviceId = active.linkedParentDeviceId,
                activeSessionId = active.id,
                source = ChildEffectiveContext.Source.ACTIVE_SESSION,
                updatedAt = active.updatedAt
            )
        }

        buildCurrentSessionCandidate()?.let { current ->
            return ChildEffectiveContext(
                serverUrl = current.serverUrl,
                ownChildDeviceId = current.ownChildDeviceId,
                linkedParentDeviceId = current.linkedParentDeviceId,
                activeSessionId = current.id,
                source = ChildEffectiveContext.Source.CURRENT_SESSION,
                updatedAt = current.updatedAt
            )
        }

        val serverUrl = resolveCurrentServerUrl()
        val childId = resolveCurrentChildId()
        if (serverUrl.isNotBlank() || childId.isNotBlank()) {
            return ChildEffectiveContext(
                serverUrl = serverUrl,
                ownChildDeviceId = childId,
                linkedParentDeviceId = resolveCurrentParentId(),
                activeSessionId = getActiveSessionId(),
                source = ChildEffectiveContext.Source.LEGACY_PREFS
            )
        }

        return null
    }

    private fun persistRuntimeIdentity(session: ChildActiveSession) {
        val normalizedServer = ServerUrlResolver.normalizeServerUrl(session.serverUrl)
        val editor = prefs.edit()
            .putString(KEY_SERVER_URL, normalizedServer)
            .putString(KEY_DEVICE_ID, session.ownChildDeviceId)
            .putString(KEY_CHILD_DEVICE_ID, session.ownChildDeviceId)
            .putString(KEY_SELECTED_PARENT_DEVICE_ID, session.linkedParentDeviceId)
            .putString(KEY_PARENT_DEVICE_ID, session.linkedParentDeviceId)
            .putString(KEY_LINKED_PARENT_DEVICE_ID, session.linkedParentDeviceId)
            .putString(KEY_ACTIVE_SESSION_ID, session.id)

        editor.apply()

        legacyPrefs.edit()
            .putString(KEY_SERVER_URL, normalizedServer)
            .putString(KEY_DEVICE_ID, session.ownChildDeviceId)
            .putString(KEY_CHILD_DEVICE_ID, session.ownChildDeviceId)
            .putString(KEY_SELECTED_PARENT_DEVICE_ID, session.linkedParentDeviceId)
            .putString(KEY_PARENT_DEVICE_ID, session.linkedParentDeviceId)
            .putString(KEY_LINKED_PARENT_DEVICE_ID, session.linkedParentDeviceId)
            .putString(LEGACY_KEY_ACTIVE_SESSION_ID, session.id)
            .apply()
    }

    private fun buildCurrentSessionCandidate(): ChildActiveSession? {
        val serverUrl = resolveCurrentServerUrlFromSources()
        val ownChildId = resolveCurrentChildIdFromSources()
        if (serverUrl.isBlank() || ownChildId.isBlank()) return null

        val linkedParentId = resolveCurrentParentIdFromSources()
        return ChildActiveSession(
            id = buildDerivedSessionId(serverUrl, ownChildId, linkedParentId),
            name = context.getString(R.string.profile_switch_current_name),
            serverUrl = serverUrl,
            ownChildDeviceId = ownChildId,
            linkedParentDeviceId = linkedParentId,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun resolveCurrentServerUrlFromSources(): String {
        return listOf(
            prefs.getString(KEY_SERVER_URL, null),
            legacyPrefs.getString(KEY_SERVER_URL, null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun resolveCurrentChildIdFromSources(): String {
        return listOf(
            prefs.getString(KEY_CHILD_DEVICE_ID, null),
            legacyPrefs.getString(KEY_CHILD_DEVICE_ID, null),
            prefs.getString(KEY_DEVICE_ID, null),
            legacyPrefs.getString(KEY_DEVICE_ID, null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun resolveCurrentParentIdFromSources(): String {
        return listOf(
            prefs.getString(KEY_SELECTED_PARENT_DEVICE_ID, null),
            prefs.getString(KEY_PARENT_DEVICE_ID, null),
            prefs.getString(KEY_LINKED_PARENT_DEVICE_ID, null),
            legacyPrefs.getString(KEY_SELECTED_PARENT_DEVICE_ID, null),
            legacyPrefs.getString(KEY_PARENT_DEVICE_ID, null),
            legacyPrefs.getString(KEY_LINKED_PARENT_DEVICE_ID, null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun buildDerivedSessionId(serverUrl: String, ownChildId: String, linkedParentId: String): String {
        val source = "${serverUrl.trim().lowercase()}|${ownChildId.trim()}|${linkedParentId.trim()}"
        return UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun sameIdentity(first: ChildActiveSession, second: ChildActiveSession): Boolean {
        return first.serverUrl.equals(second.serverUrl, ignoreCase = true) &&
            first.ownChildDeviceId == second.ownChildDeviceId &&
            first.linkedParentDeviceId == second.linkedParentDeviceId
    }

    private fun readSessionsFromPrefs(
        sourcePrefs: android.content.SharedPreferences,
        key: String
    ): List<ChildActiveSession> {
        val raw = sourcePrefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(item.toSession())
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistSessions(sessions: List<ChildActiveSession>) {
        val array = JSONArray()
        sessions.sortedBy { it.name.lowercase() }.forEach { session ->
            array.put(session.toJson())
        }
        val payload = array.toString()
        prefs.edit()
            .putString(KEY_SESSIONS_JSON, payload)
            .apply()
        legacyPrefs.edit()
            .putString(LEGACY_KEY_SESSIONS_JSON, payload)
            .apply()
    }

    private fun setActiveSessionId(sessionId: String?) {
        prefs.edit()
            .putString(KEY_ACTIVE_SESSION_ID, sessionId)
            .apply()
        legacyPrefs.edit()
            .putString(LEGACY_KEY_ACTIVE_SESSION_ID, sessionId)
            .apply()
    }

    private fun JSONObject.toSession(): ChildActiveSession {
        return ChildActiveSession(
            id = optString("id"),
            name = optString("name"),
            serverUrl = optString("serverUrl"),
            ownChildDeviceId = optString("ownChildDeviceId"),
            linkedParentDeviceId = optString("linkedParentDeviceId"),
            updatedAt = optLong("updatedAt", 0L)
        )
    }

    private fun ChildActiveSession.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("serverUrl", serverUrl)
            put("ownChildDeviceId", ownChildDeviceId)
            put("linkedParentDeviceId", linkedParentDeviceId)
            put("updatedAt", updatedAt)
        }
    }
}
