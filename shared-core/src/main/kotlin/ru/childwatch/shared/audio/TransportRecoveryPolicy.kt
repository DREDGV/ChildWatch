package ru.childwatch.shared.audio

/** Monotonic time only. Socket flags alone are never evidence of server liveness. */
class TransportRecoveryPolicy(private val startedAt: Long) {
    private var waitingSince = startedAt
    private var nextAttemptAt = startedAt
    private var attempts = 0
    private var acceptedPong = 0L

    fun shouldRecover(now: Long, requested: Boolean, usableNetwork: Boolean, ready: Boolean, pongAt: Long): Boolean {
        if (!requested || !usableNetwork) return false
        if (ready && pongAt > acceptedPong && pongAt >= waitingSince) {
            acceptedPong = pongAt
            attempts = 0
            waitingSince = pongAt
        }
        val deadline = if (ready) 60_000L else 30_000L
        if (now - waitingSince < deadline || now < nextAttemptAt) return false
        attempts++
        waitingSince = now
        nextAttemptAt = now + (15_000L shl (attempts - 1).coerceAtMost(3))
        return true
    }
}
