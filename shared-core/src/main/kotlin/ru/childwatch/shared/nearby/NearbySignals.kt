package ru.childwatch.shared.nearby

import java.util.Collections
import java.util.UUID

enum class NearbySignalSource { WIFI, BLUETOOTH_CLASSIC, BLE }
enum class NearbySourceState {
    DISABLED, COOLDOWN, PERMISSION_REQUIRED, RADIO_OFF, LOCATION_OFF, UNSUPPORTED,
    SCAN_NOT_STARTED, SCANNING, READY, EMPTY, STALE, FAILED, CANCELLED
}

/** An observation, never a person identity or a position estimate. No hardware addresses leave the collector. */
data class NearbySignal(
    val id: String,
    val source: NearbySignalSource,
    val name: String?,
    val rssiDbm: Int,
    val observedAgeMs: Long?,
    val observedDuringScan: Boolean
)

data class NearbySourceReport(
    val source: NearbySignalSource,
    val state: NearbySourceState,
    val signals: List<NearbySignal> = emptyList(),
    val activeScanRequested: Boolean = false,
    val activeScanStarted: Boolean? = null,
    /** Stable diagnostic code, not raw exception text or a radio address. */
    val reason: String? = null
)

data class NearbySignalsSnapshot(
    val capturedAtEpochMs: Long,
    val capturedElapsedMs: Long,
    val reports: List<NearbySourceReport>,
    val cooldownRemainingMs: Long = 0,
    val screenOffDuringScan: Boolean = false
)

object NearbySignalsPolicy {
    const val SCAN_WINDOW_MS = 15_000L
    const val COOLDOWN_MS = 60_000L
    const val FRESHNESS_MS = 300_000L
    const val MAX_SIGNALS_PER_SOURCE = 100
    const val MAX_NAME_LENGTH = 128

    /** Unknown/future timestamps stay unknown; they must not become a newly seen device. */
    fun ageMs(nowElapsedMs: Long, observationElapsedMs: Long): Long? =
        if (nowElapsedMs >= 0 && observationElapsedMs > 0 && observationElapsedMs <= nowElapsedMs)
            nowElapsedMs - observationElapsedMs else null

    fun cooldownRemaining(nowElapsedMs: Long, lastElapsedMs: Long, sameBoot: Boolean): Long {
        if (!sameBoot || lastElapsedMs < 0 || nowElapsedMs < lastElapsedMs) return 0
        return (COOLDOWN_MS - (nowElapsedMs - lastElapsedMs)).coerceIn(0, COOLDOWN_MS)
    }

    fun cleanName(value: String?): String? {
        val cleaned = value?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            ?.trim()?.take(MAX_NAME_LENGTH) ?: return null
        val bounded = if (cleaned.lastOrNull()?.isHighSurrogate() == true) cleaned.dropLast(1) else cleaned
        return bounded.takeIf { it.isNotEmpty() && it != "<unknown ssid>" &&
            !it.matches(Regex("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}")) }
    }

    fun state(signals: List<NearbySignal>): NearbySourceState = when {
        signals.isEmpty() -> NearbySourceState.EMPTY
        signals.none { it.observedAgeMs != null && it.observedAgeMs <= FRESHNESS_MS } -> NearbySourceState.STALE
        else -> NearbySourceState.READY
    }
}

/** Bounded ephemeral deduplication. Keys are discarded on freeze, never exposed in the snapshot. */
class NearbySignalCollector(private val source: NearbySignalSource) {
    private data class Observation(val id: String, val name: String?, val rssi: Int, val measuredMs: Long)
    private val observations = LinkedHashMap<String, Observation>()
    private var frozen = false

    @Synchronized
    fun add(ephemeralKey: String, name: String?, rssi: Int, measuredElapsedMs: Long) {
        if (frozen || ephemeralKey.isBlank() || rssi !in -127..-1) return
        val previous = observations[ephemeralKey]
        if (previous == null && observations.size >= NearbySignalsPolicy.MAX_SIGNALS_PER_SOURCE) return
        if (previous != null && measuredElapsedMs < previous.measuredMs) return
        observations[ephemeralKey] = Observation(previous?.id ?: UUID.randomUUID().toString(),
            NearbySignalsPolicy.cleanName(name) ?: previous?.name, rssi, measuredElapsedMs)
    }

    @Synchronized
    fun freeze(nowElapsedMs: Long, scanStartedElapsedMs: Long): List<NearbySignal> {
        frozen = true
        val result = observations.values.map { observation ->
            val age = NearbySignalsPolicy.ageMs(nowElapsedMs, observation.measuredMs)
            NearbySignal(observation.id, source, observation.name, observation.rssi, age,
                age != null && observation.measuredMs >= scanStartedElapsedMs && age <= NearbySignalsPolicy.SCAN_WINDOW_MS)
        }.sortedWith(compareBy<NearbySignal> { it.observedAgeMs ?: Long.MAX_VALUE }.thenByDescending { it.rssiDbm })
        observations.clear()
        return Collections.unmodifiableList(result)
    }

    /** Cancellation/error must forget dedup keys without publishing partial observations. */
    @Synchronized
    fun discard() {
        frozen = true
        observations.clear()
    }
}
