package ru.example.childwatch.location

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.example.childwatch.R
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.ParentLocationData
import kotlin.math.max

/** A compact location status shared by the main and listening screens. */
class FamilyLocationSummary(private val context: Context, private val network: NetworkClient) {
    data class Observation(val text: String, val capturedAt: Long?)

    suspend fun forPerson(personDeviceId: String, ownDeviceId: String): String =
        observationForPerson(personDeviceId, ownDeviceId).text

    suspend fun observationForPerson(personDeviceId: String, ownDeviceId: String): Observation = coroutineScope {
        if (personDeviceId == ownDeviceId && ownDeviceId.isNotBlank())
            return@coroutineScope Observation(context.getString(ru.example.childwatch.designsystem.R.string.cw_distance_self), null)
        val resolver = ru.example.childwatch.profile.ParentEffectiveContextResolver(context)
        val server = resolver.resolveServerUrl()
        val family = resolver.resolveFamilyId()
        val personRequest = async { network.getLatestLocation(personDeviceId) }
        val ownRequest = async {
            ownDeviceId.takeIf { it.isNotBlank() && it != personDeviceId }
                ?.let { network.getLatestParentLocation(it) }
        }
        val person = personRequest.await()
        val own = ownRequest.await()
        if (server != resolver.resolveServerUrl() || family != resolver.resolveFamilyId() ||
            ownDeviceId != resolver.resolveOwnParentId())
            Observation(context.getString(ru.example.childwatch.designsystem.R.string.cw_distance_loading), null)
        else Observation(format(person, own, System.currentTimeMillis()),
            person?.takeIf(::valid)?.timestamp?.let(ru.childwatch.shared.diagnostics.DeviceConnectionPolicy::epoch))
    }

    private fun format(person: ParentLocationData?, own: ParentLocationData?, now: Long): String {
        if (person == null || !valid(person)) return ru.example.childwatch.designsystem.FamilyDistance.text(context, null, null, false)
        val personAge = ageMillis(person.timestamp, now)
            ?: return ru.example.childwatch.designsystem.FamilyDistance.text(context, point(person), own?.let(::point), false)
        val age = when {
            personAge < 60_000L -> context.getString(R.string.family_location_just_now)
            personAge < 3_600_000L -> context.getString(R.string.family_location_minutes_ago, personAge / 60_000L)
            else -> context.getString(R.string.family_location_hours_ago, personAge / 3_600_000L)
        }
        val accuracy = person.accuracy.takeIf { it.isFinite() && it > 0f }
        val accuracyText = accuracy?.let {
            context.getString(R.string.family_location_accuracy, max(1, max(it, own?.accuracy?.takeIf { value -> value.isFinite() && value > 0f } ?: it).toInt()))
        }
        val distance = ru.example.childwatch.designsystem.FamilyDistance.text(context,
            point(person), own?.let(::point), false)
        return listOfNotNull(distance, age, accuracyText).joinToString(" · ")
    }

    private fun point(location: ParentLocationData) = ru.example.childwatch.designsystem.FamilyDistance.Point(
        location.latitude, location.longitude, location.timestamp, location.accuracy)

    private fun valid(point: ParentLocationData): Boolean =
        point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

    private fun ageMillis(timestamp: Long, now: Long): Long? {
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
        return (now - millis).takeIf { it >= -60_000L && it < 365L * 24 * 60 * 60 * 1000 }
            ?.coerceAtLeast(0L)
    }

}
