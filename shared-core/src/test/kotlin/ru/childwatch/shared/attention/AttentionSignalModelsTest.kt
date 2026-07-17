package ru.childwatch.shared.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttentionSignalModelsTest {
    @Test
    fun `normalization bounds duration volume ttl and vibration`() {
        val now = 1_000_000L
        val result = AttentionSignalRequest(
            requestId = "  request-123  ",
            targetDeviceId = " child-1 ",
            requesterDeviceId = " parent-1 ",
            requesterDisplayName = " Parent ",
            durationMs = 100_000L,
            volumePercent = 130,
            vibrate = false,
            vibrationPattern = AttentionVibrationPattern.SOS,
            createdAt = now,
            expiresAt = now + 500_000L
        ).normalized(now)

        assertEquals("request-123", result.requestId)
        assertEquals("child-1", result.targetDeviceId)
        assertEquals(60_000L, result.durationMs)
        assertEquals(100, result.volumePercent)
        assertEquals(AttentionVibrationPattern.OFF, result.vibrationPattern)
        assertEquals(now + 120_000L, result.expiresAt)
        assertNull(result.validationError(now))
    }

    @Test
    fun `validation rejects self target and expired requests`() {
        val now = 2_000_000L
        val selfTarget = validRequest(now).copy(targetDeviceId = "device-1")
        assertEquals("TARGET_EQUALS_REQUESTER", selfTarget.validationError(now))

        val expired = validRequest(now).copy(createdAt = now - 40_000L, expiresAt = now - 1L)
        assertEquals("REQUEST_EXPIRED", expired.validationError(now))
    }

    @Test
    fun `wire enum parsing is safe and terminal states are explicit`() {
        assertEquals(AttentionTone.SIREN, AttentionTone.fromWire("siren"))
        assertEquals(AttentionTone.ATTENTION, AttentionTone.fromWire("unknown"))
        assertEquals(AttentionVibrationPattern.PULSE, AttentionVibrationPattern.fromWire(null))
        assertEquals(AttentionSignalStatus.STARTED, AttentionSignalStatus.fromWire("started"))
        assertEquals(true, AttentionSignalStatus.EXPIRED.isTerminal)
        assertEquals(false, AttentionSignalStatus.DELIVERED.isTerminal)
    }

    private fun validRequest(now: Long) = AttentionSignalRequest(
        requestId = "request-123",
        targetDeviceId = "device-2",
        requesterDeviceId = "device-1",
        requesterDisplayName = "Parent",
        createdAt = now,
        expiresAt = now + 30_000L
    )
}
