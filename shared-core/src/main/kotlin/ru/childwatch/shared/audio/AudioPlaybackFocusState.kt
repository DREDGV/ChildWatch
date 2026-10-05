package ru.childwatch.shared.audio

/** A temporary focus loss may resume only an already requested listening session. */
class AudioPlaybackFocusState {
    private var resumeRequested = false

    @Synchronized
    fun onTransientLoss(playbackActive: Boolean) {
        // A second loss arrives while playback is paused; it must not erase the first.
        resumeRequested = resumeRequested || playbackActive
    }

    @Synchronized
    fun consumeResumeRequest(): Boolean {
        val requested = resumeRequested
        resumeRequested = false
        return requested
    }

    @Synchronized
    fun onStop() {
        resumeRequested = false
    }
}
