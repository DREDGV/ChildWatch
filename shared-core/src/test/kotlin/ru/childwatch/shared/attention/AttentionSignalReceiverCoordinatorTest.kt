package ru.childwatch.shared.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionSignalReceiverCoordinatorTest {
    private val now = 3_000_000L

    @Test
    fun `valid exact-target request emits delivered before playback and started afterwards`() {
        val trace = mutableListOf<String>()
        val coordinator = coordinator(
            emit = { trace += "STATUS:${it.status}" },
            start = { trace += "PLAYBACK:${it.requestId}" }
        )
        val request = validRequest()

        coordinator.handleStart(request)
        coordinator.onStarted(request)

        assertEquals(
            listOf(
                "STATUS:DELIVERED",
                "PLAYBACK:${request.requestId}",
                "STATUS:STARTED"
            ),
            trace
        )
    }

    @Test
    fun `wrong target and expired requests never reach playback`() {
        val statuses = mutableListOf<AttentionSignalStatusEvent>()
        val started = mutableListOf<AttentionSignalRequest>()
        val coordinator = coordinator(emit = statuses::add, start = started::add)

        coordinator.handleStart(validRequest().copy(targetDeviceId = "other-device"))
        coordinator.handleStart(
            validRequest(requestId = "request-expired").copy(
                createdAt = now - 40_000L,
                expiresAt = now - 1L
            )
        )

        assertTrue(started.isEmpty())
        assertEquals(AttentionSignalStatus.REJECTED, statuses[0].status)
        assertEquals("WRONG_TARGET", statuses[0].reason)
        assertEquals("TARGET_DEVICE_MISMATCH", statuses[0].errorCode)
        assertEquals(AttentionSignalStatus.EXPIRED, statuses[1].status)
        assertEquals("TTL_EXPIRED", statuses[1].reason)
    }

    @Test
    fun `missing context is rejected and valid request is accepted by policy`() {
        val request = validRequest()

        val missingContext = AttentionSignalReceiverPolicy.evaluate(request, "  ", now)

        assertEquals(AttentionSignalStatus.REJECTED, missingContext?.status)
        assertEquals("MISSING_DEVICE_ID", missingContext?.errorCode)
        assertNull(AttentionSignalReceiverPolicy.evaluate(request, "child-device", now))
    }

    @Test
    fun `inactive remote stop is rejected and lifecycle terminal statuses preserve reasons`() {
        val statuses = mutableListOf<AttentionSignalStatusEvent>()
        val request = validRequest()
        val coordinator = coordinator(
            emit = statuses::add,
            stop = { _, _ -> false }
        )

        coordinator.handleStop(request.requestId)
        coordinator.onStopped(request, "REPLACED")
        coordinator.onCompleted(request)
        coordinator.onFailed(request, "MEDIA_PLAYER_ERROR", "decoder")

        assertEquals(
            listOf(
                AttentionSignalStatus.REJECTED,
                AttentionSignalStatus.STOPPED,
                AttentionSignalStatus.COMPLETED,
                AttentionSignalStatus.FAILED
            ),
            statuses.map { it.status }
        )
        assertEquals("SIGNAL_NOT_ACTIVE", statuses[0].errorCode)
        assertEquals("REPLACED", statuses[1].reason)
        assertEquals("TIMEOUT", statuses[2].reason)
        assertEquals("MEDIA_PLAYER_ERROR", statuses[3].errorCode)
        assertEquals("decoder", statuses[3].message)
    }

    private fun coordinator(
        emit: (AttentionSignalStatusEvent) -> Unit = {},
        start: (AttentionSignalRequest) -> Unit = {},
        stop: (String?, String) -> Boolean = { _, _ -> true }
    ) = AttentionSignalReceiverCoordinator(
        ownDeviceId = { "child-device" },
        emitStatus = emit,
        startPlayback = start,
        stopPlayback = stop,
        now = { now }
    )

    private fun validRequest(requestId: String = "request-valid-0001") = AttentionSignalRequest(
        requestId = requestId,
        targetDeviceId = "child-device",
        requesterDeviceId = "parent-device",
        requesterDisplayName = "Parent",
        createdAt = now,
        expiresAt = now + 30_000L
    )
}
