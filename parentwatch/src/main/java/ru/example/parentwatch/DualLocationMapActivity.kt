package ru.example.parentwatch

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
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import ru.example.parentwatch.database.ParentWatchDatabase
import ru.example.parentwatch.database.entity.ParentLocation
import ru.example.parentwatch.database.repository.ParentLocationRepository
import ru.example.parentwatch.databinding.ActivityDualLocationMapBinding
import ru.example.parentwatch.location.LocationManager
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.network.ParentLocationData
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DualLocationMapActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DualLocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val AUTO_REFRESH_INTERVAL = 30_000L
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
    private var connectionLine: Polyline? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDualLocationMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        try {
            myRole = intent.getStringExtra(EXTRA_MY_ROLE) ?: ROLE_PARENT
            myId = intent.getStringExtra(EXTRA_MY_ID)?.trim().orEmpty()
            otherId = intent.getStringExtra(EXTRA_OTHER_ID)?.trim().orEmpty()

            val localPrefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            if (myId.isBlank()) {
                myId = listOf(
                    localPrefs.getString("device_id", null),
                    localPrefs.getString("child_device_id", null),
                    legacyPrefs.getString("device_id", null),
                    legacyPrefs.getString("child_device_id", null)
                ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            }
            if (otherId.isBlank()) {
                otherId = resolveParentIdCandidateFromPrefs(localPrefs, legacyPrefs, myId)
            }
            resolvedParentId = if (myRole == ROLE_PARENT) myId else otherId
            resolvedOtherId = otherId
            limitedMode = otherId.isBlank() && myRole != ROLE_CHILD

            Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
            Configuration.getInstance().userAgentValue = packageName

            prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            database = ParentWatchDatabase.getInstance(this)
            locationManager = LocationManager(this)
            networkClient = NetworkClient(this)
            parentLocationRepository = ParentLocationRepository(database.parentLocationDao())

            setupToolbar()
            setupMap()
            setupRefreshButton()
            setupCenterButtons()
            setupHistoryButton()
            checkPermissionsAndLoad()
        } catch (e: Exception) {
            handleStartupFailure(e)
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
        Toast.makeText(this, getString(R.string.map_startup_failed), Toast.LENGTH_LONG).show()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = when (myRole) {
            ROLE_PARENT -> getString(R.string.map_title_where_child)
            ROLE_CHILD -> getString(R.string.map_title_where_parents)
            else -> getString(R.string.map_title_default)
        }
        binding.toolbar.subtitle = if (limitedMode) getString(R.string.map_limited_mode_subtitle) else null
    }

    private fun setupMap() {
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
    }

    private fun setupRefreshButton() { binding.refreshButton.setOnClickListener { loadLocations() } }

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
        binding.centerMyButton.setImageResource(if (myRole == ROLE_CHILD) R.drawable.ic_child_marker else R.drawable.ic_parent_marker)
        binding.centerOtherButton.setImageResource(if (myRole == ROLE_CHILD) R.drawable.ic_parent_marker else R.drawable.ic_child_marker)
    }

    private fun updateAutoFitUi() { binding.centerBothButton.alpha = if (autoFitEnabled) 1.0f else 0.6f }

    private fun centerOnPoint(point: GeoPoint?): Boolean {
        if (point == null) return false
        mapView.controller.setCenter(point)
        return true
    }

    private fun centerOnAvailable() {
        when {
            lastMyPoint != null && lastOtherPoint != null -> centerMapOnBothLocations(
                lastMyPoint!!.latitude,
                lastMyPoint!!.longitude,
                lastOtherPoint!!.latitude,
                lastOtherPoint!!.longitude
            )
            lastMyPoint != null -> centerOnPoint(lastMyPoint)
            lastOtherPoint != null -> centerOnPoint(lastOtherPoint)
        }
    }

    private fun setupHistoryButton() {
        if (historyTargetId().isNullOrBlank()) {
            binding.historyButton.visibility = View.GONE
            return
        }
        binding.historyButton.setOnClickListener { showHistoryPeriodDialog() }
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
            outlinePaint.color = Color.parseColor("#4285F4")
            outlinePaint.strokeWidth = 8f
            outlinePaint.alpha = 200
        }
        mapView.overlays.add(0, historyLine)

        val historyIcon = ContextCompat.getDrawable(this@DualLocationMapActivity, otherMarkerIconRes())
        val firstPoint = validHistory.firstOrNull()
        val lastPoint = validHistory.lastOrNull()
        if (firstPoint != null && lastPoint != null && firstPoint != lastPoint) {
            historyStartMarker = Marker(mapView).apply {
                position = GeoPoint(firstPoint.latitude, firstPoint.longitude)
                title = getString(R.string.map_history_route_start)
                snippet = formatTimestamp(firstPoint.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = historyIcon
            }
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

        val bounds = BoundingBox.fromGeoPoints(validHistory.map { GeoPoint(it.latitude, it.longitude) })
        mapView.zoomToBoundingBox(bounds, true, 120)
        mapView.invalidate()
    }

    private fun checkPermissionsAndLoad() {
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
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (loadLocationsJob?.isActive == true) return
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.errorCard.visibility = View.GONE
        loadLocationsJob = lifecycleScope.launch {
            try {
                val cachedMy = loadCachedLocation(cacheKeyMy())?.takeIfUsable()
                val cachedOther = if (limitedMode) null else loadCachedLocation(cacheKeyOther())?.takeIfUsable()
                if (cachedMy != null || cachedOther != null) {
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

                if ((myLatFinal != null && myLonFinal != null) || otherFinal != null) {
                    displayAvailableLocations(myLatFinal, myLonFinal, myTsFinal, otherFinal)
                    binding.loadingIndicator.visibility = View.GONE
                    when {
                        limitedMode -> {
                            binding.errorCard.visibility = View.VISIBLE
                            binding.errorText.text = getString(R.string.map_limited_mode_subtitle)
                        }
                        otherFinal == null -> {
                            binding.errorCard.visibility = View.VISIBLE
                            binding.errorText.text = otherLocationUnavailableMessage()
                        }
                    }
                } else {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = if (limitedMode) getString(R.string.map_limited_mode_subtitle) else otherLocationUnavailableMessage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = getString(R.string.map_location_load_error, e.message ?: getString(R.string.map_unknown_error))
            }
        }
    }

    private fun displayLocations(myLat: Double, myLon: Double, otherLat: Double, otherLon: Double, otherSpeed: Float?, myTimestamp: Long?, otherTimestamp: Long?) {
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (!isValidCoordinate(myLat, myLon) || !isValidCoordinate(otherLat, otherLon)) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.map_invalid_coordinates)
            return
        }
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        val myIcon = if (myRole == ROLE_CHILD) R.drawable.ic_child_marker else R.drawable.ic_parent_marker
        val otherIcon = if (myRole == ROLE_CHILD) R.drawable.ic_parent_marker else R.drawable.ic_child_marker
        myMarker = Marker(mapView).apply {
            position = GeoPoint(myLat, myLon)
            title = selfMarkerTitle()
            snippet = formatMarkerSnippet(getString(R.string.map_my_location), myTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, myIcon)
        }
        otherMarker = Marker(mapView).apply {
            position = GeoPoint(otherLat, otherLon)
            title = otherMarkerTitle()
            snippet = formatMarkerSnippet(getString(R.string.map_other_location), otherTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, otherIcon)
        }
        connectionLine = Polyline().apply {
            addPoint(GeoPoint(myLat, myLon))
            addPoint(GeoPoint(otherLat, otherLon))
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(myMarker)
        mapView.overlays.add(otherMarker)
        mapView.overlays.add(connectionLine)
        if (autoFitEnabled) centerMapOnBothLocations(myLat, myLon, otherLat, otherLon)
        val etaInfo = parentLocationRepository.calculateETA(otherLat, otherLon, myLat, myLon, otherSpeed)
        binding.distanceText.text = etaInfo.formattedDistance
        binding.etaText.text = etaInfo.formattedETA
        binding.statsCard.visibility = View.VISIBLE
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
        connectionLine?.let { mapView.overlays.remove(it) }
        myMarker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            this.title = title
            snippet = formatMarkerSnippet(snippetLabel, timestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, iconRes)
        }
        mapView.overlays.add(myMarker)
        if (autoFitEnabled) {
            mapView.controller.setCenter(GeoPoint(lat, lon))
            mapView.controller.setZoom(15.0)
        }
        binding.statsCard.visibility = View.GONE
        mapView.invalidate()
    }

    private fun displayAvailableLocations(myLat: Double?, myLon: Double?, myTimestamp: Long?, otherLocation: ParentLocationData?) {
        val sanitizedMy = sanitizePoint(myLat, myLon, myTimestamp)
        val sanitizedOther = otherLocation?.takeIfUsable()
        lastMyPoint = sanitizedMy?.let { GeoPoint(it.latitude, it.longitude) }
        lastOtherPoint = sanitizedOther?.let { GeoPoint(it.latitude, it.longitude) }
        val myIcon = if (myRole == ROLE_CHILD) R.drawable.ic_child_marker else R.drawable.ic_parent_marker
        val otherIcon = if (myRole == ROLE_CHILD) R.drawable.ic_parent_marker else R.drawable.ic_child_marker
        when {
            sanitizedMy != null && sanitizedOther != null -> displayLocations(
                sanitizedMy.latitude,
                sanitizedMy.longitude,
                sanitizedOther.latitude,
                sanitizedOther.longitude,
                sanitizedOther.speed,
                sanitizedMy.timestamp,
                sanitizedOther.timestamp
            )
            sanitizedMy != null -> displaySingleLocation(sanitizedMy.latitude, sanitizedMy.longitude, selfMarkerTitle(), myIcon, sanitizedMy.timestamp, getString(R.string.map_my_location))
            sanitizedOther != null -> displaySingleLocation(sanitizedOther.latitude, sanitizedOther.longitude, otherMarkerTitle(), otherIcon, sanitizedOther.timestamp, getString(R.string.map_other_location))
            else -> binding.statsCard.visibility = View.GONE
        }
        updateLiveSubtitle(sanitizedMy?.timestamp, sanitizedOther?.timestamp)
        binding.errorCard.visibility = View.GONE
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

    private fun isStale(timestamp: Long): Boolean {
        val normalized = normalizeTimestampMillis(timestamp) ?: return false
        return System.currentTimeMillis() - normalized > STALE_THRESHOLD_MS
    }

    private fun buildSnippet(label: String, timestamp: Long?): String {
        return formatMarkerSnippet(label, timestamp)
    }

    private fun resolveParentIdCandidates(): List<String> {
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

    private fun cacheKeyMy(): String = "${MAP_CACHE_MY}_${myRole}"
    private fun cacheKeyOther(): String = "${MAP_CACHE_OTHER}_${resolvedOtherId.ifBlank { otherId }}"

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
        val bounds = BoundingBox.fromGeoPoints(listOf(GeoPoint(lat1, lon1), GeoPoint(lat2, lon2)))
        mapView.zoomToBoundingBox(bounds, true, 100)
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
                delay(AUTO_REFRESH_INTERVAL)
                if (isMapReady) loadLocations()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        if (isMapReady) startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
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
        return ParentLocationData(parentId, latitude, longitude, accuracy, timestamp, batteryLevel, speed, bearing)
    }
}
