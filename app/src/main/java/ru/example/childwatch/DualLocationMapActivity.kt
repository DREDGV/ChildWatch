package ru.example.childwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.ParentLocation
import ru.example.childwatch.database.repository.ParentLocationRepository
import ru.example.childwatch.databinding.ActivityDualLocationMapBinding
import ru.example.childwatch.location.LocationManager
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.ParentLocationData
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.contacts.ContactFeatures
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.contacts.ContactRoles
import ru.example.childwatch.utils.SecureSettingsManager
import java.io.File
import kotlin.math.*

/**
 * Dual Location Map Activity
 *
 * Shared map screen that can render the current device and a linked contact:
 * - Parent role: self + child via /api/location/latest/:childId
 * - Child role: self + parent via /api/location/parent/latest/:parentId
 *
 * Intent extras:
 * - MY_ROLE: "parent" or "child"
 * - MY_ID: current device ID
 * - OTHER_ID: linked device ID
 */
class DualLocationMapActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "DualLocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val AUTO_REFRESH_INTERVAL = 30_000L // 30 seconds
        private const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
        private const val HISTORY_LIMIT = 1000
        private const val STOP_RADIUS_METERS = 80f
        private const val STOP_MIN_DURATION_MS = 10 * 60 * 1000L
        private const val MOVING_SPEED_THRESHOLD_MPS = 1.4f
        private const val MAP_CACHE_MY = "map_cache_my"
        private const val MAP_CACHE_OTHER = "map_cache_other"
        
        const val EXTRA_MY_ROLE = "MY_ROLE"
        const val EXTRA_MY_ID = "MY_ID"
        const val EXTRA_OTHER_ID = "OTHER_ID"
        const val EXTRA_SHOW_ALL = "SHOW_ALL"
        
        const val ROLE_PARENT = "parent"
        const val ROLE_CHILD = "child"
        
        fun createIntent(context: Context, myRole: String, myId: String, otherId: String): Intent {
            return Intent(context, DualLocationMapActivity::class.java).apply {
                putExtra(EXTRA_MY_ROLE, myRole)
                putExtra(EXTRA_MY_ID, myId)
                putExtra(EXTRA_OTHER_ID, otherId)
            }
        }
    }
    
    private lateinit var binding: ActivityDualLocationMapBinding
    private lateinit var mapView: MapView
    private lateinit var prefs: SharedPreferences
    private lateinit var database: ChildWatchDatabase
    private lateinit var locationManager: LocationManager
    private lateinit var networkClient: NetworkClient
    private lateinit var parentLocationRepository: ParentLocationRepository
    
    private var myRole: String = ROLE_PARENT
    private var myId: String = ""
    private var otherId: String = ""
    private var showAllContacts: Boolean = false
    private var limitedMode: Boolean = false
    
    private var myMarker: Marker? = null
    private var otherMarker: Marker? = null
    private var connectionLine: Polyline? = null
    private val contactMarkers = mutableMapOf<String, Marker>()
    private var historyLine: Polyline? = null
    private var historyStartMarker: Marker? = null
    private var historyEndMarker: Marker? = null
    
    private var myLatitude: Double? = null
    private var myLongitude: Double? = null
    
    private var isMapReady = false
    private var autoRefreshJob: Job? = null
    private var loadLocationsJob: Job? = null
    private var autoFitEnabled = true
    private var lastMyPoint: GeoPoint? = null
    private var lastOtherPoint: GeoPoint? = null
    private var resolvedParentId: String = ""
    private var resolvedOtherId: String = ""

    private data class CachedLocation(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val speed: Float?
    )

    private data class MovementStop(
        val startTimestamp: Long,
        val endTimestamp: Long
    ) {
        val durationMs: Long
            get() = (endTimestamp - startTimestamp).coerceAtLeast(0L)
    }

    private data class RouteSummary(
        val pointCount: Int,
        val totalDistanceMeters: Float,
        val firstTimestamp: Long?,
        val lastTimestamp: Long?,
        val stopCount: Int,
        val longestStopDurationMs: Long,
        val currentStopDurationMs: Long?,
        val currentlyMoving: Boolean
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmdroidEarly()

        try {
            binding = ActivityDualLocationMapBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Get role and IDs from intent
            myRole = intent.getStringExtra(EXTRA_MY_ROLE) ?: ROLE_PARENT
            myId = intent.getStringExtra(EXTRA_MY_ID)?.trim().orEmpty()
            otherId = intent.getStringExtra(EXTRA_OTHER_ID)?.trim().orEmpty()
            showAllContacts = intent.getBooleanExtra(EXTRA_SHOW_ALL, false)

            val localPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            if (myId.isBlank()) {
                myId = listOf(
                    localPrefs.getString("device_id", null),
                    localPrefs.getString("parent_device_id", null),
                    legacyPrefs.getString("device_id", null),
                    legacyPrefs.getString("parent_device_id", null)
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }

            if (otherId.isBlank()) {
                val secureSettings = SecureSettingsManager(this)
                val excluded = listOf(
                    myId,
                    localPrefs.getString("parent_device_id", null),
                    localPrefs.getString("linked_parent_device_id", null),
                    legacyPrefs.getString("parent_device_id", null),
                    legacyPrefs.getString("linked_parent_device_id", null)
                )
                    .mapNotNull { it?.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                otherId = listOf(
                    localPrefs.getString("child_device_id", null),
                    secureSettings.getChildDeviceId(),
                    legacyPrefs.getString("child_device_id", null),
                    localPrefs.getString("selected_device_id", null),
                    legacyPrefs.getString("selected_device_id", null)
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() && it !in excluded }
                    .orEmpty()
            }

            limitedMode = !showAllContacts && otherId.isBlank()
            resolvedParentId = if (myRole == ROLE_PARENT) myId else otherId
            resolvedOtherId = otherId

            // Initialize components
            prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            database = ChildWatchDatabase.getInstance(this)
            locationManager = LocationManager(this)
            networkClient = NetworkClient(this)
            parentLocationRepository = ParentLocationRepository(database.parentLocationDao())

            // Setup UI
            setupToolbar()
            setupMap()
            setupRefreshButton()
            setupCenterButtons()
            setupHistoryButton()

            // Load locations
            checkPermissionsAndLoad()
        } catch (e: Exception) {
            handleStartupFailure(e)
        }
    }

    private fun handleStartupFailure(error: Throwable) {
        Log.e(TAG, "Map startup failed", error)
        val reason = error.message ?: getString(R.string.map_unknown_error)
        if (::binding.isInitialized) {
            binding.loadingIndicator.visibility = View.GONE
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.map_startup_failed_with_reason, reason)
            return
        }
        Toast.makeText(this, getString(R.string.map_startup_failed), Toast.LENGTH_LONG).show()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Set title based on role
        supportActionBar?.title = if (showAllContacts) {
            getString(R.string.map_title_contacts)
        } else {
            when (myRole) {
                ROLE_PARENT -> getString(R.string.map_title_where_child)
                ROLE_CHILD -> getString(R.string.map_title_where_parents)
                else -> getString(R.string.map_title_default)
            }
        }
        if (limitedMode) {
            binding.toolbar.subtitle = getString(R.string.map_limited_mode_subtitle)
        }
    }
    
    private fun setupMap() {
        try {
            mapView = binding.mapView
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.setMultiTouchControls(true)
            mapView.controller.setZoom(15.0)

            mapView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    if (autoFitEnabled) {
                        autoFitEnabled = false
                        updateAutoFitUi()
                    }
                }
                false
            }
            isMapReady = true
        } catch (e: Exception) {
            isMapReady = false
            Log.e(TAG, "Map view init failed", e)
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(
                R.string.map_init_failed_with_reason,
                e.message ?: getString(R.string.map_unknown_error)
            )
        }
    }
    
    private fun setupRefreshButton() {
        binding.refreshButton.setOnClickListener {
            loadLocations()
        }
    }

    private fun setupCenterButtons() {
        updateCenterIcons()
        updateAutoFitUi()
        if (limitedMode) {
            binding.centerOtherButton.isEnabled = false
            binding.centerOtherButton.alpha = 0.4f
            binding.centerBothButton.isEnabled = false
            binding.centerBothButton.alpha = 0.4f
        }

        binding.centerBothButton.setOnClickListener {
            autoFitEnabled = true
            updateAutoFitUi()
            centerOnAvailable()
        }

        binding.centerMyButton.setOnClickListener {
            autoFitEnabled = false
            updateAutoFitUi()
            if (!centerOnPoint(lastMyPoint)) {
                Toast.makeText(this, getString(R.string.map_my_location_not_available), Toast.LENGTH_SHORT).show()
            }
        }

        binding.centerOtherButton.setOnClickListener {
            autoFitEnabled = false
            updateAutoFitUi()
            if (!centerOnPoint(lastOtherPoint)) {
                Toast.makeText(this, getString(R.string.map_other_location_not_available), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCenterIcons() {
        val myIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_parent_marker
            ROLE_CHILD -> R.drawable.ic_child_marker
            else -> R.drawable.ic_parent_marker
        }
        val otherIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_child_marker
            ROLE_CHILD -> R.drawable.ic_parent_marker
            else -> R.drawable.ic_child_marker
        }
        binding.centerMyButton.setImageResource(myIcon)
        binding.centerOtherButton.setImageResource(otherIcon)
    }

    private fun updateAutoFitUi() {
        binding.centerBothButton.alpha = if (autoFitEnabled) 1.0f else 0.6f
    }

    private fun centerOnPoint(point: GeoPoint?): Boolean {
        if (point == null) return false
        mapView.controller.setCenter(point)
        return true
    }

    private fun centerOnAvailable() {
        val myPoint = lastMyPoint
        val otherPoint = lastOtherPoint
        when {
            myPoint != null && otherPoint != null -> {
                centerMapOnBothLocations(
                    myPoint.latitude,
                    myPoint.longitude,
                    otherPoint.latitude,
                    otherPoint.longitude
                )
            }
            myPoint != null -> centerOnPoint(myPoint)
            otherPoint != null -> centerOnPoint(otherPoint)
        }
    }
    
    private fun setupHistoryButton() {
        if (showAllContacts || historyTargetId().isNullOrBlank()) {
            binding.historyButton.visibility = View.GONE
            return
        }
        binding.historyButton.setOnClickListener {
            showHistoryPeriodDialog()
        }
    }
    
    private fun showHistoryPeriodDialog() {
        val periods = arrayOf(
            getString(R.string.map_history_today),
            getString(R.string.map_history_yesterday),
            getString(R.string.map_history_week),
            getString(R.string.map_history_month)
        )
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle(R.string.map_history_select_period)
        builder.setItems(periods) { _, which ->
            val now = System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startOfToday = calendar.timeInMillis
            val (from, to) = when (which) {
                0 -> { // Today
                    Pair(startOfToday, now)
                }
                1 -> { // Yesterday
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = startOfToday }
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    val startOfYesterday = cal.timeInMillis
                    val endOfYesterday = startOfToday - 1
                    Pair(startOfYesterday, endOfYesterday)
                }
                2 -> { // Week
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                    Pair(cal.timeInMillis, now)
                }
                3 -> { // Month
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -30)
                    Pair(cal.timeInMillis, now)
                }
                else -> Pair(now - 86400000, now)
            }
            loadLocationHistory(from, to)
        }
        builder.show()
    }
    
    private fun loadLocationHistory(fromTimestamp: Long, toTimestamp: Long) {
        lifecycleScope.launch {
            try {
                val targetId = historyTargetId()
                if (targetId.isNullOrBlank()) {
                    Toast.makeText(
                        this@DualLocationMapActivity,
                        getString(R.string.map_other_location_not_available),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val history = when (myRole) {
                    ROLE_CHILD -> networkClient.getParentLocationHistory(
                        parentId = targetId,
                        fromTimestamp = fromTimestamp,
                        toTimestamp = toTimestamp,
                        limit = HISTORY_LIMIT
                    )
                    else -> networkClient.getLocationHistory(
                        deviceId = targetId,
                        fromTimestamp = fromTimestamp,
                        toTimestamp = toTimestamp,
                        limit = HISTORY_LIMIT
                    )
                }
                
                if (history.isNullOrEmpty()) {
                    Toast.makeText(
                        this@DualLocationMapActivity,
                        getString(R.string.map_history_no_data),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                
                // Render the fetched track on the map.
                displayLocationHistory(history)
                showHistorySummary(history)
                
                Toast.makeText(
                    this@DualLocationMapActivity,
                    getString(R.string.map_history_loaded_points, history.size),
                    Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading location history", e)
                Toast.makeText(
                    this@DualLocationMapActivity,
                    getString(
                        R.string.map_history_load_error,
                        e.message ?: getString(R.string.map_unknown_error)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun displayLocationHistory(history: List<ru.example.childwatch.network.ParentLocationData>) {
        val validHistory = history
            .sortedBy { it.timestamp }
            .filter { isValidCoordinate(it.latitude, it.longitude) }
        if (validHistory.isEmpty()) return
        
        // Remove the previous history overlay before drawing a new one.
        historyLine?.let { mapView.overlays.remove(it) }
        historyStartMarker?.let { mapView.overlays.remove(it) }
        historyEndMarker?.let { mapView.overlays.remove(it) }
        historyLine = null
        historyStartMarker = null
        historyEndMarker = null
        
        // Create a polyline for the history track.
        historyLine = Polyline(mapView).apply {
            id = "history_line"
            
            // Convert all locations into map points.
            val points = validHistory.map { GeoPoint(it.latitude, it.longitude) }
            setPoints(points)
            
            // Keep the route visually distinct from the live markers.
            outlinePaint.color = Color.parseColor("#4285F4") // Google Blue
            outlinePaint.strokeWidth = 8f
            outlinePaint.alpha = 200
        }
        
        mapView.overlays.add(0, historyLine) // Keep the line below the markers.
        
        // Add route start/end markers for context.
        val firstPoint = validHistory.firstOrNull()
        val lastPoint = validHistory.lastOrNull()
        val historyIcon = ContextCompat.getDrawable(this@DualLocationMapActivity, otherMarkerIconRes())
        
        if (firstPoint != null && lastPoint != null && firstPoint != lastPoint) {
            // Start marker.
            historyStartMarker = Marker(mapView).apply {
                position = GeoPoint(firstPoint.latitude, firstPoint.longitude)
                title = getString(R.string.map_history_route_start)
                snippet = formatTimestamp(firstPoint.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = historyIcon
            }
            
            // End marker.
            historyEndMarker = Marker(mapView).apply {
                position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                title = getString(R.string.map_history_route_end)
                snippet = formatTimestamp(lastPoint.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = historyIcon
            }
            
            mapView.overlays.add(historyStartMarker)
            mapView.overlays.add(historyEndMarker)
        }
        
        mapView.invalidate()
        
        // Fit the camera to the route bounds.
        if (validHistory.isNotEmpty()) {
            safeZoomToBoundingBox(
                validHistory.map { GeoPoint(it.latitude, it.longitude) },
                validHistory.lastOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
            )
        }
    }

    private fun normalizeTimestampMillis(raw: Long?): Long? {
        if (raw == null || raw <= 0L) return null
        return when {
            raw < 10_000_000_000L -> raw * 1000L // seconds -> millis
            raw > 10_000_000_000_000L -> raw / 1000L // micros -> millis
            else -> raw
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val normalized = normalizeTimestampMillis(timestamp) ?: timestamp
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(normalized))
    }

    private fun isStale(timestamp: Long): Boolean {
        val normalized = normalizeTimestampMillis(timestamp) ?: return false
        return System.currentTimeMillis() - normalized > STALE_THRESHOLD_MS
    }

    private fun buildMarkerSnippet(label: String, timestamp: Long?): String {
        val normalized = normalizeTimestampMillis(timestamp) ?: return label
        val timeInfo = formatTimestamp(normalized)
        return if (isStale(normalized)) {
            getString(R.string.map_marker_snippet_stale, label, timeInfo)
        } else {
            getString(R.string.map_marker_snippet_fresh, label, timeInfo)
        }
    }

    private fun historyTargetId(): String? = when (myRole) {
        ROLE_CHILD -> resolvedParentId.takeIf { it.isNotBlank() }
            ?: resolvePairIds()?.first
            ?: resolveParentIdCandidates().firstOrNull()
        else -> resolvedOtherId.takeIf { it.isNotBlank() }
            ?: resolvePairIds()?.second
            ?: otherId.trim().takeIf { it.isNotBlank() }
    }

    private fun resolveParentIdCandidates(): List<String> {
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return listOf(
            resolvedParentId,
            otherId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        ).mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && it != myId.trim() }
            .distinct()
    }

    private suspend fun resolveChildIdCandidates(): List<String> {
        val secureSettings = SecureSettingsManager(this)
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val childIdsFromDb = withContext(Dispatchers.IO) {
            database.childDao().getAll().mapNotNull { it.deviceId?.trim() }
        }
        val excluded = listOf(
            myId,
            resolvedParentId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        ).mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        return listOf(
            resolvedOtherId,
            otherId,
            secureSettings.getChildDeviceId(),
            prefs.getString("child_device_id", null),
            prefs.getString("selected_device_id", null),
            legacyPrefs.getString("child_device_id", null),
            legacyPrefs.getString("selected_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .plus(childIdsFromDb)
            .filter { it.isNotBlank() && it !in excluded }
            .distinct()
    }

    private fun resolveSelfParentIdCandidates(): List<String> {
        val secureSettings = SecureSettingsManager(this)
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return listOf(
            resolvedParentId,
            myId,
            secureSettings.getDeviceId(),
            prefs.getString("device_id", null),
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        ).mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun fetchResolvedPairSnapshot(
        onResolved: (parentId: String, childId: String) -> Unit
    ): ru.example.childwatch.network.LocationPairData? {
        val candidates = when (myRole) {
            ROLE_PARENT -> {
                val parentIds = resolveSelfParentIdCandidates()
                val childIds = resolveChildIdCandidates()
                parentIds.flatMap { parentId -> childIds.map { childId -> parentId to childId } }
            }
            ROLE_CHILD -> {
                val childIds = listOf(myId, prefs.getString("device_id", null))
                    .mapNotNull { it?.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                resolveParentIdCandidates().flatMap { parentId -> childIds.map { childId -> parentId to childId } }
            }
            else -> emptyList()
        }.distinct()

        var fallback: Pair<ru.example.childwatch.network.LocationPairData, Pair<String, String>>? = null
        for ((parentId, childId) in candidates) {
            val snapshot = withContext(Dispatchers.IO) { networkClient.getLocationPair(parentId, childId) } ?: continue
            if (fallback == null) {
                fallback = snapshot to (parentId to childId)
            }
            val linkedLocation = when (myRole) {
                ROLE_PARENT -> snapshot.child?.takeIfUsable()
                ROLE_CHILD -> snapshot.parent?.takeIfUsable()
                else -> null
            }
            if (linkedLocation != null) {
                onResolved(parentId, childId)
                return snapshot
            }
        }

        fallback?.let { (snapshot, ids) ->
            onResolved(ids.first, ids.second)
            return snapshot
        }
        return null
    }

    private fun otherMarkerIconRes(): Int =
        if (myRole == ROLE_CHILD) R.drawable.ic_parent_marker else R.drawable.ic_child_marker

    private fun showHistorySummary(history: List<ParentLocationData>) {
        val summary = buildRouteSummary(history)
        if (summary.pointCount == 0) return

        val currentStatus = getString(
            if (summary.currentlyMoving) {
                R.string.map_history_status_moving
            } else {
                R.string.map_history_status_stationary
            }
        )

        val lines = mutableListOf(
            getString(R.string.map_history_summary_distance, formatDistance(summary.totalDistanceMeters)),
            getString(R.string.map_history_summary_duration, formatDuration((summary.lastTimestamp ?: 0L) - (summary.firstTimestamp ?: 0L))),
            getString(R.string.map_history_summary_points, summary.pointCount),
            getString(R.string.map_history_summary_last_seen, formatDateTime(summary.lastTimestamp)),
            getString(R.string.map_history_summary_stops, summary.stopCount),
            getString(R.string.map_history_summary_current_status, currentStatus)
        )

        lines += if (summary.longestStopDurationMs > 0L) {
            getString(R.string.map_history_summary_longest_stop, formatDuration(summary.longestStopDurationMs))
        } else {
            getString(R.string.map_history_summary_no_stop)
        }

        summary.currentStopDurationMs?.let { duration ->
            lines += getString(R.string.map_history_summary_current_stop, formatDuration(duration))
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_history_summary_title)
            .setMessage(lines.joinToString(separator = "\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildRouteSummary(history: List<ParentLocationData>): RouteSummary {
        val sortedHistory = history
            .mapNotNull { item ->
                val normalizedTimestamp = normalizeTimestampMillis(item.timestamp) ?: return@mapNotNull null
                if (!isValidCoordinate(item.latitude, item.longitude)) return@mapNotNull null
                item.copy(timestamp = normalizedTimestamp)
            }
            .sortedBy { it.timestamp }

        if (sortedHistory.isEmpty()) {
            return RouteSummary(0, 0f, null, null, 0, 0L, null, currentlyMoving = false)
        }

        var totalDistanceMeters = 0f
        for (index in 1 until sortedHistory.size) {
            val previous = sortedHistory[index - 1]
            val current = sortedHistory[index]
            totalDistanceMeters += calculateDistance(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude
            )
        }

        val stops = detectStops(sortedHistory)
        val currentlyMoving = isCurrentlyMoving(sortedHistory)
        return RouteSummary(
            pointCount = sortedHistory.size,
            totalDistanceMeters = totalDistanceMeters,
            firstTimestamp = sortedHistory.firstOrNull()?.timestamp,
            lastTimestamp = sortedHistory.lastOrNull()?.timestamp,
            stopCount = stops.size,
            longestStopDurationMs = stops.maxOfOrNull { it.durationMs } ?: 0L,
            currentStopDurationMs = detectCurrentStopDurationMs(sortedHistory, currentlyMoving),
            currentlyMoving = currentlyMoving
        )
    }

    private fun detectStops(sortedHistory: List<ParentLocationData>): List<MovementStop> {
        if (sortedHistory.size < 2) return emptyList()

        val stops = mutableListOf<MovementStop>()
        var clusterStart = sortedHistory.first()
        var clusterEnd = sortedHistory.first()
        var anchor = sortedHistory.first()

        for (point in sortedHistory.drop(1)) {
            val distance = calculateDistance(anchor.latitude, anchor.longitude, point.latitude, point.longitude)
            if (distance <= STOP_RADIUS_METERS) {
                clusterEnd = point
            } else {
                val stop = MovementStop(clusterStart.timestamp, clusterEnd.timestamp)
                if (stop.durationMs >= STOP_MIN_DURATION_MS) {
                    stops += stop
                }
                clusterStart = point
                clusterEnd = point
                anchor = point
            }
        }

        val lastStop = MovementStop(clusterStart.timestamp, clusterEnd.timestamp)
        if (lastStop.durationMs >= STOP_MIN_DURATION_MS) {
            stops += lastStop
        }

        return stops
    }

    private fun detectCurrentStopDurationMs(
        sortedHistory: List<ParentLocationData>,
        currentlyMoving: Boolean
    ): Long? {
        if (currentlyMoving || sortedHistory.size < 2) return null

        val anchor = sortedHistory.last()
        var startIndex = sortedHistory.lastIndex
        for (index in sortedHistory.lastIndex - 1 downTo 0) {
            val point = sortedHistory[index]
            val distance = calculateDistance(anchor.latitude, anchor.longitude, point.latitude, point.longitude)
            if (distance > STOP_RADIUS_METERS) break
            startIndex = index
        }

        val duration = anchor.timestamp - sortedHistory[startIndex].timestamp
        return duration.takeIf { it >= STOP_MIN_DURATION_MS }
    }

    private fun isCurrentlyMoving(sortedHistory: List<ParentLocationData>): Boolean {
        val lastPoint = sortedHistory.lastOrNull() ?: return false
        if ((lastPoint.speed ?: 0f) >= MOVING_SPEED_THRESHOLD_MPS) {
            return true
        }

        if (sortedHistory.size < 2) return false

        val recentPoints = sortedHistory.takeLast(minOf(4, sortedHistory.size))
        var recentDistance = 0f
        for (index in 1 until recentPoints.size) {
            val previous = recentPoints[index - 1]
            val current = recentPoints[index]
            recentDistance += calculateDistance(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude
            )
        }

        val durationMs = recentPoints.last().timestamp - recentPoints.first().timestamp
        if (durationMs <= 0L) return false

        val avgSpeed = recentDistance / (durationMs / 1000f)
        return avgSpeed >= MOVING_SPEED_THRESHOLD_MPS
    }

    private fun formatDistance(distanceMeters: Float): String {
        return if (distanceMeters < 1000f) {
            getString(R.string.map_distance_meters, distanceMeters.toInt())
        } else {
            getString(R.string.map_distance_km, distanceMeters / 1000f)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs < DateUtils.MINUTE_IN_MILLIS) {
            return getString(R.string.map_duration_under_minute)
        }

        val totalMinutes = durationMs / DateUtils.MINUTE_IN_MILLIS
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60

        return when {
            days > 0 -> getString(R.string.map_duration_days_hours, days, hours)
            hours > 0 -> getString(R.string.map_duration_hours_minutes, hours, minutes)
            else -> getString(R.string.map_duration_minutes, minutes)
        }
    }

    private fun formatDateTime(timestamp: Long?): String {
        val normalized = normalizeTimestampMillis(timestamp) ?: return getString(R.string.map_location_unavailable)
        return DateUtils.formatDateTime(
            this,
            normalized,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
    }

    private fun configureOsmdroidEarly() {
        runCatching {
            val basePath = File(filesDir, "osmdroid").apply { mkdirs() }
            val tileCachePath = File(cacheDir, "osmdroid_tiles").apply { mkdirs() }
            val config = Configuration.getInstance()
            config.osmdroidBasePath = basePath
            config.osmdroidTileCache = tileCachePath
            config.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            config.userAgentValue = packageName
        }.onFailure {
            Log.w(TAG, "OSMdroid early init failed, fallback to defaults: ${it.message}")
        }
    }

    private fun cacheKeyMy(): String = "${MAP_CACHE_MY}_${myRole}"

    private fun cacheKeyOther(): String = "${MAP_CACHE_OTHER}_${resolvedOtherId.ifBlank { otherId }}"

    private fun saveCachedLocation(key: String, lat: Double, lon: Double, timestamp: Long, speed: Float?) {
        val normalizedTimestamp = normalizeTimestampMillis(timestamp) ?: System.currentTimeMillis()
        val speedValue = speed?.toString() ?: ""
        prefs.edit().putString(key, "$lat|$lon|$normalizedTimestamp|$speedValue").apply()
    }

    private fun loadCachedLocation(key: String): CachedLocation? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split("|")
        if (parts.size < 3) return null
        return try {
            val lat = parts[0].toDouble()
            val lon = parts[1].toDouble()
            val ts = normalizeTimestampMillis(parts[2].toLong()) ?: return null
            val speed = parts.getOrNull(3)?.toFloatOrNull()
            CachedLocation(lat, lon, ts, speed)
        } catch (e: Exception) {
            null
        }
    }

    private fun CachedLocation.toParentLocationData(deviceId: String): ParentLocationData {
        return ParentLocationData(
            parentId = deviceId,
            latitude = latitude,
            longitude = longitude,
            accuracy = 0f,
            timestamp = timestamp,
            battery = null,
            speed = speed,
            bearing = null
        )
    }
    
    private fun checkPermissionsAndLoad() {
        if (hasLocationPermission()) {
            loadLocations()
            startAutoRefresh()
        } else {
            requestLocationPermission()
            // Still try to load other device location without my GPS
            loadLocations()
            startAutoRefresh()
        }
    }
    
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadLocations()
                startAutoRefresh()
            } else {
                Toast.makeText(this, getString(R.string.map_permission_location_denied), Toast.LENGTH_LONG).show()
                loadLocations()
                startAutoRefresh()
            }
        }
    }
    
    private fun loadLocations() {
        if (!isMapReady || !::binding.isInitialized || !::mapView.isInitialized) return
        if (isFinishing || isDestroyed) return
        if (loadLocationsJob?.isActive == true) {
            Log.d(TAG, "loadLocations already running, skip overlapping call")
            return
        }

        binding.loadingIndicator.visibility = View.VISIBLE
        binding.errorCard.visibility = View.GONE

        if (showAllContacts) {
            loadAllContactsLocations()
            return
        }

        if (limitedMode) {
            loadLocationsJob = lifecycleScope.launch {
                try {
                    val cachedMy = loadCachedLocation(cacheKeyMy())?.takeIfFresh()
                    val myLocation = if (hasLocationPermission()) {
                        withContext(Dispatchers.IO) { locationManager.getCurrentLocation() }
                    } else {
                        null
                    }

                    val myLat = myLocation?.latitude ?: cachedMy?.latitude
                    val myLon = myLocation?.longitude ?: cachedMy?.longitude
                    val myTs = myLocation?.time ?: cachedMy?.timestamp

                    if (myLocation != null && isValidCoordinate(myLocation.latitude, myLocation.longitude)) {
                        myLatitude = myLocation.latitude
                        myLongitude = myLocation.longitude
                        saveCachedLocation(
                            cacheKeyMy(),
                            myLocation.latitude,
                            myLocation.longitude,
                            myLocation.time,
                            if (myLocation.hasSpeed()) myLocation.speed else null
                        )
                    }

                    if (myLat != null && myLon != null) {
                        val myIcon = when (myRole) {
                            ROLE_PARENT -> R.drawable.ic_parent_marker
                            ROLE_CHILD -> R.drawable.ic_child_marker
                            else -> R.drawable.ic_parent_marker
                        }
                        displaySingleLocation(
                            lat = myLat,
                            lon = myLon,
                            title = selfMarkerTitle(),
                            iconRes = myIcon,
                            timestamp = myTs,
                            snippetLabel = getString(R.string.map_my_location)
                        )
                    }

                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = getString(R.string.map_limited_mode_subtitle)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading locations in limited mode", e)
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = getString(
                        R.string.map_location_load_error,
                        e.message ?: getString(R.string.map_unknown_error)
                    )
                }
            }
            return
        }
        
        loadLocationsJob = lifecycleScope.launch {
            try {
                val cachedMy = loadCachedLocation(cacheKeyMy())?.takeIfUsable()
                val cachedOther = loadCachedLocation(cacheKeyOther())?.takeIfUsable()

                if (cachedMy != null || cachedOther != null) {
                    displayAvailableLocations(
                        myLat = cachedMy?.latitude,
                        myLon = cachedMy?.longitude,
                        myTimestamp = cachedMy?.timestamp,
                        otherLocation = cachedOther?.toParentLocationData(resolvedOtherId.ifBlank { otherId })
                    )
                    binding.loadingIndicator.visibility = View.GONE
                }

                val myLocation = if (hasLocationPermission()) {
                    withContext(Dispatchers.IO) { locationManager.getCurrentLocation() }
                } else {
                    null
                }
                
                if (myLocation != null && isValidCoordinate(myLocation.latitude, myLocation.longitude)) {
                    myLatitude = myLocation.latitude
                    myLongitude = myLocation.longitude
                    saveCachedLocation(
                        cacheKeyMy(),
                        myLocation.latitude,
                        myLocation.longitude,
                        myLocation.time,
                        if (myLocation.hasSpeed()) myLocation.speed else null
                    )
                } else {
                    Log.w(TAG, "My location not available")
                }
                
                var resolvedPairParentId = resolvedParentId.ifBlank { myId }
                var resolvedPairOtherId = resolvedOtherId.ifBlank { otherId }
                val pairSnapshot = fetchResolvedPairSnapshot(
                    onResolved = { parentId, childId ->
                        resolvedPairParentId = parentId
                        resolvedPairOtherId = childId
                    }
                )
                if (resolvedPairParentId.isNotBlank()) {
                    resolvedParentId = resolvedPairParentId
                }
                if (resolvedPairOtherId.isNotBlank()) {
                    resolvedOtherId = resolvedPairOtherId
                }

                val serverSelfLocation = when (myRole) {
                    ROLE_PARENT -> pairSnapshot?.parent?.takeIfUsable()
                    ROLE_CHILD -> pairSnapshot?.child?.takeIfUsable()
                    else -> null
                }

                // Fetch the linked device location from one server snapshot first.
                val otherLocation = when (myRole) {
                    ROLE_PARENT -> pairSnapshot?.child?.takeIfUsable()
                    ROLE_CHILD -> pairSnapshot?.parent?.takeIfUsable()
                    else -> null
                } ?: withContext(Dispatchers.IO) {
                    if (myRole == ROLE_CHILD) {
                        val parentCandidates = resolveParentIdCandidates()
                        val cachedParent = parentCandidates.firstNotNullOfOrNull { parentId ->
                            parentLocationRepository.getLatestLocation(parentId)?.takeIfUsable()?.also {
                                resolvedOtherId = parentId
                            }
                        }
                        val fromServer = parentCandidates.firstNotNullOfOrNull { parentId ->
                            networkClient.getLatestParentLocation(parentId)?.takeIfUsable()?.also {
                                resolvedOtherId = parentId
                            }
                        }
                        fromServer ?: cachedParent?.toNetworkModel()
                    } else {
                        resolveChildIdCandidates().firstNotNullOfOrNull { childId ->
                            networkClient.getLatestLocation(childId)?.takeIfUsable()?.also {
                                resolvedOtherId = childId
                            }
                        }
                    }
                }

                if (otherLocation != null) {
                    saveCachedLocation(
                        cacheKeyOther(),
                        otherLocation.latitude,
                        otherLocation.longitude,
                        otherLocation.timestamp,
                        otherLocation.speed
                    )
                }

                if (serverSelfLocation != null) {
                    saveCachedLocation(
                        cacheKeyMy(),
                        serverSelfLocation.latitude,
                        serverSelfLocation.longitude,
                        serverSelfLocation.timestamp,
                        serverSelfLocation.speed
                    )
                }

                val myLatFinal = myLatitude ?: serverSelfLocation?.latitude ?: cachedMy?.latitude
                val myLonFinal = myLongitude ?: serverSelfLocation?.longitude ?: cachedMy?.longitude
                val myTsFinal = myLocation?.time ?: serverSelfLocation?.timestamp ?: cachedMy?.timestamp
                val otherFinal = otherLocation ?: cachedOther?.toParentLocationData(resolvedOtherId.ifBlank { otherId.ifBlank { "paired-device" } })
                
                if (myLatFinal != null && myLonFinal != null || otherFinal != null) {
                    displayAvailableLocations(
                        myLat = myLatFinal,
                        myLon = myLonFinal,
                        myTimestamp = myTsFinal,
                        otherLocation = otherFinal
                    )
                    binding.loadingIndicator.visibility = View.GONE
                    if (otherFinal == null && !limitedMode) {
                        binding.errorCard.visibility = View.VISIBLE
                        binding.errorText.text = otherLocationUnavailableMessage()
                    }
                } else {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = otherLocationUnavailableMessage()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = getString(
                    R.string.map_location_load_error,
                    e.message ?: getString(R.string.map_unknown_error)
                )
            }
        }
    }
    
    private fun displayLocations(
        myLat: Double,
        myLon: Double,
        otherLat: Double,
        otherLon: Double,
        otherSpeed: Float?,
        myTimestamp: Long?,
        otherTimestamp: Long?
    ) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (!isValidCoordinate(myLat, myLon) || !isValidCoordinate(otherLat, otherLon)) {
            Log.w(TAG, "displayLocations skipped due to invalid coordinates: my=($myLat,$myLon), other=($otherLat,$otherLon)")
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.map_invalid_coordinates)
            return
        }

        // Remove the previous live markers and connection line.
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        
        // Use role-specific icons for the current device.
        val myMarkerIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_parent_marker
            ROLE_CHILD -> R.drawable.ic_child_marker
            else -> R.drawable.ic_parent_marker
        }
        
        val myMarkerTitle = selfMarkerTitle()
        
        myMarker = Marker(mapView).apply {
            position = GeoPoint(myLat, myLon)
            title = myMarkerTitle
            snippet = buildMarkerSnippet(getString(R.string.map_my_location), myTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, myMarkerIcon)
        }
        mapView.overlays.add(myMarker)
        
        // Use the opposite role icon for the linked device.
        val otherMarkerIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_child_marker
            ROLE_CHILD -> R.drawable.ic_parent_marker
            else -> R.drawable.ic_child_marker
        }
        
        val otherMarkerTitle = otherMarkerTitle()
        
        otherMarker = Marker(mapView).apply {
            position = GeoPoint(otherLat, otherLon)
            title = otherMarkerTitle
            snippet = buildMarkerSnippet(getString(R.string.map_other_location), otherTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, otherMarkerIcon)
        }
        mapView.overlays.add(otherMarker)
        
        // Draw a line between both live markers.
        connectionLine = Polyline().apply {
            addPoint(GeoPoint(myLat, myLon))
            addPoint(GeoPoint(otherLat, otherLon))
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(connectionLine)
        
        // Keep both markers in view unless the user disabled auto-fit.
        if (autoFitEnabled) {

            centerMapOnBothLocations(myLat, myLon, otherLat, otherLon)

        }
        
        // Show distance and ETA for the linked device.
        val etaInfo = parentLocationRepository.calculateETA(
            otherLat, otherLon,
            myLat, myLon,
            otherSpeed
        )
        
        binding.distanceText.text = etaInfo.formattedDistance
        binding.etaText.text = etaInfo.formattedETA
        binding.statsCard.visibility = View.VISIBLE
        
        mapView.invalidate()
    }
    
    private fun displaySingleLocation(
        lat: Double,
        lon: Double,
        title: String,
        iconRes: Int,
        timestamp: Long?,
        snippetLabel: String
    ) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (!isValidCoordinate(lat, lon)) {
            Log.w(TAG, "displaySingleLocation skipped due to invalid coordinates: ($lat,$lon)")
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.map_invalid_coordinates)
            return
        }

        // Remove the previous markers before showing a single point.
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        
        myMarker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            this.title = title
            snippet = buildMarkerSnippet(snippetLabel, timestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, iconRes)
        }
        mapView.overlays.add(myMarker)
        
        // Center on the only available point.
        if (autoFitEnabled) {

            mapView.controller.setCenter(GeoPoint(lat, lon))

            mapView.controller.setZoom(15.0)

        }
        
        binding.statsCard.visibility = View.GONE
        
        mapView.invalidate()
    }

    private fun displayAvailableLocations(
        myLat: Double?,
        myLon: Double?,
        myTimestamp: Long?,
        otherLocation: ParentLocationData?
    ) {
        val sanitizedMy = sanitizePoint(myLat, myLon, myTimestamp)
        val sanitizedOther = otherLocation?.takeIfUsable()
        val otherLat = sanitizedOther?.latitude
        val otherLon = sanitizedOther?.longitude

        lastMyPoint = sanitizedMy?.let {
            GeoPoint(it.latitude, it.longitude)
        }
        lastOtherPoint = if (otherLat != null && otherLon != null) {
            GeoPoint(otherLat, otherLon)
        } else {
            null
        }

        val myTitle = selfMarkerTitle()

        val myIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_parent_marker
            ROLE_CHILD -> R.drawable.ic_child_marker
            else -> R.drawable.ic_parent_marker
        }

        val otherTitle = otherMarkerTitle()

        val otherIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_child_marker
            ROLE_CHILD -> R.drawable.ic_parent_marker
            else -> R.drawable.ic_child_marker
        }

        if (sanitizedMy != null && otherLat != null && otherLon != null) {
            displayLocations(
                myLat = sanitizedMy.latitude,
                myLon = sanitizedMy.longitude,
                otherLat = otherLat,
                otherLon = otherLon,
                otherSpeed = sanitizedOther.speed,
                myTimestamp = sanitizedMy.timestamp,
                otherTimestamp = sanitizedOther.timestamp
            )
        } else if (sanitizedMy != null) {
            displaySingleLocation(
                lat = sanitizedMy.latitude,
                lon = sanitizedMy.longitude,
                title = myTitle,
                iconRes = myIcon,
                timestamp = sanitizedMy.timestamp,
                snippetLabel = getString(R.string.map_my_location)
            )
        } else if (otherLat != null && otherLon != null) {
            displaySingleLocation(
                lat = otherLat,
                lon = otherLon,
                title = otherTitle,
                iconRes = otherIcon,
                timestamp = sanitizedOther.timestamp,
                snippetLabel = getString(R.string.map_other_location)
            )
        }

        updateLiveSubtitle(
            myTimestamp = sanitizedMy?.timestamp,
            otherTimestamp = sanitizedOther?.timestamp
        )
        binding.errorCard.visibility = View.GONE
    }

    private data class ContactPoint(
        val contact: Child,
        val location: ParentLocationData
    )

    private fun cacheKeyContact(deviceId: String): String = "map_cache_contact_$deviceId"

    private fun loadAllContactsLocations() {
        loadLocationsJob = lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) { database.childDao().getAll() }
                val eligible = contacts.filter {
                    ContactFeatures.isAllowed(it.allowedFeatures, ContactFeatures.MAP)
                }

                if (eligible.isEmpty()) {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = getString(R.string.map_contacts_empty)
                    return@launch
                }

                val cachedPoints = mutableListOf<ContactPoint>()
                for (contact in eligible) {
                    val cached = loadCachedLocation(cacheKeyContact(contact.deviceId))?.takeIfFresh()
                    if (cached != null) {
                        cachedPoints.add(ContactPoint(contact, cached.toParentLocationData(contact.deviceId)))
                    }
                }

                if (cachedPoints.isNotEmpty()) {
                    displayAllContacts(cachedPoints)
                    binding.loadingIndicator.visibility = View.GONE
                }

                val myLocation = if (hasLocationPermission()) {
                    withContext(Dispatchers.IO) { locationManager.getCurrentLocation() }
                } else {
                    null
                }

                if (myLocation != null) {
                    myLatitude = myLocation.latitude
                    myLongitude = myLocation.longitude
                    saveCachedLocation(
                        cacheKeyMy(),
                        myLocation.latitude,
                        myLocation.longitude,
                        myLocation.time,
                        if (myLocation.hasSpeed()) myLocation.speed else null
                    )
                }

                val fetched = withContext(Dispatchers.IO) {
                    eligible.map { contact ->
                        async {
                            val location = if (contact.role == ContactRoles.CHILD) {
                                networkClient.getLatestLocation(contact.deviceId)?.takeIfUsable()
                            } else {
                                networkClient.getLatestParentLocation(contact.deviceId)?.takeIfUsable()
                            }
                            contact to location
                        }
                    }.awaitAll()
                }

                val finalPoints = mutableListOf<ContactPoint>()
                val cachedMap = cachedPoints.associateBy { it.contact.deviceId }
                for ((contact, location) in fetched) {
                    val resolved = location ?: cachedMap[contact.deviceId]?.location
                    if (resolved != null) {
                        saveCachedLocation(
                            cacheKeyContact(contact.deviceId),
                            resolved.latitude,
                            resolved.longitude,
                            resolved.timestamp,
                            resolved.speed
                        )
                        finalPoints.add(ContactPoint(contact, resolved))
                    }
                }

                if (finalPoints.isNotEmpty() || myLocation != null) {
                    displayAllContacts(finalPoints)
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.GONE
                } else {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = getString(R.string.map_contacts_unavailable)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading contacts locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = getString(
                    R.string.map_map_load_error,
                    e.message ?: getString(R.string.map_unknown_error)
                )
            }
        }
    }

    private fun displayAllContacts(points: List<ContactPoint>) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return

        try {
            // Clear previous markers
            myMarker?.let { mapView.overlays.remove(it) }
            otherMarker?.let { mapView.overlays.remove(it) }
            connectionLine?.let { mapView.overlays.remove(it) }
            contactMarkers.values.forEach { mapView.overlays.remove(it) }
            contactMarkers.clear()

            val geoPoints = mutableListOf<GeoPoint>()

            // My location (if available)
            val myLat = myLatitude
            val myLon = myLongitude
            if (myLat != null && myLon != null && isValidCoordinate(myLat, myLon)) {
                val myPoint = GeoPoint(myLat, myLon)
                geoPoints.add(myPoint)
                myMarker = Marker(mapView).apply {
                    position = myPoint
                    title = selfMarkerTitle()
                    snippet = buildMarkerSnippet(getString(R.string.map_my_location), null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(this@DualLocationMapActivity, ContactIcons.resolve(0, myRole))
                }
                mapView.overlays.add(myMarker)
            }

            for (point in points) {
                val contact = point.contact
                val location = point.location
                if (!isValidCoordinate(location.latitude, location.longitude)) {
                    Log.w(TAG, "Skip invalid contact coordinate for ${contact.deviceId}: ${location.latitude},${location.longitude}")
                    continue
                }

                val geo = GeoPoint(location.latitude, location.longitude)
                geoPoints.add(geo)
                val marker = Marker(mapView).apply {
                    position = geo
                    title = contact.alias ?: contact.name
                    snippet = buildMarkerSnippet(getString(R.string.map_location_label), location.timestamp)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(
                        this@DualLocationMapActivity,
                        ContactIcons.resolve(contact.iconId, contact.role)
                    )
                }
                mapView.overlays.add(marker)
                contactMarkers[contact.deviceId] = marker
            }

            binding.statsCard.visibility = View.GONE

            if (autoFitEnabled && geoPoints.isNotEmpty()) {
                safeZoomToBoundingBox(geoPoints, geoPoints.firstOrNull())
            }

            mapView.invalidate()
        } catch (e: Exception) {
            Log.e(TAG, "displayAllContacts failed", e)
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(
                R.string.map_render_error,
                e.message ?: getString(R.string.map_unknown_error)
            )
        }
    }

    private fun selfMarkerTitle(): String = when (myRole) {
        ROLE_PARENT -> getString(R.string.map_title_me_parent)
        ROLE_CHILD -> getString(R.string.map_title_me_child)
        else -> getString(R.string.map_title_me)
    }

    private fun otherMarkerTitle(): String = when (myRole) {
        ROLE_PARENT -> getString(R.string.map_title_child)
        ROLE_CHILD -> getString(R.string.map_title_parent)
        else -> getString(R.string.map_title_other_device)
    }

    private fun otherLocationUnavailableMessage(): String = when (myRole) {
        ROLE_PARENT -> getString(R.string.map_child_location_unavailable)
        ROLE_CHILD -> getString(R.string.map_parent_location_unavailable)
        else -> getString(R.string.map_location_unavailable)
    }

    private fun resolvePairIds(): Pair<String, String>? {
        val parentId = when (myRole) {
            ROLE_PARENT -> resolvedParentId.ifBlank { myId.trim() }
            ROLE_CHILD -> resolvedParentId.ifBlank { otherId.trim() }
            else -> ""
        }
        val childId = when (myRole) {
            ROLE_PARENT -> resolvedOtherId.ifBlank { otherId.trim() }
            ROLE_CHILD -> myId.trim()
            else -> ""
        }
        if (parentId.isBlank() || childId.isBlank()) return null
        return parentId to childId
    }

    private fun updateLiveSubtitle(myTimestamp: Long?, otherTimestamp: Long?) {
        if (showAllContacts) return
        if (limitedMode) {
            binding.toolbar.subtitle = getString(R.string.map_limited_mode_subtitle)
            return
        }

        val myPart = myTimestamp?.let { "${selfMarkerTitle()}: ${formatRelativeTimestamp(it)}" }
        val otherPart = otherTimestamp?.let { "${otherMarkerTitle()}: ${formatRelativeTimestamp(it)}" }
        binding.toolbar.subtitle = listOfNotNull(myPart, otherPart).joinToString(" | ").ifBlank { null }
    }

    private fun formatRelativeTimestamp(timestamp: Long): String {
        val normalized = normalizeTimestampMillis(timestamp) ?: timestamp
        return DateUtils.getRelativeTimeSpanString(
            normalized,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    private fun centerMapOnBothLocations(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ) {
        val distance = calculateDistance(lat1, lon1, lat2, lon2)
        if (distance < 30) {
            mapView.controller.setCenter(GeoPoint(lat1, lon1))
            mapView.controller.setZoom(17.0)
            return
        }

        val points = listOf(
            GeoPoint(lat1, lon1),
            GeoPoint(lat2, lon2)
        )
        safeZoomToBoundingBox(points, points.firstOrNull())
    }

    private fun safeZoomToBoundingBox(points: List<GeoPoint>, fallback: GeoPoint?) {
        if (!::mapView.isInitialized || points.isEmpty()) return
        val validPoints = points.filter { isValidCoordinate(it.latitude, it.longitude) }
        if (validPoints.isEmpty()) return
        try {
            if (validPoints.size == 1) {
                val point = validPoints.first()
                mapView.controller.setCenter(point)
                mapView.controller.setZoom(16.0)
                return
            }
            val bounds = BoundingBox.fromGeoPoints(validPoints)
            mapView.zoomToBoundingBox(bounds, true, 100)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to zoom map bounds", e)
            fallback?.let {
                try {
                    mapView.controller.setCenter(it)
                    mapView.controller.setZoom(15.0)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
    
    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL)
                if (isMapReady) {
                    loadLocations()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
        
        if (isMapReady) {
            startAutoRefresh()
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
        autoRefreshJob?.cancel()
        loadLocationsJob?.cancel()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        autoRefreshJob?.cancel()
        loadLocationsJob?.cancel()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun ParentLocation.toNetworkModel(): ParentLocationData {
        return ParentLocationData(
            parentId = parentId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            timestamp = timestamp,
            battery = batteryLevel,
            speed = speed,
            bearing = bearing
        )
    }

    private data class SanitizedPoint(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long?
    )

    private fun sanitizePoint(lat: Double?, lon: Double?, timestamp: Long?): SanitizedPoint? {
        if (lat == null || lon == null) return null
        if (!isValidCoordinate(lat, lon)) return null
        val normalizedTimestamp = normalizeTimestampMillis(timestamp)
        return SanitizedPoint(lat, lon, normalizedTimestamp)
    }

    private fun CachedLocation.takeIfUsable(): CachedLocation? {
        if (!isValidCoordinate(latitude, longitude)) return null
        return this
    }

    private fun CachedLocation.takeIfFresh(): CachedLocation? {
        return takeIfUsable()?.takeUnless { isStale(it.timestamp) }
    }

    private fun ParentLocation.takeIfUsable(): ParentLocation? {
        if (!isValidCoordinate(latitude, longitude)) return null
        return this
    }

    private fun ParentLocation.takeIfFresh(): ParentLocation? {
        return takeIfUsable()?.takeUnless { isStale(it.timestamp) }
    }

    private fun ParentLocationData.takeIfUsable(): ParentLocationData? {
        if (!isValidCoordinate(latitude, longitude)) return null
        return this
    }

}
