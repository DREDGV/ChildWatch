package ru.childwatch.shared.usage

import java.time.Instant
import java.time.ZoneId

data class UsageArchiveWindow(val start: Long, val end: Long, val timeZoneId: String)
data class UsageArchiveActivation(val startedAt: Long, val continuing: Boolean)

/** Calendar windows and scheduling only; a closed window does not prove complete event coverage. */
object UsageArchiveRecoveryPolicy {
    const val DAYS = 7
    const val RETRY_INTERVAL_MS = 6 * 60 * 60_000L
    const val TRANSMISSION_RETRY_MS = 30 * 60_000L

    /** Retry without an acknowledgement, but never attach the archive to GPS-only uploads. */
    fun shouldTransmit(historyPresent: Boolean, includeCurrentApp: Boolean, now: Long,
                       lastAttachedAt: Long, latestCollectedAt: Long): Boolean {
        if (!historyPresent || !includeCurrentApp || now <= 0) return false
        return lastAttachedAt <= 0 || now < lastAttachedAt || (latestCollectedAt > lastAttachedAt && latestCollectedAt <= now) ||
            now - lastAttachedAt >= TRANSMISSION_RETRY_MS
    }
    /** Only the immediately active identity may retain its floor; former owners start anew. */
    fun activation(scope: String, previousScope: String?, previousSince: Long, now: Long): UsageArchiveActivation {
        val continuing = scope.isNotBlank() && scope == previousScope && previousSince > 0 && previousSince <= now
        return UsageArchiveActivation(if (continuing) previousSince else now, continuing)
    }
    private fun zone(name: String): ZoneId? = name.takeIf { it in ZoneId.getAvailableZoneIds() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    fun dayKey(now: Long, timeZoneId: String): String? {
        val zone = zone(timeZoneId) ?: return null
        if (now <= 0) return null
        return "${Instant.ofEpochMilli(now).atZone(zone).toLocalDate()}|${zone.id}"
    }

    fun shouldRecover(now: Long, timeZoneId: String, attemptedAt: Long, attemptedDayKey: String?): Boolean {
        val key = dayKey(now, timeZoneId) ?: return false
        return key != attemptedDayKey || attemptedAt <= 0 || now < attemptedAt || now - attemptedAt >= RETRY_INTERVAL_MS
    }

    fun previousDays(now: Long, timeZoneId: String, notBefore: Long = 0): List<UsageArchiveWindow> {
        val zone = zone(timeZoneId) ?: return emptyList()
        if (now <= 0) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (1..DAYS).mapNotNull { daysAgo ->
            val day = today.minusDays(daysAgo.toLong())
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (start <= 0 || start < notBefore || end > now || end <= start || end - start > 26 * 60 * 60_000L) null
            else UsageArchiveWindow(start, end, zone.id)
        }
    }
}
