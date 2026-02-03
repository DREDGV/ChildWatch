package ru.example.parentwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get role and IDs from intent
        myRole = intent.getStringExtra(EXTRA_MY_ROLE) ?: ROLE_PARENT
        myId = intent.getStringExtra(EXTRA_MY_ID) ?: ""
        otherId = intent.getStringExtra(EXTRA_OTHER_ID) ?: ""
        
        // Allow opening without full setup (limited mode: show only my location)
        limitedMode = myId.isEmpty() || otherId.isEmpty()
        
        // Initialize OSMdroid
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        
        binding = ActivityDualLocationMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
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
        
        // Load locations
        checkPermissionsAndLoad()
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
        
        isMapReady = true
    }
    
    private fun setupRefreshButton() {
        binding.refreshButton.setOnClickListener {
            loadLocations()
        }
    }
    
    private fun checkPermissionsAndLoad() {
        if (hasLocationPermission()) {
            loadLocations()
            startAutoRefresh()
        } else {
            requestLocationPermission()
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
                Toast.makeText(this, "Требуется разрешение на доступ к локации", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun loadLocations() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                // Получить мою локацию
                val myLocation = withContext(Dispatchers.IO) {
                    locationManager.getCurrentLocation()
                }
                
                if (myLocation != null) {
                    myLatitude = myLocation.latitude
                    myLongitude = myLocation.longitude
                } else {
                    Log.w(TAG, "My location not available")
                }
                
                // Получить локацию другого устройства
                // Для ребенка (ROLE_CHILD): сначала проверяем локальную БД для parent_location через WebSocket
                val otherLocation = if (limitedMode) {
                    null
                } else withContext(Dispatchers.IO) {
                    if (myRole == ROLE_CHILD && otherId.isNotEmpty()) {
                        val cachedParent = parentLocationRepository.getLatestLocation(otherId)
                        cachedParent?.toNetworkModel() ?: run {
                            Log.w(TAG, "No parent location in local DB, trying server...")
                            networkClient.getLatestParentLocation(otherId)
                        }
                    } else {
                        // Parent getting child location from server
                        networkClient.getLatestLocation(otherId)
                    }
                }
                
                if (otherLocation != null && myLatitude != null && myLongitude != null) {
                    // Отобразить обе локации
                    displayLocations(
                        myLat = myLatitude!!,
                        myLon = myLongitude!!,
                        otherLat = otherLocation.latitude,
                        otherLon = otherLocation.longitude,
                        otherSpeed = otherLocation.speed
                    )
                    
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorText.visibility = View.GONE
                    
                } else {
                    // Локация другого устройства недоступна
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorText.visibility = View.VISIBLE
                    
                    val errorMsg = if (limitedMode) {
                        "Чтобы видеть локацию родителей — свяжите устройства в Настройках."
                    } else when (myRole) {
                        ROLE_PARENT -> "Локация ребенка недоступна.\nПроверьте что детское устройство подключено к интернету."
                        ROLE_CHILD -> "Локация родителя недоступна.\nПопросите родителя включить 'Делиться моей локацией' в настройках."
                        else -> "Локация недоступна"
                    }
                    binding.errorText.text = errorMsg
                    
                    // Показать только мою локацию если доступна
                    if (myLatitude != null && myLongitude != null) {
                        displayMyLocationOnly(myLatitude!!, myLongitude!!)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = "Ошибка загрузки локации: ${e.message}"
            }
        }
    }
    
    private fun displayLocations(
        myLat: Double,
        myLon: Double,
        otherLat: Double,
        otherLon: Double,
        otherSpeed: Float?
    ) {
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
            snippet = "Моя локация"
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
            snippet = "Текущая локация"
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
        centerMapOnBothLocations(myLat, myLon, otherLat, otherLon)
        
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
    
    private fun displayMyLocationOnly(myLat: Double, myLon: Double) {
        // Очистить предыдущие маркеры
        myMarker?.let { mapView.overlays.remove(it) }
        
        // Создать только мой маркер
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
            snippet = "Моя локация"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, myMarkerIcon)
        }
        mapView.overlays.add(myMarker)
        
        // Центрировать на мне
        mapView.controller.setCenter(GeoPoint(myLat, myLon))
        mapView.controller.setZoom(15.0)
        
        binding.statsCard.visibility = View.GONE
        
        mapView.invalidate()
    }
    
    private fun centerMapOnBothLocations(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ) {
        // Рассчитать центр между двумя точками
        val centerLat = (lat1 + lat2) / 2
        val centerLon = (lon1 + lon2) / 2
        
        // Рассчитать расстояние для определения zoom уровня
        val distance = calculateDistance(lat1, lon1, lat2, lon2)
        
        val zoomLevel = when {
            distance < 100 -> 18.0    // < 100 метров
            distance < 500 -> 16.0    // < 500 метров
            distance < 1000 -> 15.0   // < 1 км
            distance < 5000 -> 13.0   // < 5 км
            distance < 10000 -> 12.0  // < 10 км
            else -> 11.0              // > 10 км
        }
        
        mapView.controller.setCenter(GeoPoint(centerLat, centerLon))
        mapView.controller.setZoom(zoomLevel)
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
        mapView.onResume()
        
        if (hasLocationPermission() && isMapReady) {
            startAutoRefresh()
        }
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
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