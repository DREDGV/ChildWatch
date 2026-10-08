package ru.childwatch.shared.family

data class FamilyPlaceDeliveryEvent(
    val id: Long, val familyId: String, val ownerMemberId: String,
    val deviceId: String, val watchId: String, val transition: String,
    val measuredAt: Long, val personName: String, val placeName: String
)

data class FamilyPlaceDeliveryBatch(val historical: Boolean, val events: List<FamilyPlaceDeliveryEvent>)
data class FamilyPlaceDeliveryPlan(val batches: List<FamilyPlaceDeliveryBatch>, val cursor: Long)

/** Validate the complete page before notification or cursor writes. IDs are ordered, clocks need not be. */
object FamilyPlaceDeliveryPolicy {
    const val FRESH_WINDOW_MS = 5 * 60_000L
    const val HISTORY_BATCH_SIZE = 20
    private const val MAX_SAFE_CURSOR = 9_007_199_254_740_991L

    fun plan(after: Long, cursor: Long, events: List<FamilyPlaceDeliveryEvent>,
        familyId: String, ownerMemberId: String, now: Long): FamilyPlaceDeliveryPlan {
        require(familyId.isNotBlank() && ownerMemberId.isNotBlank() && now > 0)
        require(after in 0..MAX_SAFE_CURSOR && cursor in after..MAX_SAFE_CURSOR)
        require(events.size <= 100)
        var previous = after
        events.forEach { event ->
            require(event.id > previous && event.id <= cursor) { "PLACE_EVENT_ORDER_INVALID" }
            require(event.familyId == familyId && event.ownerMemberId == ownerMemberId) { "PLACE_EVENT_SCOPE_INVALID" }
            require(event.deviceId.isNotBlank() && event.watchId.isNotBlank())
            require(event.transition == "ENTER" || event.transition == "EXIT")
            require(event.measuredAt > 0 && event.measuredAt <= now + 30_000L) { "PLACE_EVENT_CLOCK_INVALID" }
            require(event.personName.isNotBlank() && event.placeName.isNotBlank())
            previous = event.id
        }
        val batches = mutableListOf<FamilyPlaceDeliveryBatch>()
        val historical = mutableListOf<FamilyPlaceDeliveryEvent>()
        fun flushHistory() {
            if (historical.isNotEmpty()) {
                batches += FamilyPlaceDeliveryBatch(true, historical.toList())
                historical.clear()
            }
        }
        events.forEach { event ->
            if ((now - event.measuredAt).coerceAtLeast(0) <= FRESH_WINDOW_MS) {
                flushHistory()
                batches += FamilyPlaceDeliveryBatch(false, listOf(event))
            } else {
                historical += event
                if (historical.size == HISTORY_BATCH_SIZE) flushHistory()
            }
        }
        flushHistory()
        // Empty visible pages can advance: the server scanned rows hidden by revoked/paused watches.
        return FamilyPlaceDeliveryPlan(batches, cursor)
    }
}
