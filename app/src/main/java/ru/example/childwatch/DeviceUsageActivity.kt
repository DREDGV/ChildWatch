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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkClient = NetworkClient(this)
        effectiveContextResolver = ParentEffectiveContextResolver(this)
        linkedChildOptionsProvider = ParentLinkedChildOptionsProvider(this)
        ru.example.childwatch.location.PersonLocationStatus(this, binding.personLocationText, networkClient) {
            resolveChildDeviceId()
        }

        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(
            this,
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.refreshButton.setOnClickListener { loadUsage(force = true) }
        loadUsage(force = true)
    }

    private fun loadUsage(force: Boolean) {
        val childDeviceId = resolveChildDeviceId()
        if (childDeviceId.isNullOrBlank()) {
            showOnlyMessage(getString(R.string.device_usage_pairing_required))
            return
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

                if (latestResponse.code() == 403 || historyResponse.code() == 403) {
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
                binding.historyCard.isVisible = true
                binding.statusMessageText.isVisible = false
                renderStatus(status)
                val usageSnapshot = history.sortedByDescending { it.timestamp ?: 0L }
                    .firstOrNull { !readRecentApps(it.recentApps, it.raw).isNullOrEmpty() }
                val recentApps = if (extractUsagePermissionMissing(status)) emptyList() else
                    readRecentApps(status?.recentApps, status?.raw)
                        .ifEmpty { readRecentApps(usageSnapshot?.recentApps, usageSnapshot?.raw) }
                renderRecentApps(status, recentApps)
                val dailyRaw = status?.raw?.takeIf { it["dailyUsage"] is Map<*, *> }
                    ?: history.sortedByDescending { it.timestamp ?: 0L }
                        .firstOrNull { it.raw?.get("dailyUsage") is Map<*, *> }?.raw
                renderDailyUsage(status, dailyRaw)
                renderHistory(history.sortedByDescending { it.timestamp ?: 0L })
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

        val usageTime = (status?.raw?.get("appUsageCollectedAt") as? Number)?.toLong()
        val updatedAtText = usageTime?.takeIf { it > 0 }?.let {
            DateUtils.getRelativeTimeSpanString(
                it,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        } ?: getString(R.string.device_info_unknown)

        val batteryText = status?.batteryLevel?.takeIf { it in 0..100 }?.let {
            getString(R.string.device_usage_battery, "$it%")
        } ?: getString(R.string.device_usage_battery_unknown)

        binding.currentMetaText.text = listOf(
            getString(R.string.device_usage_last_seen, updatedAtText),
            batteryText
        ).joinToString("\n")
    }

    private fun renderDailyUsage(status: DeviceStatus?, snapshotRaw: Map<String, Any?>?) {
        binding.dailySummaryText.isVisible = true
        binding.appsSectionTitle.setText(R.string.device_usage_recent_section)
        val daily = snapshotRaw?.get("dailyUsage") as? Map<*, *>
        val start = (daily?.get("start") as? Number)?.toLong()
        val end = (daily?.get("end") as? Number)?.toLong()
        if (extractUsagePermissionMissing(status)) {
            binding.dailySummaryText.setText(R.string.device_usage_permission_missing)
            return
        }
        if (daily?.get("available") == false) {
            binding.dailySummaryText.setText(R.string.daily_usage_unavailable)
            return
        }
        if (start == null || end == null || end < start) {
            binding.dailySummaryText.setText(R.string.daily_usage_waiting)
            return
        }
        val zone = java.util.TimeZone.getTimeZone(daily?.get("timeZone") as? String ?: "UTC")
        val dateFormat = java.text.SimpleDateFormat("d MMMM", Locale.getDefault()).apply { timeZone = zone }
        val clockFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }
        val rows = (daily?.get("apps") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            .filter { ((it["totalTimeInForeground"] as? Number)?.toLong() ?: 0L) > 0L }
            .sortedByDescending { (it["totalTimeInForeground"] as? Number)?.toLong() ?: 0L }
        val total = rows.sumOf { (it["totalTimeInForeground"] as? Number)?.toLong() ?: 0L }
        binding.appsSectionTitle.text = getString(R.string.daily_usage_heading, dateFormat.format(java.util.Date(start)))
        binding.dailySummaryText.text = getString(R.string.daily_usage_summary,
            formatDuration(total), rows.size, clockFormat.format(java.util.Date(end))) +
            if (System.currentTimeMillis() - end > 5 * DateUtils.MINUTE_IN_MILLIS)
                "\n" + getString(R.string.daily_usage_stale) else ""
        binding.recentAppsContainer.removeAllViews()
        binding.recentAppsEmptyText.isVisible = rows.isEmpty()
        binding.recentAppsEmptyText.setText(R.string.daily_usage_empty)
        rows.forEach { app ->
            val duration = (app["totalTimeInForeground"] as? Number)?.toLong() ?: 0L
            val row = createUsageRow(
                title = app["appName"] as? String ?: app["packageName"] as? String ?: "",
                subtitle = "",
                meta = getString(R.string.daily_usage_app_time, formatDuration(duration),
                    if (total > 0) (duration * 100 / total).toInt() else 0)
            )
            row.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.usageShareBar).apply {
                isVisible = true
                progress = if (total > 0) (duration * 100 / total).toInt().coerceIn(0, 100) else 0
            }
            binding.recentAppsContainer.addView(row)
        }
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
                    meta = listOfNotNull(
                        app.lastUsed?.let {
                            getString(
                                R.string.device_usage_last_used,
                                DateUtils.getRelativeTimeSpanString(
                                    it,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                )
                            )
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
                    meta = getString(R.string.device_usage_history_line, relativeTime, batteryLine)
                )
            )
        }
    }

    private fun createUsageRow(title: String, subtitle: String, meta: String) =
        LayoutInflater.from(this).inflate(R.layout.item_device_usage_row, binding.recentAppsContainer, false).apply {
            findViewById<TextView>(R.id.titleText).text = title
            findViewById<TextView>(R.id.subtitleText).apply {
                text = subtitle
                isVisible = subtitle.isNotBlank()
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
