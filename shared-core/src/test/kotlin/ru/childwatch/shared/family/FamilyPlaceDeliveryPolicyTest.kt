package ru.childwatch.shared.family

import org.junit.Assert.*
import org.junit.Test

class FamilyPlaceDeliveryPolicyTest {
    private val now = 1_800_000_000_000L
    private fun event(id: Long, age: Long = 0) = FamilyPlaceDeliveryEvent(
        id, "family", "owner", "device", "watch", if (id % 2L == 0L) "EXIT" else "ENTER",
        now - age, "Ребёнок", "Школа")
    private fun plan(after: Long, cursor: Long, events: List<FamilyPlaceDeliveryEvent>) =
        FamilyPlaceDeliveryPolicy.plan(after, cursor, events, "family", "owner", now)
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Invalid page accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `old arrival and exit are one silent history and fresh event stays separate`() {
        val plan = plan(0, 3, listOf(event(1, 3_600_000), event(2, 600_000), event(3)))
        assertEquals(listOf(true, false), plan.batches.map { it.historical })
        assertEquals(listOf(1L, 2L), plan.batches.first().events.map { it.id })
        assertEquals(3L, plan.cursor)
    }
    @Test fun `history chunks are bounded and preserve every event`() {
        val events = (1L..45L).map { event(it, 600_000) }
        val plan = plan(0, 50, events)
        assertEquals(listOf(20, 20, 5), plan.batches.map { it.events.size })
        assertEquals(events, plan.batches.flatMap { it.events })
    }
    @Test fun `page gaps and empty filtered scan preserve monotonic continuation`() {
        val first = plan(10, 100, listOf(event(20), event(75)))
        assertEquals(100L, first.cursor)
        assertTrue(plan(first.cursor, 200, emptyList()).batches.isEmpty())
        assertEquals(200L, plan(100, 200, emptyList()).cursor)
        assertEquals(200L, plan(200, 200, emptyList()).cursor)
        invalid { plan(100, 99, emptyList()) }
        invalid { plan(100, 100, listOf(event(101))) }
    }
    @Test fun `duplicates reversed order and cursor outside response invalidate whole page`() {
        invalid { plan(0, 5, listOf(event(2), event(2))) }
        invalid { plan(0, 5, listOf(event(3), event(2))) }
        invalid { plan(2, 5, listOf(event(2))) }
        invalid { plan(0, 1, listOf(event(2))) }
        invalid { plan(-1, 1, emptyList()) }
    }
    @Test fun `wrong owner family transition or clock cannot become notification`() {
        invalid { plan(0, 1, listOf(event(1).copy(ownerMemberId = "another"))) }
        invalid { plan(0, 1, listOf(event(1).copy(familyId = "another"))) }
        invalid { plan(0, 1, listOf(event(1).copy(transition = "UNKNOWN"))) }
        invalid { plan(0, 1, listOf(event(1).copy(measuredAt = now + 30_001))) }
        invalid { plan(0, 1, listOf(event(1).copy(measuredAt = 0))) }
    }
    @Test fun `measurement clocks may differ between phones without sorting cursor by time`() {
        val events = listOf(event(1), event(2, 3_600_000), event(3, 300_000), event(4, 300_001))
        val plan = plan(0, 4, events)
        assertEquals(listOf(false, true, false, true), plan.batches.map { it.historical })
        assertEquals(events, plan.batches.flatMap { it.events })
    }
}
