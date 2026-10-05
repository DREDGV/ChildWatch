package ru.childwatch.shared.audio

import org.junit.Assert.*
import org.junit.Test

class AudioCaptureLoopPolicyTest {
    @Test fun `failed current loop owns cleanup and can recover desired stream`() {
        assertTrue(AudioCaptureLoopPolicy.ownsCapture(1, 1, 7, 7))
        assertTrue(AudioCaptureLoopPolicy.mayRecover(true, false))
    }
    @Test fun `late old loop cannot close a newer recorder in the same generation`() {
        assertFalse(AudioCaptureLoopPolicy.ownsCapture(1, 2, 7, 7))
    }
    @Test fun `loop from before photo pause cannot close resumed capture`() {
        assertFalse(AudioCaptureLoopPolicy.ownsCapture(1, 1, 7, 8))
    }
    @Test fun `stop and photo pause forbid automatic capture recovery`() {
        assertFalse(AudioCaptureLoopPolicy.mayRecover(false, false))
        assertFalse(AudioCaptureLoopPolicy.mayRecover(true, true))
        assertFalse(AudioCaptureLoopPolicy.mayRecover(false, true))
    }

    @Test fun `capture failure during recovery handoff rearms after owner relinquishes`() {
        assertTrue(AudioCaptureLoopPolicy.rearmRecovery(true, true, false, false))
        assertFalse(AudioCaptureLoopPolicy.rearmRecovery(true, true, false, true))
    }

    @Test fun `obsolete or cancelled recovery cannot rearm replacement or paused capture`() {
        assertFalse(AudioCaptureLoopPolicy.rearmRecovery(false, true, false, false))
        assertFalse(AudioCaptureLoopPolicy.rearmRecovery(true, false, false, false))
        assertFalse(AudioCaptureLoopPolicy.rearmRecovery(true, true, true, false))
    }
}
