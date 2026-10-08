package ru.example.childwatch

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.example.childwatch.databinding.ActivityDeviceUsageBinding
import ru.example.childwatch.network.DeviceRecentApp
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.DeviceStatusHistoryItem
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentLinkedChildOptionsProvider
import ru.example.childwatch.profile.FamilyAvatarRenderer
import java.util.Locale
import ru.childwatch.shared.usage.UsageDailyCandidate
import ru.childwatch.shared.usage.UsageDailySnapshot
import ru.childwatch.shared.usage.UsageDailyReportPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog

class DeviceUsageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }

    private lateinit var binding: ActivityDeviceUsageBinding
    private lateinit var networkClient: NetworkClient
    private lateinit var effectiveContextResolver: ParentEffectiveContextResolver
    private lateinit var linkedChildOptionsProvider: ParentLinkedChildOptionsProvider
    private var personLabelJob: Job? = null
    private var usageJob: Job? = null
    private var refreshJob: Job? = null
    private var usageScope: String? = null
    private val lastGoodByDay = linkedMapOf<String, UsageDailySnapshot>()
    private var availableDays: List<UsageDailySnapshot> = emptyList()
    private var selectedDayKey: String? = null
    private var reportStatus: DeviceStatus? = null
    private var reportHistory: List<DeviceStatusHistoryItem> = emptyList()
    private var reportRecent: List<DeviceRecentApp> = emptyList()
    private var dayPicker: AlertDialog? = null
    private var archiveMode = "pending"
    private var archiveHasMore = false
    private var detailsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkClient = NetworkClient(this)
        effectiveContextResolver = ParentEffectiveContextResolver(this)
        linkedChildOptionsProvider = ParentLinkedChildOptionsProvider(this)
        detailsExpanded = savedInstanceState?.getBoolean("usage_details_expanded") ?: false
        selectedDayKey = savedInstanceState?.getString("usage_selected_day")
        usageScope = savedInstanceState?.getString("usage_day_scope")

        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(
            this,
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.refreshButton.setOnClickListener { loadUsage(force = true) }
        binding.usageDayButton.setOnClickListener { chooseDay() }
        binding.usageDetailsButton.setOnClickListener {
            detailsExpanded = !detailsExpanded
            updateDetailsUi()
        }
        updateDetailsUi()
        loadUsage(force = true)
    }

    private fun updateDetailsUi() {
        binding.usageDetailsContainer.isVisible = detailsExpanded
        binding.historyCard.isVisible = detailsExpanded && binding.currentAppCard.isVisible && binding.recentAppsCard.isVisible
        binding.usageDetailsButton.setText(if (detailsExpanded) R.string.usage_details_hide else R.string.usage_details_show)
        androidx.core.view.ViewCompat.setStateDescription(binding.usageDetailsButton,
            getString(if (detailsExpanded) R.string.usage_details_expanded else R.string.usage_details_collapsed))
        listOf(binding.recentAppsContainer, binding.historyContainer).forEach { container ->
            for (index in 0 until container.childCount) {
                container.getChildAt(index).findViewById<TextView>(R.id.subtitleText)?.let { subtitle ->
                    if (subtitle.tag == true) subtitle.isVisible = detailsExpanded && subtitle.text.isNotBlank()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("usage_details_expanded", detailsExpanded)
        outState.putString("usage_selected_day", selectedDayKey)
        outState.putString("usage_day_scope", usageScope)
        super.onSaveInstanceState(outState)
    }

    private fun loadUsage(force: Boolean) {
        val childDeviceId = resolveChildDeviceId()
        if (childDeviceId.isNullOrBlank()) {
            usageJob?.cancel()
            usageScope = null
            clearReportCache()
            showOnlyMessage(getString(R.string.device_usage_pairing_required))
            return
        }

        val scope = usageScopeKey(childDeviceId)
        val actor = org.json.JSONArray(scope)
        if ((0 until 4).any { actor.getString(it).isBlank() }) {
            usageJob?.cancel()
            usageScope = null
            clearReportCache()
            showOnlyMessage(getString(R.string.daily_usage_access_denied))
            return
        }
        if (usageScope != scope) {
            usageScope = scope
            clearReportCache()
            binding.recentAppsContainer.removeAllViews()
            showOnlyMessage(getString(R.string.daily_usage_waiting))
        }
        updatePersonLabel(childDeviceId)
        if (force) {
            showLoading(true)
        }

        usageJob?.cancel()
        usageJob = lifecycleScope.launch {
            try {
                if (!ru.example.childwatch.profile.ParentListeningTargetPolicy(this@DeviceUsageActivity)
                        .isChildDevice(childDeviceId)) {
                    if (!isCurrentRequest(childDeviceId, scope)) return@launch
                    clearReportCache()
                    showOnlyMessage(getString(R.string.daily_usage_child_only))
                    return@launch
                }
                if (!isCurrentRequest(childDeviceId, scope)) return@launch
                val fields = org.json.JSONArray(scope)
                val scopedNetwork = NetworkClient(this@DeviceUsageActivity,
                    expectedFamilyReadScope = (0 until 4).map { fields.getString(it) })
                val latestDeferred = async { scopedNetwork.getChildDeviceStatus(childDeviceId) }
                val historyDeferred = async { scopedNetwork.getChildDeviceStatusHistory(childDeviceId, limit = 80) }
                val archiveDeferred = async { scopedNetwork.getChildUsageDays(childDeviceId, limit = 90) }

                val latestResponse = latestDeferred.await()
                val historyResponse = historyDeferred.await()
                val archiveResponse = archiveDeferred.await()
                currentCoroutineContext().ensureActive()
                if (!isCurrentRequest(childDeviceId, scope)) return@launch

                if (listOf(latestResponse.code(), historyResponse.code(), archiveResponse.code()).any { it == 403 || it == 401 }) {
                    clearReportCache()
                    showOnlyMessage(getString(R.string.daily_usage_access_denied))
                    return@launch
                }

                val status = latestResponse.body()?.status.takeIf { latestResponse.isSuccessful }
                val history = historyResponse.body()?.statuses.orEmpty().takeIf { historyResponse.isSuccessful }.orEmpty()
                val archive = if (archiveResponse.isSuccessful)
                    runCatching { parseArchive(archiveResponse.body(), childDeviceId, fields.getString(1), fields.getString(2)) }.getOrNull()
                    else null
                archiveMode = when {
                    archive != null -> "available"
                    archiveResponse.code() == 404 -> "legacy"
                    else -> "failed"
                }
                archiveHasMore = archive?.hasMore ?: false
                updateArchiveNote()

                if (status == null && history.isEmpty() && archive?.candidates.isNullOrEmpty()) {
                    if (latestResponse.isSuccessful && historyResponse.isSuccessful) {
                        if (availableDays.isEmpty() && selectedDayKey == null) showOnlyMessage(getString(R.string.daily_usage_waiting))
                        else {
                            renderSelectedDay()
                            showLoading(false)
                            binding.statusMessageText.isVisible = true
                            binding.statusMessageText.setText(R.string.daily_usage_no_new_snapshot)
                        }
                    } else showLoadFailure()
                    return@launch
                }

                val latestPermissionRaw = status?.raw ?: history.maxByOrNull { it.timestamp ?: 0L }?.raw
                if (usagePermissionMissing(latestPermissionRaw)) {
                    clearReportCache()
                    showOnlyMessage(getString(R.string.device_usage_permission_missing))
                    return@launch
                }

                binding.statusMessageText.isVisible = false
                val usageSnapshot = history.sortedByDescending { it.timestamp ?: 0L }
                    .firstOrNull { !readRecentApps(it.recentApps, it.raw).isNullOrEmpty() }
                val recentApps = if (extractUsagePermissionMissing(status)) emptyList() else
                    readRecentApps(status?.recentApps, status?.raw)
                        .ifEmpty { readRecentApps(usageSnapshot?.recentApps, usageSnapshot?.raw) }
                val candidates = listOfNotNull(status?.raw?.let { UsageDailyCandidate(it, false) }) +
                    history.mapNotNull { it.raw?.let { raw -> UsageDailyCandidate(raw, true) } }
                val savedCandidates = lastGoodByDay.values.map { UsageDailyCandidate(it.raw, true) }
                availableDays = UsageDailyReportPolicy.snapshots(archive?.candidates.orEmpty() + savedCandidates + candidates,
                    System.currentTimeMillis()).take(90)
                lastGoodByDay.clear()
                availableDays.forEach { lastGoodByDay[it.dayKey] = it }
                reportStatus = status
                reportHistory = history
                reportRecent = recentApps
                renderSelectedDay()
                showLoading(false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrentRequest(childDeviceId, scope)) showLoadFailure()
            }
        }
    }

    private fun renderStatus(status: DeviceStatus?) {
        val currentApp = status?.currentAppName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.device_usage_current_unknown)
        binding.currentAppText.text = currentApp

        // Exact collection time next to the relative one: "5 минут назад" alone does
        // not tell the owner when the phone actually reported the usage snapshot.
        val usageTime = (status?.raw?.get("appUsageCollectedAt") as? Number)?.toLong()
        val updatedAtText = usageTime?.takeIf { it > 0 }?.let {
            val relative = DateUtils.getRelativeTimeSpanString(
                it,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
            getString(R.string.device_usage_last_seen, "$relative · ${formatObservationTime(it)}")
        } ?: getString(R.string.device_usage_last_seen, getString(R.string.device_info_unknown))

        val batteryText = status?.batteryLevel?.takeIf { it in 0..100 }?.let {
            getString(R.string.device_usage_battery, "$it%")
        } ?: getString(R.string.device_usage_battery_unknown)

        binding.currentMetaText.text = listOf(
            updatedAtText,
            batteryText
        ).joinToString("\n")
    }

    /**
     * The freshest daily snapshot that really carries activity.
     *
     * The child phone uploads a new snapshot roughly every two minutes, and a missing
     * or empty collection used to overwrite a good one as the newest status. An empty
     * JSON object is a Map too, so the old check `dailyUsage is Map<*, *>` let that
     * emptiness win and the screen showed "no data" although the history held a real
     * list. Only usable snapshots count now, and the newest usable one wins.
     */
    private data class DailySnapshot(
        val raw: Map<String, Any?>,
        val collectedAt: Long?,
        val isFromHistory: Boolean
    )

    private fun usageScopeKey(childDeviceId: String): String = org.json.JSONArray()
        .put(effectiveContextResolver.resolveServerUrl().trim()).put(effectiveContextResolver.resolveFamilyId().orEmpty().trim())
        .put(effectiveContextResolver.resolveSelfMemberId().orEmpty().trim()).put(effectiveContextResolver.resolveOwnParentId().trim()).put(childDeviceId).toString()

    private data class ArchiveDays(val candidates: List<UsageDailyCandidate>, val hasMore: Boolean)

    /** Fail the entire envelope on a different actor/target, never merge its rows into this report. */
    private fun parseArchive(body: Map<String, Any?>?, device: String, family: String, actor: String): ArchiveDays {
        require(body != null && body["success"] == true && body["deviceId"] == device &&
            body["familyId"] == family && body["actorMemberId"] == actor)
        val metadata = body["archive"] as? Map<*, *> ?: error("Missing archive metadata")
        require(metadata["hasMore"] is Boolean && metadata["completeCoverage"] == false &&
            metadata.containsKey("retentionDays") && metadata["retentionDays"] == null)
        val rows = body["statuses"] as? List<*> ?: error("Missing archive days")
        require(rows.size <= 90)
        val candidates = rows.map { entry ->
            val row = entry as? Map<*, *> ?: error("Invalid archive row")
            val raw = row["raw"] as? Map<*, *> ?: error("Invalid archive snapshot")
            require(raw.keys.all { it is String })
            val normalized = raw.entries.associate { it.key as String to it.value }
            val daily = normalized["dailyUsage"] as? Map<*, *> ?: error("Missing daily window")
            require(daily["windowState"] in setOf("CLOSED", "PARTIAL", "UNKNOWN"))
            UsageDailyCandidate(normalized + ("usageArchiveSource" to true), true)
        }
        return ArchiveDays(candidates, metadata["hasMore"] as Boolean)
    }

    private fun updateArchiveNote() {
        binding.usageArchiveNote.setText(when (archiveMode) {
            "available" -> if (archiveHasMore) R.string.daily_usage_archive_more else R.string.daily_usage_archive_limits
            "legacy" -> R.string.daily_usage_archive_legacy
            "failed" -> R.string.daily_usage_archive_failed
            else -> R.string.daily_usage_report_limits
        })
    }

    private fun clearReportCache() {
        dayPicker?.dismiss()
        lastGoodByDay.clear()
        availableDays = emptyList()
        selectedDayKey = null
        reportStatus = null
        reportHistory = emptyList()
        reportRecent = emptyList()
        archiveMode = "pending"
        archiveHasMore = false
        updateArchiveNote()
        binding.usageDayButton.setText(R.string.daily_usage_latest_available)
    }

    private fun isCurrentRequest(child: String, scope: String): Boolean {
        if (resolveChildDeviceId() == child && usageScopeKey(child) == scope && usageScope == scope) return true
        if (usageScope == scope) {
            usageScope = null
            clearReportCache()
            showOnlyMessage(getString(R.string.daily_usage_context_changed))
        }
        return false
    }

    private fun dayLabel(snapshot: UsageDailySnapshot): String = java.text.SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone(snapshot.timeZoneId)
    }.format(java.util.Date(snapshot.dayLabelDate))

    private fun chooseDay() {
        val scope = usageScope ?: return
        val device = resolveChildDeviceId() ?: return
        if (!isCurrentRequest(device, scope)) return
        val choices = availableDays.toList()
        val labels = listOf(getString(R.string.daily_usage_latest_available)) + choices.map {
            getString(R.string.daily_usage_day_choice, dayLabel(it), it.timeZoneId)
        }
        dayPicker?.dismiss()
        dayPicker = MaterialAlertDialogBuilder(this).setTitle(R.string.daily_usage_choose_day)
            .setItems(labels.toTypedArray()) { _, index ->
                if (isCurrentRequest(device, scope)) {
                    selectedDayKey = choices.getOrNull(index - 1)?.dayKey
                    renderSelectedDay()
                }
            }.setNegativeButton(android.R.string.cancel, null).create().also { picker ->
                picker.setOnDismissListener { if (dayPicker === picker) dayPicker = null }
                picker.show()
            }
    }

    private fun renderSelectedDay() {
        updateArchiveNote()
        binding.usageArchiveNote.isVisible = true
        val snapshot = UsageDailyReportPolicy.selectDay(availableDays, selectedDayKey)
        binding.usageDayButton.isVisible = true
        binding.usageDayButton.text = if (selectedDayKey == null) getString(R.string.daily_usage_latest_available)
            else snapshot?.let { getString(R.string.daily_usage_day_choice, dayLabel(it), it.timeZoneId) }
                ?: getString(R.string.daily_usage_selected_missing_title)
        val historical = snapshot?.let {
            val zone = java.time.ZoneId.of(it.timeZoneId)
            java.time.Instant.ofEpochMilli(it.dayLabelDate).atZone(zone).toLocalDate() != java.time.LocalDate.now(zone)
        } ?: (selectedDayKey != null)
        binding.currentAppCard.isVisible = !historical
        binding.recentAppsCard.isVisible = true
        binding.currentMetaText.isVisible = !historical
        if (!historical) renderStatus(reportStatus)
        if (snapshot == null && selectedDayKey != null) {
            binding.recentAppsContainer.removeAllViews()
            binding.dailySummaryText.isVisible = true
            binding.dailySummaryText.setText(R.string.daily_usage_selected_missing)
            binding.appsSectionTitle.setText(R.string.daily_usage_selected_missing_title)
            binding.dailyFreshnessText.text = ""
            binding.dailyDetailsText.text = ""
            binding.recentAppsEmptyText.isVisible = false
        } else {
            renderRecentApps(reportStatus, if (snapshot == null) reportRecent else emptyList())
            renderDailyUsage(reportStatus, snapshot?.let { DailySnapshot(it.raw, it.collectedAt, it.fromHistory) }, reportHistory)
            if (snapshot == null) binding.dailyFreshnessText.setText(R.string.daily_usage_recent_unbounded)
        }
        if (!historical) renderHistory(reportHistory.sortedByDescending { it.timestamp ?: 0L })
        else binding.historyContainer.removeAllViews()
        updateDetailsUi()
    }

    private fun dailyUsageRows(daily: Map<*, *>): List<Map<*, *>> =
        (daily["apps"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            .filter { ((it["totalTimeInForeground"] as? Number)?.toLong() ?: 0L) > 0L }

    /** Clock time `HH:mm` in the given zone. Defaults to the parent's own zone. */
    private fun formatClock(millis: Long, zone: java.util.TimeZone? = null): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            if (zone != null) timeZone = zone
        }.format(java.util.Date(millis))

    /** Collection/observation time uses the parent's zone, explicitly named in the UI. */
    private fun formatObservationTime(millis: Long): String =
        java.text.SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(java.util.Date(millis))

    /**
     * Why there is nothing to show, stated honestly instead of staying silent: no
     * statistics permission, an empty Android event journal, or no snapshot yet today.
     * [anyUsableInHistory] tells apart "never collected" from "collected, then wiped".
     */
    private fun dailyUnavailableMessage(
        status: DeviceStatus?,
        history: List<DeviceStatusHistoryItem>,
        anyUsableInHistory: Boolean
    ): String {
        if (extractUsagePermissionMissing(status)) return getString(R.string.device_usage_permission_missing)
        val newestDaily = status?.raw?.get("dailyUsage") as? Map<*, *>
            ?: history.sortedByDescending { it.timestamp ?: 0L }
                .firstOrNull { it.raw?.get("dailyUsage") is Map<*, *> }?.raw?.get("dailyUsage") as? Map<*, *>
        val available = newestDaily?.get("available")
        val hasWindow = (newestDaily?.get("start") as? Number) != null && (newestDaily?.get("end") as? Number) != null
        return when {
            available == false && !hasWindow -> getString(R.string.daily_usage_no_permission_or_empty)
            anyUsableInHistory -> getString(R.string.daily_usage_no_today)
            else -> getString(R.string.daily_usage_waiting)
        }
    }

    private fun renderDailyUsage(
        status: DeviceStatus?,
        snapshot: DailySnapshot?,
        history: List<DeviceStatusHistoryItem>
    ) {
        binding.dailySummaryText.isVisible = true
        binding.appsSectionTitle.setText(R.string.device_usage_recent_section)
        val daily = snapshot?.raw?.get("dailyUsage") as? Map<*, *>
        val start = (daily?.get("start") as? Number)?.toLong()
        val end = (daily?.get("end") as? Number)?.toLong()
        if (snapshot == null || daily == null || start == null || end == null || end < start) {
            binding.dailyDetailsText.text = ""
            binding.dailyFreshnessText.text = ""
            val anyUsableInHistory = availableDays.isNotEmpty()
            binding.dailySummaryText.text = dailyUnavailableMessage(status, history, anyUsableInHistory)
            binding.recentAppsEmptyText.isVisible = binding.recentAppsContainer.childCount == 0
            binding.recentAppsEmptyText.setText(R.string.device_usage_recent_empty)
            return
        }
        // The fallback list was rendered first. A daily snapshot replaces it;
        // mixing both lists duplicates apps and puts recent-launch data under a daily heading.
        binding.recentAppsContainer.removeAllViews()
        val zone = java.util.TimeZone.getTimeZone(daily["timeZone"] as? String ?: "UTC")
        val dateFormat = java.text.SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).apply { timeZone = zone }
        val rows = dailyUsageRows(daily)
            .sortedByDescending { (it["totalTimeInForeground"] as? Number)?.toLong() ?: 0L }
        val total = rows.sumOf { (it["totalTimeInForeground"] as? Number)?.toLong() ?: 0L }
        val lastUsedTimes = rows.mapNotNull { (it["lastUsed"] as? Number)?.toLong()?.takeIf { time -> time > 0 } }
        val firstSeen = rows.mapNotNull { (it["firstUsed"] as? Number)?.toLong()?.takeIf { time -> time in start..end } }.minOrNull()
        val lastSeen = rows.mapNotNull { (it["lastForegroundAt"] as? Number)?.toLong()?.takeIf { time -> time in start..end } }.maxOrNull()
            ?: lastUsedTimes.filter { it in start..end }.maxOrNull()

        binding.appsSectionTitle.text = getString(R.string.daily_usage_heading, dateFormat.format(java.util.Date(start)))
        binding.dailySummaryText.text = getString(R.string.daily_usage_summary, formatDuration(total), rows.size, formatClock(end, zone))
        binding.dailyDetailsText.text = getString(R.string.daily_usage_day_window,
            firstSeen?.let { formatClock(it, zone) } ?: "—", lastSeen?.let { formatClock(it, zone) } ?: "—")
        binding.dailyFreshnessText.text = dailyDataAgeNote(snapshot, end, zone)
        binding.recentAppsEmptyText.isVisible = rows.isEmpty()
        binding.recentAppsEmptyText.setText(R.string.daily_usage_empty)
        rows.forEach { app ->
            val duration = (app["totalTimeInForeground"] as? Number)?.toLong() ?: 0L
            val share = if (total > 0) (duration * 100 / total).toInt().coerceAtLeast(0) else 0
            val lastUsed = (app["lastUsed"] as? Number)?.toLong()?.takeIf { it in start..end }
            val row = createUsageRow(
                title = (app["appName"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (app["packageName"] as? String)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.device_info_unknown),
                subtitle = lastUsed?.let { getString(R.string.daily_usage_app_last_used, formatClock(it, zone)) } ?: "",
                meta = getString(R.string.daily_usage_app_time, formatDuration(duration), share)
            )
            row.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.usageShareBar).apply {
                isVisible = true
                progress = share.coerceIn(0, 100)
            }
            binding.recentAppsContainer.addView(row)
        }
    }

    /**
     * States the time the shown data belongs to: always when the displayed snapshot
     * came from the history or the phone marked it stale, and when the snapshot is
     * simply older than a few minutes.
     */
    private fun dailyDataAgeNote(snapshot: DailySnapshot, end: Long, zone: java.util.TimeZone): String {
        // appUsageCollectedAt is the scan clock, not server receive time. Legacy end is only a period bound.
        val collectedAt = (snapshot.raw["appUsageCollectedAt"] as? Number)?.toLong()?.takeIf { it > 0 }
        val staleFlag = snapshot.raw["appUsageStale"] == true
        val collectedFormat = java.text.SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val endFormat = java.text.SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).apply { timeZone = zone }
        val collectedNote = collectedAt?.let { getString(
                R.string.daily_usage_collected_at,
                endFormat.format(java.util.Date(end)),
                collectedFormat.format(java.util.Date(it))
            ) }.orEmpty()
        val staleNote = if (staleFlag || snapshot.isFromHistory ||
            System.currentTimeMillis() - end > 5 * DateUtils.MINUTE_IN_MILLIS)
            getString(R.string.daily_usage_stale, endFormat.format(java.util.Date(end))) else ""
        val source = getString(when {
            snapshot.raw["usageArchiveSource"] == true -> R.string.daily_usage_source_archive
            snapshot.isFromHistory -> R.string.daily_usage_source_saved
            else -> R.string.daily_usage_source_latest
        }, zone.id)
        val daily = snapshot.raw["dailyUsage"] as? Map<*, *>
        val windowNote = getString(when (daily?.get("windowState")) {
            "CLOSED" -> R.string.daily_usage_window_closed
            "PARTIAL" -> R.string.daily_usage_window_partial
            else -> R.string.daily_usage_window_unknown
        })
        val unknownClock = if (collectedAt == null) getString(R.string.daily_usage_collection_unknown, endFormat.format(java.util.Date(end))) else ""
        return listOf(source, collectedNote, unknownClock, windowNote, staleNote).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun readRecentApps(apps: List<DeviceRecentApp>?, raw: Map<String, Any?>?): List<DeviceRecentApp> {
        if (!apps.isNullOrEmpty()) return apps
        return (raw?.get("recentApps") as? List<*>)?.mapNotNull { entry ->
            val item = entry as? Map<*, *> ?: return@mapNotNull null
            DeviceRecentApp(
                packageName = item["packageName"] as? String,
                appName = item["appName"] as? String,
                lastUsed = (item["lastUsed"] as? Number)?.toLong(),
                totalTimeInForeground = (item["totalTimeInForeground"] as? Number)?.toLong(),
                isSystemApp = item["isSystemApp"] as? Boolean
            )
        }.orEmpty()
    }

    private fun renderRecentApps(status: DeviceStatus?, apps: List<DeviceRecentApp>) {
        val recentApps = apps
            .filter { !it.appName.isNullOrBlank() || !it.packageName.isNullOrBlank() }
            .sortedByDescending { it.lastUsed ?: 0L }

        binding.recentAppsContainer.removeAllViews()
        val permissionMissing = extractUsagePermissionMissing(status)
        binding.recentAppsEmptyText.isVisible = recentApps.isEmpty()
        binding.recentAppsEmptyText.text = if (permissionMissing) {
            getString(R.string.device_usage_permission_missing)
        } else {
            getString(R.string.device_usage_recent_empty)
        }

        recentApps.forEach { app ->
            binding.recentAppsContainer.addView(
                createUsageRow(
                    title = app.appName ?: app.packageName.orEmpty(),
                    subtitle = app.packageName ?: "",
                    technicalSubtitle = true,
                    meta = listOfNotNull(
                        app.lastUsed?.takeIf { it > 0 }?.let {
                            // Relative time alone ("5 минут назад") is not a time of day:
                            // the owner asked for the exact clock time as well.
                            val relative = DateUtils.getRelativeTimeSpanString(
                                it,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                            getString(R.string.device_usage_last_used, "$relative · ${formatObservationTime(it)}")
                        },
                        app.totalTimeInForeground?.takeIf { it > 0 }?.let {
                            getString(R.string.device_usage_foreground_time, formatDuration(it))
                        }
                    ).joinToString("\n")
                )
            )
        }
    }

    private fun renderHistory(items: List<DeviceStatusHistoryItem>) {
        binding.historyContainer.removeAllViews()

        val compactHistory = mutableListOf<DeviceStatusHistoryItem>()
        var previousPackage: String? = null
        items.forEach { item ->
            if (compactHistory.size >= 20) return@forEach
            val packageName = item.currentAppPackage?.takeIf { it.isNotBlank() }
            val appName = item.currentAppName?.takeIf { it.isNotBlank() }
            if (packageName == null && appName == null) {
                return@forEach
            }
            if (packageName != null && packageName == previousPackage) {
                return@forEach
            }
            compactHistory += item
            previousPackage = packageName
            if (compactHistory.size >= 20) {
                return@forEach
            }
        }

        binding.historyEmptyText.isVisible = compactHistory.isEmpty()
        compactHistory.forEach { item ->
            val observedTime = item.timestamp?.takeIf { it > 0 }?.let(::formatObservationTime)
                ?: getString(R.string.device_info_unknown)

            val batteryLine = item.batteryLevel?.takeIf { it in 0..100 }?.let {
                getString(R.string.device_usage_battery, "$it%")
            } ?: getString(R.string.device_usage_battery_unknown)

            binding.historyContainer.addView(
                createUsageRow(
                    title = item.currentAppName?.takeIf { it.isNotBlank() }
                        ?: item.currentAppPackage.orEmpty(),
                    subtitle = item.currentAppPackage ?: "",
                    technicalSubtitle = true,
                    meta = getString(R.string.device_usage_history_line, observedTime, batteryLine)
                )
            )
        }
    }

    private fun createUsageRow(title: String, subtitle: String, meta: String, technicalSubtitle: Boolean = false) =
        LayoutInflater.from(this).inflate(R.layout.item_device_usage_row, binding.recentAppsContainer, false).apply {
            findViewById<TextView>(R.id.titleText).text = title
            findViewById<TextView>(R.id.subtitleText).apply {
                text = subtitle
                tag = technicalSubtitle
                isVisible = subtitle.isNotBlank() && (!technicalSubtitle || detailsExpanded)
            }
            findViewById<TextView>(R.id.metaText).apply {
                text = meta
                isVisible = meta.isNotBlank()
            }
        }

    private fun resolveChildDeviceId(): String? {
        return intent.getStringExtra(EXTRA_DEVICE_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: effectiveContextResolver.resolveFocusedChildId().takeIf { it.isNotBlank() }
    }

    private fun updatePersonLabel(childDeviceId: String) {
        val scope = usageScopeKey(childDeviceId)
        binding.deviceIdText.setText(R.string.device_usage_person_loading)
        FamilyAvatarRenderer.bind(binding.personAvatar, null)
        personLabelJob?.cancel()
        personLabelJob = lifecycleScope.launch {
            val localName = runCatching {
                ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@DeviceUsageActivity)
                    .childDao()
                    .getByDeviceId(childDeviceId)
                    ?.name
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (!isCurrentRequest(childDeviceId, scope)) return@launch
            if (localName != null) {
                binding.deviceIdText.text = getString(R.string.device_usage_person, localName)
            }

            val canonicalOption = runCatching {
                linkedChildOptionsProvider.getOptions()
                    .firstOrNull { it.deviceId == childDeviceId }
            }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (canonicalOption != null && resolveChildDeviceId() == childDeviceId &&
                usageScopeKey(childDeviceId) == scope && usageScope == scope) {
                val canonicalName = canonicalOption.displayName.trim()
                    .ifBlank { localName ?: getString(R.string.chat_partner_child) }
                binding.deviceIdText.text = getString(R.string.device_usage_person, canonicalName)
                FamilyAvatarRenderer.bind(binding.personAvatar, canonicalOption.avatarKey)
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.refreshButton.isEnabled = !isLoading
        binding.progressBar.isVisible = isLoading
        binding.statusMessageText.isVisible = isLoading
        if (isLoading) {
            binding.statusMessageText.text = getString(R.string.device_usage_loading)
        }
    }

    private fun showLoadFailure() {
        if (reportStatus == null && availableDays.isEmpty() && reportRecent.isEmpty() && selectedDayKey == null) {
            showOnlyMessage(getString(R.string.daily_usage_load_failed))
            return
        }
        showLoading(false)
        // Keep the last displayed snapshot readable during a temporary outage.
        binding.statusMessageText.isVisible = true
        binding.statusMessageText.setText(R.string.daily_usage_refresh_failed)
    }

    private fun showOnlyMessage(message: String) {
        showLoading(false)
        binding.usageArchiveNote.isVisible = false
        binding.currentAppCard.isVisible = false
        binding.usageDayButton.isVisible = false
        binding.recentAppsCard.isVisible = false
        binding.historyCard.isVisible = false
        binding.statusMessageText.isVisible = true
        binding.statusMessageText.text = message
        binding.recentAppsContainer.removeAllViews()
        binding.historyContainer.removeAllViews()
        binding.recentAppsEmptyText.isVisible = false
        binding.historyEmptyText.isVisible = false
    }

    private fun extractUsagePermissionMissing(status: DeviceStatus?): Boolean {
        return usagePermissionMissing(status?.raw)
    }

    private fun usagePermissionMissing(raw: Map<String, Any?>?): Boolean {
        if (raw?.get("usagePermissionGranted") == false) return true
        val rawCurrentApp = raw?.get("currentApp") as? Map<*, *> ?: return false
        val error = rawCurrentApp["error"] as? String ?: return false
        return error.contains("Permission", ignoreCase = true)
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> String.format(Locale.getDefault(), "%d ч %d мин", hours, minutes)
            minutes > 0 -> String.format(Locale.getDefault(), "%d мин", minutes)
            else -> String.format(Locale.getDefault(), "%d сек", seconds)
        }
    }

    override fun onDestroy() {
        dayPicker?.dismiss()
        usageJob?.cancel()
        personLabelJob?.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        resolveChildDeviceId()?.let { child ->
            if (usageScope != usageScopeKey(child)) loadUsage(force = true)
        }
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(DateUtils.MINUTE_IN_MILLIS)
                if (usageJob?.isActive != true) loadUsage(force = false)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        super.onStop()
    }
}
