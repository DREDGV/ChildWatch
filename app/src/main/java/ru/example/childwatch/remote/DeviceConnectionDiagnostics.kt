package ru.example.childwatch.remote

import android.content.Context
import ru.childwatch.shared.diagnostics.DeviceConnectionPolicy as Policy
import ru.example.childwatch.R
import ru.example.childwatch.network.DeviceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Describes independent observations, never an inferred all-functions-online state. */
object DeviceConnectionDiagnostics {
    private fun time(at: Long) = SimpleDateFormat("dd.MM · HH:mm:ss", Locale.getDefault()).format(Date(at))
    private fun at(value: Any?) = Policy.epoch(value as? Number)
    private fun stamp(context: Context, label: Int, at: Long?) = at?.let { context.getString(label, time(it)) }
    private fun section(context: Context, title: Int, state: Int, detail: String, at: Long?,
                        label: Int = R.string.connection_snapshot_at): CharSequence =
        DeviceFeatureDiagnostics.section(context, title, state, detail, stamp(context, label, at), false)

    fun connection(context: Context, status: DeviceStatus?, parentReady: Boolean,
                   heartbeatAt: Long?, canonical: Boolean, failed: Boolean, cached: Boolean,
                   now: Long = System.currentTimeMillis()): CharSequence {
        val heartbeat = if (canonical) Policy.freshness(heartbeatAt, now, 120_000) else Policy.Freshness.UNKNOWN
        val heartbeatLabel = when (heartbeat) {
            Policy.Freshness.FRESH -> R.string.connection_heartbeat_recent
            Policy.Freshness.STALE -> R.string.connection_heartbeat_old
            else -> R.string.connection_heartbeat_unknown
        }
        val lines = mutableListOf(context.getString(if (parentReady) R.string.connection_parent_ready
            else R.string.connection_parent_not_ready), context.getString(R.string.connection_parent_note))
        if (canonical) stamp(context, R.string.connection_heartbeat_at, heartbeatAt)?.let(lines::add)
        if (heartbeat == Policy.Freshness.CLOCK_MISMATCH) lines += context.getString(R.string.connection_clock_mismatch)
        if (failed) lines += context.getString(R.string.connection_read_failed)
        else if (cached && status != null) lines += context.getString(R.string.connection_cached)
        return section(context, R.string.connection_title, heartbeatLabel, lines.joinToString("\n"), null)
    }

    fun telemetry(context: Context, status: DeviceStatus?, now: Long = System.currentTimeMillis()): CharSequence {
        val at = at(status?.timestamp)
        val freshness = Policy.freshness(at, now)
        val label = when (freshness) {
            Policy.Freshness.FRESH -> R.string.connection_data_fresh
            Policy.Freshness.STALE -> R.string.connection_data_stale
            else -> R.string.connection_data_unknown
        }
        val detail = context.getString(if (freshness == Policy.Freshness.CLOCK_MISMATCH)
            R.string.connection_clock_mismatch else R.string.connection_data_note)
        return section(context, R.string.connection_data_title, label, detail, at)
    }

    fun coordinates(context: Context, capturedAt: Long?, now: Long = System.currentTimeMillis()): CharSequence {
        val freshness = Policy.freshness(capturedAt, now, Policy.LOCATION_FRESH_MS)
        val label = when (freshness) {
            Policy.Freshness.FRESH -> R.string.connection_coordinates_fresh
            Policy.Freshness.STALE -> R.string.connection_coordinates_stale
            else -> R.string.connection_coordinates_unknown
        }
        return section(context, R.string.connection_coordinates_title, label,
            context.getString(if (freshness == Policy.Freshness.CLOCK_MISMATCH) R.string.connection_clock_mismatch
                else R.string.connection_coordinates_note), capturedAt, R.string.connection_measured_at)
    }

    fun microphone(context: Context, status: DeviceStatus?, now: Long = System.currentTimeMillis()): CharSequence {
        val raw = status?.raw?.get("microphoneReadiness") as? Map<*, *>
        val at = at(raw?.get("collectedAt"))
        val state = Policy.microphone(at, raw?.get("permissionGranted") as? Boolean,
            raw?.get("microphoneMuted") as? Boolean, now)
        val label = when (state) {
            Policy.Microphone.STALE -> R.string.connection_microphone_stale
            Policy.Microphone.PERMISSION_MISSING -> R.string.connection_microphone_denied
            Policy.Microphone.MUTED -> R.string.connection_microphone_muted
            Policy.Microphone.PREREQUISITES_ONLY -> R.string.connection_microphone_prerequisites
            else -> R.string.connection_microphone_unknown
        }
        val detail = when (state) {
            Policy.Microphone.PERMISSION_MISSING -> R.string.connection_microphone_permission_help
            Policy.Microphone.MUTED -> R.string.connection_microphone_muted_help
            Policy.Microphone.UNKNOWN, Policy.Microphone.STALE -> R.string.connection_microphone_unknown_help
            else -> R.string.connection_microphone_note
        }
        return section(context, R.string.connection_microphone_title, label, context.getString(detail), at)
    }

    fun network(context: Context, status: DeviceStatus?, now: Long = System.currentTimeMillis()): CharSequence {
        val raw = status?.raw?.get("connectionReadiness") as? Map<*, *>
        val at = at(raw?.get("collectedAt"))
        val fresh = Policy.freshness(at, now) == Policy.Freshness.FRESH
        val label = when {
            !fresh -> R.string.connection_network_unknown
            raw?.get("networkPresent") == false -> R.string.connection_network_absent
            raw?.get("internetValidated") == true -> R.string.connection_network_validated
            raw?.get("networkPresent") == true -> R.string.connection_network_unvalidated
            else -> R.string.connection_network_unknown
        }
        val transport = when (raw?.get("transport")) {
            "WIFI" -> R.string.connection_transport_wifi
            "MOBILE" -> R.string.connection_transport_mobile
            "VPN" -> R.string.connection_transport_vpn
            "ETHERNET" -> R.string.connection_transport_ethernet
            "OTHER" -> R.string.connection_transport_other
            else -> null
        }
        val detail = listOfNotNull(transport?.takeIf { fresh }?.let {
            context.getString(R.string.connection_network_transport, context.getString(it))
        }, context.getString(R.string.connection_network_note)).joinToString("\n\n")
        return section(context, R.string.connection_network_title, label, detail, at)
    }
}
