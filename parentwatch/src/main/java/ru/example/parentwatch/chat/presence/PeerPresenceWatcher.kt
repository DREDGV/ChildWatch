package ru.example.parentwatch.chat.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.parentwatch.network.NetworkClient

/**
 * Tracks whether the other side of a chat is currently online.
 *
 * The server reports this for every device link, but no chat screen asked for
 * it, so a user could not tell whether the other side was reachable. This polls
 * the existing endpoint while the screen is visible.
 */
class PeerPresenceWatcher(
    private val networkClient: NetworkClient,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val onPresenceChanged: (PresenceSnapshot) -> Unit
) {

    companion object {
        private const val TAG = "PeerPresence"

        /**
         * A peer going offline is noticed within seconds, while the request
         * stays well inside the server's 60-per-minute rate limit.
         */
        const val DEFAULT_POLL_INTERVAL_MS = 20_000L
    }

    data class PresenceSnapshot(
        val deviceId: String?,
        val displayName: String?,
        val isOnline: Boolean,
        /** True once an answer arrived, so "unknown" stays distinct from "offline". */
        val isKnown: Boolean
    ) {
        companion object {
            val UNKNOWN = PresenceSnapshot(null, null, false, false)
        }
    }

    private var job: Job? = null
    private var targetDeviceId: String = ""

    /**
     * Watches the parent side of this child's family context.
     *
     * @param ownChildDeviceId this device's child id, used for the lookup
     * @param selfDeviceIds ids that must never be reported as the peer
     */
    fun start(ownChildDeviceId: String?, selfDeviceIds: List<String>) {
        val normalized = ownChildDeviceId?.trim().orEmpty()
        if (normalized.isBlank()) return
        if (normalized == targetDeviceId && job?.isActive == true) return

        targetDeviceId = normalized
        stop()
        onPresenceChanged(PresenceSnapshot.UNKNOWN)
        job = scope.launch {
            while (isActive) {
                fetch(normalized, selfDeviceIds)?.let(onPresenceChanged)
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun fetch(
        targetDeviceId: String,
        selfDeviceIds: List<String>
    ): PresenceSnapshot? {
        val response = runCatching { networkClient.getFamilyPresence(targetDeviceId) }.getOrNull()
        val body = response?.takeIf { it.isSuccessful }?.body()
        if (body?.success != true) {
            android.util.Log.w(
                TAG,
                "presence for $targetDeviceId failed: http=${response?.code()} " +
                    "body=${body?.success} error=${response?.errorBody()?.string()?.take(120)}"
            )
            return null
        }

        val participants = body.participants
        val selfIds = selfDeviceIds.mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
        if (participants.isEmpty()) return PresenceSnapshot(null, null, false, true)

        // The other side is the first participant that is not this device.
        val peer = participants.firstOrNull { it.deviceId !in selfIds } ?: participants.first()
        android.util.Log.i(
            TAG,
            "presence for $targetDeviceId: self=$selfIds peer=${peer.deviceId} online=${peer.isOnline}"
        )
        return PresenceSnapshot(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            isOnline = peer.isOnline,
            isKnown = true
        )
    }
}
