package ru.childwatch.shared.diagnostics

/** Evidence age is independent for heartbeat, telemetry, location and prerequisites. */
object DeviceConnectionPolicy {
    enum class Freshness { UNKNOWN, FRESH, STALE, CLOCK_MISMATCH }
    enum class Microphone { UNKNOWN, STALE, PERMISSION_MISSING, MUTED, PREREQUISITES_ONLY }
    const val TELEMETRY_FRESH_MS = 180_000L
    const val LOCATION_FRESH_MS = 120_000L
    const val FUTURE_TOLERANCE_MS = 30_000L

    fun epoch(value: Number?): Long? {
        val raw = value?.toDouble() ?: return null
        if (!raw.isFinite() || raw <= 0 || raw >= Long.MAX_VALUE.toDouble()) return null
        val at = raw.toLong()
        return if (at < 10_000_000_000L) at * 1000 else at
    }

    fun freshness(at: Long?, now: Long, freshMs: Long = TELEMETRY_FRESH_MS): Freshness = when {
        at == null || at <= 0 || now <= 0 -> Freshness.UNKNOWN
        at > now && at - now > FUTURE_TOLERANCE_MS -> Freshness.CLOCK_MISMATCH
        now - at <= freshMs -> Freshness.FRESH
        else -> Freshness.STALE
    }

    fun microphone(at: Long?, granted: Boolean?, muted: Boolean?, now: Long): Microphone =
        when (freshness(at, now)) {
            Freshness.STALE -> Microphone.STALE
            Freshness.UNKNOWN, Freshness.CLOCK_MISMATCH -> Microphone.UNKNOWN
            Freshness.FRESH -> when {
                granted == false -> Microphone.PERMISSION_MISSING
                muted == true -> Microphone.MUTED
                granted == null -> Microphone.UNKNOWN
                else -> Microphone.PREREQUISITES_ONLY
            }
        }
}
