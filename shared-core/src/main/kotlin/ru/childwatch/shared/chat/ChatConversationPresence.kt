package ru.childwatch.shared.chat

import kotlinx.coroutines.*

data class ChatPresenceParticipant(
    val memberId: String,
    val displayName: String? = null,
    val role: String? = null,
    val avatarKey: String? = null,
    val chatOpen: Boolean = false,
    val deviceConnected: Boolean? = null
)
data class ChatPresenceSnapshot(
    val success: Boolean = false,
    val conversationId: String = "",
    val actorMemberId: String = "",
    val observedAt: Long = 0,
    val expiresInMs: Long = 0,
    val participants: List<ChatPresenceParticipant> = emptyList()
)
data class ChatPresenceRenewRequest(val sessionId: String? = null)
data class ChatPresenceLease(
    val success: Boolean = false,
    val conversationId: String = "",
    val sessionId: String = "",
    val expiresInMs: Long = 0
)

interface ChatPresenceTransport {
    suspend fun renew(sessionId: String?): ChatPresenceLease
    suspend fun snapshot(): ChatPresenceSnapshot
    suspend fun leave(sessionId: String)
}
class ChatPresenceRequestError(val status: Int) : Exception("Presence request failed: $status")

/** One RESUMED screen, one independent lease. A failed refresh immediately clears live claims. */
class ChatConversationPresenceWatcher(
    private val scope: CoroutineScope,
    private val changed: (ChatPresenceSnapshot?) -> Unit,
    private val pollIntervalMs: Long = 15_000,
    private val refreshTimeoutMs: Long = 10_000
) {
    private var job: Job? = null
    private var generation = 0L

    fun start(transport: ChatPresenceTransport) {
        if (job?.isActive == true) return
        val current = ++generation
        changed(null)
        job = scope.launch {
            var sessionId: String? = null
            try {
                while (isActive && current == generation) {
                    try {
                        val snapshot = withTimeoutOrNull(refreshTimeoutMs) {
                            val lease = transport.renew(sessionId)
                            sessionId = lease.sessionId
                            transport.snapshot()
                        } ?: error("Presence refresh timed out")
                        ensureActive()
                        if (current == generation) changed(snapshot)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (current == generation) changed(null)
                        if (error is ChatPresenceRequestError) {
                            if (error.status == 409) sessionId = null
                            if (error.status in listOf(401, 403, 404)) break
                        }
                    }
                    delay(pollIntervalMs)
                }
            } finally {
                sessionId?.let { token ->
                    withContext(NonCancellable) {
                        withTimeoutOrNull(5_000) { runCatching { transport.leave(token) } }
                    }
                }
            }
        }
    }

    fun stop() {
        ++generation
        job?.cancel()
        job = null
        changed(null)
    }
}
