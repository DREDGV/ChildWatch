package ru.example.childwatch

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
 * РЈРЅРёРІРµСЂСЃР°Р»СЊРЅР°СЏ РєР°СЂС‚Р° РґР»СЏ РїРѕРєР°Р·Р° РґРІСѓС… СѓСЃС‚СЂРѕР№СЃС‚РІ:
 * - ChildWatch (СЂРѕРґРёС‚РµР»СЊ): РЇ + Р РµР±РµРЅРѕРє (СЃ СЃРµСЂРІРµСЂР° /api/location/latest/:childId)
 * - ParentWatch (СЂРµР±РµРЅРѕРє): РЇ + Р РѕРґРёС‚РµР»СЊ (СЃ СЃРµСЂРІРµСЂР° /api/location/parent/latest/:parentId)
 * 
 * Intent extras:
 * - MY_ROLE: "parent" РёР»Рё "child"
 * - MY_ID: ID РјРѕРµРіРѕ СѓСЃС‚СЂРѕР№СЃС‚РІР°
 * - OTHER_ID: ID РґСЂСѓРіРѕРіРѕ СѓСЃС‚СЂРѕР№СЃС‚РІР°
 */
class DualLocationMapActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "DualLocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val AUTO_REFRESH_INTERVAL = 30_000L // 30 СЃРµРєСѓРЅРґ
        private const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
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

    private data class CachedLocation(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val speed: Float?
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
                    localPrefs.getString("selected_device_id", null),
                    localPrefs.getString("child_device_id", null),
                    secureSettings.getChildDeviceId(),
                    legacyPrefs.getString("selected_device_id", null),
                    legacyPrefs.getString("child_device_id", null)
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() && it !in excluded }
                    .orEmpty()
            }

            limitedMode = !showAllContacts && otherId.isBlank()

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
        supportActionBar?.title = if (showAllContacts) {
            "рџ“Ќ РљР°СЂС‚Р° РєРѕРЅС‚Р°РєС‚РѕРІ"
        } else {
            when (myRole) {
                ROLE_PARENT -> "рџ“Ќ Р“РґРµ СЂРµР±РµРЅРѕРє?"
                ROLE_CHILD -> "рџ“Ќ Р“РґРµ СЂРѕРґРёС‚РµР»Рё?"
                else -> "рџ“Ќ РљР°СЂС‚Р°"
            }
        }
        if (limitedMode) {
            binding.toolbar.subtitle = "Р РµР¶РёРј РїСЂРѕСЃРјРѕС‚СЂР° вЂ” СЃРІСЏР¶РёС‚Рµ СѓСЃС‚СЂРѕР№СЃС‚РІР° РІ РЅР°СЃС‚СЂРѕР№РєР°С…"
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
            binding.errorText.text = "РќРµ СѓРґР°Р»РѕСЃСЊ РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°С‚СЊ РєР°СЂС‚Сѓ: ${e.message}"
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
    
    private fun setupHistoryButton() {
        binding.historyButton.setOnClickListener {
            showHistoryPeriodDialog()
        }
    }
    
    private fun showHistoryPeriodDialog() {
        val periods = arrayOf("РЎРµРіРѕРґРЅСЏ", "Р’С‡РµСЂР°", "РќРµРґРµР»СЏ", "РњРµСЃСЏС†")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Р’С‹Р±РµСЂРёС‚Рµ РїРµСЂРёРѕРґ")
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
                0 -> { // РЎРµРіРѕРґРЅСЏ
                    Pair(startOfToday, now)
                }
                1 -> { // Р’С‡РµСЂР°
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = startOfToday }
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    val startOfYesterday = cal.timeInMillis
                    val endOfYesterday = startOfToday - 1
                    Pair(startOfYesterday, endOfYesterday)
                }
                2 -> { // РќРµРґРµР»СЏ
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                    Pair(cal.timeInMillis, now)
                }
                3 -> { // РњРµСЃСЏС†
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
                val history = networkClient.getLocationHistory(
                    deviceId = otherId,
                    fromTimestamp = fromTimestamp,
                    toTimestamp = toTimestamp,
                    limit = 1000
                )
                
                if (history.isNullOrEmpty()) {
                    Toast.makeText(
                        this@DualLocationMapActivity,
                        "РќРµС‚ РґР°РЅРЅС‹С… Р·Р° РІС‹Р±СЂР°РЅРЅС‹Р№ РїРµСЂРёРѕРґ",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                
                // РћС‚РѕР±СЂР°Р¶Р°РµРј РёСЃС‚РѕСЂРёСЋ РЅР° РєР°СЂС‚Рµ
                displayLocationHistory(history)
                
                Toast.makeText(
                    this@DualLocationMapActivity,
                    "Р—Р°РіСЂСѓР¶РµРЅРѕ ${history.size} С‚РѕС‡РµРє",
                    Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading location history", e)
                Toast.makeText(
                    this@DualLocationMapActivity,
                    "РћС€РёР±РєР° Р·Р°РіСЂСѓР·РєРё РёСЃС‚РѕСЂРёРё: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun displayLocationHistory(history: List<ru.example.childwatch.network.ParentLocationData>) {
        if (history.isEmpty()) return
        
        // РЈРґР°Р»СЏРµРј СЃС‚Р°СЂСѓСЋ Р»РёРЅРёСЋ Рё РјР°СЂРєРµСЂС‹ РёСЃС‚РѕСЂРёРё, РµСЃР»Рё РµСЃС‚СЊ
        historyLine?.let { mapView.overlays.remove(it) }
        historyStartMarker?.let { mapView.overlays.remove(it) }
        historyEndMarker?.let { mapView.overlays.remove(it) }
        historyLine = null
        historyStartMarker = null
        historyEndMarker = null
        
        // РЎРѕР·РґР°С‘Рј Polyline РґР»СЏ РёСЃС‚РѕСЂРёРё
        historyLine = Polyline(mapView).apply {
            id = "history_line"
            
            // РЎРѕСЂС‚РёСЂСѓРµРј РїРѕ РІСЂРµРјРµРЅРё (РѕС‚ СЃС‚Р°СЂС‹С… Рє РЅРѕРІС‹Рј)
            val sortedHistory = history.sortedBy { it.timestamp }
            
            // Р”РѕР±Р°РІР»СЏРµРј С‚РѕС‡РєРё
            val points = sortedHistory.map { GeoPoint(it.latitude, it.longitude) }
            setPoints(points)
            
            // РЎС‚РёР»СЊ Р»РёРЅРёРё СЃ РіСЂР°РґРёРµРЅС‚РѕРј (РѕС‚ РїСЂРѕР·СЂР°С‡РЅРѕРіРѕ Рє СЏСЂРєРѕРјСѓ)
            outlinePaint.color = Color.parseColor("#4285F4") // Google Blue
            outlinePaint.strokeWidth = 8f
            outlinePaint.alpha = 200
        }
        
        mapView.overlays.add(0, historyLine) // Р”РѕР±Р°РІР»СЏРµРј РїРѕРґ РјР°СЂРєРµСЂС‹
        
        // Р”РѕР±Р°РІР»СЏРµРј РјР°СЂРєРµСЂС‹ РЅР°С‡Р°Р»Р° Рё РєРѕРЅС†Р° РјР°СЂС€СЂСѓС‚Р°
        val firstPoint = history.minByOrNull { it.timestamp }
        val lastPoint = history.maxByOrNull { it.timestamp }
        
        if (firstPoint != null && lastPoint != null && firstPoint != lastPoint) {
            // РњР°СЂРєРµСЂ РЅР°С‡Р°Р»Р° (Р·РµР»С‘РЅС‹Р№)
            historyStartMarker = Marker(mapView).apply {
                position = GeoPoint(firstPoint.latitude, firstPoint.longitude)
                title = "РќР°С‡Р°Р»Рѕ РјР°СЂС€СЂСѓС‚Р°"
                snippet = formatTimestamp(firstPoint.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@DualLocationMapActivity, R.drawable.ic_child_marker)
            }
            
            // РњР°СЂРєРµСЂ РєРѕРЅС†Р° (РєСЂР°СЃРЅС‹Р№)
            historyEndMarker = Marker(mapView).apply {
                position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                title = "РљРѕРЅРµС† РјР°СЂС€СЂСѓС‚Р°"
                snippet = formatTimestamp(lastPoint.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@DualLocationMapActivity, R.drawable.ic_parent_marker)
            }
            
            mapView.overlays.add(historyStartMarker)
            mapView.overlays.add(historyEndMarker)
        }
        
        mapView.invalidate()
        
        // Р¦РµРЅС‚СЂРёСЂСѓРµРј РєР°СЂС‚Сѓ РЅР° РёСЃС‚РѕСЂРёРё
        if (history.isNotEmpty()) {
            safeZoomToBoundingBox(
                history.map { GeoPoint(it.latitude, it.longitude) },
                history.lastOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
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

    private fun buildSnippet(label: String, timestamp: Long?): String {
        val normalized = normalizeTimestampMillis(timestamp) ?: return label
        val timeInfo = formatTimestamp(normalized)
        return if (isStale(normalized)) {
            "$label - $timeInfo (\u0443\u0441\u0442\u0430\u0440.)"
        } else {
            "$label - $timeInfo"
        }
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
                Toast.makeText(this, "Р›РѕРєР°С†РёСЏ СѓСЃС‚СЂРѕР№СЃС‚РІР° РЅРµРґРѕСЃС‚СѓРїРЅР°: РЅРµС‚ СЂР°Р·СЂРµС€РµРЅРёСЏ", Toast.LENGTH_LONG).show()
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
                    val cachedMy = loadCachedLocation(cacheKeyMy())
                    val myLocation = if (hasLocationPermission()) {
                        withContext(Dispatchers.IO) { locationManager.getCurrentLocation() }
                    } else {
                        null
                    }

                    val myLat = myLocation?.latitude ?: cachedMy?.latitude
                    val myLon = myLocation?.longitude ?: cachedMy?.longitude
                    val myTs = myLocation?.time ?: cachedMy?.timestamp

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

                    if (myLat != null && myLon != null) {
                        val myTitle = when (myRole) {
                            ROLE_PARENT -> getString(R.string.my_location) + " (Родитель)"
                            ROLE_CHILD -> getString(R.string.my_location) + " (Ребенок)"
                            else -> getString(R.string.my_location)
                        }
                        val myIcon = when (myRole) {
                            ROLE_PARENT -> R.drawable.ic_parent_marker
                            ROLE_CHILD -> R.drawable.ic_child_marker
                            else -> R.drawable.ic_parent_marker
                        }
                        displaySingleLocation(myLat, myLon, myTitle, myIcon, myTs)
                    }

                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = getString(R.string.child_location_unavailable)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading locations in limited mode", e)
                    binding.loadingIndicator.visibility = View.GONE
                    binding.errorCard.visibility = View.VISIBLE
                    binding.errorText.text = getString(R.string.location_unavailable) + ": ${e.message}"
                }
            }
            return
        }
        
        loadLocationsJob = lifecycleScope.launch {
            try {
                val cachedMy = loadCachedLocation(cacheKeyMy())
                val cachedOther = loadCachedLocation(cacheKeyOther())

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
                
                // РџРѕР»СѓС‡РёС‚СЊ Р»РѕРєР°С†РёСЋ РґСЂСѓРіРѕРіРѕ СѓСЃС‚СЂРѕР№СЃС‚РІР° СЃ СЃРµСЂРІРµСЂР°
                // РСЃРїРѕР»СЊР·СѓРµРј РѕРґРёРЅ endpoint РґР»СЏ РІСЃРµС… СѓСЃС‚СЂРѕР№СЃС‚РІ РґР»СЏ СЃРѕРІРјРµСЃС‚РёРјРѕСЃС‚Рё
                val otherLocation = withContext(Dispatchers.IO) {
                    if (myRole == ROLE_CHILD) {
                        val cachedParent = parentLocationRepository.getLatestLocation(otherId)
                        val fromServer = networkClient.getLatestParentLocation(otherId)
                        fromServer ?: cachedParent?.toNetworkModel()
                    } else {
                        networkClient.getLatestLocation(otherId)
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

                val myLatFinal = myLatitude ?: cachedMy?.latitude
                val myLonFinal = myLongitude ?: cachedMy?.longitude
                val myTsFinal = myLocation?.time ?: cachedMy?.timestamp
                val otherFinal = otherLocation ?: cachedOther?.toParentLocationData(otherId)
                
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

                    val errorMsg = when (myRole) {
                        ROLE_PARENT -> "Р›РѕРєР°С†РёСЏ СЂРµР±РµРЅРєР° РЅРµРґРѕСЃС‚СѓРїРЅР°.\nРџСЂРѕРІРµСЂСЊС‚Рµ С‡С‚Рѕ РґРµС‚СЃРєРѕРµ СѓСЃС‚СЂРѕР№СЃС‚РІРѕ РїРѕРґРєР»СЋС‡РµРЅРѕ Рє РёРЅС‚РµСЂРЅРµС‚Сѓ."
                        ROLE_CHILD -> "Р›РѕРєР°С†РёСЏ СЂРѕРґРёС‚РµР»СЏ РЅРµРґРѕСЃС‚СѓРїРЅР°.\nРџРѕРїСЂРѕСЃРёС‚Рµ СЂРѕРґРёС‚РµР»СЏ РІРєР»СЋС‡РёС‚СЊ 'Р”РµР»РёС‚СЊСЃСЏ РјРѕРµР№ Р»РѕРєР°С†РёРµР№' РІ РЅР°СЃС‚СЂРѕР№РєР°С…."
                        else -> "Р›РѕРєР°С†РёСЏ РЅРµРґРѕСЃС‚СѓРїРЅР°"
                    }
                    binding.errorText.text = errorMsg
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = "РћС€РёР±РєР° Р·Р°РіСЂСѓР·РєРё Р»РѕРєР°С†РёРё: ${e.message}"
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
            binding.errorText.text = "РџРѕР»СѓС‡РµРЅС‹ РЅРµРєРѕСЂСЂРµРєС‚РЅС‹Рµ РєРѕРѕСЂРґРёРЅР°С‚С‹. РћР±РЅРѕРІРёС‚Рµ РєР°СЂС‚Сѓ."
            return
        }

        // РћС‡РёСЃС‚РёС‚СЊ РїСЂРµРґС‹РґСѓС‰РёРµ РјР°СЂРєРµСЂС‹
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        
        // РЎРѕР·РґР°С‚СЊ РјРѕР№ РјР°СЂРєРµСЂ (Р·РµР»РµРЅС‹Р№ РґР»СЏ СЂРѕРґРёС‚РµР»СЏ, СЃРёРЅРёР№ РґР»СЏ СЂРµР±РµРЅРєР°)
        val myMarkerIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_parent_marker
            ROLE_CHILD -> R.drawable.ic_child_marker
            else -> R.drawable.ic_parent_marker
        }
        
        val myMarkerTitle = when (myRole) {
            ROLE_PARENT -> "РЇ (Р РѕРґРёС‚РµР»СЊ)"
            ROLE_CHILD -> "РЇ (Р РµР±РµРЅРѕРє)"
            else -> "РЇ"
        }
        
        myMarker = Marker(mapView).apply {
            position = GeoPoint(myLat, myLon)
            title = myMarkerTitle
            snippet = buildSnippet("РњРѕСЏ Р»РѕРєР°С†РёСЏ", myTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, myMarkerIcon)
        }
        mapView.overlays.add(myMarker)
        
        // РЎРѕР·РґР°С‚СЊ РјР°СЂРєРµСЂ РґСЂСѓРіРѕРіРѕ СѓСЃС‚СЂРѕР№СЃС‚РІР°
        val otherMarkerIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_child_marker
            ROLE_CHILD -> R.drawable.ic_parent_marker
            else -> R.drawable.ic_child_marker
        }
        
        val otherMarkerTitle = when (myRole) {
            ROLE_PARENT -> "Р РµР±РµРЅРѕРє"
            ROLE_CHILD -> "Р РѕРґРёС‚РµР»СЊ"
            else -> "Р”СЂСѓРіРѕРµ СѓСЃС‚СЂРѕР№СЃС‚РІРѕ"
        }
        
        otherMarker = Marker(mapView).apply {
            position = GeoPoint(otherLat, otherLon)
            title = otherMarkerTitle
            snippet = buildSnippet("РўРµРєСѓС‰Р°СЏ Р»РѕРєР°С†РёСЏ", otherTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, otherMarkerIcon)
        }
        mapView.overlays.add(otherMarker)
        
        // РЎРѕР·РґР°С‚СЊ Р»РёРЅРёСЋ РјРµР¶РґСѓ РјР°СЂРєРµСЂР°РјРё
        connectionLine = Polyline().apply {
            addPoint(GeoPoint(myLat, myLon))
            addPoint(GeoPoint(otherLat, otherLon))
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(connectionLine)
        
        // Р¦РµРЅС‚СЂРёСЂРѕРІР°С‚СЊ РєР°СЂС‚Сѓ
        if (autoFitEnabled) {

            centerMapOnBothLocations(myLat, myLon, otherLat, otherLon)

        }
        
        // Р Р°СЃСЃС‡РёС‚Р°С‚СЊ Рё РїРѕРєР°Р·Р°С‚СЊ СЂР°СЃСЃС‚РѕСЏРЅРёРµ Рё ETA
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
        if (!isMapReady || !::mapView.isInitialized || isFinishing || isDestroyed) return
        if (!isValidCoordinate(lat, lon)) {
            Log.w(TAG, "displaySingleLocation skipped due to invalid coordinates: ($lat,$lon)")
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = "РџРѕР»СѓС‡РµРЅС‹ РЅРµРєРѕСЂСЂРµРєС‚РЅС‹Рµ РєРѕРѕСЂРґРёРЅР°С‚С‹. РћР±РЅРѕРІРёС‚Рµ РєР°СЂС‚Сѓ."
            return
        }

        // РћС‡РёСЃС‚РёС‚СЊ РїСЂРµРґС‹РґСѓС‰РёРµ РјР°СЂРєРµСЂС‹
        myMarker?.let { mapView.overlays.remove(it) }
        otherMarker?.let { mapView.overlays.remove(it) }
        connectionLine?.let { mapView.overlays.remove(it) }
        
        myMarker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            this.title = title
            snippet = buildSnippet("РўРµРєСѓС‰Р°СЏ Р»РѕРєР°С†РёСЏ", timestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@DualLocationMapActivity, iconRes)
        }
        mapView.overlays.add(myMarker)
        
        // Р¦РµРЅС‚СЂРёСЂРѕРІР°С‚СЊ РЅР° С‚РѕС‡РєРµ
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
            ROLE_PARENT -> "РЇ (Р РѕРґРёС‚РµР»СЊ)"
            ROLE_CHILD -> "РЇ (Р РµР±РµРЅРѕРє)"
            else -> "РЇ"
        }

        val myIcon = when (myRole) {
            ROLE_PARENT -> R.drawable.ic_parent_marker
            ROLE_CHILD -> R.drawable.ic_child_marker
            else -> R.drawable.ic_parent_marker
        }

        val otherTitle = when (myRole) {
            ROLE_PARENT -> "Р РµР±РµРЅРѕРє"
            ROLE_CHILD -> "Р РѕРґРёС‚РµР»СЊ"
            else -> "Р”СЂСѓРіРѕРµ СѓСЃС‚СЂРѕР№СЃС‚РІРѕ"
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
                    binding.errorText.text = "РќРµС‚ РєРѕРЅС‚Р°РєС‚РѕРІ РґР»СЏ РѕС‚РѕР±СЂР°Р¶РµРЅРёСЏ РЅР° РєР°СЂС‚Рµ"
                    return@launch
                }

                val cachedPoints = mutableListOf<ContactPoint>()
                for (contact in eligible) {
                    val cached = loadCachedLocation(cacheKeyContact(contact.deviceId))
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
                                networkClient.getLatestLocation(contact.deviceId)
                            } else {
                                networkClient.getLatestParentLocation(contact.deviceId)
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
                    binding.errorText.text = "Р›РѕРєР°С†РёРё РєРѕРЅС‚Р°РєС‚РѕРІ РЅРµРґРѕСЃС‚СѓРїРЅС‹"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading contacts locations", e)
                binding.loadingIndicator.visibility = View.GONE
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = "РћС€РёР±РєР° Р·Р°РіСЂСѓР·РєРё РєР°СЂС‚С‹: ${e.message}"
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
                    title = "Я"
                    snippet = buildSnippet("Моя локация", null)
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
                    snippet = buildSnippet("Локация", location.timestamp)
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
            binding.errorText.text = "Ошибка отрисовки карты: ${e.message}"
        }
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
        val earthRadius = 6371000.0 // РјРµС‚СЂС‹
        
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

}






