package ru.example.childwatch

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.Locale
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.Polyline
import ru.example.childwatch.databinding.ActivityLocationMapNewBinding
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.LocationData
import ru.example.childwatch.utils.PermissionHelper
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Color

/**
 * Location Map Activity with Google Maps integration
 *
 * Features:
 * - Google Map display
 * - Child location marker
 * - Real-time location updates from server
 * - Location history
 * - Auto-refresh
 */
class LocationMapActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "LocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_ZOOM = 15f
        private const val AUTO_REFRESH_INTERVAL = 30000L // 30 seconds
    }

    private lateinit var binding: ActivityLocationMapNewBinding
    private lateinit var networkClient: NetworkClient
    private var googleMap: GoogleMap? = null
    private var currentMarker: com.google.android.gms.maps.model.Marker? = null
    private val historyMarkers = mutableListOf<com.google.android.gms.maps.model.Marker>()
    private var historyPolyline: Polyline? = null
    private var isHistoryVisible = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoRefreshJob: Job? = null

    // Child device ID (should be configured in settings or obtained from server)
    private var childDeviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationMapNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize network client
        networkClient = NetworkClient(this)

        // Load child device ID from preferences
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        childDeviceId = prefs.getString("child_device_id", null)

        // Setup UI
        setupUI()

        // Initialize map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
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

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Configure map
        googleMap?.apply {
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isCompassEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
        }

        // Request location permission
        if (!PermissionHelper.hasLocationPermissions(this)) {
            requestLocationPermission()
        } else {
            enableMyLocation()
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
            googleMap?.isMyLocationEnabled = true
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

                binding.loadingProgress.hide()

                if (response.isSuccessful && response.body() != null) {
                    val locationData = response.body()!!
                    val location = locationData.location

                    if (location != null) {
                        updateMapLocation(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = location.timestamp
                        )

                        updateLocationInfo(
                            buildLocationInfoText(location),
                            location.timestamp
                        )
                    } else {
                        updateLocationInfo("❌ Данные о местоположении недоступны", null)
                    }
                } else {
                    val errorMsg = if (response.code() == 404) {
                        "Местоположение ребенка пока не получено"
                    } else {
                        "Ошибка загрузки: ${response.code()}"
                    }
                    updateLocationInfo("❌ $errorMsg", null)
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

    private fun updateMapLocation(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long) {
        val position = LatLng(latitude, longitude)

        googleMap?.apply {
            // Remove old marker
            currentMarker?.remove()

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

                // Add new marker
                currentMarker = addMarker(
                    MarkerOptions()
                        .position(position)
                        .title("Местоположение ребенка")
                        .snippet(snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )

                // Show info window
                currentMarker?.showInfoWindow()
            }

            // Move camera
            animateCamera(CameraUpdateFactory.newLatLngZoom(position, DEFAULT_ZOOM))
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

    private fun centerMapOnChild() {
        currentMarker?.let { marker ->
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, DEFAULT_ZOOM))
        } ?: run {
            Toast.makeText(this, "Местоположение недоступно", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLocationInfo(text: String, timestamp: Long?) {
        val timeInfo = if (timestamp != null) {
            val timeAgo = getTimeAgo(timestamp)
            "\nОбновлено: $timeAgo назад"
        } else {
            ""
        }

        binding.locationInfoText.text = text + timeInfo
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
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
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

                val response = withContext(Dispatchers.IO) {
                    networkClient.getLocationHistory(
                        childDeviceId = childDeviceId!!,
                        startTime = startTime,
                        endTime = endTime,
                        limit = 200
                    )
                }

                binding.loadingProgress.hide()

                if (response.isSuccessful && response.body() != null) {
                    val historyData = response.body()!!

                    if (historyData.success && historyData.locations.isNotEmpty()) {
                        displayHistoryOnMap(historyData.locations)
                        isHistoryVisible = true
                        binding.viewHistoryButton.text = "Скрыть историю"
                        Toast.makeText(this@LocationMapActivity, "Показано ${historyData.locations.size} точек", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@LocationMapActivity, "История перемещений пуста", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@LocationMapActivity, "Не удалось загрузить историю", Toast.LENGTH_SHORT).show()
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

        googleMap?.let { map ->
            // Create list of LatLng points
            val points = locations.map { LatLng(it.latitude, it.longitude) }

            // Draw polyline (route)
            historyPolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(Color.BLUE)
                    .width(10f)
                    .geodesic(true)
            )

            // Add markers for significant points (first, last, and every 10th point)
            locations.forEachIndexed { index, location ->
                if (index == 0 || index == locations.size - 1 || index % 10 == 0) {
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(LatLng(location.latitude, location.longitude))
                            .title(when {
                                index == 0 -> "Начало"
                                index == locations.size - 1 -> "Конец"
                                else -> "Точка ${index + 1}"
                            })
                            .snippet(formatTime(location.timestamp))
                            .icon(BitmapDescriptorFactory.defaultMarker(
                                when {
                                    index == 0 -> BitmapDescriptorFactory.HUE_GREEN
                                    index == locations.size - 1 -> BitmapDescriptorFactory.HUE_RED
                                    else -> BitmapDescriptorFactory.HUE_ORANGE
                                }
                            ))
                    )
                    marker?.let { historyMarkers.add(it) }
                }
            }

            // Adjust camera to show all points
            if (points.isNotEmpty()) {
                val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                points.forEach { boundsBuilder.include(it) }
                val bounds = boundsBuilder.build()
                val padding = 100 // pixels
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            }
        }
    }

    /**
     * Hide history from map
     */
    private fun hideHistory() {
        // Remove polyline
        historyPolyline?.remove()
        historyPolyline = null

        // Remove all history markers
        historyMarkers.forEach { it.remove() }
        historyMarkers.clear()

        isHistoryVisible = false
        binding.viewHistoryButton.text = "Показать историю"
        Toast.makeText(this, "История скрыта", Toast.LENGTH_SHORT).show()

        // Return to current location view
        centerMapOnChild()
    }
}
