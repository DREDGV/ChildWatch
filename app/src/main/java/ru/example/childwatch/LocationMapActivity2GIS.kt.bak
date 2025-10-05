package ru.example.childwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import ru.dgis.sdk.Context
import ru.dgis.sdk.DGis
import ru.dgis.sdk.coordinates.GeoPoint
import ru.dgis.sdk.map.*
import ru.example.childwatch.databinding.ActivityLocationMap2gisBinding
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.LocationData
import ru.example.childwatch.utils.PermissionHelper
import java.text.SimpleDateFormat
import java.util.*

/**
 * Location Map Activity with 2GIS Maps integration
 *
 * Features:
 * - 2GIS Map display (perfect for Russia)
 * - Child location marker
 * - Real-time location updates from server
 * - Auto-refresh every 30 seconds
 */
class LocationMapActivity2GIS : AppCompatActivity() {

    companion object {
        private const val TAG = "LocationMapActivity2GIS"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_ZOOM = 16.0
        private const val AUTO_REFRESH_INTERVAL = 30000L // 30 seconds
    }

    private lateinit var binding: ActivityLocationMap2gisBinding
    private lateinit var networkClient: NetworkClient
    private lateinit var sdkContext: Context
    private var map: Map? = null
    private var currentMarker: Marker? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoRefreshJob: Job? = null

    // Child device ID (from settings)
    private var childDeviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize 2GIS SDK
        sdkContext = DGis.initialize(this)

        binding = ActivityLocationMap2gisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize network client
        networkClient = NetworkClient(this)

        // Load child device ID from preferences
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        childDeviceId = prefs.getString("child_device_id", null)

        // Setup UI
        setupUI()

        // Initialize map
        lifecycleScope.launch {
            binding.mapView.getMapAsync { map ->
                this@LocationMapActivity2GIS.map = map
                onMapReady()
            }
        }
    }

    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Карта местоположения (2GIS)"

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
            Toast.makeText(this, "История перемещений (в разработке)", Toast.LENGTH_SHORT).show()
        }

        // Show initial message
        updateLocationInfo("Подключение к серверу...", null)
    }

    private fun onMapReady() {
        Log.d(TAG, "Map ready")

        // Request location permission
        if (!PermissionHelper.hasLocationPermissions(this)) {
            requestLocationPermission()
        }

        // Load initial location
        refreshLocation()

        // Start auto-refresh
        startAutoRefresh()
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
                    Toast.makeText(this@LocationMapActivity2GIS, errorMsg, Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Location refresh error", e)
                binding.loadingProgress.hide()
                updateLocationInfo("❌ Ошибка подключения к серверу", null)
                Toast.makeText(this@LocationMapActivity2GIS, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateMapLocation(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long) {
        val position = GeoPoint(latitude, longitude)

        map?.let { map ->
            // Remove old marker
            currentMarker?.let { map.mapObjectManager.removeObject(it) }

            // Create marker options
            val markerOptions = MarkerOptions(
                position = position,
                icon = sdkContext.imageFactory.fromResource(android.R.drawable.ic_menu_mylocation)
            )

            // Add new marker
            currentMarker = map.mapObjectManager.addMarker(markerOptions)

            // Move camera to position
            val cameraPosition = CameraPosition(
                point = position,
                zoom = Zoom(DEFAULT_ZOOM)
            )

            map.camera.move(
                cameraPosition,
                Duration.ofSeconds(1),
                CameraAnimationType.LINEAR
            )

            Log.d(TAG, "Map updated: $latitude, $longitude")
        }
    }

    private fun centerMapOnChild() {
        currentMarker?.let { marker ->
            map?.let { map ->
                val cameraPosition = CameraPosition(
                    point = marker.position,
                    zoom = Zoom(DEFAULT_ZOOM)
                )
                map.camera.move(
                    cameraPosition,
                    Duration.ofSeconds(1),
                    CameraAnimationType.LINEAR
                )
            }
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
                refreshLocation()
            } else {
                Toast.makeText(this, "Разрешение на геолокацию необходимо для работы", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        stopAutoRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
        serviceScope.cancel()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
