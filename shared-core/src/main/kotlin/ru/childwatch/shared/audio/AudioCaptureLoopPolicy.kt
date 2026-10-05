package ru.childwatch.shared.audio

/** A terminated loop may only clean up the recorder it still owns. */
object AudioCaptureLoopPolicy {
    fun ownsCapture(endedOwner: Long, activeOwner: Long, endedGeneration: Long, activeGeneration: Long): Boolean =
        endedOwner == activeOwner && endedGeneration == activeGeneration

    fun mayRecover(streamingDesired: Boolean, capturePaused: Boolean): Boolean =
        streamingDesired && !capturePaused

    fun rearmRecovery(ownsRecovery: Boolean, streamingDesired: Boolean, capturePaused: Boolean, isRecording: Boolean): Boolean =
        ownsRecovery && mayRecover(streamingDesired, capturePaused) && !isRecording
}
