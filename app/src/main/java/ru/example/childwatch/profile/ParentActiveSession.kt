package ru.example.childwatch.profile

import org.json.JSONObject
import java.util.UUID

data class ParentActiveSession(
    val profileId: String,
    val profileName: String,
    val serverUrl: String,
    val ownParentDeviceId: String,
    val linkedChildDeviceId: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isComplete(): Boolean {
        return serverUrl.isNotBlank() && ownParentDeviceId.isNotBlank()
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("profileId", profileId)
            put("profileName", profileName)
            put("serverUrl", serverUrl)
            put("ownParentDeviceId", ownParentDeviceId)
            put("linkedChildDeviceId", linkedChildDeviceId)
            put("updatedAt", updatedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ParentActiveSession? {
            val profileId = json.optString("profileId").trim()
            val profileName = json.optString("profileName").trim()
            val serverUrl = json.optString("serverUrl").trim()
            val ownParentDeviceId = json.optString("ownParentDeviceId").trim()
            val linkedChildDeviceId = json.optString("linkedChildDeviceId").trim()
            val updatedAt = json.optLong("updatedAt", System.currentTimeMillis())

            if (serverUrl.isBlank() && ownParentDeviceId.isBlank() && linkedChildDeviceId.isBlank()) {
                return null
            }

            return ParentActiveSession(
                profileId = profileId.ifBlank {
                    buildDerivedProfileId(serverUrl, ownParentDeviceId, linkedChildDeviceId)
                },
                profileName = profileName,
                serverUrl = serverUrl,
                ownParentDeviceId = ownParentDeviceId,
                linkedChildDeviceId = linkedChildDeviceId,
                updatedAt = updatedAt
            )
        }

        fun buildDerivedProfileId(
            serverUrl: String,
            ownParentDeviceId: String,
            linkedChildDeviceId: String
        ): String {
            val source = "${serverUrl.trim().lowercase()}|${ownParentDeviceId.trim()}|${linkedChildDeviceId.trim()}"
            return UUID.nameUUIDFromBytes(source.toByteArray(Charsets.UTF_8)).toString()
        }
    }
}

data class ParentEffectiveContext(
    val activeSession: ParentActiveSession?,
    val serverUrl: String,
    val ownParentDeviceId: String,
    val linkedChildDeviceId: String,
    val source: String
) {
    val activeProfileId: String?
        get() = activeSession?.profileId?.takeIf { it.isNotBlank() }

    val isComplete: Boolean
        get() = serverUrl.isNotBlank() && ownParentDeviceId.isNotBlank()
}
