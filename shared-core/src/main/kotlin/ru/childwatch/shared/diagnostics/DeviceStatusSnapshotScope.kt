package ru.childwatch.shared.diagnostics

/** Identity of a read/cache entry. Display names and another phone's measurements are never keys. */
data class DeviceStatusSnapshotScope(val actor: List<String>, val targetDeviceId: String) {
    init { require(actor.size == 4 && actor.all { it.isNotBlank() } && targetDeviceId.isNotBlank()) }
    val cacheKey: String get() {
        val encoded = (actor + targetDeviceId).joinToString("") { "${it.length}:$it" }
        return "audio_status_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun accepts(current: DeviceStatusSnapshotScope?, responseDeviceId: String?, success: Boolean): Boolean =
        current == this && success && responseDeviceId == targetDeviceId

    companion object {
        fun from(actor: List<String>, targetDeviceId: String): DeviceStatusSnapshotScope? {
            val fields = actor.map(String::trim)
            val target = targetDeviceId.trim()
            if (fields.size != 4 || fields.any(String::isBlank) || target.isBlank()) return null
            return DeviceStatusSnapshotScope(fields, target)
        }
    }
}
