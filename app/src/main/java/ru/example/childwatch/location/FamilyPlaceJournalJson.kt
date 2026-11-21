package ru.example.childwatch.location

import org.json.JSONObject
import ru.childwatch.shared.family.FamilyPlaceJournalEvent
import ru.childwatch.shared.family.FamilyPlaceJournalPage

internal object FamilyPlaceJournalJson {
    fun parse(response: JSONObject, expectedDevice: String? = null): FamilyPlaceJournalPage {
        require(response.get("success") == true)
        val eventRows = response.getJSONArray("events")
        val events = (0 until eventRows.length()).map { index ->
            val row = eventRows.getJSONObject(index)
            if (expectedDevice != null) require(string(row, "device_id") == expectedDevice)
            FamilyPlaceJournalEvent(integer(row, "id"), string(row, "family_id"), string(row, "owner_member_id"),
                string(row, "target_member_id"), integer(row, "measured_at"), string(row, "transition"), string(row, "place_name"))
        }
        val hasMore = response.get("hasMore") as? Boolean ?: throw IllegalArgumentException("PLACE_HISTORY_BOOLEAN_INVALID")
        require(response.has("nextBefore"))
        val next = if (response.isNull("nextBefore")) null else integer(response, "nextBefore")
        val retention = integer(response, "retentionDays")
        require(retention in 0..Int.MAX_VALUE.toLong())
        return FamilyPlaceJournalPage(string(response, "familyId"), string(response, "ownerMemberId"),
            string(response, "targetMemberId"), events, integer(response, "snapshot"), next, hasMore, retention.toInt())
    }
    private fun integer(row: JSONObject, key: String): Long = when (val value = row.get(key)) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw IllegalArgumentException("PLACE_HISTORY_INTEGER_INVALID")
    }
    private fun string(row: JSONObject, key: String): String =
        row.get(key) as? String ?: throw IllegalArgumentException("PLACE_HISTORY_STRING_INVALID")
}
