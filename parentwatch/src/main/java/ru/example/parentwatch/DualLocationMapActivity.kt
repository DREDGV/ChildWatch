package ru.example.parentwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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
import ru.example.parentwatch.database.ParentWatchDatabase
import ru.example.parentwatch.database.entity.ParentLocation
import ru.example.parentwatch.database.repository.ParentLocationRepository
import ru.example.parentwatch.databinding.ActivityDualLocationMapBinding
import ru.example.parentwatch.location.LocationManager
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.network.ParentLocationData
import kotlin.math.*

/**
 * Dual Location Map Activity
 * 
 * Универсальная карта для показа двух устройств:
 * - ChildWatch (родитель): Я + Ребенок (с сервера /api/location/latest/:childId)
 * - ParentWatch (ребенок): Я + Родитель (с сервера /api/location/parent/latest/:parentId)
 * 
 * Intent extras:
 * - MY_ROLE: "parent" или "child"
 * - MY_ID: ID моего устройства
 * - OTHER_ID: ID другого устройства
 */
class DualLocationMapActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "DualLocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val AUTO_REFRESH_INTERVAL = 30_000L // 30 секунд
        private const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
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
    
    private var myRole: String = ROLE_PARENT
    private var myId: String = ""
    private var otherId: String = ""
    private var limitedMode: Boolean = false
    
    private var myMarker: Marker? = null
    private var otherMarker: Marker? = null
    private var connectionLine: Polyline? = null
    
    private var myLatitude: Double? = null
    private var myLongitude: Double? = null
    
    private var isMapReady = false
    private var autoRefreshJob: Job? = null
    private var autoFitEnabled = true
    private var lastMyPoint: GeoPoint? = null
    private var lastOtherPoint: GeoPoint? = null

    private data class CachedLocation(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val speed: Float?
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDualLocationMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            // Get role and IDs from intent
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
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }

            if (otherId.isBlank()) {
                otherId = resolveParentIdCandidateFromPrefs(localPrefs, legacyPrefs, myId)
            }

            // In child mode we can resolve parentId dynamically; limited mode only when parent target is unknown.
            limitedMode = otherId.isEmpty() && myRole != ROLE_CHILD

            // Initialize OSMdroid
            Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
            Configuration.getInstance().userAgentValue = packageName

            // Initialize components
            prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            database = ParentWatchDatabase.getInstance(this)
            locationManager = LocationManager(this)
            networkClient = NetworkClient(this)
            parentLocationRepository = ParentLocationRepository(database.parentLocationDao())

            // Setup UI
            setupToolbar()
            setupMap()
            setupRefreshButton()
            setupCenterButtons()

            // Load locations
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
        val candidates = listOf(
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            prefs.getString("selected_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("selected_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && it != myDeviceId }

        return candidates.firstOrNull().orEmpty()
    }

    private fun handleStartupFailure(error: Throwable) {
        Log.e(TAG, "Map startup failed", error)
        if (::binding.isInitialized) {
            binding.loadingIndicator.visibility = View.GONE
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = "Map startup failed: ${error.message ?: "unknown error"}"
            return
        }
        Toast.makeText(this, "Map startup failed", Toast.LENGTH_LONG).show()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Set title based on role
        supportActionBar?.title = when (myRole) {
            ROLE_PARENT -> "📍 Где ребенок?"
            ROLE_CHILD -> "📍 Где родители?"
            else -> "📍 Карта"
        }
        if (limitedMode) {
            binding.toolbar.subtitle = "Режим просмотра — свяжите устройства"
        }
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
                Toast.makeText(this, "My location not available", Toast.LENGTH_SHORT).show()
            }
        }

        binding.centerOtherButton.setOnClickListener {
            autoFitEnabled = false
            updateAutoFitUi()
            if (!centerOnPoint(lastOtherPoint)) {
                Toast.makeText(this, "Other location not available", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Локация устройства недоступна: нет разрешения", Toast.LENGTH_LONG).show()
                loadLocations()
                startAutoRefresh()
            }
        }
    }
    
    private fun loadLocations() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.errorCard.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                val cachedMy = loadCachedLocation(cacheKeyMy())
                val cachedOther = if (limitedMode) null else loadCachedLocation(cacheKeyOther())

                if (cachedMy != null || cachedOther != null) {
                    displayAvailableLocations(
                        myLat = cachedMy?.latitude,
                        myLon = cachedMy?.longitude,
                        myTimestamp = cachedMy?.timestamp,
                        otherLocation = cachedOther?.toParentLocationData(otherId)
                    )
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
                } else {
                    Log.w(TAG, "My location not available")
                }
                
                // Получить локацию другого устройства
                // Для ребенка (ROLE_CHILD): пробуем несколько parentId и локальный fallback
                var resolvedOtherId = otherId
                val otherLocation = if (limitedMode) {
                    null
                } else withContext(Dispatchers.IO) {
                    if (myRole == ROLE_CHILD) {
                        val parentCandidates = resolveParentIdCandidates()
                        val fromServer = parentCandidates.firstNotNullOfOrNull { parentId ->
                            networkClient.getLatestParentLocation(parentId)?.also {
                                resolvedOtherId = parentId
                            }
                        }
                        val cachedParentExact = parentCandidates.firstNotNullOfOrNull { parentId ->
                            parentLocationRepository.getLatestLocation(parentId)?.also {
                                resolvedOtherId = parentId
                            }
                        }
                        val cachedParentAny = parentLocationRepository.getLatestLocationAny()
                        fromServer ?: cachedParentExact?.toNetworkModel() ?: cachedParentAny?.toNetworkModel()
                    } else {
                        // Parent getting child location from server
                        networkClient.getLatestLocation(otherId)
                    }
                }
                
                if (otherLocation != null) {
                    if (resolvedOtherId.isNotBlank() && resolvedOtherId != otherId) {
                        otherId = resolvedOtherId
                    }
                    saveCachedLocation(
                        cacheKeyOther(),
                        otherLocation.latitude,
                        otherLocation.longitude,
                        otherLocation.timestamp,
                        otherLocation.speed
                    )
                }

                val myLatFinal = myLatitude ?: cachedMy?.latitude
                val myLonFinal = myLongitude ?: cachedMy?.longitude
                val myTsFinal = myLocation?.time ?: cachedMy?.timestamp
                val otherFinal = otherLocation ?: cachedOther?.toParentLocationData(
                    resolvedOtherId.ifBlank { otherId.ifBlank { "paired-device" } }
                )

                if (myLatFinal != null && myLonFinal != null || otherFinal != null) {
                    displayAvailableLocations(
                        myLat = myLatFinal,
                        myLon = myLonFinal,
                        myTimestamp = myTsFinal,
                        otherLocation = otherFinal
                    )
                    binding.loadingIndicator.visibility = View.GONE
                } else {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE

                    val errorMsg = if (limitedMode) {
                        "Чтобы видеть локацию родителей — свяжите устройства в Настройках."
                    } else when (myRole) {
                        ROLE_PARENT -> "Локация ребенка недоступна.\nПроверьте что детское устройство подключено к интернету."
                        ROLE_CHILD -> "Локация родителя недоступна.\nПопросите родителя включить 'Делиться моей локацией' в настройках."
                        else -> "Локация недоступна"
                    }
                    binding.errorText.text = errorMsg
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = "Ошибка загрузки локации: ${e.message}"
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
        if (!isValidCoordinate(myLat, myLon) || !isValidCoordinate(otherLat, otherLon)) {
            Log.w(TAG, "displayLocations skipped due to invalid coordinates: my=($myLat,$myLon), other=($otherLat,$otherLon)")
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = "Получены некорректные координаты. Обновите карту."
            return
        }

        // Очистить предыдущие маркеры
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        
        // Создать мой маркер (зеленый для родителя, синий для ребенка)
        val myMarkerIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_parent_marker
            ROLE_CHILD -> R.drawable.ic_child_marker
            else -> R.drawable.ic_parent_marker
        }
        
        val myMarkerTitle = when (myRole) {
            ROLE_PARENT -> "Я (Родитель)"
            ROLE_CHILD -> "Я (Ребенок)"
            else -> "Я"
        }
        
        myMarker = Marker(mapView).apply {
            position = GeoPoint(myLat, myLon)
            title = myMarkerTitle
            snippet = buildSnippet("Моя локация", myTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, myMarkerIcon)
        }
        mapView.overlays.add(myMarker)
        
        // Создать маркер другого устройства
        val otherMarkerIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_child_marker
            ROLE_CHILD -> R.drawable.ic_parent_marker
            else -> R.drawable.ic_child_marker
        }
        
        val otherMarkerTitle = when (myRole) {
            ROLE_PARENT -> "Ребенок"
            ROLE_CHILD -> "Родитель"
            else -> "Другое устройство"
        }
        
        otherMarker = Marker(mapView).apply {
            position = GeoPoint(otherLat, otherLon)
            title = otherMarkerTitle
            snippet = buildSnippet("Текущая локация", otherTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, otherMarkerIcon)
        }
        mapView.overlays.add(otherMarker)
        
        // Создать линию между маркерами
        connectionLine = Polyline().apply {
            addPoint(GeoPoint(myLat, myLon))
            addPoint(GeoPoint(otherLat, otherLon))
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(connectionLine)
        
        // Центрировать карту
        if (autoFitEnabled) {

            centerMapOnBothLocations(myLat, myLon, otherLat, otherLon)

        }
        
        // Рассчитать и показать расстояние и ETA
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
    
    private fun displaySingleLocation(lat: Double, lon: Double, title: String, iconRes: Int, timestamp: Long?) {
        if (!isValidCoordinate(lat, lon)) {
            Log.w(TAG, "displaySingleLocation skipped due to invalid coordinates: ($lat,$lon)")
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = "Получены некорректные координаты. Обновите карту."
            return
        }

        // Очистить предыдущие маркеры
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        
        myMarker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            this.title = title
            snippet = buildSnippet("Текущая локация", timestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, iconRes)
        }
        mapView.overlays.add(myMarker)
        
        // Центрировать на точке
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
        val otherLat = otherLocation?.latitude
        val otherLon = otherLocation?.longitude

        lastMyPoint = if (myLat != null && myLon != null) {
            GeoPoint(myLat, myLon)
        } else {
            null
        }
        lastOtherPoint = if (otherLat != null && otherLon != null) {
            GeoPoint(otherLat, otherLon)
        } else {
            null
        }

        val myTitle = when (myRole) {
            ROLE_PARENT -> "Я (Родитель)"
            ROLE_CHILD -> "Я (Ребенок)"
            else -> "Я"
        }

        val myIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_parent_marker
            ROLE_CHILD -> R.drawable.ic_child_marker
            else -> R.drawable.ic_parent_marker
        }

        val otherTitle = when (myRole) {
            ROLE_PARENT -> "Ребенок"
            ROLE_CHILD -> "Родитель"
            else -> "Другое устройство"
        }

        val otherIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_child_marker
            ROLE_CHILD -> R.drawable.ic_parent_marker
            else -> R.drawable.ic_child_marker
        }

        val staleWarnings = mutableListOf<String>()
        if (myTimestamp != null && isStale(myTimestamp)) {
            staleWarnings.add(getString(R.string.my_location) + " " + getString(R.string.location_stale_warning))
        }
        if (otherLocation?.timestamp != null && isStale(otherLocation.timestamp)) {
            staleWarnings.add(getString(R.string.other_location) + " " + getString(R.string.location_stale_warning))
        }

        if (myLat != null && myLon != null && otherLat != null && otherLon != null) {
            displayLocations(
                myLat = myLat,
                myLon = myLon,
                otherLat = otherLat,
                otherLon = otherLon,
                otherSpeed = otherLocation.speed,
                myTimestamp = myTimestamp,
                otherTimestamp = otherLocation.timestamp
            )
        } else if (myLat != null && myLon != null) {
            displaySingleLocation(myLat, myLon, myTitle, myIcon, myTimestamp)
        } else if (otherLat != null && otherLon != null) {
            displaySingleLocation(otherLat, otherLon, otherTitle, otherIcon, otherLocation.timestamp)
        }

        if (staleWarnings.isNotEmpty()) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = "⚠️ ${staleWarnings.joinToString(", ")}"
        } else {
            binding.errorCard.visibility = View.GONE
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

    private fun buildSnippet(label: String, timestamp: Long?): String {
        val normalized = normalizeTimestampMillis(timestamp) ?: return label
        val timeInfo = formatTimestamp(normalized)
        return if (isStale(normalized)) {
            "$label - $timeInfo (\u0443\u0441\u0442\u0430\u0440.)"
        } else {
            "$label - $timeInfo"
        }
    }

    private fun resolveParentIdCandidates(): List<String> {
        val legacyPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val ownId = myId.trim()
        val candidates = listOf(
            otherId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            prefs.getString("selected_device_id", null),
            prefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("selected_device_id", null),
            legacyPrefs.getString("device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && it != ownId }
            .distinct()

        return buildList {
            addAll(candidates)
            if (ownId.isNotBlank() && ownId !in candidates) {
                // Compatibility fallback: some legacy parent builds reported location under child device ID.
                add(ownId)
            }
        }
    }

    private fun cacheKeyMy(): String = "${MAP_CACHE_MY}_${myRole}"

    private fun cacheKeyOther(): String = "${MAP_CACHE_OTHER}_${otherId}"

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
    
    private fun centerMapOnBothLocations(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ) {
        if (!isValidCoordinate(lat1, lon1) || !isValidCoordinate(lat2, lon2)) {
            Log.w(TAG, "Skip centerMapOnBothLocations due to invalid coordinates")
            return
        }

        val distance = calculateDistance(lat1, lon1, lat2, lon2)
        if (distance < 30) {
            mapView.controller.setCenter(GeoPoint(lat1, lon1))
            mapView.controller.setZoom(17.0)
            return
        }

        val bounds = BoundingBox.fromGeoPoints(
            listOf(
                GeoPoint(lat1, lon1),
                GeoPoint(lat2, lon2)
            )
        )
        mapView.zoomToBoundingBox(bounds, true, 100)
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // метры
        
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
    }
    
    override fun onDestroy() {
        super.onDestroy()
        autoRefreshJob?.cancel()
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

}





