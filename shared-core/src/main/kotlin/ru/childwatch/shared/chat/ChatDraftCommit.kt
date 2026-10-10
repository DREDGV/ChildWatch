package ru.childwatch.shared.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Finish the short durable insert after copying; cancellation cannot split a row from its file. */
object ChatDraftCommit {
    suspend fun <T> finishPrepared(owner: Job?, persist: suspend () -> T, discard: () -> Unit): T {
        var committed = false
        try {
            return withContext(NonCancellable) {
                // Cancel before accepting the prepared draft; once insertion starts, finish its ACK.
                owner?.ensureActive()
                val value = persist()
                committed = true
                value
            }
        } catch (error: Throwable) {
            if (!committed) discard()
            throw error
        }
    }
}
