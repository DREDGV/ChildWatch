package ru.example.parentwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import ru.example.parentwatch.attention.ChildAttentionSignalLauncher
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import ru.example.parentwatch.database.ParentWatchDatabase
import ru.example.parentwatch.database.entity.ParentLocation
import ru.example.parentwatch.database.repository.ParentLocationRepository
import ru.example.parentwatch.databinding.ActivityDualLocationMapBinding
import ru.example.parentwatch.location.LocationManager
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.contacts.ContactIcons
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.session.ChildEffectiveContextProvider
import ru.example.parentwatch.session.ChildFamilyDirectoryRepository
import ru.example.parentwatch.session.ChildParticipantNameResolver
import ru.example.parentwatch.network.ParentLocationData
import ru.childwatch.shared.family.FamilyRole
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DualLocationMapActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DualLocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val AUTO_REFRESH_INTERVAL = 30_000L
        private const val LIVE_MODE_REFRESH_INTERVAL = 10_000L
        private const val LIVE_MODE_DURATION_MS = 10 * 60 * 1000L
        private const val STALE_THRESHOLD_MS = 10 * 60 * 1000L
        private const val HISTORY_LIMIT = 1000
        private const val STOP_RADIUS_METERS = 80f
        private const val STOP_MIN_DURATION_MS = 10 * 60 * 1000L
        private const val MOVING_SPEED_THRESHOLD_MPS = 1.4f
        private const val MAP_CACHE_MY = "map_cache_my"
        private const val MAP_CACHE_OTHER = "map_cache_other"
        const val EXTRA_MY_ROLE = "MY_ROLE"
        const val EXTRA_MY_ID = "MY_ID"
        const val EXTRA_OTHER_ID = "OTHER_ID"
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

    private enum class MapDiagnosticReason {
        NONE,
        PAIR_NOT_CONFIGURED,
        CHILD_ID_MISSING,
        PARENT_ID_MISSING,
        ONLY_SELF_AVAILABLE,
        NO_LINKED_SERVER_LOCATION,
        USING_CACHED_LINKED,
        LINKED_STALE
    }

    private lateinit var binding: ActivityDualLocationMapBinding
    private lateinit var mapView: MapView
    private lateinit var prefs: SharedPreferences
    private lateinit var database: ParentWatchDatabase
    private lateinit var locationManager: LocationManager
    private lateinit var networkClient: NetworkClient
    private lateinit var parentLocationRepository: ParentLocationRepository

    private var myRole = ROLE_PARENT
    private var myId = ""
    private var otherId = ""
    private var limitedMode = false
    private var myMarker: Marker? = null
    private var otherMarker: Marker? = null
    private var otherAccuracyOverlay: Polygon? = null
    private var connectionLine: Polyline? = null
    private var historyLine: Polyline? = null
    private var historyStartMarker: Marker? = null
    private var historyEndMarker: Marker? = null
    private val familyMarkers = mutableMapOf<String, Marker>()
    private var myLatitude: Double? = null
    private var myLongitude: Double? = null
    private var isMapReady = false
    private var autoRefreshJob: Job? = null
    private var loadLocationsJob: Job? = null
    private var autoFitEnabled = true
    private var isStatsCardCollapsed = false
    private var liveModeUntilMs: Long = 0L
    private var lastLinkedSourceRes: Int? = null
    private var lastMyPoint: GeoPoint? = null
    private var lastOtherPoint: GeoPoint? = null
    private var resolvedParentId: String = ""
    private var resolvedOtherId: String = ""
    private var currentDiagnosticReason: MapDiagnosticReason = MapDiagnosticReason.NONE
    private var dependenciesReady = false
    private val contextProvider by lazy { ChildEffectiveContextProvider.get(this) }
    private val mapNamespace by lazy { contextProvider.featureContext("map")?.storageNamespace ?: "legacy" }
    private val participantNameResolver by lazy { ChildParticipantNameResolver(this) }

    private data class CachedLocation(val latitude: Double, val longitude: Double, val timestamp: Long, val speed: Float?)
    private data class SanitizedPoint(val latitude: Double, val longitude: Double, val timestamp: Long?)
    private data class MovementStop(val startTimestamp: Long, val endTimestamp: Long) {
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

    private data class TimelineEvent(
        val timestamp: Long,
        val priority: Int,
        val label: String
    )

    private data class FamilyMarkerCandidate(
        val deviceId: String,
        val title: String,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long?,
        val iconId: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmdroidEarly()
        try {
            binding = ActivityDualLocationMapBinding.inflate(layoutInflater)
            setContentView(binding.root)
            myRole = intent.getStringExtra(EXTRA_MY_ROLE) ?: ROLE_PARENT
            myId = intent.getStringExtra(EXTRA_MY_ID)?.trim().orEmpty()
            otherId = intent.getStringExtra(EXTRA_OTHER_ID)?.trim().orEmpty()

            val canonicalContext = contextProvider.featureContext("map")
            if (myRole == ROLE_CHILD) {
                canonicalContext?.selfDeviceId?.takeIf(String::isNotBlank)?.let { myId = it }
                canonicalContext?.targetDeviceId?.takeIf(String::isNotBlank)?.let { otherId = it }
            }
            val effectiveContext = ChildEffectiveContextResolver(this).resolveEffectiveContext()
            val localPrefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            if (myId.isBlank()) {
                myId = listOf(
                    effectiveContext?.ownChildDeviceId,
                    localPrefs.getString("device_id", null),
                    localPrefs.getString("child_device_id", null),
                    legacyPrefs.getString("device_id", null),
                    legacyPrefs.getString("child_device_id", null)
                ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            }
            if (otherId.isBlank()) {
                otherId = effectiveContext?.linkedParentDeviceId?.takeIf { it.isNotBlank() }
                    ?: resolveParentIdCandidateFromPrefs(localPrefs, legacyPrefs, myId)
            }
            resolvedParentId = if (myRole == ROLE_PARENT) myId else otherId
            resolvedOtherId = otherId
            limitedMode = otherId.isBlank()

            setupToolbar()
            positionMapControlsBelowHeader()
            setupMap()
            setupRefreshButton()
            setupLiveModeButton()
            setupStatsCard()
            setupCenterButtons()
            setupHistoryButton()
            setupTimelineButton()
            setupAttentionSignalButton()
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    initializeDependenciesAndLoad()
                }
            }
        } catch (t: Throwable) {
            handleStartupFailure(t)
        }
    }

    private fun initializeDependenciesAndLoad() {
        if (dependenciesReady || isFinishing || isDestroyed) return
        try {
            prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            database = ParentWatchDatabase.getInstance(this)
            locationManager = LocationManager(this)
            networkClient = NetworkClient(this)
            parentLocationRepository = ParentLocationRepository(database.parentLocationDao())
            dependenciesReady = true
            checkPermissionsAndLoad()
        } catch (t: Throwable) {
            handleStartupFailure(t)
        }
    }

    private fun resolveParentIdCandidateFromPrefs(
        prefs: SharedPreferences,
        legacyPrefs: SharedPreferences,
        myDeviceId: String
    ): String {
        return listOf(
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() && it != myDeviceId }.orEmpty()
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
        val message = getString(R.string.map_startup_failed_with_reason, reason)
        runCatching {
            val textView = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)
                text = message
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#121212"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
            setContentView(FrameLayout(this).apply { addView(textView) })
        }.onFailure {
            Toast.makeText(this, getString(R.string.map_startup_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = when (myRole) {
            ROLE_PARENT -> getString(R.string.map_title_where_child)
            ROLE_CHILD -> getString(R.string.map_title_where_parents)
            else -> getString(R.string.map_title_default)
        }
        binding.toolbar.subtitle = if (limitedMode) getString(R.string.map_limited_mode_subtitle) else null
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun positionMapControlsBelowHeader() {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            val params = binding.mapTopControls.layoutParams as? ViewGroup.MarginLayoutParams
                ?: return@post
            val spacing = (12 * resources.displayMetrics.density).toInt()
            val requiredTopMargin = binding.appBarLayout.bottom + spacing
            if (params.topMargin != requiredTopMargin) {
                params.topMargin = requiredTopMargin
                binding.mapTopControls.layoutParams = params
            }
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
                    if (event.action == MotionEvent.ACTION_DOWN && binding.statsCard.visibility == View.VISIBLE) {
                        collapseStatsCard()
                    }
                    if (autoFitEnabled) {
                        autoFitEnabled = false
                        updateAutoFitUi()
                    }
                }
                false
            }
            isMapReady = true
        } catch (t: Throwable) {
            isMapReady = false
            Log.e(TAG, "Map view init failed", t)
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(
                R.string.map_init_failed_with_reason,
                t.message ?: getString(R.string.map_unknown_error)
            )
        }
    }

    private fun setupRefreshButton() { binding.refreshButton.setOnClickListener { loadLocations() } }

    private fun setupStatsCard() {
        binding.collapseStatsButton.setOnClickListener { collapseStatsCard() }
    }

    private fun collapseStatsCard() {
        isStatsCardCollapsed = true
        binding.statsCard.visibility = View.GONE
    }

    private fun expandStatsCard() {
        isStatsCardCollapsed = false
        binding.statsCard.visibility = View.VISIBLE
    }

    private fun setupLiveModeButton() {
        updateLiveModeUi()
        binding.liveModeButton.setOnClickListener {
            val wasActive = isLiveModeActive()
            liveModeUntilMs = if (wasActive) {
                0L
            } else {
                System.currentTimeMillis() + LIVE_MODE_DURATION_MS
            }
            updateLiveModeUi()
            startAutoRefresh()
            loadLocations()
            Toast.makeText(
                this,
                if (wasActive) getString(R.string.map_live_mode_disabled)
                else getString(R.string.map_live_mode_enabled),
                Toast.LENGTH_SHORT
            ).show()
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
        binding.centerBothButton.setOnClickListener { autoFitEnabled = true; updateAutoFitUi(); centerOnAvailable() }
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
        binding.centerMyButton.setImageResource(resolveMyMarkerIconRes())
        binding.centerOtherButton.setImageResource(resolveOtherMarkerIconRes())
    }

    private fun updateAutoFitUi() { binding.centerBothButton.alpha = if (autoFitEnabled) 1.0f else 0.6f }

    private fun centerOnPoint(point: GeoPoint?): Boolean {
        if (point == null) return false
        mapView.controller.setCenter(point)
        return true
    }

    private fun centerOnAvailable() {
        val myPoint = lastMyPoint
        val otherPoint = lastOtherPoint
        when {
            myPoint != null && otherPoint != null -> centerMapOnBothLocations(
                myPoint.latitude,
                myPoint.longitude,
                otherPoint.latitude,
                otherPoint.longitude
            )
            myPoint != null -> centerOnPoint(myPoint)
            otherPoint != null -> centerOnPoint(otherPoint)
        }
    }

    private fun setupHistoryButton() {
        if (historyTargetId().isNullOrBlank()) {
            binding.historyButton.visibility = View.GONE
            return
        }
        binding.historyButton.setOnClickListener { showHistoryPeriodDialog() }
    }

    private fun setupTimelineButton() {
        binding.timelineButton.setOnClickListener { loadTodayTimeline() }
        updateTimelineButtonVisibility()
    }

    private fun setupAttentionSignalButton() {
        val canCallParent = myRole == ROLE_CHILD && otherId.isNotBlank()
        binding.attentionSignalButton.visibility = if (canCallParent) View.VISIBLE else View.GONE
        if (!canCallParent) return
        binding.attentionSignalButton.setOnClickListener {
            val targetDeviceId = resolvedOtherId.ifBlank { otherId }
            ChildAttentionSignalLauncher.show(
                activity = this,
                explicitTargetDeviceId = targetDeviceId,
                explicitTargetName = participantNameResolver.resolveActiveParentDisplayName()
            )
        }
    }

    private fun updateTimelineButtonVisibility() {
        binding.timelineButton.visibility = if (historyTargetId().isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showHistoryPeriodDialog() {
        val periods = arrayOf(
            getString(R.string.map_history_today),
            getString(R.string.map_history_yesterday),
            getString(R.string.map_history_week),
            getString(R.string.map_history_month)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_history_select_period)
            .setItems(periods) { _, which ->
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
                    0 -> startOfToday to now
                    1 -> {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startOfToday }
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                        cal.timeInMillis to (startOfToday - 1)
                    }
                    2 -> {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                        cal.timeInMillis to now
                    }
                    3 -> {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -30)
                        cal.timeInMillis to now
                    }
                    else -> (now - DateUtils.DAY_IN_MILLIS) to now
                }
                loadLocationHistory(from, to)
            }
            .show()
    }

    private fun loadTodayTimeline() {
        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfToday = calendar.timeInMillis
        lifecycleScope.launch {
            try {
                val history = fetchLocationHistory(startOfToday, now)
                if (history.isNullOrEmpty()) {
                    Toast.makeText(
                        this@DualLocationMapActivity,
                        getString(R.string.map_timeline_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                displayLocationHistory(history)
                showTodayTimelineDialog(history)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading today timeline", e)
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

    private suspend fun fetchLocationHistory(
        fromTimestamp: Long,
        toTimestamp: Long
    ): List<ParentLocationData>? {
        val targetId = historyTargetId()
        if (targetId.isNullOrBlank()) {
            Toast.makeText(
                this@DualLocationMapActivity,
                getString(R.string.map_other_location_not_available),
                Toast.LENGTH_SHORT
            ).show()
            return null
        }

        return when (myRole) {
            ROLE_CHILD -> networkClient.getParentLocationHistory(
                parentId = targetId,
                fromTimestamp = fromTimestamp,
                toTimestamp = toTimestamp,
                limit = HISTORY_LIMIT
            )
            else -> networkClient.getLocationHistory(
                childDeviceId = targetId,
                startTime = fromTimestamp,
                endTime = toTimestamp,
                limit = HISTORY_LIMIT
            ).body()?.locations?.map {
                ParentLocationData(
                    parentId = targetId,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    timestamp = it.timestamp,
                    battery = null,
                    speed = null,
                    bearing = null
                )
            }
        }
    }

    private fun loadLocationHistory(fromTimestamp: Long, toTimestamp: Long) {
        lifecycleScope.launch {
            try {
                val history = fetchLocationHistory(fromTimestamp, toTimestamp)

                if (history.isNullOrEmpty()) {
                    Toast.makeText(
                        this@DualLocationMapActivity,
                        getString(R.string.map_history_no_data),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

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
                    getString(R.string.map_history_load_error, e.message ?: getString(R.string.map_unknown_error)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun displayLocationHistory(history: List<ParentLocationData>) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        val validHistory = history
            .sortedBy { it.timestamp }
            .filter { isValidCoordinate(it.latitude, it.longitude) }
        if (validHistory.isEmpty()) return

        historyLine?.let { mapView.overlays.remove(it) }
        historyStartMarker?.let { mapView.overlays.remove(it) }
        historyEndMarker?.let { mapView.overlays.remove(it) }
        historyLine = null
        historyStartMarker = null
        historyEndMarker = null

        historyLine = Polyline(mapView).apply {
            id = "history_line"
            setPoints(validHistory.map { GeoPoint(it.latitude, it.longitude) })
            outlinePaint.color = historyAccentColor()
            outlinePaint.strokeWidth = 8f
            outlinePaint.alpha = 200
        }
        mapView.overlays.add(0, historyLine)

        val historyStartIcon = tintedDrawable(R.drawable.ic_route_start_marker, historyAccentColor())
        val historyEndIcon = tintedDrawable(R.drawable.ic_route_end_marker, historyAccentColor())
        val firstPoint = validHistory.firstOrNull()
        val lastPoint = validHistory.lastOrNull()
        if (firstPoint != null && lastPoint != null && firstPoint != lastPoint) {
            historyStartMarker = Marker(mapView).apply {
                position = GeoPoint(firstPoint.latitude, firstPoint.longitude)
                title = getString(R.string.map_history_route_start)
                snippet = formatTimestamp(firstPoint.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = historyStartIcon
            }
            historyEndMarker = Marker(mapView).apply {
                position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                title = getString(R.string.map_history_route_end)
                snippet = formatTimestamp(lastPoint.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = historyEndIcon
            }
            mapView.overlays.add(historyStartMarker)
            mapView.overlays.add(historyEndMarker)
        }

        safeZoomToBoundingBox(
            validHistory.map { GeoPoint(it.latitude, it.longitude) },
            validHistory.lastOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
        )
        mapView.invalidate()
    }

    private fun checkPermissionsAndLoad() {
        if (!dependenciesReady) return
        if (hasLocationPermission()) {
            loadLocations()
            startAutoRefresh()
        } else {
            requestLocationPermission()
            loadLocations()
            startAutoRefresh()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
        if (!dependenciesReady || !isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (loadLocationsJob?.isActive == true) return
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.errorCard.visibility = View.GONE
        loadLocationsJob = lifecycleScope.launch {
            try {
                if (myRole == ROLE_CHILD) {
                    runCatching { participantNameResolver.refreshCanonicalDirectory() }
                        .onFailure { Log.w(TAG, "Canonical family directory refresh failed", it) }
                }
                val cachedMy = loadCachedLocation(cacheKeyMy())?.takeIfUsable()
                val cachedOther = if (limitedMode) null else loadCachedLocation(cacheKeyOther())?.takeIfUsable()
                if (cachedMy != null || cachedOther != null) {
                    lastLinkedSourceRes = cachedOther?.let { R.string.map_stats_source_cache_short }
                    displayAvailableLocations(
                        cachedMy?.latitude,
                        cachedMy?.longitude,
                        cachedMy?.timestamp,
                        cachedOther?.toParentLocationData(resolvedOtherId.ifBlank { otherId })
                    )
                    binding.loadingIndicator.visibility = View.GONE
                }

                val myLocation = if (hasLocationPermission()) withContext(Dispatchers.IO) { locationManager.getCurrentLocation() } else null
                if (myLocation != null && isValidCoordinate(myLocation.latitude, myLocation.longitude)) {
                    myLatitude = myLocation.latitude
                    myLongitude = myLocation.longitude
                    saveCachedLocation(cacheKeyMy(), myLocation.latitude, myLocation.longitude, myLocation.time, if (myLocation.hasSpeed()) myLocation.speed else null)
                }

                var localResolvedParentId = resolvedParentId.ifBlank { myId }
                var localResolvedOtherId = resolvedOtherId.ifBlank { otherId }
                val pairSnapshot = fetchResolvedPairSnapshot { parentId, childId ->
                    localResolvedParentId = parentId
                    localResolvedOtherId = childId
                }
                if (localResolvedParentId.isNotBlank()) {
                    resolvedParentId = localResolvedParentId
                }
                if (localResolvedOtherId.isNotBlank()) {
                    resolvedOtherId = localResolvedOtherId
                }
                updateTimelineButtonVisibility()

                val serverSelfLocation = when (myRole) {
                    ROLE_PARENT -> pairSnapshot?.parent?.takeIfUsable()
                    ROLE_CHILD -> pairSnapshot?.child?.takeIfUsable()
                    else -> null
                }

                val otherLocation = when (myRole) {
                    ROLE_PARENT -> pairSnapshot?.child?.takeIfUsable()
                    ROLE_CHILD -> pairSnapshot?.parent?.takeIfUsable()
                    else -> null
                } ?: if (limitedMode) {
                    null
                } else withContext(Dispatchers.IO) {
                    if (myRole == ROLE_CHILD) {
                        val parentCandidates = resolveParentIdCandidates()
                        val fromServer = parentCandidates.firstNotNullOfOrNull { parentId ->
                            networkClient.getLatestParentLocation(parentId)?.takeIfUsable()?.also { resolvedOtherId = parentId }
                        }
                        val cachedParent = parentCandidates.firstNotNullOfOrNull { parentId ->
                            parentLocationRepository.getLatestLocation(parentId)?.takeIfUsable()?.also { resolvedOtherId = parentId }
                        }
                        fromServer ?: cachedParent?.toNetworkModel()
                    } else {
                        resolveChildIdCandidates().firstNotNullOfOrNull { childId ->
                            networkClient.getLatestLocation(childId)?.takeIfUsable()?.also { resolvedOtherId = childId }
                        }
                    }
                }

                if (otherLocation != null) {
                    if (resolvedOtherId.isNotBlank() && resolvedOtherId != otherId) otherId = resolvedOtherId
                    saveCachedLocation(cacheKeyOther(), otherLocation.latitude, otherLocation.longitude, otherLocation.timestamp, otherLocation.speed)
                }

                if (serverSelfLocation != null) {
                    saveCachedLocation(cacheKeyMy(), serverSelfLocation.latitude, serverSelfLocation.longitude, serverSelfLocation.timestamp, serverSelfLocation.speed)
                }

                val currentMyValid = myLocation?.takeIf { isValidCoordinate(it.latitude, it.longitude) }
                val myLatFinal = currentMyValid?.latitude ?: serverSelfLocation?.latitude ?: cachedMy?.latitude
                val myLonFinal = currentMyValid?.longitude ?: serverSelfLocation?.longitude ?: cachedMy?.longitude
                val myTsFinal = currentMyValid?.time ?: serverSelfLocation?.timestamp ?: cachedMy?.timestamp
                val otherFinal = otherLocation ?: cachedOther?.toParentLocationData(resolvedOtherId.ifBlank { otherId.ifBlank { "paired-device" } })
                val usingCachedOther = otherLocation == null && otherFinal != null
                val selfAvailable = myLatFinal != null && myLonFinal != null
                lastLinkedSourceRes = when {
                    usingCachedOther -> R.string.map_stats_source_cache_short
                    otherLocation != null -> R.string.map_stats_source_server_short
                    else -> null
                }

                if ((myLatFinal != null && myLonFinal != null) || otherFinal != null) {
                    displayAvailableLocations(myLatFinal, myLonFinal, myTsFinal, otherFinal)
                    binding.loadingIndicator.visibility = View.GONE
                    applyDiagnosticState(
                        resolveDiagnosticReason(
                            selfAvailable = selfAvailable,
                            linkedLocation = otherFinal,
                            usingCachedLinked = usingCachedOther
                        ),
                        linkedTimestamp = otherFinal?.timestamp,
                        sourceRes = when {
                            usingCachedOther -> R.string.map_diag_source_cache
                            otherLocation != null -> R.string.map_diag_source_server
                            myLocation != null -> R.string.map_diag_source_gps
                            else -> null
                        }
                    )
                } else {
                    binding.loadingIndicator.visibility = View.GONE
                    applyDiagnosticState(
                        resolveDiagnosticReason(
                            selfAvailable = false,
                            linkedLocation = null,
                            usingCachedLinked = false
                        ),
                        linkedTimestamp = null,
                        sourceRes = null
                    )
                }

                renderFamilyMarkers(loadFamilyMarkersForCurrentChild())
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = getString(R.string.map_location_load_error, e.message ?: getString(R.string.map_unknown_error))
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
        otherTimestamp: Long?,
        linkedLocation: ParentLocationData
    ) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (!isValidCoordinate(myLat, myLon) || !isValidCoordinate(otherLat, otherLon)) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.map_invalid_coordinates)
            return
        }
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        otherAccuracyOverlay?.let { mapView.overlays.remove(it) }
        otherAccuracyOverlay = null
        connectionLine?.let { mapView.overlays.remove(it) }
        clearFamilyMarkers()
        val myIcon = resolveMyMarkerIconRes()
        val otherIcon = resolveOtherMarkerIconRes()
        myMarker = Marker(mapView).apply {
            position = GeoPoint(myLat, myLon)
            title = selfMarkerTitle()
            snippet = formatMarkerSnippet(getString(R.string.map_my_location), myTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createParticipantMarkerDrawable(
                iconRes = myIcon,
                accentColor = if (myRole == ROLE_CHILD) selfChildAccentColor() else childAccentColor(),
                title = selfMarkerTitle()
            )
        }
        otherMarker = Marker(mapView).apply {
            position = GeoPoint(otherLat, otherLon)
            title = otherMarkerTitle()
            snippet = formatMarkerSnippet(getString(R.string.map_other_location), otherTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createParticipantMarkerDrawable(
                iconRes = otherIcon,
                accentColor = if (myRole == ROLE_CHILD) participantAccentColor(otherId, ROLE_PARENT) else childAccentColor(),
                title = otherMarkerTitle()
            )
            setOnMarkerClickListener { marker, _ ->
                marker.showInfoWindow()
                expandStatsCard()
                true
            }
        }
        connectionLine = Polyline().apply {
            addPoint(GeoPoint(myLat, myLon))
            addPoint(GeoPoint(otherLat, otherLon))
            outlinePaint.color = connectionAccentColor()
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(myMarker)
        mapView.overlays.add(otherMarker)
        mapView.overlays.add(connectionLine)
        otherAccuracyOverlay = addAccuracyOverlay(
            GeoPoint(otherLat, otherLon),
            linkedLocation.accuracy
        )
        if (autoFitEnabled) centerMapOnBothLocations(myLat, myLon, otherLat, otherLon)
        val etaInfo = parentLocationRepository.calculateETA(otherLat, otherLon, myLat, myLon, otherSpeed)
        bindLinkedStats(
            linkedLocation = linkedLocation,
            distanceMeters = etaInfo.distanceMeters,
            etaText = formatEtaStatus(etaInfo)
        )
        mapView.invalidate()
    }

    private fun displaySingleLocation(lat: Double, lon: Double, title: String, iconRes: Int, timestamp: Long?, snippetLabel: String) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (!isValidCoordinate(lat, lon)) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.map_invalid_coordinates)
            return
        }
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        otherAccuracyOverlay?.let { mapView.overlays.remove(it) }
        otherAccuracyOverlay = null
        connectionLine?.let { mapView.overlays.remove(it) }
        clearFamilyMarkers()
        myMarker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            this.title = title
            snippet = formatMarkerSnippet(snippetLabel, timestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createParticipantMarkerDrawable(
                iconRes = iconRes,
                accentColor = if (iconRes == R.drawable.ic_child_marker) {
                    childAccentColor()
                } else {
                    participantAccentColor(title, ROLE_PARENT, emphasizeSelf = myRole == ROLE_CHILD)
                },
                title = title
            )
        }
        mapView.overlays.add(myMarker)
        if (autoFitEnabled) {
            mapView.controller.setCenter(GeoPoint(lat, lon))
            mapView.controller.setZoom(15.0)
        }
        binding.statsCard.visibility = View.GONE
        binding.movementStatusText.text = ""
        binding.pointMetaText.text = ""
        mapView.invalidate()
    }

    private fun displayAvailableLocations(myLat: Double?, myLon: Double?, myTimestamp: Long?, otherLocation: ParentLocationData?) {
        val sanitizedMy = sanitizePoint(myLat, myLon, myTimestamp)
        val sanitizedOther = otherLocation?.takeIfUsable()
        lastMyPoint = sanitizedMy?.let { GeoPoint(it.latitude, it.longitude) }
        lastOtherPoint = sanitizedOther?.let { GeoPoint(it.latitude, it.longitude) }
        val myIcon = resolveMyMarkerIconRes()
        val otherIcon = resolveOtherMarkerIconRes()
        when {
            sanitizedMy != null && sanitizedOther != null -> displayLocations(
                sanitizedMy.latitude,
                sanitizedMy.longitude,
                sanitizedOther.latitude,
                sanitizedOther.longitude,
                sanitizedOther.speed,
                sanitizedMy.timestamp,
                sanitizedOther.timestamp,
                sanitizedOther
            )
            sanitizedMy != null -> displaySingleLocation(sanitizedMy.latitude, sanitizedMy.longitude, selfMarkerTitle(), myIcon, sanitizedMy.timestamp, getString(R.string.map_my_location))
            sanitizedOther != null -> {
                displaySingleLocation(sanitizedOther.latitude, sanitizedOther.longitude, otherMarkerTitle(), otherIcon, sanitizedOther.timestamp, getString(R.string.map_other_location))
                otherAccuracyOverlay = addAccuracyOverlay(
                    GeoPoint(sanitizedOther.latitude, sanitizedOther.longitude),
                    sanitizedOther.accuracy
                )
                bindLinkedStats(linkedLocation = sanitizedOther)
            }
            else -> binding.statsCard.visibility = View.GONE
        }
        updateLiveSubtitle(sanitizedMy?.timestamp, sanitizedOther?.timestamp)
        updateTimelineButtonVisibility()
        binding.errorCard.visibility = View.GONE
    }

    private fun clearFamilyMarkers() {
        if (familyMarkers.isEmpty()) return
        familyMarkers.values.forEach { mapView.overlays.remove(it) }
        familyMarkers.clear()
    }

    private fun addAccuracyOverlay(center: GeoPoint, accuracy: Float): Polygon? {
        val radiusMeters = accuracy.takeIf { it.isFinite() && it > 0f && it <= 50_000f }?.toDouble()
            ?: return null
        return Polygon(mapView).apply {
            points = Polygon.pointsAsCircle(center, radiusMeters)
            outlinePaint.color = Color.argb(170, 0, 105, 92)
            outlinePaint.strokeWidth = 3f * resources.displayMetrics.density
            fillPaint.color = Color.argb(38, 0, 105, 92)
            mapView.overlays.add(0, this)
        }
    }

    private fun buildFamilyMarkerTitle(link: ru.example.parentwatch.network.LinkedParentLink): String {
        return participantNameResolver.resolveParentDisplayName(
            parentDeviceId = link.parentDeviceId,
            legacyCandidates = listOf(link.parentDisplayName, link.displayName)
        ) ?: getString(R.string.family_member_name_missing)
    }

    private fun isActiveFamilyLink(link: ru.example.parentwatch.network.LinkedParentLink): Boolean {
        return link.isActive != false
    }

    private fun dedupeLinkedParents(
        linkedParents: List<ru.example.parentwatch.network.LinkedParentLink>,
        excludedParentIds: Set<String>
    ): List<ru.example.parentwatch.network.LinkedParentLink> {
        val deduped = LinkedHashMap<String, ru.example.parentwatch.network.LinkedParentLink>()
        linkedParents.asSequence()
            .filter(::isActiveFamilyLink)
            .forEach { link ->
                val parentId = link.parentDeviceId.trim()
                if (parentId.isBlank() || parentId in excludedParentIds || deduped.containsKey(parentId)) {
                    return@forEach
                }
                deduped[parentId] = link
            }
        return deduped.values.toList()
    }

    private suspend fun loadFamilyMarkersForCurrentChild(): List<FamilyMarkerCandidate> = withContext(Dispatchers.IO) {
        val childDeviceId = myId.trim()
        if (childDeviceId.isBlank()) return@withContext emptyList()

        val excludedParentIds = listOf(resolvedOtherId, otherId, resolvedParentId)
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        // One canonical family member produces one marker even when the
        // person has reinstalled the app or owns several phones.
        val canonicalDirectory = ChildFamilyDirectoryRepository(this@DualLocationMapActivity).loadCached()
        val canonicalMarkers = mutableListOf<FamilyMarkerCandidate>()
        canonicalDirectory?.people.orEmpty()
            .asSequence()
            .filter { it.member.role == FamilyRole.PARENT || it.member.role == FamilyRole.GUARDIAN }
            .forEach { person ->
                val preferredDeviceId = person.activeDevices
                    .firstOrNull { it.deviceId == resolvedOtherId || it.deviceId == otherId }
                    ?.deviceId
                val device = person.primaryDevice(preferredDeviceId) ?: return@forEach
                if (device.deviceId in excludedParentIds) return@forEach
                val location = try {
                    networkClient.getLatestParentLocation(device.deviceId)?.takeIfUsable()
                } catch (_: Exception) {
                    null
                } ?: return@forEach
                canonicalMarkers += FamilyMarkerCandidate(
                    deviceId = device.deviceId,
                    title = person.member.displayName,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = location.timestamp,
                    iconId = ContactIcons.PARENT
                )
            }
        if (canonicalMarkers.isNotEmpty()) return@withContext canonicalMarkers

        // Compatibility fallback for an old/offline server. Human labels are
        // still filtered by ChildParticipantNameResolver; ids are never shown.
        val response = runCatching { networkClient.getLinkedParents(childDeviceId) }.getOrNull()
        val linkedParents = response?.body()?.parents.orEmpty()
        if (linkedParents.isEmpty()) return@withContext emptyList()

        val markers = mutableListOf<FamilyMarkerCandidate>()
        dedupeLinkedParents(linkedParents, excludedParentIds).asSequence()
            .forEach { link ->
                val location = try {
                    networkClient.getLatestParentLocation(link.parentDeviceId)?.takeIfUsable()
                } catch (_: Exception) {
                    null
                } ?: return@forEach

                markers += FamilyMarkerCandidate(
                    deviceId = link.parentDeviceId,
                    title = buildFamilyMarkerTitle(link),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = location.timestamp,
                    iconId = link.parentMarkerIconId?.takeIf(ContactIcons::isKnown) ?: ContactIcons.PARENT
                )
            }
        markers
    }

    private fun renderFamilyMarkers(markers: List<FamilyMarkerCandidate>) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        clearFamilyMarkers()
        if (markers.isEmpty()) {
            mapView.invalidate()
            return
        }

        val points = mutableListOf<GeoPoint>()
        markers.forEach { candidate ->
            if (!isValidCoordinate(candidate.latitude, candidate.longitude)) return@forEach
            val marker = Marker(mapView).apply {
                position = GeoPoint(candidate.latitude, candidate.longitude)
                title = candidate.title.ifBlank { candidate.deviceId }
                snippet = formatMarkerSnippet(title, candidate.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = createParticipantMarkerDrawable(
                    iconRes = ContactIcons.resolve(candidate.iconId, ROLE_PARENT),
                    accentColor = participantAccentColor(candidate.deviceId, ROLE_PARENT, emphasizeSelf = candidate.deviceId == otherId),
                    title = title
                )
            }
            familyMarkers[candidate.deviceId] = marker
            mapView.overlays.add(marker)
            points += marker.position
        }

        if (autoFitEnabled) {
            myMarker?.position?.let { points += it }
            otherMarker?.position?.let { points += it }
            if (points.isNotEmpty()) {
                safeZoomToBoundingBox(points, points.firstOrNull())
            }
        }

        mapView.invalidate()
    }

    private fun bindLinkedStats(
        linkedLocation: ParentLocationData,
        distanceMeters: Float? = null,
        etaText: String? = null
    ) {
        val battery = linkedLocation.battery?.takeIf { it in 0..100 }?.let { "$it%" }
            ?: getString(R.string.map_person_battery_unknown)
        val accuracy = linkedLocation.accuracy.takeIf { it.isFinite() && it > 0f }?.let {
            getString(R.string.map_person_accuracy_meters, it.toInt().coerceAtLeast(1))
        } ?: getString(R.string.map_person_accuracy_unknown)
        val updated = normalizeTimestampMillis(linkedLocation.timestamp)?.let(::formatRelativeTimestamp) ?: "нет данных"
        binding.mapPersonNameText.text = otherMarkerTitle()
        binding.mapPersonSummaryText.text = getString(
            R.string.map_person_summary,
            battery,
            accuracy,
            updated
        )
        binding.distanceText.text = distanceMeters?.let { formatEtaDistance(it) } ?: "--"
        binding.etaText.text = etaText ?: "--"
        binding.movementStatusText.text = getString(resolveMovementStatusText(linkedLocation))
        binding.pointMetaText.text = buildPointMetaText(linkedLocation)
        binding.statsCard.visibility = if (isStatsCardCollapsed) View.GONE else View.VISIBLE
    }

    private fun resolveMovementStatusText(linkedLocation: ParentLocationData): Int {
        val normalizedTimestamp = normalizeTimestampMillis(linkedLocation.timestamp)
        if (normalizedTimestamp != null && isStale(normalizedTimestamp)) {
            return R.string.map_stats_status_stale
        }

        val speed = linkedLocation.speed ?: 0f
        return when {
            speed >= 6f -> R.string.map_stats_status_transit
            speed >= MOVING_SPEED_THRESHOLD_MPS -> R.string.map_stats_status_moving
            else -> R.string.map_stats_status_stationary
        }
    }

    private fun buildPointMetaText(linkedLocation: ParentLocationData): String {
        val parts = mutableListOf<String>()
        lastLinkedSourceRes?.let { parts += getString(it) }
        normalizeTimestampMillis(linkedLocation.timestamp)?.let { timestamp ->
            parts += getString(R.string.map_stats_meta_updated, formatRelativeTimestamp(timestamp))
        }
        if (linkedLocation.accuracy.isFinite() && linkedLocation.accuracy > 0f) {
            parts += getString(
                R.string.map_stats_meta_accuracy,
                linkedLocation.accuracy.toInt().coerceAtLeast(1)
            )
        }
        linkedLocation.battery?.takeIf { it in 0..100 }?.let { battery ->
            parts += getString(R.string.map_stats_meta_battery, battery)
        }
        return parts.joinToString(" | ")
    }

    private fun resolveDiagnosticReason(
        selfAvailable: Boolean,
        linkedLocation: ParentLocationData?,
        usingCachedLinked: Boolean
    ): MapDiagnosticReason {
        val linkedIdMissing = resolvedOtherId.isBlank() && otherId.isBlank()
        return when {
            limitedMode && linkedIdMissing -> missingLinkedReason()
            limitedMode -> MapDiagnosticReason.PAIR_NOT_CONFIGURED
            linkedLocation == null && selfAvailable -> MapDiagnosticReason.ONLY_SELF_AVAILABLE
            linkedLocation == null -> MapDiagnosticReason.NO_LINKED_SERVER_LOCATION
            usingCachedLinked -> {
                if ((linkedLocation.timestamp).let { isStale(it) }) {
                    MapDiagnosticReason.LINKED_STALE
                } else {
                    MapDiagnosticReason.USING_CACHED_LINKED
                }
            }
            isStale(linkedLocation.timestamp) -> MapDiagnosticReason.LINKED_STALE
            else -> MapDiagnosticReason.NONE
        }
    }

    private fun missingLinkedReason(): MapDiagnosticReason = when (myRole) {
        ROLE_PARENT -> MapDiagnosticReason.CHILD_ID_MISSING
        ROLE_CHILD -> MapDiagnosticReason.PARENT_ID_MISSING
        else -> MapDiagnosticReason.PAIR_NOT_CONFIGURED
    }

    private fun applyDiagnosticState(
        reason: MapDiagnosticReason,
        linkedTimestamp: Long?,
        sourceRes: Int?
    ) {
        currentDiagnosticReason = reason
        logDiagnosticState(reason, linkedTimestamp, sourceRes)
        if (reason == MapDiagnosticReason.NONE) {
            binding.errorCard.visibility = View.GONE
            return
        }

        val baseText = when (reason) {
            MapDiagnosticReason.PAIR_NOT_CONFIGURED -> getString(R.string.map_diag_pair_not_configured)
            MapDiagnosticReason.CHILD_ID_MISSING -> getString(R.string.map_diag_child_id_missing)
            MapDiagnosticReason.PARENT_ID_MISSING -> getString(R.string.map_diag_parent_id_missing)
            MapDiagnosticReason.ONLY_SELF_AVAILABLE -> getString(R.string.map_diag_only_self_available)
            MapDiagnosticReason.NO_LINKED_SERVER_LOCATION -> getString(R.string.map_diag_no_server_location)
            MapDiagnosticReason.USING_CACHED_LINKED -> getString(R.string.map_diag_using_cached_location)
            MapDiagnosticReason.LINKED_STALE -> getString(R.string.map_diag_linked_stale)
            MapDiagnosticReason.NONE -> return
        }

        val sourceText = sourceRes?.let { getString(it) }
        val freshnessText = linkedTimestamp
            ?.takeIf { it > 0L }
            ?.let { getString(R.string.map_diag_last_update, formatRelativeTimestamp(it)) }
        if (reason == MapDiagnosticReason.LINKED_STALE || reason == MapDiagnosticReason.USING_CACHED_LINKED) {
            binding.errorCard.visibility = View.GONE
            val inlineText = when (reason) {
                MapDiagnosticReason.LINKED_STALE -> getString(R.string.map_stale_warning)
                MapDiagnosticReason.USING_CACHED_LINKED -> getString(R.string.map_diag_using_cached_location)
                else -> baseText
            }
            val currentSubtitle = binding.toolbar.subtitle?.toString()?.takeIf { it.isNotBlank() }
            binding.toolbar.subtitle = listOfNotNull(currentSubtitle, inlineText, freshnessText)
                .joinToString(" • ")
                .ifBlank { currentSubtitle }
            binding.toolbar.subtitle = listOfNotNull(currentSubtitle, inlineText, freshnessText)
                .joinToString(" | ")
                .ifBlank { currentSubtitle }
            return
        }
        binding.errorCard.visibility = View.VISIBLE
        binding.errorText.text = listOfNotNull(baseText, sourceText, freshnessText).joinToString("\n")
    }

    private fun logDiagnosticState(reason: MapDiagnosticReason, linkedTimestamp: Long?, sourceRes: Int?) {
        val source = sourceRes?.let { runCatching { getString(it) }.getOrNull() } ?: "none"
        Log.d(
            TAG,
            "Map diagnostic role=$myRole myId=$myId otherId=$otherId resolvedParentId=$resolvedParentId resolvedOtherId=$resolvedOtherId limitedMode=$limitedMode reason=$reason source=$source linkedTimestamp=$linkedTimestamp"
        )
    }

    private fun normalizeTimestampMillis(raw: Long?): Long? {
        if (raw == null || raw <= 0L) return null
        return when {
            raw < 10_000_000_000L -> raw * 1000L
            raw > 10_000_000_000_000L -> raw / 1000L
            else -> raw
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val normalized = normalizeTimestampMillis(timestamp) ?: timestamp
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(normalized))
    }

    private fun childAccentColor(): Int = Color.parseColor("#F59E0B")

    private fun selfChildAccentColor(): Int = Color.parseColor("#0F766E")

    private fun participantAccentColor(deviceId: String?, role: String, emphasizeSelf: Boolean = false): Int {
        if (role == ROLE_CHILD) return childAccentColor()
        if (emphasizeSelf) return selfChildAccentColor()
        val palette = intArrayOf(
            Color.parseColor("#2563EB"),
            Color.parseColor("#7C3AED"),
            Color.parseColor("#DC2626"),
            Color.parseColor("#0891B2"),
            Color.parseColor("#16A34A"),
            Color.parseColor("#EA580C")
        )
        val normalized = deviceId?.trim().orEmpty()
        if (normalized.isBlank()) return palette.first()
        return palette[(normalized.hashCode() and Int.MAX_VALUE) % palette.size]
    }

    private fun resolveMyMarkerIconRes(): Int {
        return if (myRole == ROLE_CHILD) {
            ContactIcons.resolve(participantNameResolver.resolveChildMarkerIconId(), ROLE_CHILD)
        } else {
            R.drawable.ic_parent_marker
        }
    }

    private fun resolveOtherMarkerIconRes(): Int {
        return if (myRole == ROLE_CHILD) {
            ContactIcons.resolve(
                participantNameResolver.resolveLinkedParentMarkerIconId(otherId) ?: ContactIcons.PARENT,
                ROLE_PARENT
            )
        } else {
            R.drawable.ic_child_marker
        }
    }

    private fun historyAccentColor(): Int = when (myRole) {
        ROLE_CHILD -> participantAccentColor(historyTargetId(), ROLE_PARENT)
        ROLE_PARENT -> childAccentColor()
        else -> Color.parseColor("#4285F4")
    }

    private fun connectionAccentColor(): Int = when (myRole) {
        ROLE_CHILD -> Color.parseColor("#6366F1")
        ROLE_PARENT -> Color.parseColor("#0EA5E9")
        else -> Color.parseColor("#2196F3")
    }

    private fun shortMarkerLabel(title: String): String {
        val normalized = title.trim()
        if (normalized.isBlank()) return "?"
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
            normalized.length <= 8 -> normalized
            else -> normalized.take(7)
        }
    }

    private fun createParticipantMarkerDrawable(
        @DrawableRes iconRes: Int,
        accentColor: Int,
        title: String
    ): Drawable? {
        val density = resources.displayMetrics.density
        val label = shortMarkerLabel(title)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val chipHeight = 22f * density
        val chipPadding = 10f * density
        val iconSize = 34f * density
        val outerCircle = iconSize / 2f + 5f * density
        val textWidth = maxOf(24f * density, textPaint.measureText(label))
        val width = maxOf((iconSize + 18f * density).toInt(), (textWidth + chipPadding * 2f).toInt())
        val height = (chipHeight + outerCircle * 2f + 6f * density).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawRoundRect(
            2f * density,
            0f,
            width - 2f * density,
            chipHeight,
            10f * density,
            10f * density,
            chipFill
        )
        canvas.drawRoundRect(
            2f * density,
            0f,
            width - 2f * density,
            chipHeight,
            10f * density,
            10f * density,
            chipStroke
        )
        val textY = chipHeight / 2f - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(label, width / 2f, textY, textPaint)

        val circleCx = width / 2f
        val circleCy = chipHeight + outerCircle
        val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        canvas.drawCircle(circleCx, circleCy, outerCircle, circleFill)
        canvas.drawCircle(circleCx, circleCy, outerCircle, circleStroke)

        val iconDrawable = ContextCompat.getDrawable(this, iconRes)?.mutate()
            ?: return BitmapDrawable(resources, bitmap)
        DrawableCompat.setTint(iconDrawable, accentColor)
        val halfIcon = iconSize / 2f
        iconDrawable.setBounds(
            (circleCx - halfIcon).toInt(),
            (circleCy - halfIcon).toInt(),
            (circleCx + halfIcon).toInt(),
            (circleCy + halfIcon).toInt()
        )
        iconDrawable.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

    private fun tintedDrawable(@DrawableRes iconRes: Int, accentColor: Int): Drawable? {
        val drawable = ContextCompat.getDrawable(this, iconRes)?.mutate() ?: return null
        DrawableCompat.setTint(drawable, accentColor)
        return drawable
    }

    private fun isStale(timestamp: Long): Boolean {
        val normalized = normalizeTimestampMillis(timestamp) ?: return false
        return System.currentTimeMillis() - normalized > STALE_THRESHOLD_MS
    }

    private fun buildSnippet(label: String, timestamp: Long?): String {
        return formatMarkerSnippet(label, timestamp)
    }

    private fun resolveParentIdCandidates(): List<String> {
        contextProvider.current()?.targetDeviceId
            ?.takeIf { it.isNotBlank() && it != myId.trim() }
            ?.let { return listOf(it) }
        val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        return listOf(
            resolvedParentId,
            otherId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        ).mapNotNull { it?.trim() }.filter { it.isNotBlank() && it != myId.trim() }.distinct()
    }

    private fun resolveChildIdCandidates(): List<String> {
        contextProvider.current()?.selfDeviceId
            ?.takeIf(String::isNotBlank)
            ?.let { return listOf(it) }
        val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
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
            prefs.getString("child_device_id", null),
            prefs.getString("selected_device_id", null),
            legacyPrefs.getString("child_device_id", null),
            legacyPrefs.getString("selected_device_id", null)
        ).mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && it !in excluded }
            .distinct()
    }

    private fun resolveSelfParentIdCandidates(): List<String> {
        val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        return listOf(
            resolvedParentId,
            myId,
            prefs.getString("device_id", null),
            prefs.getString("child_device_id", null),
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("child_device_id", null)
        ).mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun fetchResolvedPairSnapshot(
        onResolved: (parentId: String, childId: String) -> Unit
    ): ru.example.parentwatch.network.LocationPairData? {
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

        var fallback: Pair<ru.example.parentwatch.network.LocationPairData, Pair<String, String>>? = null
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

    private fun configureOsmdroidEarly() {
        runCatching {
            val basePath = java.io.File(filesDir, "osmdroid").apply { mkdirs() }
            val tileCachePath = java.io.File(cacheDir, "osmdroid_tiles").apply { mkdirs() }
            val config = Configuration.getInstance()
            config.osmdroidBasePath = basePath
            config.osmdroidTileCache = tileCachePath
            config.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            config.userAgentValue = packageName
        }.onFailure {
            Log.w(TAG, "OSMdroid early init failed, fallback to defaults: ${it.message}")
        }
    }

    private fun cacheKeyMy(): String = "$mapNamespace::${MAP_CACHE_MY}_${myRole}"
    private fun cacheKeyOther(): String = "$mapNamespace::${MAP_CACHE_OTHER}_${resolvedOtherId.ifBlank { otherId }}"

    private fun saveCachedLocation(key: String, lat: Double, lon: Double, timestamp: Long, speed: Float?) {
        val normalizedTimestamp = normalizeTimestampMillis(timestamp) ?: System.currentTimeMillis()
        prefs.edit().putString(key, "$lat|$lon|$normalizedTimestamp|${speed?.toString().orEmpty()}").apply()
    }

    private fun loadCachedLocation(key: String): CachedLocation? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split("|")
        if (parts.size < 3) return null
        return try {
            CachedLocation(
                latitude = parts[0].toDouble(),
                longitude = parts[1].toDouble(),
                timestamp = normalizeTimestampMillis(parts[2].toLong()) ?: return null,
                speed = parts.getOrNull(3)?.toFloatOrNull()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun CachedLocation.toParentLocationData(deviceId: String): ParentLocationData {
        return ParentLocationData(deviceId, latitude, longitude, 0f, timestamp, null, speed, null)
    }

    private fun selfMarkerTitle(): String = when (myRole) {
        ROLE_PARENT -> getString(R.string.map_title_me_parent)
        ROLE_CHILD -> participantNameResolver.resolveChildDisplayName()
        else -> getString(R.string.map_title_me)
    }

    private fun otherMarkerTitle(): String = when (myRole) {
        ROLE_PARENT -> getString(R.string.map_title_child)
        ROLE_CHILD -> participantNameResolver.resolveParentDisplayName(
            resolvedOtherId.ifBlank { otherId }
        ) ?: participantNameResolver.resolveActiveParentDisplayName()
        else -> getString(R.string.map_title_other_device)
    }

    private fun formatEtaDistance(distanceMeters: Float): String {
        return if (distanceMeters < 1000f) {
            getString(R.string.map_distance_meters_clean, distanceMeters.toInt())
        } else {
            getString(R.string.map_distance_km_clean, distanceMeters / 1000f)
        }
    }

    private fun formatEtaStatus(etaInfo: ru.example.parentwatch.database.repository.ETAInfo): String {
        if (!etaInfo.isMoving) {
            return when (myRole) {
                ROLE_PARENT -> getString(R.string.map_eta_stationary_child)
                ROLE_CHILD -> getString(R.string.map_eta_stationary_parent)
                else -> getString(R.string.map_eta_stationary_other)
            }
        }

        val etaSeconds = etaInfo.etaSeconds ?: return getString(R.string.map_eta_unknown_clean)
        return when {
            etaSeconds < 60 -> getString(R.string.map_eta_under_minute_clean)
            etaSeconds < 3600 -> getString(R.string.map_eta_minutes_clean, etaSeconds / 60)
            else -> {
                val hours = etaSeconds / 3600
                val minutes = (etaSeconds % 3600) / 60
                getString(R.string.map_eta_hours_minutes_clean, hours, minutes)
            }
        }
    }

    private fun isLiveModeActive(): Boolean {
        if (liveModeUntilMs <= 0L) return false
        if (System.currentTimeMillis() >= liveModeUntilMs) {
            liveModeUntilMs = 0L
            return false
        }
        return true
    }

    private fun currentAutoRefreshInterval(): Long {
        return if (isLiveModeActive()) LIVE_MODE_REFRESH_INTERVAL else AUTO_REFRESH_INTERVAL
    }

    private fun updateLiveModeUi() {
        val active = isLiveModeActive()
        val text = if (active) {
            val remainingMs = (liveModeUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val remainingMinutes = (remainingMs / 60000L).toInt().coerceAtLeast(1)
            getString(R.string.map_live_mode_until, remainingMinutes)
        } else {
            getString(R.string.map_live_mode_button)
        }
        binding.liveModeButton.text = text
        binding.liveModeButton.alpha = if (active) 1.0f else 0.88f
        binding.updateCadenceText.text = if (active) {
            getString(R.string.map_live_mode_active)
        } else {
            getString(R.string.map_updated_adaptive)
        }
    }

    private fun otherLocationUnavailableMessage(): String = when (myRole) {
        ROLE_PARENT -> getString(R.string.map_child_location_unavailable)
        ROLE_CHILD -> getString(R.string.map_parent_location_unavailable)
        else -> getString(R.string.map_location_unavailable)
    }

    private fun formatMarkerSnippet(label: String, timestamp: Long?): String {
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

    private fun otherMarkerIconRes(): Int =
        if (myRole == ROLE_CHILD) R.drawable.ic_parent_marker else R.drawable.ic_child_marker

    private fun buildMarkerSnippet(label: String, timestamp: Long?): String {
        return formatMarkerSnippet(label, timestamp)
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
        updateLiveModeUi()
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

    private fun sanitizePoint(lat: Double?, lon: Double?, timestamp: Long?): SanitizedPoint? {
        if (lat == null || lon == null || !isValidCoordinate(lat, lon)) return null
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

    private fun centerMapOnBothLocations(lat1: Double, lon1: Double, lat2: Double, lon2: Double) {
        if (!isValidCoordinate(lat1, lon1) || !isValidCoordinate(lat2, lon2)) return
        val distance = calculateDistance(lat1, lon1, lat2, lon2)
        if (distance < 30) {
            mapView.controller.setCenter(GeoPoint(lat1, lon1))
            mapView.controller.setZoom(17.0)
            return
        }
        safeZoomToBoundingBox(
            listOf(GeoPoint(lat1, lon1), GeoPoint(lat2, lon2)),
            GeoPoint(lat1, lon1)
        )
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
                runCatching {
                    mapView.controller.setCenter(it)
                    mapView.controller.setZoom(15.0)
                }
            }
        }
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }

    private fun showTodayTimelineDialog(history: List<ParentLocationData>) {
        val lines = buildTodayTimelineLines(history)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_timeline_title)
            .setMessage(
                if (lines.isEmpty()) {
                    getString(R.string.map_timeline_empty)
                } else {
                    lines.joinToString(separator = "\n")
                }
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildTodayTimelineLines(history: List<ParentLocationData>): List<String> {
        val sortedHistory = history
            .mapNotNull { item ->
                val normalizedTimestamp = normalizeTimestampMillis(item.timestamp) ?: return@mapNotNull null
                if (!isValidCoordinate(item.latitude, item.longitude)) return@mapNotNull null
                item.copy(timestamp = normalizedTimestamp)
            }
            .sortedBy { it.timestamp }
        if (sortedHistory.isEmpty()) return emptyList()

        val events = mutableListOf<TimelineEvent>()
        val firstPoint = sortedHistory.first()
        val lastPoint = sortedHistory.last()
        events += TimelineEvent(
            timestamp = firstPoint.timestamp,
            priority = 0,
            label = getString(R.string.map_timeline_start, formatTimestamp(firstPoint.timestamp))
        )
        detectStops(sortedHistory).forEach { stop ->
            events += TimelineEvent(
                timestamp = stop.startTimestamp,
                priority = 20,
                label = getString(
                    R.string.map_timeline_stop,
                    formatDuration(stop.durationMs),
                    formatTimestamp(stop.startTimestamp)
                )
            )
        }
        if (lastPoint.timestamp != firstPoint.timestamp) {
            events += TimelineEvent(
                timestamp = lastPoint.timestamp,
                priority = 99,
                label = getString(R.string.map_timeline_end, formatTimestamp(lastPoint.timestamp))
            )
        }
        return events
            .sortedWith(compareBy<TimelineEvent> { it.timestamp }.thenBy { it.priority })
            .map { it.label }
            .distinct()
    }

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

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(currentAutoRefreshInterval())
                updateLiveModeUi()
                if (isMapReady) loadLocations()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.root.post {
            runCatching {
                if (::mapView.isInitialized && !isFinishing && !isDestroyed) mapView.onResume()
            }.onFailure {
                Log.w(TAG, "MapView onResume failed", it)
            }
        }
        updateLiveModeUi()
        if (isMapReady && dependenciesReady) startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            if (::mapView.isInitialized) mapView.onPause()
        }.onFailure {
            Log.w(TAG, "MapView onPause failed", it)
        }
        autoRefreshJob?.cancel()
        loadLocationsJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoRefreshJob?.cancel()
        loadLocationsJob?.cancel()
    }

    private fun ParentLocation.toNetworkModel(): ParentLocationData {
        return ParentLocationData(parentId, latitude, longitude, accuracy, timestamp, batteryLevel, speed, bearing)
    }
}
