package ru.example.childwatch.chat.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient

/**
 * Tracks whether the person on the other side of a chat is currently online.
 *
 * The server already reports this per device link, but no chat screen asked for
 * it, so a user could not tell whether the other side was reachable before
 * writing. This polls the existing endpoint while the screen is visible and
 * reports every answer through [onPresenceChanged].
 *
 * Polling is used rather than a socket subscription because the server exposes
 * presence as a query and a chat screen only needs a coarse answer.
 */
class PeerPresenceWatcher(
    private val networkClient: NetworkClient,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val onPresenceChanged: (PresenceSnapshot) -> Unit
) {

    companion object {
        /**
         * Someone going offline should be noticed within seconds, while the
         * request stays well inside the server's 60-per-minute rate limit.
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
     * Watches the conversation peer for [targetChildDeviceId].
     *
     * @param targetChildDeviceId the child device whose family context is read
     * @param selfDeviceIds this application's own ids, never reported as the peer
     */
    fun start(targetChildDeviceId: String?, selfDeviceIds: List<String>) {
        val normalized = targetChildDeviceId?.trim().orEmpty()
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

    /**
     * Picks the entry for the other side: an explicit device id wins, otherwise
     * the first participant that is not this device.
     */
    private suspend fun fetch(
        targetDeviceId: String,
        selfDeviceIds: List<String>
    ): PresenceSnapshot? {
        val response = runCatching { networkClient.getFamilyPresence(targetDeviceId) }.getOrNull()
        val body = response?.takeIf { it.isSuccessful }?.body()
        if (body?.success != true) return null

        val participants = body.participants
        val selfIds = selfDeviceIds.mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
        if (participants.isEmpty()) return PresenceSnapshot(null, null, false, true)

        val peer = participants.firstOrNull { it.deviceId == targetDeviceId && it.deviceId !in selfIds }
            ?: participants.firstOrNull { it.deviceId !in selfIds }
            ?: participants.first()

        return PresenceSnapshot(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            isOnline = peer.isOnline,
            isKnown = true
        )
    }
}
