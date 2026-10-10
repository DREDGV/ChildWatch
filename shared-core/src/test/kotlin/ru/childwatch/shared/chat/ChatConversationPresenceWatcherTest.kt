package ru.childwatch.shared.chat

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ChatConversationPresenceWatcherTest {
    @Test fun leavingClosesOnlyItsCapturedTokenAndClearsLiveStatus() = runBlocking {
        val received = CompletableDeferred<Unit>()
        val left = CompletableDeferred<String>()
        val observed = mutableListOf<ChatPresenceSnapshot?>()
        val transport = object : ChatPresenceTransport {
            override suspend fun renew(sessionId: String?) = ChatPresenceLease(true, "room", "own-token", 45_000)
            override suspend fun snapshot() = ChatPresenceSnapshot(true, "room", "me")
            override suspend fun leave(sessionId: String) { left.complete(sessionId) }
        }
        val watcher = ChatConversationPresenceWatcher(this, { observed.add(it); if (it != null) received.complete(Unit) })
        watcher.start(transport)
        withTimeout(1_000) { received.await() }
        watcher.stop()
        assertNull(observed.last())
        assertEquals("own-token", withTimeout(1_000) { left.await() })
    }

    @Test fun failedRefreshClearsClaimsAndAccessDenialStopsRegistration() = runBlocking {
        val left = CompletableDeferred<Unit>()
        var fetches = 0
        val observed = mutableListOf<ChatPresenceSnapshot?>()
        val transport = object : ChatPresenceTransport {
            override suspend fun renew(sessionId: String?) = ChatPresenceLease(true, "room", "token", 45_000)
            override suspend fun snapshot(): ChatPresenceSnapshot {
                if (++fetches == 1) return ChatPresenceSnapshot(true, "room", "me")
                throw ChatPresenceRequestError(403)
            }
            override suspend fun leave(sessionId: String) { left.complete(Unit) }
        }
        val watcher = ChatConversationPresenceWatcher(this, { observed.add(it) }, 1)
        watcher.start(transport)
        withTimeout(1_000) { left.await() }
        assertEquals(2, fetches)
        assertTrue(observed.any { it != null })
        assertNull(observed.last())
        watcher.stop()
    }

    @Test fun lateResponseFromPausedScreenCannotReplaceNewScreen() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val left = CompletableDeferred<String>()
        val fresh = CompletableDeferred<Unit>()
        val observed = mutableListOf<String?>()
        val old = object : ChatPresenceTransport {
            override suspend fun renew(sessionId: String?) = ChatPresenceLease(true, "old", "old-token", 45_000)
            override suspend fun snapshot(): ChatPresenceSnapshot = withContext(NonCancellable) {
                pending.complete(Unit); release.await(); ChatPresenceSnapshot(true, "old", "me")
            }
            override suspend fun leave(sessionId: String) { left.complete(sessionId) }
        }
        val newer = object : ChatPresenceTransport {
            override suspend fun renew(sessionId: String?) = ChatPresenceLease(true, "new", "new-token", 45_000)
            override suspend fun snapshot() = ChatPresenceSnapshot(true, "new", "me")
            override suspend fun leave(sessionId: String) { }
        }
        val watcher = ChatConversationPresenceWatcher(this, {
            observed.add(it?.conversationId); if (it?.conversationId == "new") fresh.complete(Unit)
        })
        watcher.start(old)
        withTimeout(1_000) { pending.await() }
        watcher.stop(); watcher.start(newer)
        withTimeout(1_000) { fresh.await() }
        release.complete(Unit)
        assertEquals("old-token", withTimeout(1_000) { left.await() })
        assertFalse(observed.contains("old"))
        assertEquals("new", observed.last())
        watcher.stop()
    }
}
