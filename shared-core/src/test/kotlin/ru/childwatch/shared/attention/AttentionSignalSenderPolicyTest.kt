package ru.childwatch.shared.attention

import org.junit.Assert.*
import org.junit.Test

class AttentionSignalSenderPolicyTest {
    private val policy = AttentionSignalSenderPolicy
    private val sentAt = 1_000_000L
    private fun initial(duration: Long = 15_000) = policy.start("request-123", "child-a", sentAt, duration)
    private fun event(state: AttentionSignalSenderState, status: AttentionSignalStatus, at: Long,
                      request: String = state.requestId, target: String = state.targetDeviceId, timestamp: Long = at) =
        policy.onStatus(state, request, target, status, at, timestamp)

    @Test fun anotherRequestOrAnotherChildCannotChangeTheFrozenTarget() {
        val state = initial()
        assertEquals(state, event(state, AttentionSignalStatus.STARTED, sentAt + 1, request = "other"))
        assertEquals(state, event(state, AttentionSignalStatus.STARTED, sentAt + 1, target = "child-b"))
        assertEquals(state, event(state, AttentionSignalStatus.STARTED, sentAt + 1, timestamp = 0))
        assertEquals(state, event(state, AttentionSignalStatus.STARTED, sentAt + 1,
            timestamp = sentAt + 1 + AttentionSignalContract.MAX_CLOCK_SKEW_MS + 1))
    }

    @Test fun confirmedLongPlaybackWaitsItsDurationAndGraceInsteadOfBecomingUnknownAfterFifteenSeconds() {
        val startedAt = sentAt + 1_000
        val started = event(initial(60_000), AttentionSignalStatus.STARTED, startedAt)
        assertFalse(policy.tick(started, startedAt + 20_000).isUnknown)
        assertFalse(policy.tick(started, startedAt + 50_000).isUnknown)
        assertFalse(policy.tick(started, startedAt + 60_000 + policy.GRACE_MS - 1).isUnknown)
        val unknown = policy.tick(started, startedAt + 60_000 + policy.GRACE_MS)
        assertTrue(unknown.isUnknown)
        assertEquals(AttentionSignalStatus.STARTED, unknown.status)
        assertFalse(policy.canSend(unknown, startedAt + 65_000))
        val stopping = policy.requestStop(started, startedAt + 2_000)
        assertTrue(policy.tick(stopping, startedAt + 2_000 + policy.STOP_WAIT_MS).isUnknown)
    }

    @Test fun reorderedAndRegressiveEventsCannotUndoProgressOrTerminalOutcome() {
        val started = event(initial(), AttentionSignalStatus.STARTED, sentAt + 10)
        assertEquals(started, event(started, AttentionSignalStatus.DELIVERED, sentAt + 20))
        assertEquals(started, event(started, AttentionSignalStatus.FAILED, sentAt + 30, timestamp = sentAt + 9))
        val completed = event(started, AttentionSignalStatus.COMPLETED, sentAt + 40)
        assertTrue(completed.isTerminal)
        assertEquals(completed, event(completed, AttentionSignalStatus.STARTED, sentAt + 50))
        assertEquals(completed, event(completed, AttentionSignalStatus.FAILED, sentAt + 60))
        assertTrue(policy.canSend(completed, sentAt + 40))
    }

    @Test fun silenceBecomesUnknownWithoutInventingServerExpiryOrAllowingAnotherSound() {
        val initial = initial(60_000)
        assertFalse(policy.tick(initial, sentAt + policy.SILENCE_MS - 1).isUnknown)
        val unknown = policy.tick(initial, sentAt + policy.SILENCE_MS)
        assertTrue(unknown.isUnknown)
        assertFalse(unknown.waiting)
        assertNull(unknown.status)
        assertEquals(sentAt + 95_000, unknown.safeUntil)
        assertFalse(policy.canSend(unknown, unknown.safeUntil - 1))
        assertTrue(policy.canSend(unknown, unknown.safeUntil))
        assertNull(policy.tick(unknown, unknown.safeUntil).status)
        val expired = event(unknown, AttentionSignalStatus.EXPIRED, sentAt + 20_000)
        assertEquals(AttentionSignalStatus.EXPIRED, expired.status)
        assertFalse(expired.isUnknown)
    }

    @Test fun stopWaitIsFiniteButLateMatchingEvidenceStillClosesTheRequest() {
        val started = event(initial(), AttentionSignalStatus.STARTED, sentAt + 1)
        val stopping = policy.requestStop(started, sentAt + 2)
        assertEquals(stopping, policy.requestStop(stopping, sentAt + 3))
        val unknown = policy.tick(stopping, sentAt + 2 + policy.STOP_WAIT_MS)
        assertTrue(unknown.isUnknown)
        assertFalse(policy.canSend(unknown, sentAt + 12_000))
        val abandoned = policy.stopWaiting(unknown)
        assertFalse(abandoned.waiting)
        assertFalse(policy.shouldPoll(abandoned, sentAt + 13_000))
        val stopped = event(abandoned, AttentionSignalStatus.STOPPED, sentAt + 14_000)
        assertTrue(stopped.isTerminal)
        assertFalse(stopped.waiting)
        assertTrue(policy.canSend(stopped, sentAt + 14_000))
    }

    @Test fun readOnlyPollingIsBoundedAndDoesNotRefreshTheWaitingDeadline() {
        val initial = initial()
        assertFalse(policy.shouldPoll(initial, sentAt + policy.POLL_AFTER_MS - 1))
        assertTrue(policy.shouldPoll(initial, sentAt + policy.POLL_AFTER_MS))
        val polled = policy.markPolled(initial, sentAt + policy.POLL_AFTER_MS)
        assertFalse(policy.shouldPoll(polled, sentAt + policy.POLL_AFTER_MS + policy.POLL_INTERVAL_MS - 1))
        assertTrue(policy.shouldPoll(polled, sentAt + policy.POLL_AFTER_MS + policy.POLL_INTERVAL_MS))
        assertTrue(policy.tick(polled, sentAt + policy.SILENCE_MS).isUnknown)
        assertFalse(policy.shouldPoll(polled, polled.safeUntil))
        val queued = event(initial, AttentionSignalStatus.QUEUED, sentAt + 1)
        assertEquals(queued, event(queued, AttentionSignalStatus.QUEUED, sentAt + 14_000))
        assertTrue(policy.tick(queued, sentAt + 16_000).isUnknown)
    }

    @Test fun recoveryUnknownRetainsLastEvidenceAndTheSafetyGuard() {
        val delivered = event(initial(), AttentionSignalStatus.DELIVERED, sentAt + 1)
        val unknown = policy.markUnknown(delivered, sentAt + 2)
        assertEquals(AttentionSignalStatus.DELIVERED, unknown.status)
        assertEquals(delivered.lastEvidenceAt, unknown.lastEvidenceAt)
        assertTrue(unknown.isUnknown)
        assertFalse(policy.canSend(unknown, sentAt + 3))
        assertTrue(policy.canStop(unknown, sentAt + 3))
        val stopping = policy.requestStop(unknown, sentAt + 4)
        assertTrue(stopping.stopPending)
        assertFalse(policy.canStop(stopping, sentAt + 5))
        assertFalse(policy.canStop(unknown, unknown.safeUntil))
        val completed = event(unknown, AttentionSignalStatus.COMPLETED, sentAt + 6)
        assertEquals(completed, policy.markUnknown(completed, sentAt + 7))
    }
}
