package ru.childwatch.shared.chat

import org.junit.Assert.*
import org.junit.Test

class VoiceRecordingPolicyTest {
    @Test fun acceptsOnlyBoundedNonEmptyRecording() {
        assertTrue(VoiceRecordingPolicy.usable(180_000, 10L * 1024 * 1024))
        assertFalse(VoiceRecordingPolicy.usable(180_001, 1))
        assertFalse(VoiceRecordingPolicy.usable(1, 10L * 1024 * 1024 + 1))
        assertFalse(VoiceRecordingPolicy.usable(0, 1024))
        assertFalse(VoiceRecordingPolicy.usable(1000, 0))
    }
    @Test fun elapsedCannotDependOnWallClockOrBecomeNegative() {
        assertEquals(1500L, VoiceRecordingPolicy.elapsed(1000, 2500))
        assertEquals(0L, VoiceRecordingPolicy.elapsed(2500, 1000))
        assertEquals(180_000L, VoiceRecordingPolicy.elapsed(1000, Long.MAX_VALUE))
    }
    @Test fun neverOverwriteUnsentDraftAndRequireSpaceAndPermission() {
        val space = VoiceRecordingPolicy.START_FREE_BYTES
        assertTrue(VoiceRecordingPolicy.mayStart(true, space, false))
        assertFalse(VoiceRecordingPolicy.mayStart(false, space, false))
        assertFalse(VoiceRecordingPolicy.mayStart(true, space - 1, false))
        assertFalse(VoiceRecordingPolicy.mayStart(true, space, true))
    }
}
