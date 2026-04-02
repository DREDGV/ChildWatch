package ru.example.childwatch

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import ru.example.childwatch.databinding.ActivityDeviceUsageBinding
import ru.example.childwatch.network.DeviceRecentApp
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.DeviceStatusHistoryItem
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import java.util.Locale

class DeviceUsageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }

    private lateinit var binding: ActivityDeviceUsageBinding
    private lateinit var networkClient: NetworkClient
    private lateinit var effectiveContextResolver: ParentEffectiveContextResolver
    private lateinit var activeSessionStore: ParentActiveSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkClient = NetworkClient(this)
        effectiveContextResolver = ParentEffectiveContextResolver(this)
        activeSessionStore = ParentActiveSessionStore(this)

        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(
            this,
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val explicitTarget = intent.getStringExtra(EXTRA_DEVICE_ID)?.trim().orEmpty()
        if (explicitTarget.isNotBlank()) {
            activeSessionStore.updateFocusedChildId(explicitTarget)
        }

        binding.refreshButton.setOnClickListener { loadUsage(force = true) }
        loadUsage(force = true)
    }

    private fun loadUsage(force: Boolean) {
        val childDeviceId = resolveChildDeviceId()
        if (childDeviceId.isNullOrBlank()) {
            showOnlyMessage(getString(R.string.device_usage_pairing_required))
            return
        }

        binding.deviceIdText.text = getString(R.string.device_usage_device_id, childDeviceId)
        if (force) {
            showLoading(true)
        }

        lifecycleScope.launch {
            try {
                val latestDeferred = async { networkClient.getChildDeviceStatus(childDeviceId) }
                val historyDeferred = async { networkClient.getChildDeviceStatusHistory(childDeviceId, limit = 80) }

                val latestResponse = latestDeferred.await()
                val historyResponse = historyDeferred.await()

                val status = latestResponse.body()?.status.takeIf { latestResponse.isSuccessful }
                val history = historyResponse.body()?.statuses.orEmpty().takeIf { historyResponse.isSuccessful }.orEmpty()

                if (status == null && history.isEmpty()) {
                    showOnlyMessage(getString(R.string.device_usage_status_unavailable))
                    return@launch
                }

                binding.currentAppCard.isVisible = true
                binding.recentAppsCard.isVisible = true
                binding.historyCard.isVisible = true
                binding.statusMessageText.isVisible = false
                renderStatus(status)
                renderRecentApps(status)
                renderHistory(history)
                showLoading(false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showOnlyMessage(getString(R.string.device_usage_status_unavailable))
                Toast.makeText(this@DeviceUsageActivity, error.message ?: getString(R.string.device_usage_open_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderStatus(status: DeviceStatus?) {
        val currentApp = status?.currentAppName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.device_usage_current_unknown)
        binding.currentAppText.text = currentApp

        val updatedAtText = status?.timestamp?.takeIf { it > 0 }?.let {
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

    private fun renderRecentApps(status: DeviceStatus?) {
        val recentApps = status?.recentApps.orEmpty()
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

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.isVisible = isLoading
        binding.statusMessageText.isVisible = isLoading
        if (isLoading) {
            binding.statusMessageText.text = getString(R.string.device_usage_loading)
        }
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
}
