package ru.childwatch.shared.usage

import java.time.Instant
import java.time.ZoneId

data class UsageDailyCandidate(val raw: Map<String, Any?>, val fromHistory: Boolean)

data class UsageDailySnapshot(
    val raw: Map<String, Any?>,
    val collectedAt: Long?,
    val fromHistory: Boolean,
    val dayKey: String,
    val dayLabelDate: Long,
    val timeZoneId: String
)

/** Selects usable cumulative measurements; never adds separate measurements of the same day. */
object UsageDailyReportPolicy {
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val MAX_WINDOW_MS = 26 * 60 * 60_000L
    private const val FUTURE_TOLERANCE_MS = 60_000L
    private val namedZones = ZoneId.getAvailableZoneIds()

    fun snapshots(candidates: List<UsageDailyCandidate>, now: Long): List<UsageDailySnapshot> {
        if (now <= 0 || now > MAX_SAFE_INTEGER) return emptyList()
        val grouped = mutableMapOf<String, UsageDailySnapshot>()
        candidates.forEach { candidate ->
            val snapshot = normalize(candidate, now) ?: return@forEach
            val previous = grouped[snapshot.dayKey]
            if (previous == null || newer(snapshot, previous)) grouped[snapshot.dayKey] = snapshot
        }
        return grouped.values.sortedWith(compareByDescending<UsageDailySnapshot> { it.dayLabelDate }
            .thenByDescending { it.collectedAt ?: 0L }.thenBy { it.dayKey })
    }

    /** An explicit unavailable day must not silently display another day's activity. */
    fun selectDay(snapshots: List<UsageDailySnapshot>, selectedDayKey: String?): UsageDailySnapshot? =
        if (selectedDayKey == null) snapshots.firstOrNull() else snapshots.firstOrNull { it.dayKey == selectedDayKey }

    private fun newer(next: UsageDailySnapshot, previous: UsageDailySnapshot): Boolean {
        val nextAt = next.collectedAt ?: 0L
        val previousAt = previous.collectedAt ?: 0L
        if (nextAt != previousAt) return nextAt > previousAt
        val nextEnd = (next.raw["dailyUsage"] as Map<*, *>)["end"] as Long
        val previousEnd = (previous.raw["dailyUsage"] as Map<*, *>)["end"] as Long
        if (nextEnd != previousEnd) return nextEnd > previousEnd
        return previous.fromHistory && !next.fromHistory
    }

    private fun normalize(candidate: UsageDailyCandidate, now: Long): UsageDailySnapshot? {
        val daily = candidate.raw["dailyUsage"] as? Map<*, *> ?: return null
        if (daily.isEmpty() || daily["available"] == false) return null
        if (daily.containsKey("available") && daily["available"] !is Boolean) return null
        val start = integer(daily["start"])?.takeIf { it > 0 } ?: return null
        val end = integer(daily["end"])?.takeIf { it > start } ?: return null
        if (end - start > MAX_WINDOW_MS || start > now || (end > now && end - now > FUTURE_TOLERANCE_MS)) return null
        val zoneName = (daily["timeZone"] as? String)?.trim()?.takeIf { it in namedZones } ?: return null
        val zone = runCatching { ZoneId.of(zoneName) }.getOrNull() ?: return null
        val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        // Exact next midnight is a complete day, including a 25-hour DST day.
        if (Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate() != date) return null
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        if (dayStart <= 0) return null

        val rows = daily["apps"] as? List<*> ?: return null
        val normalizedRows = mutableListOf<Map<String, Any?>>()
        val packages = mutableSetOf<String>()
        var total = 0L
        rows.forEach { item ->
            val row = stringMap(item as? Map<*, *> ?: return null) ?: return null
            val packageName = (row["packageName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (packageName != null && !packages.add(packageName)) return null
            val duration = integer(row["totalTimeInForeground"]) ?: return null
            if (duration < 0 || duration > end - start) return null
            if (duration == 0L) return@forEach
            if (total > MAX_SAFE_INTEGER - duration) return null
            total += duration
            normalizedRows += row + ("totalTimeInForeground" to duration)
        }
        // The child currently drops empty snapshots. An empty array cannot prove zero use.
        if (normalizedRows.isEmpty()) return null

        val explicitCollection = candidate.raw["appUsageCollectedAt"]
        val collected = if (explicitCollection == null) end else integer(explicitCollection)?.takeIf { it > 0 } ?: return null
        if (collected > now && collected - now > FUTURE_TOLERANCE_MS) return null
        val normalizedDaily = stringMap(daily) ?: return null
        val raw = candidate.raw + ("dailyUsage" to (normalizedDaily + mapOf(
            "start" to start, "end" to end, "timeZone" to zone.id, "apps" to normalizedRows.toList()
        )))
        return UsageDailySnapshot(raw, collected, candidate.fromHistory,
            "$date|${zone.id}", dayStart, zone.id)
    }

    private fun stringMap(value: Map<*, *>): Map<String, Any?>? {
        if (value.keys.any { it !is String }) return null
        return value.entries.associate { (key, item) -> key as String to item }
    }

    private fun integer(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }
        is Float, is Double -> (value as Number).toDouble().takeIf {
            it.isFinite() && it >= -MAX_SAFE_INTEGER.toDouble() && it <= MAX_SAFE_INTEGER.toDouble() && it % 1.0 == 0.0
        }?.toLong()
        else -> null
    }
}
