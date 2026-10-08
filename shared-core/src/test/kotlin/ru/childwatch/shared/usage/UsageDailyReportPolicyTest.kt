package ru.childwatch.shared.usage

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageDailyReportPolicyTest {
    private val policy = UsageDailyReportPolicy
    private fun midnight(date: String, zone: String = "Asia/Novosibirsk") =
        LocalDate.parse(date).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
    private fun candidate(start: Long, end: Long, zone: String = "Asia/Novosibirsk", duration: Any? = 60_000L,
                          collected: Any? = end, history: Boolean = false): UsageDailyCandidate =
        UsageDailyCandidate(mapOf("appUsageCollectedAt" to collected, "dailyUsage" to mapOf(
            "start" to start, "end" to end, "timeZone" to zone, "available" to true,
            "apps" to listOf(mapOf("packageName" to "app", "totalTimeInForeground" to duration))
        )), history)
    private fun changeDaily(value: UsageDailyCandidate, extra: Map<String, Any?>) = value.copy(raw = value.raw +
        ("dailyUsage" to ((value.raw["dailyUsage"] as Map<*, *>).entries.associate { it.key as String to it.value } + extra)))

    @Test fun duplicatePackageRowsAreRejectedInsteadOfInflatingDailyTotals() {
        val start = midnight("2026-10-08")
        val value = candidate(start, start + 60_000)
        val duplicate = changeDaily(value, mapOf("apps" to listOf(
            mapOf("packageName" to "app", "totalTimeInForeground" to 20_000L),
            mapOf("packageName" to " app ", "totalTimeInForeground" to 30_000L)
        )))
        assertTrue(policy.snapshots(listOf(duplicate), start + 60_000).isEmpty())
        val distinct = changeDaily(value, mapOf("apps" to listOf(
            mapOf("packageName" to "app", "totalTimeInForeground" to 20_000L),
            mapOf("packageName" to "other", "totalTimeInForeground" to 30_000L)
        )))
        assertEquals(1, policy.snapshots(listOf(distinct), start + 60_000).size)
    }

    @Test fun midnightSeparatesDaysAndUsesChildDateRatherThanUploadOrParentTime() {
        val today = midnight("2026-10-08")
        val yesterday = midnight("2026-10-07")
        val values = policy.snapshots(listOf(candidate(yesterday, today, history = true), candidate(today, today + 60_000)), today + 120_000)
        assertEquals(listOf("2026-10-08|Asia/Novosibirsk", "2026-10-07|Asia/Novosibirsk"), values.map { it.dayKey })
        assertEquals(today, values[0].dayLabelDate)
        assertTrue(values[1].fromHistory)
    }

    @Test fun newestUsableDailyMeasurementReplacesEarlierCumulativeTotalWithoutAdding() {
        val start = midnight("2026-10-08")
        val old = candidate(start, start + 60_000, duration = 10_000L, history = true)
        val latest = candidate(start, start + 120_000, duration = 20_000L)
        val result = policy.snapshots(listOf(latest, old), start + 180_000).single()
        val daily = result.raw["dailyUsage"] as Map<*, *>
        assertEquals(20_000L, ((daily["apps"] as List<*>).single() as Map<*, *>)["totalTimeInForeground"])
        assertEquals(start + 120_000, result.collectedAt)
        assertFalse(result.fromHistory)
    }

    @Test fun emptyOrUnavailableNewerPayloadCannotEraseAnEarlierUsableDay() {
        val start = midnight("2026-10-08")
        val usable = candidate(start, start + 60_000, history = true)
        val empty = changeDaily(candidate(start, start + 120_000), mapOf("apps" to emptyList<Any>()))
        val unavailable = changeDaily(candidate(start, start + 180_000), mapOf("available" to false))
        val result = policy.snapshots(listOf(usable, empty, unavailable, UsageDailyCandidate(mapOf("dailyUsage" to emptyMap<String, Any>()), false)), start + 180_000)
        assertEquals(1, result.size)
        assertTrue(result.single().fromHistory)
        assertEquals(start + 60_000, result.single().collectedAt)
    }

    @Test fun explicitMissingDayNeverFallsBackToAnotherDate() {
        val start = midnight("2026-10-08")
        val values = policy.snapshots(listOf(candidate(start, start + 60_000)), start + 60_000)
        assertEquals(values.single(), policy.selectDay(values, null))
        assertNull(policy.selectDay(values, "2026-10-07|Asia/Novosibirsk"))
        assertNull(policy.selectDay(values, ""))
    }

    @Test fun validTimeZonesRemainDistinctAndUnknownZoneDoesNotSilentlyBecomeUtc() {
        val utc = midnight("2026-10-08", "UTC")
        val values = policy.snapshots(listOf(candidate(utc, utc + 60_000, "UTC"),
            candidate(utc, utc + 60_000, "Asia/Novosibirsk")), utc + 60_000)
        assertEquals(2, values.size)
        assertEquals(setOf("UTC", "Asia/Novosibirsk"), values.map { it.timeZoneId }.toSet())
        for (zone in listOf("Not/AZone", "", "+07:00", "GMT+07:00"))
            assertTrue(policy.snapshots(listOf(candidate(utc, utc + 60_000, zone)), utc + 60_000).isEmpty())
    }

    @Test fun fullDstDaysAllowTwentyThreeAndTwentyFiveHoursButRejectCrossDateWindows() {
        for (date in listOf("2026-03-29", "2026-10-25")) {
            val start = midnight(date, "Europe/Berlin")
            val end = LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
            val value = policy.snapshots(listOf(candidate(start, end, "Europe/Berlin")), end).single()
            assertEquals("$date|Europe/Berlin", value.dayKey)
            assertTrue(policy.snapshots(listOf(candidate(start, end + 1, "Europe/Berlin")), end + 1).isEmpty())
        }
    }

    @Test fun malformedFutureAndUnboundedTimesAreRejectedWithoutUploadTimeFallback() {
        val start = midnight("2026-10-08")
        val now = start + 120_000
        val valid = candidate(start, start + 60_000)
        val invalid = listOf(changeDaily(valid, mapOf("start" to 0L)), changeDaily(valid, mapOf("end" to start)),
            changeDaily(valid, mapOf("end" to Double.NaN)), changeDaily(valid, mapOf("end" to now + 60_001)),
            changeDaily(valid, mapOf("start" to "bad")), candidate(start, start + 27 * 60 * 60_000L),
            candidate(start, start + 60_000, collected = "bad"), candidate(start, start + 60_000, collected = 0L),
            candidate(start, start + 60_000, collected = now + 60_001), candidate(start, start + 60_000, collected = Double.POSITIVE_INFINITY))
        for (value in invalid) assertTrue(policy.snapshots(listOf(value), now).isEmpty())
        assertTrue(policy.snapshots(listOf(valid), 0).isEmpty())
    }

    @Test fun durationsMustBeFiniteSafeIntegersAndZeroRowsDoNotProveZeroUse() {
        val start = midnight("2026-10-08")
        for (duration in listOf(null, "10", -1L, 0L, 1.5, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE))
            assertTrue(policy.snapshots(listOf(candidate(start, start + 60_000, duration = duration)), start + 60_000).isEmpty())
        val accepted = policy.snapshots(listOf(candidate(start, start + 60_000, duration = 10.0)), start + 60_000).single()
        assertEquals(10L, ((((accepted.raw["dailyUsage"] as Map<*, *>)["apps"] as List<*>).single()) as Map<*, *>)["totalTimeInForeground"])
        val overflow = changeDaily(candidate(start, start + 60_000), mapOf("apps" to listOf(
            mapOf("totalTimeInForeground" to 9_007_199_254_740_991L), mapOf("totalTimeInForeground" to 1L))))
        assertTrue(policy.snapshots(listOf(overflow), start + 60_000).isEmpty())
        assertTrue(policy.snapshots(listOf(candidate(start, start + 60_000, duration = 60_001L)), start + 60_000).isEmpty())
        val overlapping = changeDaily(candidate(start, start + 60_000), mapOf("apps" to listOf(
            mapOf("totalTimeInForeground" to 60_000L), mapOf("totalTimeInForeground" to 60_000L))))
        assertEquals(1, policy.snapshots(listOf(overlapping), start + 60_000).size)
    }

    @Test fun collectionFallbackIsOnlyWindowEndAndDoesNotPopulateMissingSourceField() {
        val start = midnight("2026-10-08")
        val input = candidate(start, start + 60_000).let { it.copy(raw = it.raw - "appUsageCollectedAt") }
        val value = policy.snapshots(listOf(input), start + 120_000).single()
        assertEquals(start + 60_000, value.collectedAt)
        assertFalse(value.raw.containsKey("appUsageCollectedAt"))
    }

    @Test fun tiePrefersCurrentStatusAndPartialLegacyWindowUsesCalendarDayLabel() {
        val start = midnight("2026-10-08")
        val current = candidate(start + 30_000, start + 60_000, duration = 10_000L)
        val result = policy.snapshots(listOf(current.copy(fromHistory = true), current), start + 120_000).single()
        assertFalse(result.fromHistory)
        assertEquals(start, result.dayLabelDate)
    }
}
