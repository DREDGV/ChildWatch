package ru.example.childwatch

import android.Manifest
import android.content.Context
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
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.repository.ParentLocationRepository
import ru.example.childwatch.databinding.ActivityParentLocationMapBinding
import ru.example.childwatch.location.LocationManager
import ru.example.childwatch.network.NetworkClient
import kotlin.math.*

/**
 * Parent Location Map Activity
 * 
 * Показывает карту с локацией родителя и ребенка
 * - Маркеры для обоих устройств
 * - Линия между ними с расстоянием
 * - ETA расчет если родитель движется
 * - Auto-refresh каждые 30 секунд
 */
class ParentLocationMapActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ParentLocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val AUTO_REFRESH_INTERVAL = 30_000L // 30 секунд
    }
    
    private lateinit var binding: ActivityParentLocationMapBinding
    private lateinit var mapView: MapView
    private lateinit var prefs: SharedPreferences
    private lateinit var database: ChildWatchDatabase
    private lateinit var parentLocationRepository: ParentLocationRepository
    private lateinit var locationManager: LocationManager
    private lateinit var networkClient: NetworkClient
    
    private var parentMarker: Marker? = null
    private var childMarker: Marker? = null
    private var connectionLine: Polyline? = null
    
    private var autoRefreshJob: Job? = null
    private var isMapReady = false
    
    private var childLatitude: Double? = null
    private var childLongitude: Double? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure OSMdroid
        Configuration.getInstance().load(this, getPreferences(MODE_PRIVATE))
        
        binding = ActivityParentLocationMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        initializeComponents()
        setupMapView()
        checkPermissionsAndLoad()
        setupRefreshButton()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Где родители?"
        }
    }
    
    private fun initializeComponents() {
        prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        database = ChildWatchDatabase.getInstance(this)
        parentLocationRepository = ParentLocationRepository(database.parentLocationDao())
        locationManager = LocationManager(this)
        networkClient = NetworkClient(this)
    }
    
    private fun setupMapView() {
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
                // Получить локацию ребенка
                val childLocation = withContext(Dispatchers.IO) {
                    locationManager.getCurrentLocation()
                }
                
                if (childLocation != null) {
                    childLatitude = childLocation.latitude
                    childLongitude = childLocation.longitude
                } else {
                    Log.w(TAG, "Child location not available")
                }
                
                // Получить локацию родителя
                val parentId = prefs.getString("parent_id", "unknown") ?: "unknown"
                val parentLocation = withContext(Dispatchers.IO) {
                    parentLocationRepository.getLatestLocation(parentId)
                }
                
                if (parentLocation != null && childLatitude != null && childLongitude != null) {
                    // Отобразить обе локации
                    displayLocations(
                        parentLat = parentLocation.latitude,
                        parentLon = parentLocation.longitude,
                        parentSpeed = parentLocation.speed,
                        childLat = childLatitude!!,
                        childLon = childLongitude!!
                    )
                    
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorText.visibility = View.GONE
                    
                } else {
                    // Локация родителя недоступна
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = "Локация родителя недоступна.\nПопросите родителя включить 'Делиться моей локацией' в настройках."
                    
                    // Показать только локацию ребенка если доступна
                    if (childLatitude != null && childLongitude != null) {
                        displayChildLocationOnly(childLatitude!!, childLongitude!!)
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
        parentLat: Double,
        parentLon: Double,
        parentSpeed: Float?,
        childLat: Double,
        childLon: Double
    ) {
        // Очистить предыдущие маркеры
        parentMarker?.let { mapView.overlays.remove(it) }
        childMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        
        // Создать маркер родителя (зеленый)
        parentMarker = Marker(mapView).apply {
            position = GeoPoint(parentLat, parentLon)
            title = "Родитель"
            snippet = "Текущая локация"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@ParentLocationMapActivity, R.drawable.ic_parent_marker)
        }
        mapView.overlays.add(parentMarker)
        
        // Создать маркер ребенка (синий)
        childMarker = Marker(mapView).apply {
            position = GeoPoint(childLat, childLon)
            title = "Я"
            snippet = "Моя локация"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@ParentLocationMapActivity, R.drawable.ic_child_marker)
        }
        mapView.overlays.add(childMarker)
        
        // Создать линию между маркерами
        connectionLine = Polyline().apply {
            addPoint(GeoPoint(parentLat, parentLon))
            addPoint(GeoPoint(childLat, childLon))
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(connectionLine)
        
        // Центрировать карту
        centerMapOnBothLocations(parentLat, parentLon, childLat, childLon)
        
        // Рассчитать и показать расстояние и ETA
        val etaInfo = parentLocationRepository.calculateETA(
            parentLat, parentLon,
            childLat, childLon,
            parentSpeed
        )
        
        binding.distanceText.text = etaInfo.formattedDistance
        binding.etaText.text = etaInfo.formattedETA
        binding.statsCard.visibility = View.VISIBLE
        
        mapView.invalidate()
    }
    
    private fun displayChildLocationOnly(childLat: Double, childLon: Double) {
        // Очистить предыдущие маркеры
        childMarker?.let { mapView.overlays.remove(it) }
        
        // Создать только маркер ребенка
        childMarker = Marker(mapView).apply {
            position = GeoPoint(childLat, childLon)
            title = "Я"
            snippet = "Моя локация"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@ParentLocationMapActivity, R.drawable.ic_child_marker)
        }
        mapView.overlays.add(childMarker)
        
        // Центрировать на ребенке
        mapView.controller.setCenter(GeoPoint(childLat, childLon))
        mapView.controller.setZoom(15.0)
        
        binding.statsCard.visibility = View.GONE
        
        mapView.invalidate()
    }
    
    private fun centerMapOnBothLocations(
        parentLat: Double,
        parentLon: Double,
        childLat: Double,
        childLon: Double
    ) {
        // Рассчитать центр между двумя точками
        val centerLat = (parentLat + childLat) / 2
        val centerLon = (parentLon + childLon) / 2
        
        // Рассчитать расстояние для определения zoom уровня
        val distance = calculateDistance(parentLat, parentLon, childLat, childLon)
        
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
}
