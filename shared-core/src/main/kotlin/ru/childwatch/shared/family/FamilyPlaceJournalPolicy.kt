package ru.childwatch.shared.family

data class FamilyPlaceJournalEvent(
    val id: Long, val familyId: String, val ownerMemberId: String, val targetMemberId: String,
    val measuredAt: Long, val transition: String, val placeName: String
)

data class FamilyPlaceJournalPage(
    val familyId: String, val ownerMemberId: String, val targetMemberId: String,
    val events: List<FamilyPlaceJournalEvent>, val snapshot: Long, val nextBefore: Long?,
    val hasMore: Boolean, val retentionDays: Int
)

/** Journal pagination never reads or writes notification delivery cursors. Validate before rendering. */
object FamilyPlaceJournalPolicy {
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L

    fun validatePage(page: FamilyPlaceJournalPage, familyId: String, ownerMemberId: String,
        targetMemberId: String, before: Long?, snapshot: Long?, now: Long): FamilyPlaceJournalPage {
        require(listOf(familyId, ownerMemberId, targetMemberId).all { it.isNotBlank() })
        require(page.familyId == familyId && page.ownerMemberId == ownerMemberId && page.targetMemberId == targetMemberId)
        require(now > 0 && now <= Long.MAX_VALUE - 30_000L)
        require(page.snapshot in 0..MAX_SAFE_ID)
        require(snapshot == null || snapshot == page.snapshot)
        require(before == null || (snapshot != null && before in 1..MAX_SAFE_ID && before <= page.snapshot))
        require(page.retentionDays == 30 && page.events.size <= 50)
        var previous = before ?: (page.snapshot + 1)
        page.events.forEach { event ->
            require(event.id in 1..page.snapshot && event.id < previous) { "PLACE_JOURNAL_ORDER_INVALID" }
            require(event.familyId == familyId && event.ownerMemberId == ownerMemberId && event.targetMemberId == targetMemberId)
            require(event.transition == "ENTER" || event.transition == "EXIT")
            require(event.measuredAt > 0 && event.measuredAt <= now + 30_000L)
            require(event.placeName.isNotBlank())
            previous = event.id
        }
        if (page.hasMore) require(page.events.isNotEmpty() && page.nextBefore == page.events.last().id)
        else require(page.nextBefore == null)
        return page
    }
}
