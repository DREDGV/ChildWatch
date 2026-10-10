package ru.childwatch.shared.chat

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ChatDraftCommitTest {
    @Test fun cancellationBeforeInsertDiscardsOnlyTheUncommittedCopy() = runBlocking {
        val owner = Job().also { it.cancel() }
        var inserted = false
        var sourcePresent = true
        try {
            ChatDraftCommit.finishPrepared(owner, { inserted=true }, { sourcePresent=false })
            fail("Cancelled preparation was committed")
        } catch (_: CancellationException) { }
        assertFalse(inserted)
        assertFalse(sourcePresent)
    }
    @Test fun cancellationBetweenDatabaseCommitAndAcknowledgementKeepsTheSource() = runBlocking {
        val inserted = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        var rowPresent = false
        var sourcePresent = true
        val worker = launch(Dispatchers.Default) {
            ChatDraftCommit.finishPrepared(currentCoroutineContext()[Job], {
                rowPresent=true // The database has already committed the row.
                inserted.complete(Unit)
                acknowledge.await() // Force cancellation before DAO acknowledgement is returned.
            }, { sourcePresent=false })
        }
        inserted.await()
        worker.cancel()
        acknowledge.complete(Unit)
        worker.join()
        assertTrue(worker.isCancelled)
        assertTrue(rowPresent)
        assertTrue(sourcePresent)
    }
    @Test fun insertFailureStillDeletesTheUnreferencedCopy() = runBlocking {
        var sourcePresent = true
        try {
            ChatDraftCommit.finishPrepared(Job(), { throw IOException("Insert rejected") }, { sourcePresent=false })
            fail("Failed insert was accepted")
        } catch (_: IOException) { }
        assertFalse(sourcePresent)
    }
}
