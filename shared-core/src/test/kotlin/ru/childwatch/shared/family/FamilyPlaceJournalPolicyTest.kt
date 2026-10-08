package ru.childwatch.shared.family

import org.junit.Assert.*
import org.junit.Test

class FamilyPlaceJournalPolicyTest {
    private val now = 1_800_000_000_000L
    private fun row(id: Long) = FamilyPlaceJournalEvent(id, "f", "a", "c", now - id * 1000,
        if (id % 2 == 0L) "EXIT" else "ENTER", "Школа")
    private fun page(vararg ids: Long, snapshot: Long = 10, more: Boolean = false) =
        FamilyPlaceJournalPage("f", "a", "c", ids.map(::row), snapshot, if (more) ids.last() else null, more, 30)
    private fun validate(page: FamilyPlaceJournalPage, before: Long? = null, snapshot: Long? = null) =
        FamilyPlaceJournalPolicy.validatePage(page, "f", "a", "c", before, snapshot, now)
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Malformed history accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `stable descending pages remain separate from delivery cursors`() {
        val first = validate(page(10, 8, 7, more = true))
        val second = validate(page(6, 2), first.nextBefore, first.snapshot)
        assertEquals(listOf(10L, 8L, 7L, 6L, 2L), (first.events + second.events).map { it.id })
        assertFalse(second.hasMore)
    }
    @Test fun `empty history and exhausted snapshot are valid`() {
        assertTrue(validate(page(snapshot = 0)).events.isEmpty())
        assertTrue(validate(page(snapshot = 10), before = 2, snapshot = 10).events.isEmpty())
    }
    @Test fun `duplicates overlap ascending order and wrong anchors are rejected`() {
        invalid { validate(page(8, 8)) }
        invalid { validate(page(7, 8)) }
        invalid { validate(page(8), before = 8, snapshot = 10) }
        invalid { validate(page(11)) }
        invalid { validate(page(8), snapshot = 9) }
        invalid { validate(page(8), before = 9) }
    }
    @Test fun `family owner target and every row are validated before display`() {
        val good = page(8, 7)
        invalid { validate(good.copy(targetMemberId = "other")) }
        invalid { validate(good.copy(ownerMemberId = "other")) }
        invalid { validate(good.copy(familyId = "other")) }
        for (bad in listOf(row(7).copy(targetMemberId = "other"), row(7).copy(ownerMemberId = "other"),
            row(7).copy(familyId = "other"), row(7).copy(transition = "UNKNOWN"), row(7).copy(placeName = " "),
            row(7).copy(measuredAt = 0), row(7).copy(measuredAt = now + 30_001))) {
            invalid { validate(good.copy(events = listOf(row(8), bad))) }
        }
    }
    @Test fun `pagination must progress and terminate explicitly`() {
        invalid { validate(page(8, 7, more = true).copy(nextBefore = 8)) }
        invalid { validate(page(8).copy(hasMore = true)) }
        invalid { validate(page().copy(hasMore = true, nextBefore = 1)) }
        invalid { validate(page(8).copy(nextBefore = 8)) }
        invalid { validate(page(8).copy(retentionDays = 7)) }
    }
}
