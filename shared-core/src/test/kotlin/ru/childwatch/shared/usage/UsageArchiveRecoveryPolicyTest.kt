package ru.childwatch.shared.usage

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageArchiveRecoveryPolicyTest {
    private val policy = UsageArchiveRecoveryPolicy
    private fun start(day: String, zone: String) = LocalDate.parse(day).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun archiveTransmissionSkipsGpsAndRetriesWithoutAckButNewMeasurementCanSendImmediately() {
        val now = 1_000_000L
        assertFalse(policy.shouldTransmit(true, false, now, 0, now))
        assertFalse(policy.shouldTransmit(false, true, now, 0, now))
        assertFalse(policy.shouldTransmit(true, true, 0, 0, 0))
        assertTrue(policy.shouldTransmit(true, true, now, 0, now))
        assertFalse(policy.shouldTransmit(true, true, now + policy.TRANSMISSION_RETRY_MS - 1, now, now))
        assertTrue(policy.shouldTransmit(true, true, now + policy.TRANSMISSION_RETRY_MS, now, now))
        assertTrue(policy.shouldTransmit(true, true, now + 1, now, now + 1))
        assertFalse(policy.shouldTransmit(true, true, now + 1, now, now + 60_000))
        assertTrue(policy.shouldTransmit(true, true, now - 1, now, now - 2))
        // Restart restores the last attempt; it does not create another immediate upload.
        assertFalse(policy.shouldTransmit(true, true, now + 60_000, now, now - 1))
    }

    @Test fun restartKeepsOnlyCurrentOwnersFloorAndReturningOwnerStartsNewActivation() {
        val first = policy.activation("owner-a", null, 0, 100)
        assertEquals(UsageArchiveActivation(100, false), first)
        assertEquals(UsageArchiveActivation(100, true), policy.activation("owner-a", "owner-a", 100, 200))
        assertEquals(UsageArchiveActivation(200, false), policy.activation("owner-b", "owner-a", 100, 200))
        assertEquals(UsageArchiveActivation(300, false), policy.activation("owner-a", "owner-b", 200, 300))
        assertEquals(UsageArchiveActivation(100, false), policy.activation("owner-a", "owner-a", 200, 100))
        assertEquals(UsageArchiveActivation(100, false), policy.activation("owner-a", "owner-a", 0, 100))
    }

    @Test fun exactlySevenPreviousClosedChildDaysNeverIncludesToday() {
        val now = start("2026-10-08", "Asia/Novosibirsk") + 60_000
        val days = policy.previousDays(now, "Asia/Novosibirsk")
        assertEquals(7, days.size)
        assertEquals(start("2026-10-07", "Asia/Novosibirsk"), days.first().start)
        assertEquals(start("2026-10-08", "Asia/Novosibirsk"), days.first().end)
        assertEquals(start("2026-10-01", "Asia/Novosibirsk"), days.last().start)
        assertTrue(days.all { it.end <= now })
    }

    @Test fun dstWindowsUseCalendarBoundariesRatherThanSubtractingTwentyFourHours() {
        val autumn = policy.previousDays(start("2026-10-26", "Europe/Berlin"), "Europe/Berlin").first()
        assertEquals(25 * 60 * 60_000L, autumn.end - autumn.start)
        val spring = policy.previousDays(start("2026-03-30", "Europe/Berlin"), "Europe/Berlin").first()
        assertEquals(23 * 60 * 60_000L, spring.end - spring.start)
    }

    @Test fun collectionThrottlesForSixHoursAndDayOrTimezoneChangeInvalidatesSchedule() {
        val now = start("2026-10-08", "UTC") + 12 * 60 * 60_000L
        val key = policy.dayKey(now, "UTC")
        assertFalse(policy.shouldRecover(now + 60_000, "UTC", now, key))
        assertFalse(policy.shouldRecover(now + policy.RETRY_INTERVAL_MS - 1, "UTC", now, key))
        assertTrue(policy.shouldRecover(now + policy.RETRY_INTERVAL_MS, "UTC", now, key))
        assertTrue(policy.shouldRecover(now + 60_000, "Asia/Novosibirsk", now, key))
        assertTrue(policy.shouldRecover(now + 24 * 60 * 60_000L, "UTC", now, key))
        assertTrue(policy.shouldRecover(now - 1, "UTC", now, key))
    }

    @Test fun ownershipFloorExcludesEarlierDaysAndInvalidClockOrZoneNeverProducesWindows() {
        val now = start("2026-10-08", "UTC")
        val floor = start("2026-10-06", "UTC") + 1
        assertEquals(listOf(start("2026-10-07", "UTC")), policy.previousDays(now, "UTC", floor).map { it.start })
        assertTrue(policy.previousDays(0, "UTC").isEmpty())
        assertTrue(policy.previousDays(now, "Not/AZone").isEmpty())
        assertFalse(policy.shouldRecover(now, "Not/AZone", 0, null))
    }
}
