package ru.example.childwatch.remote

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import ru.example.childwatch.R
import ru.example.childwatch.network.DeviceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Snapshot facts, not connectivity or authorization inferred from a cached status. */
object DeviceFeatureDiagnostics {
    private const val FRESH_MS = 180_000L
    private fun epoch(value: Any?): Long? = (value as? Number)?.toLong()?.takeIf { it > 0 }
        ?.let { if (it < 1_000_000_000_000L) it * 1000L else it }
    private fun fresh(at: Long?, now: Long) = at != null && now - at in -30_000L..FRESH_MS

    fun usage(context: Context, status: DeviceStatus, person: String, now: Long = System.currentTimeMillis()): CharSequence {
        val raw = status.raw
        val snapshotAt = epoch(raw?.get("timestamp")) ?: epoch(status.timestamp)
        val permission = raw?.get("usagePermissionGranted") as? Boolean
        val daily = raw?.get("dailyUsage") as? Map<*, *>
        val dataAt = epoch(raw?.get("appUsageCollectedAt"))
        val message = when {
            !fresh(snapshotAt, now) -> context.getString(R.string.diagnostics_usage_unconfirmed, person)
            permission == false -> context.getString(R.string.diagnostics_usage_permission, person)
            permission == null -> context.getString(R.string.diagnostics_usage_unknown, person)
            daily?.get("available") == false || dataAt == null || dataAt > now + 30_000L -> context.getString(R.string.diagnostics_usage_collecting)
            raw?.get("appUsageStale") == true -> context.getString(R.string.diagnostics_usage_retained)
            else -> context.getString(R.string.diagnostics_usage_available)
        }
        val state = when {
            !fresh(snapshotAt, now) || permission == null -> R.string.diagnostics_state_unconfirmed
            permission == false -> R.string.diagnostics_state_permission
            daily?.get("available") == false || dataAt == null || dataAt > now + 30_000L -> R.string.diagnostics_state_waiting
            raw?.get("appUsageStale") == true -> R.string.diagnostics_state_retained
            else -> R.string.diagnostics_state_usage_ready
        }
        return section(context, R.string.diagnostics_usage_title, state, message,
            dataAt?.takeIf { it <= now + 30_000L }?.let {
                context.getString(R.string.diagnostics_data_at, time(it))
            }, state == R.string.diagnostics_state_usage_ready)
    }

    fun location(context: Context, status: DeviceStatus, person: String, now: Long = System.currentTimeMillis()): CharSequence {
        val state = status.raw?.get("locationReadiness") as? Map<*, *>
        val at = epoch(state?.get("collectedAt"))
        val permission = state?.get("permissionGranted") as? Boolean
        val message = when {
            !fresh(at, now) -> context.getString(R.string.diagnostics_location_unconfirmed, person)
            state?.get("sharingEnabled") == false -> context.getString(R.string.diagnostics_location_sharing_off, person)
            permission == false -> context.getString(R.string.diagnostics_location_permission, person)
            state?.get("enabled") == false -> context.getString(R.string.diagnostics_location_disabled, person)
            permission == null || state?.get("enabled") == null -> context.getString(R.string.diagnostics_location_unconfirmed, person)
            state?.get("backgroundPermissionGranted") == false -> context.getString(R.string.diagnostics_location_background, person)
            state?.get("precisePermissionGranted") == false -> context.getString(R.string.diagnostics_location_approximate, person)
            else -> context.getString(R.string.diagnostics_location_prerequisites)
        }
        val stateLabel = when {
            !fresh(at, now) -> R.string.diagnostics_state_unconfirmed
            state?.get("sharingEnabled") == false -> R.string.diagnostics_state_sharing_off
            permission == false -> R.string.diagnostics_state_permission
            state?.get("enabled") == false -> R.string.diagnostics_state_location_off
            permission == null || state?.get("enabled") == null -> R.string.diagnostics_state_unconfirmed
            state?.get("backgroundPermissionGranted") == false -> R.string.diagnostics_state_background
            state?.get("precisePermissionGranted") == false -> R.string.diagnostics_state_approximate
            else -> R.string.diagnostics_state_location_ready
        }
        return section(context, R.string.diagnostics_location_title, stateLabel, message,
            at?.takeIf { it <= now + 30_000L }?.let {
                context.getString(R.string.diagnostics_checked_at, time(it))
            }, stateLabel == R.string.diagnostics_state_location_ready)
    }

    /** One reading order for TalkBack and sighted readers: task, state, action, measured time. */
    internal fun section(context: Context, title: Int, state: Int, detail: String,
                        timestamp: String?, ready: Boolean): CharSequence {
        val text = SpannableStringBuilder(context.getString(title))
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append("\n")
        val stateStart = text.length
        text.append(context.getString(state))
        text.setSpan(StyleSpan(Typeface.BOLD), stateStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(ContextCompat.getColor(context,
            if (ready) R.color.cw_color_primary else R.color.cw_color_on_surface_variant)),
            stateStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append("\n").append(detail)
        timestamp?.let {
            text.append("\n")
            val timeStart = text.length
            text.append(it)
            text.setSpan(RelativeSizeSpan(0.875f), timeStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.cw_color_on_surface_variant)),
                timeStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun time(at: Long) = SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(Date(at))
}
