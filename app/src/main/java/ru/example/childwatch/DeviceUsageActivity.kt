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
import ru.example.childwatch.databinding.ActivityDeviceUsageBinding
import ru.example.childwatch.network.DeviceRecentApp
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.DeviceStatusHistoryItem
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentLinkedChildOptionsProvider
import ru.example.childwatch.profile.FamilyAvatarRenderer
import java.util.Locale

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
    private var lastGoodDailySnapshot: DailySnapshot? = null
    private var detailsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkClient = NetworkClient(this)
        effectiveContextResolver = ParentEffectiveContextResolver(this)
        linkedChildOptionsProvider = ParentLinkedChildOptionsProvider(this)
        detailsExpanded = savedInstanceState?.getBoolean("usage_details_expanded") ?: false

        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(
            this,
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.refreshButton.setOnClickListener { loadUsage(force = true) }
        binding.usageDetailsButton.setOnClickListener {
            detailsExpanded = !detailsExpanded
            updateDetailsUi()
        }
        updateDetailsUi()
        loadUsage(force = true)
    }

    private fun updateDetailsUi() {
        binding.usageDetailsContainer.isVisible = detailsExpanded
        binding.historyCard.isVisible = detailsExpanded && binding.recentAppsCard.isVisible
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
        super.onSaveInstanceState(outState)
    }

    private fun loadUsage(force: Boolean) {
        val childDeviceId = resolveChildDeviceId()
        if (childDeviceId.isNullOrBlank()) {
            showOnlyMessage(getString(R.string.device_usage_pairing_required))
            return
        }

        val scope = usageScopeKey(childDeviceId)
        if (usageScope != scope) {
            usageScope = scope
            lastGoodDailySnapshot = null
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
                    showOnlyMessage(getString(R.string.daily_usage_child_only))
                    return@launch
                }
                val latestDeferred = async { networkClient.getChildDeviceStatus(childDeviceId) }
                val historyDeferred = async { networkClient.getChildDeviceStatusHistory(childDeviceId, limit = 80) }

                val latestResponse = latestDeferred.await()
                val historyResponse = historyDeferred.await()
                if (usageScopeKey(childDeviceId) != scope || usageScope != scope) return@launch

                if (latestResponse.code() == 403 || historyResponse.code() == 403) {
                    lastGoodDailySnapshot = null
                    showOnlyMessage(getString(R.string.daily_usage_access_denied))
                    return@launch
                }

                val status = latestResponse.body()?.status.takeIf { latestResponse.isSuccessful }
                val history = historyResponse.body()?.statuses.orEmpty().takeIf { historyResponse.isSuccessful }.orEmpty()

                if (status == null && history.isEmpty()) {
                    showLoadFailure()
                    return@launch
                }

                binding.currentAppCard.isVisible = true
                binding.recentAppsCard.isVisible = true
                binding.historyCard.isVisible = detailsExpanded
                binding.statusMessageText.isVisible = false
                renderStatus(status)
                val usageSnapshot = history.sortedByDescending { it.timestamp ?: 0L }
                    .firstOrNull { !readRecentApps(it.recentApps, it.raw).isNullOrEmpty() }
                val recentApps = if (extractUsagePermissionMissing(status)) emptyList() else
                    readRecentApps(status?.recentApps, status?.raw)
                        .ifEmpty { readRecentApps(usageSnapshot?.recentApps, usageSnapshot?.raw) }
                renderRecentApps(status, recentApps)
                val selectedSnapshot = selectDailySnapshot(status, history)
                if (selectedSnapshot != null && (selectedSnapshot.collectedAt ?: 0L) >= (lastGoodDailySnapshot?.collectedAt ?: 0L)) lastGoodDailySnapshot = selectedSnapshot
                val displayedSnapshot = selectedSnapshot?.takeIf { (it.collectedAt ?: 0L) >= (lastGoodDailySnapshot?.collectedAt ?: 0L) }
                    ?: lastGoodDailySnapshot?.copy(isFromHistory = true)
                renderDailyUsage(status, displayedSnapshot, history)
                renderHistory(history.sortedByDescending { it.timestamp ?: 0L })
                updateDetailsUi()
                showLoading(false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showLoadFailure()
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
            getString(R.string.device_usage_last_seen, "$relative · ${formatClock(it)}")
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
        .put(effectiveContextResolver.resolveServerUrl()).put(effectiveContextResolver.resolveFamilyId())
        .put(effectiveContextResolver.resolveOwnParentId()).put(childDeviceId).toString()

    private fun selectDailySnapshot(status: DeviceStatus?, history: List<DeviceStatusHistoryItem>): DailySnapshot? {
        val candidates = mutableListOf<DailySnapshot>()
        status?.raw?.takeIf { isDailyUsageUsable(it["dailyUsage"]) }?.let { raw ->
            candidates += DailySnapshot(raw, (raw["appUsageCollectedAt"] as? Number)?.toLong()?.takeIf { it > 0 }
                ?: (raw["dailyUsage"] as? Map<*, *>)?.get("end")?.let { (it as? Number)?.toLong() }, false)
        }
        history.forEach { item ->
            item.raw?.takeIf { isDailyUsageUsable(it["dailyUsage"]) }?.let { raw ->
                candidates += DailySnapshot(raw, (raw["appUsageCollectedAt"] as? Number)?.toLong()?.takeIf { it > 0 }
                    ?: (raw["dailyUsage"] as? Map<*, *>)?.get("end")?.let { (it as? Number)?.toLong() }, true)
            }
        }
        return candidates.maxByOrNull { it.collectedAt ?: 0L }
    }

    /**
     * A snapshot is usable only when it names its day and holds at least one app with
     * real foreground time. `{}`, a missing key and `available == false` are not data:
     * treating them as data is what made the screen claim there was nothing to show.
     */
    private fun isDailyUsageUsable(dailyUsage: Any?): Boolean {
        val daily = dailyUsage as? Map<*, *> ?: return false
        if (daily["available"] == false) return false
        val start = (daily["start"] as? Number)?.toLong() ?: return false
        val end = (daily["end"] as? Number)?.toLong() ?: return false
        if (start <= 0L || end <= start) return false
        return dailyUsageRows(daily).isNotEmpty()
    }

    private fun dailyUsageRows(daily: Map<*, *>): List<Map<*, *>> =
        (daily["apps"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            .filter { ((it["totalTimeInForeground"] as? Number)?.toLong() ?: 0L) > 0L }

    /** Clock time `HH:mm` in the given zone. Defaults to the parent's own zone. */
    private fun formatClock(millis: Long, zone: java.util.TimeZone? = null): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            if (zone != null) timeZone = zone
        }.format(java.util.Date(millis))

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
            val anyUsableInHistory = history.any { isDailyUsageUsable(it.raw?.get("dailyUsage")) }
            binding.dailySummaryText.text = dailyUnavailableMessage(status, history, anyUsableInHistory)
            binding.recentAppsEmptyText.isVisible = binding.recentAppsContainer.childCount == 0
            binding.recentAppsEmptyText.setText(R.string.device_usage_recent_empty)
            return
        }
        // The fallback list was rendered first. A daily snapshot replaces it;
        // mixing both lists duplicates apps and puts recent-launch data under a daily heading.
        binding.recentAppsContainer.removeAllViews()
        val zone = java.util.TimeZone.getTimeZone(daily["timeZone"] as? String ?: "UTC")
        val dateFormat = java.text.SimpleDateFormat("d MMMM", Locale.getDefault()).apply { timeZone = zone }
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
            val lastUsed = (app["lastUsed"] as? Number)?.toLong()?.takeIf { it > 0 }
            val row = createUsageRow(
                title = app["appName"] as? String ?: app["packageName"] as? String ?: "",
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
        val collectedAt = snapshot.collectedAt?.takeIf { it > 0 }
        val staleFlag = snapshot.raw["appUsageStale"] == true
        val collectedFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val endFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }
        val collectedNote = collectedAt?.let { getString(
                R.string.daily_usage_collected_at,
                endFormat.format(java.util.Date(end)),
                collectedFormat.format(java.util.Date(it))
            ) }.orEmpty()
        val staleNote = if (staleFlag || snapshot.isFromHistory ||
            System.currentTimeMillis() - end > 5 * DateUtils.MINUTE_IN_MILLIS)
            getString(R.string.daily_usage_stale, endFormat.format(java.util.Date(end))) else ""
        return listOf(collectedNote, staleNote).filter { it.isNotBlank() }.joinToString("\n")
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
                            getString(R.string.device_usage_last_used, "$relative · ${formatClock(it)}")
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
            val relativeTime = item.timestamp?.takeIf { it > 0 }?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
            } ?: getString(R.string.device_info_unknown)

            val batteryLine = item.batteryLevel?.takeIf { it in 0..100 }?.let {
                getString(R.string.device_usage_battery, "$it%")
            } ?: getString(R.string.device_usage_battery_unknown)

            binding.historyContainer.addView(
                createUsageRow(
                    title = item.currentAppName?.takeIf { it.isNotBlank() }
                        ?: item.currentAppPackage.orEmpty(),
                    subtitle = item.currentAppPackage ?: "",
                    technicalSubtitle = true,
                    meta = getString(R.string.device_usage_history_line, relativeTime, batteryLine)
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
            if (localName != null) {
                binding.deviceIdText.text = getString(R.string.device_usage_person, localName)
            }

            val canonicalOption = runCatching {
                linkedChildOptionsProvider.getOptions()
                    .firstOrNull { it.deviceId == childDeviceId }
            }.getOrNull()
            if (canonicalOption != null && resolveChildDeviceId() == childDeviceId) {
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
        showLoading(false)
        // Keep the last displayed snapshot readable during a temporary outage.
        binding.statusMessageText.isVisible = true
        binding.statusMessageText.setText(R.string.daily_usage_refresh_failed)
        binding.currentAppCard.isVisible = true
    }

    private fun showOnlyMessage(message: String) {
        showLoading(false)
        binding.currentAppCard.isVisible = false
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
        if (status?.raw?.get("usagePermissionGranted") == false) return true
        val rawCurrentApp = status?.raw?.get("currentApp") as? Map<*, *> ?: return false
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
        usageJob?.cancel()
        personLabelJob?.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
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
