package ru.childwatch.shared.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackFocusStateTest {
    @Test fun `repeated temporary loss still resumes the original session once`() {
        val state = AudioPlaybackFocusState()
        state.onTransientLoss(playbackActive = true)
        state.onTransientLoss(playbackActive = false)
        assertTrue(state.consumeResumeRequest())
        assertFalse(state.consumeResumeRequest())
    }

    @Test fun `stop while paused prevents later gain from restarting listening`() {
        val state = AudioPlaybackFocusState()
        state.onTransientLoss(playbackActive = true)
        state.onStop()
        assertFalse(state.consumeResumeRequest())
    }

    @Test fun `focus events while idle do not request listening`() {
        val state = AudioPlaybackFocusState()
        state.onTransientLoss(playbackActive = false)
        assertFalse(state.consumeResumeRequest())
    }
}
