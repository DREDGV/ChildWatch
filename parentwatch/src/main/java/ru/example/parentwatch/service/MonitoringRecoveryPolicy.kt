package ru.example.parentwatch.service

object MonitoringRecoveryPolicy {
    fun shouldRecover(desired: Boolean?, previouslyRunning: Boolean, legacyAutoStart: Boolean): Boolean =
        desired ?: (previouslyRunning || legacyAutoStart)
}
