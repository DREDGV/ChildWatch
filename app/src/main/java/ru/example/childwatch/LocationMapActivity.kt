package ru.example.childwatch

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import ru.example.childwatch.databinding.ActivityLocationMapNewBinding
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.LocationData
import ru.example.childwatch.network.ParentLocationData
import ru.example.childwatch.utils.PermissionHelper
import kotlinx.coroutines.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.Date
import java.util.Locale
import android.graphics.Color

/**
 * Location Map Activity with OpenStreetMap integration
 *
 * Features:
 * - OpenStreetMap display (works in Russia without VPN)
 * - Child location marker
 * - Real-time location updates from server
 * - Location history
 * - Auto-refresh
 */
class LocationMapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_ZOOM = 15.0
        private const val AUTO_REFRESH_INTERVAL = 30000L // 30 seconds
    }

    private lateinit var binding: ActivityLocationMapNewBinding
    private lateinit var networkClient: NetworkClient
    private val database by lazy { ChildWatchDatabase.getInstance(this) }
    private var osmMapView: MapView? = null
    private var currentMarker: Marker? = null
    private var parentMarker: Marker? = null
    private val historyMarkers = mutableListOf<Marker>()
    private var historyPolyline: Polyline? = null
    private var isHistoryVisible = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoRefreshJob: Job? = null

    // Child device ID (should be configured in settings or obtained from server)
    private var childDeviceId: String? = null
    private var parentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OSMdroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityLocationMapNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize network client
        networkClient = NetworkClient(this)

        // Load child device ID from preferences
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        childDeviceId = prefs.getString("child_device_id", null)
        parentId = prefs.getString(
            "parent_id",
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        )

        // Setup UI
        setupUI()

        // Initialize map
        initializeMap()
    }

    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Карта местоположения"

        // Refresh button
        binding.refreshLocationButton.setOnClickListener {
            refreshLocation()
        }

        // Center map button
        binding.centerMapButton.setOnClickListener {
            centerMapOnChild()
        }

        // View history button
        binding.viewHistoryButton.setOnClickListener {
            toggleHistoryView()
        }

        // Show initial message
        updateLocationInfo("Подключение к серверу...", null)
    }

    private fun initializeMap() {
        osmMapView = binding.osmMapView

        osmMapView?.apply {
            // Set tile source (OpenStreetMap)
            setTileSource(TileSourceFactory.MAPNIK)

            // Enable zoom controls
            setBuiltInZoomControls(true)
            setMultiTouchControls(true)

            // Set default zoom and center (will be updated when location loads)
            controller.setZoom(DEFAULT_ZOOM)

            // Enable my location overlay if permission granted
            if (PermissionHelper.hasLocationPermissions(this@LocationMapActivity)) {
                enableMyLocation()
            } else {
                requestLocationPermission()
            }
        }

        // Load initial location
        refreshLocation()

        // Start auto-refresh
        startAutoRefresh()
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // OSMdroid doesn't have built-in MyLocation like Google Maps
            // We could add a MyLocationNewOverlay if needed, but not essential for this app
            Log.d(TAG, "Location permission granted")
        }
    }

    private fun refreshLocation() {
        if (childDeviceId == null) {
            updateLocationInfo("⚠️ ID устройства ребенка не настроен", null)
            Toast.makeText(this, "Настройте ID устройства ребенка в настройках", Toast.LENGTH_LONG).show()
            return
        }

        binding.loadingProgress.show()
        updateLocationInfo("Загрузка местоположения...", null)

        serviceScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    networkClient.getChildLocation(childDeviceId!!)
                }
                val parentLocation = fetchParentLocation()

                binding.loadingProgress.hide()

                if (response.isSuccessful && response.body() != null) {
                    val locationData = response.body()!!
                    val location = locationData.location

                    if (location != null) {
                        updateMapLocation(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = location.timestamp,
                            parentLocation = parentLocation
                        )

                        updateLocationInfo(
                            buildLocationInfoText(location),
                            location.timestamp,
                            parentLocation
                        )
                    } else {
                        updateLocationInfo("❌ Данные о местоположении недоступны", null, parentLocation)
                    }
                } else {
                    val errorMsg = if (response.code() == 404) {
                        "Местоположение ребенка пока не получено"
                    } else {
                        "Ошибка загрузки: ${response.code()}"
                    }
                    updateLocationInfo("❌ $errorMsg", null, parentLocation)
                    Toast.makeText(this@LocationMapActivity, errorMsg, Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Location refresh error", e)
                binding.loadingProgress.hide()
                updateLocationInfo("❌ Ошибка подключения к серверу", null)
                Toast.makeText(this@LocationMapActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateMapLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        timestamp: Long,
        parentLocation: ParentLocationData?
    ) {
        val position = GeoPoint(latitude, longitude)

        osmMapView?.apply {
            // Remove old marker
            currentMarker?.let { overlays.remove(it) }
            parentMarker?.let { overlays.remove(it) }

            // Get address asynchronously
            serviceScope.launch {
                val address = getAddressFromLocation(latitude, longitude)
                val snippet = buildString {
                    if (address != null) {
                        appendLine(address)
                    }
                    appendLine("Точность: ${accuracy.toInt()} м")
                    append("Время: ${formatTime(timestamp)}")
                }

                // Create new marker
                currentMarker = Marker(this@apply).apply {
                    this.position = position
                    this.title = "Местоположение ребенка"
                    this.snippet = snippet
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                    // Show info window
                    showInfoWindow()
                }

                // Add marker to map
                currentMarker?.let { overlays.add(it) }
                parentLocation?.let { parent ->
                    val parentPoint = GeoPoint(parent.latitude, parent.longitude)
                    val parentSnippet = buildString {
                        appendLine("Точность: ${parent.accuracy.toInt()} м")
                        append("Время: ${formatTime(parent.timestamp)}")
                    }
                    parentMarker = Marker(this@apply).apply {
                        setPosition(parentPoint)
                        setTitle("Ваше местоположение")
                        setSnippet(parentSnippet)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    parentMarker?.let { overlays.add(it) }
                }
                invalidate()

                // Move camera (center between parent and child if оба есть)
                val target = parentLocation?.let { parent ->
                    GeoPoint(
                        (latitude + parent.latitude) / 2,
                        (longitude + parent.longitude) / 2
                    )
                } ?: position
                controller.animateTo(target)
                controller.setZoom(
                    parentLocation?.let { parent ->
                        val distance = calculateDistance(latitude, longitude, parent.latitude, parent.longitude)
                        when {
                            distance < 100 -> 18.0
                            distance < 500 -> 16.0
                            distance < 1000 -> 15.0
                            distance < 5000 -> 13.0
                            distance < 10000 -> 12.0
                            else -> 11.0
                        }
                    } ?: DEFAULT_ZOOM
                )
            }
        }
    }

    /**
     * Get address from coordinates using Geocoder (reverse geocoding)
     */
    private suspend fun getAddressFromLocation(latitude: Double, longitude: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@LocationMapActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    // Format address nicely
                    buildString {
                        address.thoroughfare?.let { append(it) } // Street name
                        if (address.subThoroughfare != null) {
                            append(", ${address.subThoroughfare}") // House number
                        }
                        if (address.locality != null) {
                            if (isNotEmpty()) append(", ")
                            append(address.locality) // City
                        }
                    }.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } catch (e: IOException) {
                Log.e(TAG, "Geocoder error", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected geocoder error", e)
                null
            }
        }
    }

    private suspend fun fetchParentLocation(): ParentLocationData? {
        val id = parentId ?: return null
        return withContext(Dispatchers.IO) {
            val dao = database.parentLocationDao()
            val local = runCatching { dao.getLatestLocation(id) }.getOrNull()
            local?.let {
                ParentLocationData(
                    parentId = it.parentId,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    timestamp = it.timestamp,
                    battery = it.batteryLevel,
                    speed = it.speed,
                    bearing = it.bearing
                )
            } ?: networkClient.getLatestParentLocation(id)
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return (earthRadius * c).toFloat()
    }

    private fun centerMapOnChild() {
        currentMarker?.let { marker ->
            osmMapView?.controller?.animateTo(marker.position)
            osmMapView?.controller?.setZoom(DEFAULT_ZOOM)
        } ?: run {
            Toast.makeText(this, "Местоположение недоступно", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLocationInfo(text: String, timestamp: Long?, parentLocation: ParentLocationData? = null) {
        val timeInfo = if (timestamp != null) {
            val timeAgo = getTimeAgo(timestamp)
            "\nОбновлено: $timeAgo назад"
        } else {
            ""
        }

        val parentInfo = parentLocation?.let {
            "\n\n👤 Родитель: ${String.format("%.5f", it.latitude)}, ${String.format("%.5f", it.longitude)}" +
                "\n🕐 Время: ${formatTime(it.timestamp)}"
        } ?: "\n\n👤 Родитель: нет данных"

        binding.locationInfoText.text = text + timeInfo + parentInfo
    }

    private fun buildLocationInfoText(location: LocationData): String {
        return buildString {
            appendLine("📍 Координаты:")
            appendLine("${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}")
            appendLine()
            appendLine("🎯 Точность: ${location.accuracy.toInt()} метров")
            appendLine("🕐 Время: ${formatTime(location.timestamp)}")
        }
    }

    private fun formatTime(timestamp: Long): String {
        val format = SimpleDateFormat("HH:mm:ss, dd.MM.yyyy", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    private fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "$days д."
            hours > 0 -> "$hours ч."
            minutes > 0 -> "$minutes мин."
            else -> "$seconds сек."
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL)
                refreshLocation()
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
                refreshLocation()
            } else {
                Toast.makeText(this, "Разрешение на геолокацию необходимо для работы", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        osmMapView?.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        osmMapView?.onPause()
        stopAutoRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    /**
     * Toggle history view on/off
     */
    private fun toggleHistoryView() {
        if (isHistoryVisible) {
            hideHistory()
        } else {
            showHistory()
        }
    }

    /**
     * Load and display location history on map
     */
    private fun showHistory() {
        if (childDeviceId == null) {
            Toast.makeText(this, "ID устройства не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        // Show dialog to select time period
        showTimePeriodDialog()
    }

    /**
     * Show dialog to select time period for history
     */
    private fun showTimePeriodDialog() {
        val periods = arrayOf(
            "Последний час",
            "Последние 3 часа",
            "Последние 12 часов",
            "Последний день",
            "Последние 3 дня",
            "Последняя неделя"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Выберите период")
            .setItems(periods) { _, which ->
                val hours = when (which) {
                    0 -> 1
                    1 -> 3
                    2 -> 12
                    3 -> 24
                    4 -> 72
                    5 -> 168
                    else -> 24
                }
                loadHistory(hours)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Load history for specified number of hours
     */
    private fun loadHistory(hours: Int) {
        binding.loadingProgress.show()

        serviceScope.launch {
            try {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (hours * 60 * 60 * 1000L)

                val historyData = withContext(Dispatchers.IO) {
                    networkClient.getLocationHistory(
                        deviceId = childDeviceId!!,
                        fromTimestamp = startTime,
                        toTimestamp = endTime,
                        limit = 200
                    )
                }

                binding.loadingProgress.hide()

                if (historyData != null && historyData.isNotEmpty()) {
                    // Convert ParentLocationData to LocationData
                    val locations = historyData.map { parentLoc ->
                        ru.example.childwatch.network.LocationData(
                            latitude = parentLoc.latitude,
                            longitude = parentLoc.longitude,
                            accuracy = parentLoc.accuracy,
                            timestamp = parentLoc.timestamp,
                            recordedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(parentLoc.timestamp))
                        )
                    }
                    displayHistoryOnMap(locations)
                    isHistoryVisible = true
                    binding.viewHistoryButton.text = "Скрыть историю"
                    Toast.makeText(this@LocationMapActivity, "Показано ${locations.size} точек", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@LocationMapActivity, "История перемещений пуста", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading history", e)
                binding.loadingProgress.hide()
                Toast.makeText(this@LocationMapActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Display location history on map with polyline and markers
     */
    private fun displayHistoryOnMap(locations: List<LocationData>) {
        if (locations.isEmpty()) return

        osmMapView?.let { map ->
            // Create list of GeoPoint points
            val points = locations.map { GeoPoint(it.latitude, it.longitude) }

            // Draw polyline (route)
            historyPolyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = Color.BLUE
                outlinePaint.strokeWidth = 10f
            }
            map.overlays.add(historyPolyline)

            // Add markers for significant points (first, last, and every 10th point)
            locations.forEachIndexed { index, location ->
                if (index == 0 || index == locations.size - 1 || index % 10 == 0) {
                    val marker = Marker(map).apply {
                        position = GeoPoint(location.latitude, location.longitude)
                        title = when {
                            index == 0 -> "Начало"
                            index == locations.size - 1 -> "Конец"
                            else -> "Точка ${index + 1}"
                        }
                        snippet = formatTime(location.timestamp)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    map.overlays.add(marker)
                    historyMarkers.add(marker)
                }
            }

            // Adjust camera to show all points
            if (points.isNotEmpty()) {
                // Calculate bounding box
                val minLat = points.minOf { it.latitude }
                val maxLat = points.maxOf { it.latitude }
                val minLon = points.minOf { it.longitude }
                val maxLon = points.maxOf { it.longitude }

                val centerLat = (minLat + maxLat) / 2
                val centerLon = (minLon + maxLon) / 2

                map.controller.setCenter(GeoPoint(centerLat, centerLon))

                // Calculate appropriate zoom level
                val latSpan = maxLat - minLat
                val lonSpan = maxLon - minLon
                val maxSpan = maxOf(latSpan, lonSpan)

                val zoom = when {
                    maxSpan > 1.0 -> 8.0
                    maxSpan > 0.5 -> 10.0
                    maxSpan > 0.1 -> 12.0
                    maxSpan > 0.05 -> 14.0
                    else -> 15.0
                }
                map.controller.setZoom(zoom)
            }

            map.invalidate()
        }
    }

    /**
     * Hide history from map
     */
    private fun hideHistory() {
        osmMapView?.let { map ->
            // Remove polyline
            historyPolyline?.let { map.overlays.remove(it) }
            historyPolyline = null

            // Remove all history markers
            historyMarkers.forEach { map.overlays.remove(it) }
            historyMarkers.clear()

            map.invalidate()
        }

        isHistoryVisible = false
        binding.viewHistoryButton.text = "Показать историю"
        Toast.makeText(this, "История скрыта", Toast.LENGTH_SHORT).show()

        // Return to current location view
        centerMapOnChild()
    }
}
