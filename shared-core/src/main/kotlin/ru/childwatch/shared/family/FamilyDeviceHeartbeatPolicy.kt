package ru.childwatch.shared.family

/** A selected phone's server heartbeat; location capture/upload times are not presence. */
object FamilyDeviceHeartbeatPolicy {
    const val ONLINE_WINDOW_MS = 2 * 60_000L
    const val RECENT_WINDOW_MS = 24 * 60 * 60_000L
    private const val FUTURE_TOLERANCE_MS = 30_000L

    fun presence(lastSeenAt: Long?, canonical: Boolean, now: Long): FamilyPresenceState {
        if (!canonical || lastSeenAt == null || lastSeenAt <= 0 || now <= 0 ||
            lastSeenAt > now + FUTURE_TOLERANCE_MS) return FamilyPresenceState.UNKNOWN
        return when ((now - lastSeenAt).coerceAtLeast(0)) {
            in 0..ONLINE_WINDOW_MS -> FamilyPresenceState.ONLINE
            in 0..RECENT_WINDOW_MS -> FamilyPresenceState.RECENTLY_ACTIVE
            else -> FamilyPresenceState.OFFLINE
        }
    }
}
