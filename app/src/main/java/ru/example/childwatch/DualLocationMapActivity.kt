package ru.example.childwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import ru.example.childwatch.attention.ParentAttentionSignalLauncher
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.profile.ParentFamilyDirectoryRepository
import ru.example.childwatch.profile.ParentFamilyDirectorySource
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.ParentLocation
import ru.example.childwatch.database.entity.Geofence
import ru.example.childwatch.database.repository.ParentLocationRepository
import ru.example.childwatch.databinding.ActivityDualLocationMapBinding
import ru.example.childwatch.location.LocationManager
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.ParentLocationData
import ru.example.childwatch.network.FamilyLiveLocation
import ru.example.childwatch.designsystem.MapMemberStrip
import ru.example.childwatch.designsystem.MapAvatarIcon
import ru.example.childwatch.designsystem.MapRouteSegments
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.contacts.ContactFeatures
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.contacts.ContactRoles
import ru.example.childwatch.utils.SecureSettingsManager
import java.io.File
import java.util.Locale
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
        private const val LIVE_MODE_REFRESH_INTERVAL = 10_000L
        private const val LIVE_MODE_DURATION_MS = 10 * 60 * 1000L
        private const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
        private const val HISTORY_LIMIT = 1000
        private const val STOP_RADIUS_METERS = 80f
        private const val STOP_MIN_DURATION_MS = 10 * 60 * 1000L
        private const val MOVING_SPEED_THRESHOLD_MPS = 1.4f
        private const val MAP_CACHE_MY = "map_cache_my"
        private const val MAP_CACHE_OTHER = "map_cache_other"
        private val PLACE_RADIUS_OPTIONS = intArrayOf(150, 250, 500, 1000)
        
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
    private var myAccuracyOverlay: Polygon? = null
    private var otherAccuracyOverlay: Polygon? = null
    private var connectionLine: Polyline? = null
    private val contactMarkers = mutableMapOf<String, Marker>()
    private val contactAccuracyOverlays = mutableMapOf<String, Polygon>()
    private var liveFamilyId: String? = null
    private var liveSelfMemberId: String? = null
    private var familyLivePoints: List<GeoPoint> = emptyList()
    private var currentFamilyLocations: List<FamilyLiveLocation> = emptyList()
    private var selectedFamilyMemberId: String? = null
    private var selectedPlacesDeviceId: String? = null
    private val familyMotionDevices = mutableMapOf<String, String>()
    private val familyTrailFixes = mutableMapOf<String, MutableList<MapRouteSegments.Fix>>()
    private val familyTrailLines = mutableListOf<Polyline>()
    private var familyTrailLoadJob: kotlinx.coroutines.Job? = null
    private val familyTrailRequestedAt = mutableMapOf<String, Long>()
    private val repositionFamilyMarkers = Runnable {
        if (isMapReady && showAllContacts && currentFamilyLocations.isNotEmpty()) {
            placeFamilyMarkers(currentFamilyLocations)
            mapView.invalidate()
        }
    }
    private var familyInitiallyCentered = false
    private val familyMarkers = mutableMapOf<String, Marker>()
    private val familyAccuracyOverlays = mutableMapOf<String, Polygon>()
    private val historyLines = mutableListOf<Polyline>()
    private var historyStartMarker: Marker? = null
    private var historyEndMarker: Marker? = null
    private val placeOverlays = mutableMapOf<Long, Pair<Polygon, Marker>>()
    private var placesForCurrentChild: List<Geofence> = emptyList()
    private var liveModeUntilMs: Long = 0L
    private var lastLinkedSourceRes: Int? = null
    
    private var myLatitude: Double? = null
    private var myLongitude: Double? = null
    private var myLocationAccuracy: Float? = null
    
    private var isMapReady = false
    private var autoRefreshJob: Job? = null
    private var loadLocationsJob: Job? = null
    private var autoFitEnabled = true

    private fun pickupScope(): String {
        val resolver = ru.example.childwatch.profile.ParentEffectiveContextResolver(this)
        val family = resolver.resolveFamilyId()?.takeIf { it.isNotBlank() } ?: return ""
        val member = resolver.resolveSelfMemberId()?.takeIf { it.isNotBlank() } ?: return ""
        val server = resolver.resolveServerUrl().takeIf { it.isNotBlank() } ?: return ""
        val own = resolver.resolveOwnParentId().takeIf { it.isNotBlank() } ?: return ""
        return org.json.JSONArray(listOf(server, family, member, own)).toString()
    }
    private val pickupController by lazy {
        ru.example.childwatch.designsystem.FamilyPickupController(this,
            object : ru.example.childwatch.designsystem.FamilyPickupController.Host {
                override fun scope(): String = pickupScope()
                override fun meetingPoint(): DoubleArray? = if (::mapView.isInitialized)
                    doubleArrayOf(mapView.mapCenter.latitude, mapView.mapCenter.longitude).also {
                        autoFitEnabled = false; updateAutoFitUi()
                    } else null
                override fun focusPoint(latitude: Double, longitude: Double) {
                    if (!::mapView.isInitialized) return
                    leaveHistory()
                    autoFitEnabled = false; updateAutoFitUi()
                    pickupPointMarker?.let { mapView.overlays.remove(it) }
                    pickupPointMarker = Marker(mapView).apply {
                        position = GeoPoint(latitude, longitude)
                        title = getString(R.string.pickup_meeting_point)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { marker, _ ->
                            mapView.overlays.remove(marker); pickupPointMarker = null; mapView.invalidate(); true
                        }
                    }.also { mapView.overlays.add(it) }
                    pickupMarkerScope = pickupScope()
                    mapView.controller.animateTo(GeoPoint(latitude, longitude))
                    mapView.invalidate()
                    Toast.makeText(this@DualLocationMapActivity, R.string.pickup_marker_hint, Toast.LENGTH_LONG).show()
                }
                override fun badge(active: Int) {
                    if (pickupMarkerScope != pickupScope() && ::mapView.isInitialized) {
                        pickupPointMarker?.let { mapView.overlays.remove(it) }; pickupPointMarker = null; mapView.invalidate()
                    }
                    val item = binding.toolbar.menu.findItem(93242) ?: return
                    item.title = if (active > 0) getString(R.string.pickup_badge, active) else getString(R.string.pickup_title)
                    item.isEnabled = true
                }
                override fun request(method: String, suffix: String, body: org.json.JSONObject?,
                    callback: ru.example.childwatch.designsystem.FamilyPickupController.Callback) {
                    val expected = pickupScope()
                    lifecycleScope.launch {
                        try {
                            if (expected.isBlank() || expected != pickupScope()) throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
                            val parts = org.json.JSONArray(expected)
                            val result = networkClient.pickupRequest(parts.getString(1), method, suffix, body, expectedScope = expected)
                            if (expected != pickupScope()) throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
                            result.optJSONObject("actor")?.let {
                                if (it.optString("memberId") != parts.getString(2)) throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
                            }
                            callback.complete(result, null)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            callback.complete(null, "PICKUP_CANCELLED"); throw cancelled
                        } catch (failure: Exception) {
                            callback.complete(null, failure.message ?: "PICKUP_UNAVAILABLE")
                        }
                    }
                }
            })
    }
    private var pickupPointMarker: Marker? = null
    private var pickupMarkerScope: String = ""
    private fun setupPickupEntry() {
        if (binding.toolbar.menu.findItem(93242) == null) {
            binding.toolbar.menu.add(0, 93242, 0, getString(R.string.pickup_title)).apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener { pickupController.show(); true }
            }
        }
        pickupController.resume()
        if (intent.getBooleanExtra("open_pickups", false)) {
            intent.removeExtra("open_pickups")
            if (intent.getStringExtra("pickup_scope") == pickupScope()) pickupController.show()
        }
    }
    private val familyPlacesController by lazy {
        ru.example.childwatch.location.FamilyPlacesController(this, networkClient,
            target = {
                val deviceId = selectedPlacesDeviceId ?: selectedFamilyMemberId?.let { id -> currentFamilyLocations.firstOrNull { it.memberId == id }?.deviceId }
                    ?: linkedPersonDeviceId()
                val member = familyPresentationByDevice[deviceId]
                val family = liveFamilyId ?: ru.example.childwatch.profile.ParentEffectiveContextResolver(this).resolveFamilyId()
                if (family.isNullOrBlank() || member == null) null else
                    ru.example.childwatch.location.FamilyPlacesController.Target(family, member.memberId, member.displayName)
            },
            center = { mapView.mapCenter.let { it.latitude to it.longitude } },
            rendered = { places -> renderServerPlaces(places) })
    }
    private val serverPlaceOverlays = mutableListOf<Polygon>()
    private val mapOptions by lazy { ru.example.childwatch.designsystem.FamilyMapOptions(this) }
    private val markerMotion = ru.example.childwatch.designsystem.MapMarkerMotion()
    private val mapFollow = ru.example.childwatch.designsystem.MapFollowSelection()
    private var followTouchX = 0f
    private var followTouchY = 0f

    private val familyTrailJobs = mutableMapOf<String, Job>()
    private val familyTrailAllowed = mutableSetOf<String>()
    private var ownDistancePoint: ru.example.childwatch.designsystem.FamilyDistance.Point? = null
    private var lastMyPoint: GeoPoint? = null
    private var lastOtherPoint: GeoPoint? = null
    private var resolvedParentId: String = ""
    private var resolvedOtherId: String = ""
    private var currentDiagnosticReason: MapDiagnosticReason = MapDiagnosticReason.NONE
    private var dependenciesReady = false
    private val contextProvider by lazy { ParentEffectiveContextProvider.get(this) }
    private val mapNamespace by lazy { contextProvider.featureContext("map")?.storageNamespace ?: "legacy" }
    private val participantNameResolver by lazy { ParentParticipantNameResolver(this) }
    private val familyDirectoryRepository by lazy { ParentFamilyDirectoryRepository(this) }
    private var familyPresentationByDevice: Map<String, MapPersonPresentation> = emptyMap()
    private var isStatsCardCollapsed = true
    private var isPersonDetailsExpanded = false

    private data class MapPersonPresentation(
        val memberId: String,
        val displayName: String,
        val avatarValue: String?
    )

    private data class CachedLocation(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val speed: Float?,
        val accuracy: Float?
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
        val iconId: Int,
        val accuracy: Float,
        val battery: Int?,
        val avatarValue: String?
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmdroidEarly()

        try {
            binding = ActivityDualLocationMapBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.root.viewTreeObserver.addOnGlobalLayoutListener { alignBottomMapControls() }
            binding.distanceText.visibility = if (mapOptions.distances()) View.VISIBLE else View.GONE
            binding.appBarLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                positionMapControlsBelowHeader()
            }

            // Get role and IDs from intent
            myRole = intent.getStringExtra(EXTRA_MY_ROLE) ?: ROLE_PARENT
            myId = intent.getStringExtra(EXTRA_MY_ID)?.trim().orEmpty()
            otherId = intent.getStringExtra(EXTRA_OTHER_ID)?.trim().orEmpty()
            showAllContacts = intent.getBooleanExtra(EXTRA_SHOW_ALL, false)

            val canonicalContext = contextProvider.featureContext("map")
            if (myRole == ROLE_PARENT) {
                if (myId.isBlank()) {
                    canonicalContext?.selfDeviceId?.takeIf(String::isNotBlank)?.let { myId = it }
                }
                if (otherId.isBlank()) {
                    canonicalContext?.targetDeviceId?.takeIf(String::isNotBlank)?.let { otherId = it }
                }
            }
            val effectiveContext = ParentEffectiveContextResolver(this).resolve()
            val localPrefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val secureSettings = SecureSettingsManager(this)
            if (myId.isBlank()) {
                myId = listOf(
                    effectiveContext.ownParentDeviceId,
                    secureSettings.getDeviceId(),
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

                val stableChildId = listOf(
                    effectiveContext.linkedChildDeviceId,
                    localPrefs.getString("child_device_id", null),
                    secureSettings.getChildDeviceId(),
                    legacyPrefs.getString("child_device_id", null)
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() && it !in excluded }

                otherId = stableChildId ?: listOf(
                    localPrefs.getString("selected_device_id", null),
                    legacyPrefs.getString("selected_device_id", null)
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() && it !in excluded }
                    .orEmpty()

                if (stableChildId != null) {
                    localPrefs.edit()
                        .putString("child_device_id", stableChildId)
                        .putString("selected_device_id", stableChildId)
                        .apply()
                }
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
            setupLiveModeButton()
            setupCenterButtons()
            setupHistoryButton()
            setupTimelineButton()
            setupAttentionSignalButton()
            setupPersonCard()
            loadFamilyPresentation()
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    initializeDependenciesAndLoad()
                }
            }
        } catch (e: Exception) {
            handleStartupFailure(e)
        }
    }

    private fun initializeDependenciesAndLoad() {
        if (dependenciesReady || isFinishing || isDestroyed) return
        try {
            prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            database = ChildWatchDatabase.getInstance(this)
            locationManager = LocationManager(this)
            networkClient = NetworkClient(this)
            parentLocationRepository = ParentLocationRepository(database.parentLocationDao())
            dependenciesReady = true

            setupPlacesButton()
            observePlaces()
            checkPermissionsAndLoad()
        } catch (t: Throwable) {
            handleStartupFailure(t)
        }
    }

    private fun loadFamilyPresentation() {
        lifecycleScope.launch {
            val result = runCatching { familyDirectoryRepository.load() }.getOrNull()
                ?: return@launch
            val directory = result.directory
            liveFamilyId = directory.family.id.takeIf {
                result.source == ParentFamilyDirectorySource.SERVER
            }
            liveSelfMemberId = directory.selfMemberId
            familyPresentationByDevice = buildMap {
                directory.people.forEach { person ->
                    person.activeDevices.forEach { device ->
                        put(
                            device.deviceId,
                            MapPersonPresentation(
                                memberId = person.member.id,
                                displayName = person.member.displayName,
                                avatarValue = person.member.avatarKey
                            )
                        )
                    }
                }
            }
            // Decode the pictures before the main thread has to draw them. Cropping
            // a preset needs the whole sheet in memory, and doing that while the
            // first marker was drawn cost about a second of skipped frames.
            withContext(Dispatchers.IO) {
                FamilyAvatarRenderer.warmUp(
                    this@DualLocationMapActivity,
                    directory.people.map { it.member.avatarKey }
                )
                preloadMapPhotos(directory.people.map { it.member.avatarKey })
            }
            bindSelectedPersonIdentity()
            if (otherMarker != null && dependenciesReady) loadLocations()
        }
    }

    private fun bindSelectedPersonIdentity(deviceId: String = linkedPersonDeviceId()) {
        val presentation = familyPresentationByDevice[deviceId]
        val fallbackName = if (myRole == ROLE_PARENT) {
            participantNameResolver.resolveFocusedChildDisplayName(deviceId)
        } else {
            otherMarkerTitle()
        }
        binding.mapPersonName.text = presentation?.displayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: fallbackName
        FamilyAvatarRenderer.bind(binding.mapPersonAvatar, presentation?.avatarValue)
    }

    private fun linkedPersonDeviceId(): String = resolvedOtherId.trim().ifBlank { otherId.trim() }

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
    
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        mapOptions.install(binding.toolbar) {
            stopMapFollow()
            markerMotion.clear()
            binding.distanceText.visibility = if (mapOptions.distances()) View.VISIBLE else View.GONE
            if (!mapOptions.trails() && !mapOptions.speeds()) familyTrailJobs.values.forEach { it.cancel() }
            renderFamilyMotion()
            drawSelectedFamilyTrail()
            loadLocations()
        }
        binding.toolbar.menu.removeItem(93242)
        binding.toolbar.menu.add(0, 93242, 0, ru.example.childwatch.designsystem.R.string.cw_map_family)
            .setOnMenuItemClickListener { showFamilyOverview(); true }
        setupPlacesButton()
        return true
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
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

    private fun setupPersonCard() {
        binding.collapseStatsButton.setOnClickListener { collapseStatsCard() }
        binding.statsCard.setOnClickListener {
            isPersonDetailsExpanded = !isPersonDetailsExpanded
            binding.mapPersonDetails.visibility = if (isPersonDetailsExpanded) View.VISIBLE else View.GONE
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (historyPanel?.isVisible == true) { historyPanel?.hide(); return }
                if (isViewingHistory) { leaveHistory(); return }
                val popupOpen = ::mapView.isInitialized && mapView.overlays
                    .filterIsInstance<Marker>().any { it.isInfoWindowOpen }
                if (binding.statsCard.visibility == View.VISIBLE || popupOpen) {
                    collapseStatsCard()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun alignBottomMapControls() {
        // Details and bottom actions must never occupy the same touch area.
        // A vertical stack cannot fit between the header and actions in landscape.
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val orientation = if (landscape) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL
        if (binding.centerButtonsContainer.orientation != orientation) binding.centerButtonsContainer.orientation = orientation
        val centerParams = binding.centerButtonsContainer.layoutParams as? android.widget.LinearLayout.LayoutParams
        val centerGravity = if (landscape) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.END
        val centerWidth = if (landscape) android.view.ViewGroup.LayoutParams.MATCH_PARENT else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (centerParams != null && (centerParams.gravity != centerGravity || centerParams.width != centerWidth)) {
            centerParams.gravity = centerGravity
            centerParams.width = centerWidth
            binding.centerButtonsContainer.layoutParams = centerParams
        }
        val gap = (12f * resources.displayMetrics.density).toInt()
        for (index in 0 until binding.centerButtonsContainer.childCount) {
            val button = binding.centerButtonsContainer.getChildAt(index)
            val params = button.layoutParams as? android.widget.LinearLayout.LayoutParams ?: continue
            val last = index == binding.centerButtonsContainer.childCount - 1
            val bottomGap = if (!landscape && !last) gap else 0
            val endGap = if (landscape && !last) gap else 0
            val width = if (landscape) 0 else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            val weight = if (landscape) 1f else 0f
            if (params.bottomMargin != bottomGap || params.marginEnd != endGap || params.width != width || params.weight != weight) {
                params.width = width
                params.weight = weight
                params.bottomMargin = bottomGap
                params.marginEnd = endGap
                button.layoutParams = params
            }
        }
        val cardVisible = binding.statsCard.visibility == View.VISIBLE
        val centers = if (cardVisible) View.GONE else View.VISIBLE
        if (binding.centerButtonsContainer.visibility != centers) {
            binding.centerButtonsContainer.visibility = centers
        }
        val params = binding.statsCard.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val bottom = binding.bottomMapActions.height + (32f * resources.displayMetrics.density).toInt()
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            binding.statsCard.layoutParams = params
        }
    }

    private fun collapseStatsCard() {
        isStatsCardCollapsed = true
        isPersonDetailsExpanded = false
        binding.mapPersonDetails.visibility = View.GONE
        binding.statsCard.visibility = View.GONE
        if (::mapView.isInitialized) {
            mapView.overlays.filterIsInstance<Marker>().forEach { it.closeInfoWindow() }
            mapView.invalidate()
        }
    }

    private fun expandStatsCard() {
        isStatsCardCollapsed = false
        binding.statsCard.visibility = View.VISIBLE
    }

    private fun setupMap() {
        try {
            mapView = binding.mapView
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.setMultiTouchControls(true)
            mapView.controller.setZoom(15.0)
            mapView.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    mapView.removeCallbacks(repositionFamilyMarkers)
                    mapView.postDelayed(repositionFamilyMarkers, 220L)
                    return false
                }
                override fun onZoom(event: ZoomEvent?): Boolean {
                    mapView.removeCallbacks(repositionFamilyMarkers)
                    mapView.postDelayed(repositionFamilyMarkers, 160L)
                    return false
                }
            })

            mapView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    followTouchX = event.x; followTouchY = event.y
                }
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                    (event.actionMasked == MotionEvent.ACTION_MOVE &&
                        (kotlin.math.abs(event.x - followTouchX) > slop || kotlin.math.abs(event.y - followTouchY) > slop))) {
                    if (mapFollow.active()) stopMapFollow()
                }
                if (event.action == MotionEvent.ACTION_DOWN) { collapseStatsCard(); historyPanel?.hide() }
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    if (autoFitEnabled && !isViewingHistory) {
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

    private fun positionMapControlsBelowHeader() {
        val top = binding.appBarLayout.bottom + (12f * resources.displayMetrics.density).toInt()
        listOf(binding.refreshButton, binding.liveModeButton).forEach { button ->
            val params = button.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                ?: return@forEach
            if (params.topMargin != top) {
                params.topMargin = top
                button.layoutParams = params
            }
        }
    }
    
    private fun setupRefreshButton() {
        binding.refreshButton.setOnClickListener {
            loadLocations()
        }
    }

    private fun setupLiveModeButton() {
        if (showAllContacts) {
            binding.liveModeButton.visibility = View.GONE
            return
        }
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

    private fun setupPlacesButton() {
        binding.placesButton.visibility = if (myRole == ROLE_PARENT) View.VISIBLE else View.GONE
        binding.placesButton.setOnClickListener { familyPlacesController.show() }
        // Keep previous on-phone places accessible while migrating to family places.
        if (binding.toolbar.menu.findItem(93241) == null) binding.toolbar.menu.add(0, 93241, 0,
            getString(R.string.family_places_legacy)).setOnMenuItemClickListener { showPlacesMenu(); true }
    }

    private fun renderServerPlaces(places: List<org.json.JSONObject>) {
        if (!::mapView.isInitialized) return
        serverPlaceOverlays.forEach(mapView.overlays::remove); serverPlaceOverlays.clear()
        places.filter { it.optInt("enabled") == 1 }.forEach { place ->
            val polygon = Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(GeoPoint(place.getDouble("latitude"), place.getDouble("longitude")), place.getDouble("radius"))
                fillPaint.color = Color.argb(28, 18, 108, 103)
                outlinePaint.color = getColor(ru.example.childwatch.designsystem.R.color.cw_color_primary)
                outlinePaint.strokeWidth = 2f * resources.displayMetrics.density
                title = place.getString("name")
            }
            serverPlaceOverlays += polygon; mapView.overlays.add(0, polygon)
        }
        mapView.invalidate()
    }

    private fun setupTimelineButton() {
        binding.timelineButton.setOnClickListener { loadTodayTimeline() }
        updateTimelineButtonVisibility()
    }

    private fun setupAttentionSignalButton() {
        val canCallChild = !showAllContacts && myRole == ROLE_PARENT && otherId.isNotBlank()
        binding.attentionSignalButton.visibility = if (canCallChild) View.VISIBLE else View.GONE
        if (!canCallChild) return
        binding.attentionSignalButton.setOnClickListener {
            val targetDeviceId = resolvedOtherId.ifBlank { otherId }
            ParentAttentionSignalLauncher.show(
                activity = this,
                explicitTargetDeviceId = targetDeviceId,
                explicitTargetName = participantNameResolver.resolveFocusedChildDisplayName(targetDeviceId),
                explicitTargetAvatarValue = familyPresentationByDevice[targetDeviceId]?.avatarValue
            )
        }
    }

    private fun updateTimelineButtonVisibility() {
        val canShowTimeline = historySelectionKey().isNotBlank()
        binding.timelineButton.visibility = if (canShowTimeline) View.VISIBLE else View.GONE
    }

    private fun observePlaces() {
        val targetId = historyTargetId().orEmpty()
        if (targetId.isBlank() || myRole != ROLE_PARENT) {
            renderPlaceOverlays(emptyList())
            return
        }
        database.geofenceDao().getAllGeofences(targetId).observe(this) { places ->
            placesForCurrentChild = places.orEmpty()
            renderPlaceOverlays(placesForCurrentChild)
            updateLiveModeUi()
        }
    }

    private data class FollowPoint(val member: String, val device: String, val latitude: Double, val longitude: Double, val time: Long)

    private fun followScope(): String = networkClient.resolveConfiguredServerUrl() + "\n" + (ru.example.childwatch.profile.ParentEffectiveContextResolver(this).resolveFamilyId() ?: liveFamilyId).orEmpty()

    private fun selectedFollowPoint(): FollowPoint? = currentFamilyLocations.firstOrNull { it.memberId == selectedFamilyMemberId }?.let {
        FollowPoint(it.memberId, it.deviceId, it.latitude, it.longitude,
            it.timestamp?.let(::normalizeTimestampMillis) ?: 0L)
    }

    private fun updateFollowUi() {
        val text = if (mapFollow.active()) R.string.map_follow_active else R.string.map_follow_start
        binding.centerOtherButton.setText(text)
        binding.centerOtherButton.contentDescription = getString(if (mapFollow.active()) R.string.map_follow_stop_description else R.string.map_follow_start_description)
        binding.mapPersonFollowButton.setText(text)
        binding.mapPersonFollowButton.contentDescription = binding.centerOtherButton.contentDescription
        binding.mapPersonFollowButton.isChecked = mapFollow.active()
    }

    private fun stopMapFollow() {
        mapFollow.stop()
        updateFollowUi()
    }

    private fun toggleMapFollow() {
        if (mapFollow.active()) { stopMapFollow(); return }
        val point = selectedFollowPoint()
        if (point == null || !point.latitude.isFinite() || !point.longitude.isFinite() ||
            !mapFollow.start(followScope(), point.member, point.device, point.time, System.currentTimeMillis())) {
            Toast.makeText(this, R.string.map_follow_no_fresh_point, Toast.LENGTH_SHORT).show()
            updateFollowUi()
            return
        }
        leaveHistory()
        collapseStatsCard()
        autoFitEnabled = false
        updateAutoFitUi()
        updateFollowUi()
        followMarkerPosition(point.member, point.device, point.latitude, point.longitude)
    }

    private fun refreshMapFollow() {
        if (!mapFollow.active()) return
        val point = selectedFollowPoint()
        if (point == null || !mapFollow.refresh(followScope(), point.member, point.device, point.time, System.currentTimeMillis())) {
            stopMapFollow()
            return
        }
        val position = contactMarkers[point.member]?.position ?: GeoPoint(point.latitude, point.longitude)
        followMarkerPosition(point.member, point.device, position.latitude, position.longitude)
    }

    private fun followMarkerPosition(member: String, device: String, latitude: Double, longitude: Double) {
        if (!mapFollow.matches(followScope(), member, device) || isViewingHistory ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val center = mapView.mapCenter
        if (kotlin.math.abs(center.latitude - latitude) > 0.00000001 || kotlin.math.abs(center.longitude - longitude) > 0.00000001)
            mapView.controller.setCenter(GeoPoint(latitude, longitude))
    }

    private fun setupCenterButtons() {
        updateCenterIcons()
        updateAutoFitUi()
        if (showAllContacts) {
            binding.centerBothButton.contentDescription = getString(R.string.map_center_family)
        }
        if (limitedMode) {
            binding.centerOtherButton.isEnabled = false
            binding.centerOtherButton.alpha = 0.4f
            binding.centerBothButton.isEnabled = false
            binding.centerBothButton.alpha = 0.4f
        }

        binding.centerBothButton.setOnClickListener { stopMapFollow(); leaveHistory();
            autoFitEnabled = true
            updateAutoFitUi()
            if (showAllContacts && familyLivePoints.isNotEmpty()) {
                safeZoomToBoundingBox(familyLivePoints, familyLivePoints.firstOrNull())
            } else {
                centerOnAvailable()
            }
        }

        binding.centerMyButton.setOnClickListener { stopMapFollow(); leaveHistory();
            autoFitEnabled = false
            updateAutoFitUi()
            if (!centerOnPoint(lastMyPoint)) {
                Toast.makeText(this, getString(R.string.map_my_location_not_available), Toast.LENGTH_SHORT).show()
            }
        }

        binding.centerOtherButton.setOnClickListener { toggleMapFollow() }
        binding.mapPersonFollowButton.setOnClickListener { toggleMapFollow() }
        updateFollowUi()
    }

    private fun updateCenterIcons() {
        val myIcon = resolveMyMarkerIconRes()
        val otherIcon = resolveOtherMarkerIconRes()
        binding.centerMyButton.setIconResource(myIcon)
        binding.centerOtherButton.setIconResource(otherIcon)
    }

    private fun updateAutoFitUi() {
        binding.centerBothButton.alpha = if (autoFitEnabled && !isViewingHistory) 1.0f else 0.6f
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
        binding.historyButton.setOnClickListener { showHistoryPeriodDialog() }
        binding.historyButton.visibility = if (historySelectionKey().isBlank()) View.GONE else View.VISIBLE
    }

    private fun showHistoryPeriodDialog() {
        val periods = arrayOf(
            getString(R.string.map_history_today),
            getString(R.string.map_history_yesterday),
            getString(R.string.map_history_week),
            getString(R.string.map_history_month),
            getString(ru.example.childwatch.designsystem.R.string.cw_history_choose_date)
        )
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle(historyPersonName())
        builder.setItems(periods) { _, which ->
            if (which == 4) { showHistoryDatePicker(); return@setItems }
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

    private fun loadTodayTimeline() {
        val day = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        loadLocationHistory(day.timeInMillis, System.currentTimeMillis())
    }

    private suspend fun fetchLocationHistory(
        fromTimestamp: Long,
        toTimestamp: Long
    ): List<ru.example.childwatch.network.ParentLocationData>? {
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
                deviceId = targetId,
                fromTimestamp = fromTimestamp,
                toTimestamp = toTimestamp,
                limit = HISTORY_LIMIT
            )
        }
    }

    private fun showTodayTimelineDialog(history: List<ParentLocationData>) {
        val lines = buildTodayTimelineLines(history)
        android.app.AlertDialog.Builder(this)
            .setTitle(historyPersonName())
            .setMessage(
                if (lines.isEmpty()) {
                    getString(R.string.map_timeline_empty)
                } else {
                    lines.joinToString(separator = "\n")
                }
            )
            .setNeutralButton(ru.example.childwatch.designsystem.R.string.cw_history_summary) { _, _ -> showHistorySummary(history) }
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
        events += buildPlaceTimelineEvents(sortedHistory)
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

    private fun buildPlaceTimelineEvents(history: List<ParentLocationData>): List<TimelineEvent> {
        val activePlaces = placesForCurrentChild.filter { it.isActive }
        if (activePlaces.isEmpty()) return emptyList()

        val placesById = activePlaces.associateBy { it.id }
        val events = mutableListOf<TimelineEvent>()
        var previousPlaceIds = emptySet<Long>()

        history.forEach { point ->
            val pointGeo = GeoPoint(point.latitude, point.longitude)
            val currentPlaceIds = activePlaces
                .asSequence()
                .filter { isPointInsidePlace(pointGeo, it) }
                .map { it.id }
                .toSet()

            (currentPlaceIds - previousPlaceIds).forEach { placeId ->
                val place = placesById[placeId] ?: return@forEach
                events += TimelineEvent(
                    timestamp = point.timestamp,
                    priority = 10,
                    label = getString(
                        R.string.map_timeline_arrive_place,
                        place.name,
                        formatTimestamp(point.timestamp)
                    )
                )
            }

            (previousPlaceIds - currentPlaceIds).forEach { placeId ->
                val place = placesById[placeId] ?: return@forEach
                events += TimelineEvent(
                    timestamp = point.timestamp,
                    priority = 11,
                    label = getString(
                        R.string.map_timeline_leave_place,
                        place.name,
                        formatTimestamp(point.timestamp)
                    )
                )
            }

            previousPlaceIds = currentPlaceIds
        }

        return events
    }
    
    private fun historySelectionKey(): String = if (showAllContacts)
        currentFamilyLocations.firstOrNull { it.memberId == selectedFamilyMemberId }?.let { "${ru.example.childwatch.profile.ParentEffectiveContextResolver(this).resolveFamilyId() ?: liveFamilyId}:${it.memberId}:${it.deviceId}" }.orEmpty()
        else historyTargetId().orEmpty()

    private fun historyPersonName(): String = if (showAllContacts)
        currentFamilyLocations.firstOrNull { it.memberId == selectedFamilyMemberId }?.displayName ?: getString(R.string.map_title_other_device)
        else otherMarkerTitle()

    private var historyCursor: Marker? = null

    private fun clearHistoryCursor() {
        if (::mapView.isInitialized) {
            historyCursor?.let { mapView.overlays.remove(it); it.closeInfoWindow() }
            mapView.invalidate()
        }
        historyCursor = null
    }

    private fun historyAccessKey(): String = "${contextProvider.current()?.selfDeviceId}:${contextProvider.current()?.selfMemberId}:${historySelectionKey()}"

    private var historyRequestToken = 0
    private var historyLoadJob: kotlinx.coroutines.Job? = null
    private var isViewingHistory = false
    private var historyBoundKey: String? = null
    private var historyBoundServer: String? = null

    private fun ensureHistoryContext() {
        if (isViewingHistory && (historyBoundKey != historyAccessKey() || historyBoundServer != networkClient.resolveConfiguredServerUrl())) leaveHistory()
    }
    private var historyPanel: ru.example.childwatch.designsystem.MapHistoryPanel? = null

    private fun leaveHistory() {
        historyRequestToken++
        historyLoadJob?.cancel()
        historyPanel?.dispose()
        historyPanel = null
        isViewingHistory = false
        clearHistoryCursor()
        if (::mapView.isInitialized) {
            historyLines.forEach { mapView.overlays.remove(it) }
            historyLines.clear()
            historyStartMarker?.let { mapView.overlays.remove(it) }
            historyEndMarker?.let { mapView.overlays.remove(it) }
            historyStartMarker = null
            historyEndMarker = null
            mapView.invalidate()
        }
    }

    private fun loadLocationHistory(fromTimestamp: Long, toTimestamp: Long) {
        val target = historyAccessKey()
        if (target.isBlank()) return
        leaveHistory()
        collapseStatsCard()
        markerMotion.clear()
        stopMapFollow()
        isViewingHistory = true
        historyBoundKey = target
        historyBoundServer = networkClient.resolveConfiguredServerUrl()
        familyTrailLines.forEach { mapView.overlays.remove(it) }
        familyTrailLines.clear()
        autoFitEnabled = false
        updateAutoFitUi()
        val token = ++historyRequestToken
        val server = networkClient.resolveConfiguredServerUrl()
        val selected = currentFamilyLocations.firstOrNull { it.memberId == selectedFamilyMemberId }
            ?: currentFamilyLocations.firstOrNull { it.deviceId == historyTargetId() }
        val family = ru.example.childwatch.profile.ParentEffectiveContextResolver(this).resolveFamilyId() ?: liveFamilyId
        val device = selected?.deviceId
        val member = selected?.memberId
        var centeredAt: Long? = null
        val collected = mutableListOf<ParentLocationData>()
        fun valid() = token == historyRequestToken && target == historyAccessKey() &&
            server == networkClient.resolveConfiguredServerUrl() && !isFinishing && !isDestroyed
        fun render(finished: Boolean) {
            if (!valid()) return
            val points = collected.mapNotNull { point ->
                val time = normalizeTimestampMillis(point.timestamp) ?: return@mapNotNull null
                if (time !in fromTimestamp..toTimestamp || !isValidCoordinate(point.latitude, point.longitude)) return@mapNotNull null
                point.copy(timestamp = time)
            }.distinctBy { Triple(it.timestamp, it.latitude, it.longitude) }.sortedBy { it.timestamp }
            displayLocationHistory(points)
            historyPanel?.update(points.map { MapRouteSegments.Fix(it.latitude, it.longitude, it.timestamp, it.accuracy, it.speedMps, it.speedAccuracyMps) }, finished)
        }
        historyPanel = ru.example.childwatch.designsystem.MapHistoryPanel(this, binding.root,
            historyPersonName(), fromTimestamp, toTimestamp,
            object : ru.example.childwatch.designsystem.MapHistoryPanel.Listener {
                override fun onPoint(point: MapRouteSegments.Fix?) {
                    if (!valid() || !::mapView.isInitialized) return
                    clearHistoryCursor()
                    if (point == null) return
                    historyCursor = Marker(mapView).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        title = historyPersonName()
                        snippet = formatTimestamp(point.timestampMs)
                        icon = tintedDrawable(R.drawable.ic_route_start_marker, historyAccentColor())
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(historyCursor)
                    if (centeredAt != point.timestampMs) {
                        mapView.controller.setCenter(GeoPoint(point.latitude, point.longitude))
                        centeredAt = point.timestampMs
                    }
                    mapView.invalidate()
                }
                override fun onLive() { leaveHistory(); autoFitEnabled = true; updateAutoFitUi(); centerOnAvailable(); loadLocations() }
                override fun onRetry() { if (valid()) loadLocationHistory(fromTimestamp, toTimestamp) }
                override fun onEvents() {
                    if (valid()) showTodayTimelineDialog(collected.mapNotNull { point ->
                        normalizeTimestampMillis(point.timestamp)?.let { point.copy(timestamp = it) }
                    }.filter { it.timestamp in fromTimestamp..toTimestamp && isValidCoordinate(it.latitude, it.longitude) }.sortedBy { it.timestamp })
                }
            })
        historyLoadJob = lifecycleScope.launch {
            try {
                if (family == null || member == null || device == null) {
                    val legacy = kotlinx.coroutines.withTimeout(30_000L) { fetchLocationHistory(fromTimestamp, toTimestamp) }
                        ?: throw java.io.IOException("History unavailable")
                    if (!valid()) return@launch
                    collected.addAll(legacy)
                    render(legacy.size < HISTORY_LIMIT)
                    if (legacy.size >= HISTORY_LIMIT) historyPanel?.limited(true)
                    return@launch
                }
                var cursor: String? = null
                var revision: String? = null
                val seen = mutableSetOf<String>()
                do {
                    val page = kotlinx.coroutines.withTimeout(30_000L) {
                        networkClient.getFamilyHistoryPage(family, member, device, fromTimestamp, toTimestamp, cursor)
                    }
                    if (!valid()) return@launch
                    if (revision != null && revision != page.revision) throw ru.example.childwatch.network.HistoryReadException(409)
                    revision = page.revision
                    if (collected.size + page.points.size > 100_000) throw ru.example.childwatch.network.HistoryReadException(413)
                    collected.addAll(page.points)
                    render(!page.hasMore)
                    cursor = page.nextCursor
                    if (page.hasMore && (cursor == null || !seen.add(cursor))) throw java.io.IOException("Repeated history page")
                } while (page.hasMore)
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (valid()) historyPanel?.error(false, false)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not load day history", error)
                if (valid()) {
                    val status = (error as? ru.example.childwatch.network.HistoryReadException)?.status
                    if (status == 401 || status == 403 || status == 409) {
                        displayLocationHistory(emptyList())
                        collected.clear()
                    }
                    if (status == 413) historyPanel?.limited(false)
                    else historyPanel?.error(status == 401 || status == 403, status == 409)
                }
            }
        }
    }

    private fun showHistoryDatePicker() {
        val day = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, year, month, date ->
            day.set(year, month, date, 0, 0, 0)
            day.set(java.util.Calendar.MILLISECOND, 0)
            val from = day.timeInMillis
            day.add(java.util.Calendar.DAY_OF_YEAR, 1)
            loadLocationHistory(from, minOf(day.timeInMillis - 1, System.currentTimeMillis()))
        }, day.get(java.util.Calendar.YEAR), day.get(java.util.Calendar.MONTH), day.get(java.util.Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun displayLocationHistory(history: List<ru.example.childwatch.network.ParentLocationData>) {
        clearHistoryCursor()
        val fixes = history
            .sortedBy { it.timestamp }
            .filter { isValidCoordinate(it.latitude, it.longitude) }
            .mapNotNull { point ->
                normalizeTimestampMillis(point.timestamp)?.let { timestamp ->
                    MapRouteSegments.Fix(point.latitude, point.longitude, timestamp, point.accuracy, point.speedMps, point.speedAccuracyMps)
                }
            }
        val segments = MapRouteSegments.split(fixes.sortedBy { it.timestampMs })
        
        // Remove the previous history overlay before drawing a new one.
        historyLines.forEach(mapView.overlays::remove)
        historyLines.clear()
        historyStartMarker?.let { mapView.overlays.remove(it) }
        historyEndMarker?.let { mapView.overlays.remove(it) }
        historyStartMarker = null
        historyEndMarker = null
        if (segments.isEmpty()) { mapView.invalidate(); return }

        segments.filter { it.size >= 2 }.forEachIndexed { index, segment ->
            val line = Polyline(mapView).apply {
                id = "history_line_$index"
                setPoints((if (mapOptions.speedColors()) segment else MapRouteSegments.simplify(segment)).map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = Color.rgb(39, 79, 221)
                outlinePaint.strokeWidth = 4.5f * resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.alpha = 255
            }
            
            val casing = routeCasing(line)
            historyLines += casing
            historyLines += line
            mapView.overlays.add(0, line)
            mapView.overlays.add(0, casing)
        }
        
        // Add route start/end markers for context.
        val firstPoint = segments.first().first()
        val lastPoint = segments.last().last()
        val historyStartIcon = tintedDrawable(R.drawable.ic_route_start_marker, historyAccentColor())
        val historyEndIcon = tintedDrawable(R.drawable.ic_route_end_marker, historyAccentColor())
        
        if (firstPoint != lastPoint) {
            // Start marker.
            historyStartMarker = Marker(mapView).apply {
                position = GeoPoint(firstPoint.latitude, firstPoint.longitude)
                title = getString(R.string.map_history_route_start)
                snippet = formatTimestamp(firstPoint.timestampMs)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = historyStartIcon
            }
            
            // End marker.
            historyEndMarker = Marker(mapView).apply {
                position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                title = getString(R.string.map_history_route_end)
                snippet = formatTimestamp(lastPoint.timestampMs)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = historyEndIcon
            }
            
            mapView.overlays.add(historyStartMarker)
            mapView.overlays.add(historyEndMarker)
        }
        
        mapView.invalidate()
        
        // Fit the camera to the route bounds.
        safeZoomToBoundingBox(
            segments.flatten().map { GeoPoint(it.latitude, it.longitude) },
            GeoPoint(lastPoint.latitude, lastPoint.longitude)
        )
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

    private fun isStale(timestamp: Long?): Boolean {
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

    private fun childAccentColor(): Int = Color.parseColor("#F59E0B")

    private fun selfParentAccentColor(): Int = Color.parseColor("#0F766E")

    private fun participantAccentColor(deviceId: String?, role: String, emphasizeSelf: Boolean = false): Int {
        if (deviceId.isNullOrBlank() && (role == ROLE_CHILD || role == ContactRoles.CHILD)) return childAccentColor()
        if (emphasizeSelf) return selfParentAccentColor()
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

    private fun resolveLocalChildMarkerIconId(childDeviceId: String?): Int {
        val normalizedChildId = childDeviceId?.trim().orEmpty()
        if (normalizedChildId.isBlank()) return ContactIcons.CHILD
        if (!::database.isInitialized) return ContactIcons.CHILD

        return runBlocking(Dispatchers.IO) {
            database.childDao().getByDeviceId(normalizedChildId)
        }?.iconId?.takeIf(ContactIcons::isKnown) ?: ContactIcons.CHILD
    }

    private fun resolveMyMarkerIconRes(): Int {
        return when (myRole) {
            ROLE_PARENT -> ContactIcons.resolve(
                participantNameResolver.resolveOwnParentMarkerIconId(),
                ContactRoles.PARENT
            )
            ROLE_CHILD -> ContactIcons.resolve(
                resolveLocalChildMarkerIconId(myId),
                ContactRoles.CHILD
            )
            else -> R.drawable.ic_parent_marker
        }
    }

    private fun resolveOtherMarkerIconRes(): Int {
        return when (myRole) {
            ROLE_PARENT -> ContactIcons.resolve(
                resolveLocalChildMarkerIconId(otherId),
                ContactRoles.CHILD
            )
            ROLE_CHILD -> ContactIcons.resolve(
                participantNameResolver.resolveOwnParentMarkerIconId(),
                ContactRoles.PARENT
            )
            else -> R.drawable.ic_child_marker
        }
    }

    private fun historyAccentColor(): Int = when (myRole) {
        ROLE_PARENT -> childAccentColor()
        ROLE_CHILD -> participantAccentColor(historyTargetId(), ROLE_PARENT)
        else -> Color.parseColor("#4285F4")
    }

    private fun connectionAccentColor(): Int = when (myRole) {
        ROLE_PARENT -> Color.parseColor("#0EA5E9")
        ROLE_CHILD -> Color.parseColor("#6366F1")
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
        title: String,
        avatarValue: String? = null,
        subtitle: String? = null,
        /**
         * True when the position is old enough that it may no longer be true.
         *
        * The marker is drawn faded with a clock beside it. Tapping already revealed
         * the time, but a marker that looks the same whether it is ten seconds or ten
         * hours old invites a parent to read an old position as a current one.
         */
        stale: Boolean = false
    ): Drawable? {
        val density = resources.displayMetrics.density
        val label = shortMarkerLabel(title)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 10f * density
            textAlign = Paint.Align.CENTER
        }
        val chipHeight = (if (subtitle.isNullOrBlank()) 22f else 36f) * density
        val chipPadding = 10f * density
        val iconSize = 34f * density
        val outerCircle = iconSize / 2f + 1f * density
        val textWidth = maxOf(24f * density, textPaint.measureText(label),
            subtitle?.let(subtitlePaint::measureText) ?: 0f)
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
        val textY = (if (subtitle.isNullOrBlank()) chipHeight / 2f else 11f * density) -
            ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(label, width / 2f, textY, textPaint)
        if (!subtitle.isNullOrBlank()) {
            val subtitleY = 27f * density -
                ((subtitlePaint.descent() + subtitlePaint.ascent()) / 2f)
            canvas.drawText(subtitle, width / 2f, subtitleY, subtitlePaint)
        }

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

        // A stored picture wins; otherwise the marker gets the person's letter
        // avatar, and the contact icon tint is kept for the no-avatar case.
        val iconDrawable = if (avatarValue.isNullOrBlank()) {
            ContextCompat.getDrawable(this, iconRes)?.mutate()
        } else {
            FamilyAvatarRenderer.drawable(this, avatarValue)?.mutate()
        } ?: return BitmapDrawable(resources, bitmap)
        if (avatarValue.isNullOrBlank()) {
            DrawableCompat.setTint(iconDrawable, accentColor)
        }
        val halfIcon = iconSize / 2f
        val saveCount = canvas.save()
        canvas.clipPath(Path().apply {
            addCircle(circleCx, circleCy, halfIcon, Path.Direction.CW)
        })
        iconDrawable.setBounds(
            (circleCx - halfIcon).toInt(),
            (circleCy - halfIcon).toInt(),
            (circleCx + halfIcon).toInt(),
            (circleCy + halfIcon).toInt()
        )
        iconDrawable.draw(canvas)
        canvas.restoreToCount(saveCount)

        // An old position is drawn faded and given a clock, so it cannot be mistaken
        // for where the person is now.
        if (stale) {
            val faded = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            faded.alpha = 150
            canvas.drawCircle(circleCx, circleCy, outerCircle - 1f * density, faded)

            val clockRadius = 7f * density
            val clockCx = circleCx + outerCircle - clockRadius
            val clockCy = circleCy + outerCircle - clockRadius
            val clockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#6B7280")
            }
            val clockMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * density
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawCircle(clockCx, clockCy, clockRadius, clockFill)
            canvas.drawLine(clockCx, clockCy, clockCx, clockCy - clockRadius * 0.5f, clockMark)
            canvas.drawLine(clockCx, clockCy, clockCx + clockRadius * 0.45f, clockCy, clockMark)
        }

        return BitmapDrawable(resources, bitmap)
    }

    private fun tintedDrawable(@DrawableRes iconRes: Int, accentColor: Int): Drawable? {
        val drawable = ContextCompat.getDrawable(this, iconRes)?.mutate() ?: return null
        DrawableCompat.setTint(drawable, accentColor)
        return drawable
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
        contextProvider.current()?.selfDeviceId
            ?.takeIf(String::isNotBlank)
            ?.let { return listOf(it) }
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
        contextProvider.current()?.targetDeviceId
            ?.takeIf { it.isNotBlank() && it != myId.trim() }
            ?.let { return listOf(it) }
        val effectiveContext = ParentEffectiveContextResolver(this).resolve()
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

        val primaryCandidates = listOf(
            resolvedOtherId,
            otherId,
            effectiveContext.linkedChildDeviceId,
            secureSettings.getChildDeviceId(),
            prefs.getString("child_device_id", null),
            legacyPrefs.getString("child_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && it !in excluded }
            .distinct()

        if (primaryCandidates.isNotEmpty()) {
            return primaryCandidates.plus(
                childIdsFromDb.filter { it.isNotBlank() && it !in excluded && it !in primaryCandidates }
            )
        }

        return listOf(
            prefs.getString("selected_device_id", null),
            legacyPrefs.getString("selected_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .plus(childIdsFromDb)
            .filter { it.isNotBlank() && it !in excluded }
            .distinct()
    }

    private fun resolveSelfParentIdCandidates(): List<String> {
        contextProvider.current()?.selfDeviceId
            ?.takeIf(String::isNotBlank)
            ?.let { return listOf(it) }
        val effectiveContext = ParentEffectiveContextResolver(this).resolve()
        val secureSettings = SecureSettingsManager(this)
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return listOf(
            resolvedParentId,
            myId,
            effectiveContext.ownParentDeviceId,
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

        val lines = mutableListOf(
            getString(R.string.map_history_summary_distance, formatDistance(summary.totalDistanceMeters)),
            getString(R.string.map_history_summary_duration, formatDuration((summary.lastTimestamp ?: 0L) - (summary.firstTimestamp ?: 0L))),
            getString(R.string.map_history_summary_points, summary.pointCount),
            getString(R.string.map_history_summary_last_seen, formatDateTime(summary.lastTimestamp)),
            getString(R.string.map_history_summary_stops, summary.stopCount)
        )

        lines += if (summary.longestStopDurationMs > 0L) {
            getString(R.string.map_history_summary_longest_stop, formatDuration(summary.longestStopDurationMs))
        } else {
            getString(R.string.map_history_summary_no_stop)
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

        val routeSegments = MapRouteSegments.split(sortedHistory.map { point ->
            MapRouteSegments.Fix(point.latitude, point.longitude, point.timestamp, point.accuracy)
        })
        val totalDistanceMeters = routeSegments.sumOf { segment ->
            MapRouteSegments.simplify(segment).zipWithNext().sumOf { (previous, current) ->
                MapRouteSegments.distanceMeters(previous, current)
            }
        }.toFloat()

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
            val contiguous = MapRouteSegments.split(listOf(
                MapRouteSegments.Fix(clusterEnd.latitude, clusterEnd.longitude, clusterEnd.timestamp, clusterEnd.accuracy),
                MapRouteSegments.Fix(point.latitude, point.longitude, point.timestamp, point.accuracy)
            )).any { it.size == 2 }
            if (distance <= STOP_RADIUS_METERS && contiguous) {
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

    private fun cacheKeyMy(): String = "$mapNamespace::${MAP_CACHE_MY}_${myRole}"

    private fun cacheKeyOther(): String = "$mapNamespace::${MAP_CACHE_OTHER}_${resolvedOtherId.ifBlank { otherId }}"

    private fun saveCachedLocation(
        key: String,
        lat: Double,
        lon: Double,
        timestamp: Long,
        speed: Float?,
        accuracy: Float? = null
    ) {
        val normalizedTimestamp = normalizeTimestampMillis(timestamp) ?: System.currentTimeMillis()
        val speedValue = speed?.toString() ?: ""
        val accuracyValue = accuracy?.takeIf { it.isFinite() && it > 0f }?.toString() ?: ""
        prefs.edit().putString(key, "$lat|$lon|$normalizedTimestamp|$speedValue|$accuracyValue").apply()
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
            val accuracy = parts.getOrNull(4)?.toFloatOrNull()
            CachedLocation(lat, lon, ts, speed, accuracy)
        } catch (e: Exception) {
            null
        }
    }

    private fun CachedLocation.toParentLocationData(deviceId: String): ParentLocationData {
        return ParentLocationData(
            parentId = deviceId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy ?: 0f,
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
        ensureHistoryContext()
        if (!dependenciesReady || !isMapReady || !::binding.isInitialized || !::mapView.isInitialized) return
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
                    lastLinkedSourceRes = null
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
                        myLocationAccuracy = myLocation.accuracy
                        saveCachedLocation(
                            cacheKeyMy(),
                            myLocation.latitude,
                            myLocation.longitude,
                            myLocation.time,
                            if (myLocation.hasSpeed()) myLocation.speed else null,
                            myLocation.accuracy
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
                            snippetLabel = getString(R.string.map_my_location),
                            accuracy = myLocation?.accuracy ?: cachedMy?.accuracy
                        )
                    }

                    renderFamilyMarkers(loadFamilyMarkersForCurrentChild())

                    binding.loadingIndicator.visibility = View.GONE
                    applyDiagnosticState(
                        reason = missingLinkedReason(),
                        linkedTimestamp = null,
                        sourceRes = when {
                            myLocation != null -> R.string.map_diag_source_gps
                            cachedMy != null -> R.string.map_diag_source_cache
                            else -> null
                        }
                    )
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
                    lastLinkedSourceRes = cachedOther?.let { R.string.map_stats_source_cache_short }
                    displayAvailableLocations(
                        myLat = cachedMy?.latitude,
                        myLon = cachedMy?.longitude,
                        myTimestamp = cachedMy?.timestamp,
                        otherLocation = cachedOther?.toParentLocationData(resolvedOtherId.ifBlank { otherId }),
                        myAccuracy = cachedMy?.accuracy
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
                    myLocationAccuracy = myLocation.accuracy
                    saveCachedLocation(
                        cacheKeyMy(),
                        myLocation.latitude,
                        myLocation.longitude,
                        myLocation.time,
                        if (myLocation.hasSpeed()) myLocation.speed else null,
                        myLocation.accuracy
                    )
                } else {
                    Log.w(TAG, "My location not available")
                }
                
                var resolvedPairParentId = resolvedParentId.ifBlank { myId }
                var resolvedPairOtherId = resolvedOtherId.ifBlank { otherId }
                val pairSnapshot = fetchResolvedPairSnapshot(
                    onResolved = { parentId, childId ->
                        resolvedPairParentId = parentId
                        // "The other device" is the far end of the pair, and which end
                        // that is depends on who is looking. Taking childId
                        // unconditionally pointed every "other" reading at the wrong
                        // person whenever this screen ran in the child role.
                        resolvedPairOtherId = if (myRole == ROLE_CHILD) parentId else childId
                    }
                )
                if (resolvedPairParentId.isNotBlank()) {
                    resolvedParentId = resolvedPairParentId
                }
                if (resolvedPairOtherId.isNotBlank()) {
                    resolvedOtherId = resolvedPairOtherId
                }
                updateTimelineButtonVisibility()
                setupPlacesButton()

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
                        otherLocation.speed,
                        otherLocation.accuracy
                    )
                }

                if (serverSelfLocation != null) {
                    saveCachedLocation(
                        cacheKeyMy(),
                        serverSelfLocation.latitude,
                        serverSelfLocation.longitude,
                        serverSelfLocation.timestamp,
                        serverSelfLocation.speed,
                        serverSelfLocation.accuracy
                    )
                }

                val myLatFinal = myLatitude ?: serverSelfLocation?.latitude ?: cachedMy?.latitude
                val myLonFinal = myLongitude ?: serverSelfLocation?.longitude ?: cachedMy?.longitude
                val myTsFinal = myLocation?.time ?: serverSelfLocation?.timestamp ?: cachedMy?.timestamp
                val myAccuracyFinal = myLocation?.accuracy ?: serverSelfLocation?.accuracy ?: cachedMy?.accuracy
                myLocationAccuracy = myAccuracyFinal
                val otherFinal = otherLocation ?: cachedOther?.toParentLocationData(resolvedOtherId.ifBlank { otherId.ifBlank { "paired-device" } })
                val usingCachedOther = otherLocation == null && otherFinal != null
                val selfAvailable = myLatFinal != null && myLonFinal != null
                lastLinkedSourceRes = when {
                    usingCachedOther -> R.string.map_stats_source_cache_short
                    otherLocation != null -> R.string.map_stats_source_server_short
                    else -> null
                }
                
                if (myLatFinal != null && myLonFinal != null || otherFinal != null) {
                    displayAvailableLocations(
                        myLat = myLatFinal,
                        myLon = myLonFinal,
                        myTimestamp = myTsFinal,
                        otherLocation = otherFinal,
                        myAccuracy = myAccuracyFinal
                    )
                    renderFamilyMarkers(loadFamilyMarkersForCurrentChild())
                    binding.loadingIndicator.visibility = View.GONE
                    applyDiagnosticState(
                        reason = resolveDiagnosticReason(
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
                        reason = resolveDiagnosticReason(
                            selfAvailable = false,
                            linkedLocation = null,
                            usingCachedLinked = false
                        ),
                        linkedTimestamp = null,
                        sourceRes = null
                    )
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
        otherTimestamp: Long?,
        linkedLocation: ParentLocationData,
        myAccuracy: Float?
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
        clearLiveAccuracyOverlays()
        clearFamilyMarkers()
        
        // Use role-specific icons for the current device.
        val myMarkerIcon = resolveMyMarkerIconRes()
        
        val myMarkerTitle = selfMarkerTitle()
        
        myMarker = Marker(mapView).apply {
            setInfoWindow(null) // Participant details use the closeable app card.
            position = GeoPoint(myLat, myLon)
            title = myMarkerTitle
            snippet = buildMarkerSnippet(getString(R.string.map_my_location), myTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createParticipantMarkerDrawable(
                iconRes = myMarkerIcon,
                accentColor = if (myRole == ROLE_PARENT) selfParentAccentColor() else childAccentColor(),
                title = myMarkerTitle,
                avatarValue = familyPresentationByDevice[myId]?.avatarValue,
                stale = isStale(myTimestamp)
            )
        }
        mapView.overlays.add(myMarker)
        myAccuracyOverlay = addAccuracyOverlay(
            center = GeoPoint(myLat, myLon),
            accuracy = myAccuracy,
            accentColor = if (myRole == ROLE_PARENT) selfParentAccentColor() else childAccentColor()
        )
        
        // Use the opposite role icon for the linked device.
        val otherMarkerIcon = resolveOtherMarkerIconRes()
        
        val otherMarkerTitle = otherMarkerTitle()
        
        otherMarker = Marker(mapView).apply {
            setInfoWindow(null) // Participant details use the closeable app card.
            position = GeoPoint(otherLat, otherLon)
            title = otherMarkerTitle
            snippet = buildMarkerSnippet(getString(R.string.map_other_location), otherTimestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createParticipantMarkerDrawable(
                iconRes = otherMarkerIcon,
                accentColor = if (myRole == ROLE_PARENT) childAccentColor() else participantAccentColor(otherId, ROLE_PARENT),
                title = otherMarkerTitle,
                avatarValue = familyPresentationByDevice[linkedPersonDeviceId()]?.avatarValue,
                stale = isStale(otherTimestamp)
            )
            setOnMarkerClickListener { marker, _ ->
                leaveHistory()
                expandStatsCard()
                bindSelectedPersonIdentity()
                binding.statsCard.visibility = if (isStatsCardCollapsed) View.GONE else View.VISIBLE
                mapView.controller.animateTo(marker.position)
                true
            }
        }
        mapView.overlays.add(otherMarker)
        otherAccuracyOverlay = addAccuracyOverlay(
            center = GeoPoint(otherLat, otherLon),
            accuracy = linkedLocation.accuracy,
            accentColor = if (myRole == ROLE_PARENT) childAccentColor() else participantAccentColor(otherId, ROLE_PARENT)
        )
        
        // Draw a line between both live markers.
        connectionLine = Polyline().apply {
            addPoint(GeoPoint(myLat, myLon))
            addPoint(GeoPoint(otherLat, otherLon))
            outlinePaint.color = connectionAccentColor()
            outlinePaint.strokeWidth = 8f
        }
        if (mapOptions.distances()) mapView.overlays.add(connectionLine)
        
        // Keep both markers in view unless the user disabled auto-fit.
        if (autoFitEnabled && !isViewingHistory) {

            centerMapOnBothLocations(myLat, myLon, otherLat, otherLon)

        }
        
        // Show distance and ETA for the linked device.
        val etaInfo = parentLocationRepository.calculateETA(
            otherLat, otherLon,
            myLat, myLon,
            otherSpeed
        )
        bindLinkedStats(
            linkedLocation = linkedLocation,
            distanceMeters = etaInfo.distanceMeters,
            etaText = formatEtaStatus(etaInfo)
        )
        
        mapView.invalidate()
    }
    
    private fun displaySingleLocation(
        lat: Double,
        lon: Double,
        title: String,
        iconRes: Int,
        timestamp: Long?,
        snippetLabel: String,
        accuracy: Float? = null
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
        clearLiveAccuracyOverlays()
        clearFamilyMarkers()
        
        myMarker = Marker(mapView).apply {
            setInfoWindow(null) // Participant details use the closeable app card.
            position = GeoPoint(lat, lon)
            this.title = title
            snippet = buildMarkerSnippet(snippetLabel, timestamp)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createParticipantMarkerDrawable(
                iconRes = iconRes,
                accentColor = if (iconRes == R.drawable.ic_child_marker) {
                    childAccentColor()
                } else {
                    participantAccentColor(title, ROLE_PARENT, emphasizeSelf = myRole == ROLE_PARENT)
                },
                title = title,
                stale = isStale(timestamp)
            )
        }
        mapView.overlays.add(myMarker)
        myAccuracyOverlay = addAccuracyOverlay(
            center = GeoPoint(lat, lon),
            accuracy = accuracy,
            accentColor = if (iconRes == R.drawable.ic_child_marker) childAccentColor()
            else participantAccentColor(title, ROLE_PARENT, emphasizeSelf = myRole == ROLE_PARENT)
        )
        
        // Center on the only available point.
        if (autoFitEnabled && !isViewingHistory) {

            mapView.controller.setCenter(GeoPoint(lat, lon))

            mapView.controller.setZoom(15.0)

        }
        
        binding.statsCard.visibility = View.GONE
        binding.movementStatusText.text = ""
        binding.pointMetaText.text = ""
        
        mapView.invalidate()
    }

    private fun displayAvailableLocations(
        myLat: Double?,
        myLon: Double?,
        myTimestamp: Long?,
        otherLocation: ParentLocationData?,
        myAccuracy: Float? = null
    ) {
        ownDistancePoint = if (myLat != null && myLon != null)
            ru.example.childwatch.designsystem.FamilyDistance.Point(myLat, myLon, myTimestamp ?: 0L, myAccuracy ?: 0f)
        else null
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
                otherTimestamp = sanitizedOther.timestamp,
                linkedLocation = sanitizedOther,
                myAccuracy = myAccuracy
            )
        } else if (sanitizedMy != null) {
            displaySingleLocation(
                lat = sanitizedMy.latitude,
                lon = sanitizedMy.longitude,
                title = myTitle,
                iconRes = myIcon,
                timestamp = sanitizedMy.timestamp,
                snippetLabel = getString(R.string.map_my_location),
                accuracy = myAccuracy
            )
        } else if (otherLat != null && otherLon != null) {
            displaySingleLocation(
                lat = otherLat,
                lon = otherLon,
                title = otherTitle,
                iconRes = otherIcon,
                timestamp = sanitizedOther.timestamp,
                snippetLabel = getString(R.string.map_other_location),
                accuracy = sanitizedOther.accuracy
            )
            bindLinkedStats(linkedLocation = sanitizedOther)
        }

        updateLiveSubtitle(
            myTimestamp = sanitizedMy?.timestamp,
            otherTimestamp = sanitizedOther?.timestamp
        )
        updateTimelineButtonVisibility()
        binding.errorCard.visibility = View.GONE
    }

    private fun clearFamilyMarkers() {
        familyMarkers.values.forEach { mapView.overlays.remove(it) }
        familyMarkers.clear()
        familyAccuracyOverlays.values.forEach { mapView.overlays.remove(it) }
        familyAccuracyOverlays.clear()
    }

    private fun clearLiveAccuracyOverlays() {
        myAccuracyOverlay?.let { mapView.overlays.remove(it) }
        otherAccuracyOverlay?.let { mapView.overlays.remove(it) }
        myAccuracyOverlay = null
        otherAccuracyOverlay = null
    }

    private fun clearContactAccuracyOverlays() {
        contactAccuracyOverlays.values.forEach { mapView.overlays.remove(it) }
        contactAccuracyOverlays.clear()
    }

    /** Draw the actual GPS uncertainty radius in map metres, not screen dp. */
    private fun addAccuracyOverlay(
        center: GeoPoint,
        accuracy: Float?,
        accentColor: Int
    ): Polygon? {
        val radiusMeters = accuracy
            ?.takeIf { it.isFinite() && it > 0f && it <= 50_000f }
            ?.toDouble()
            ?: return null
        val overlay = Polygon(mapView).apply {
            points = Polygon.pointsAsCircle(center, radiusMeters)
            outlinePaint.color = Color.argb(
                105,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            outlinePaint.strokeWidth = 3f * resources.displayMetrics.density
            fillPaint.color = Color.argb(
                18,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
        }
        mapView.overlays.add(0, overlay)
        return overlay
    }

    private fun buildFamilyMarkerTitle(link: ru.example.childwatch.network.LinkedParentLink): String {
        return listOf(link.parentDisplayName, link.displayName, link.parentDeviceName, link.parentDeviceId)
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun isActiveFamilyLink(link: ru.example.childwatch.network.LinkedParentLink): Boolean {
        return link.isActive?.let { it != 0 } ?: true
    }

    private fun excludedFamilyParentIds(): Set<String> {
        val secureSettings = SecureSettingsManager(this)
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return listOf(
            myId,
            resolvedParentId,
            secureSettings.getDeviceId(),
            prefs.getString("device_id", null),
            prefs.getString("parent_device_id", null),
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun dedupeLinkedParents(
        linkedParents: List<ru.example.childwatch.network.LinkedParentLink>,
        excludedParentIds: Set<String>
    ): List<ru.example.childwatch.network.LinkedParentLink> {
        val deduped = LinkedHashMap<String, ru.example.childwatch.network.LinkedParentLink>()
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
        val childDeviceId = listOf(
            if (myRole == ROLE_PARENT) resolvedOtherId else null,
            if (myRole == ROLE_PARENT) otherId else null,
            if (myRole == ROLE_PARENT) historyTargetId() else null,
            if (myRole == ROLE_CHILD) myId else null
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        if (childDeviceId.isBlank()) return@withContext emptyList()

        val response = runCatching { networkClient.getLinkedParents(childDeviceId) }.getOrNull()
        val linkedParents = response?.body()?.parents.orEmpty()
        if (linkedParents.isEmpty()) return@withContext emptyList()

        val excludedParentIds = buildSet {
            addAll(excludedFamilyParentIds())
            if (myRole == ROLE_CHILD) {
                listOf(resolvedOtherId, otherId)
                    .mapNotNull { it?.trim() }
                    .filter { it.isNotBlank() }
                    .forEach(::add)
            }
        }

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
                    iconId = link.parentMarkerIconId?.takeIf(ContactIcons::isKnown) ?: ContactIcons.PARENT,
                    accuracy = location.accuracy,
                    battery = location.battery,
                    avatarValue = familyPresentationByDevice[link.parentDeviceId]?.avatarValue
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
                setInfoWindow(null) // Participant details use the closeable app card.
                position = GeoPoint(candidate.latitude, candidate.longitude)
                title = candidate.title.ifBlank { candidate.deviceId }
                snippet = buildMarkerSnippet(title, candidate.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = createParticipantMarkerDrawable(
                    iconRes = ContactIcons.resolve(candidate.iconId, ContactRoles.PARENT),
                    accentColor = participantAccentColor(candidate.deviceId, ROLE_PARENT, emphasizeSelf = candidate.deviceId == myId),
                    title = title,
                    avatarValue = candidate.avatarValue,
                    stale = isStale(candidate.timestamp)
                )
                setOnMarkerClickListener { marker, _ ->
                    leaveHistory()
                    expandStatsCard()
                    bindFamilyMarkerCard(candidate)
                    mapView.controller.animateTo(marker.position)
                    true
                }
            }
            familyMarkers[candidate.deviceId] = marker
            mapView.overlays.add(marker)
            addAccuracyOverlay(
                center = marker.position,
                accuracy = candidate.accuracy,
                accentColor = participantAccentColor(candidate.deviceId, ROLE_PARENT, emphasizeSelf = candidate.deviceId == myId)
            )?.let { familyAccuracyOverlays[candidate.deviceId] = it }
            points += marker.position
        }

        if (autoFitEnabled && !isViewingHistory) {
            myMarker?.position?.let { points += it }
            otherMarker?.position?.let { points += it }
            if (points.isNotEmpty()) {
                safeZoomToBoundingBox(points, points.firstOrNull())
            }
        }

        mapView.invalidate()
    }

    private fun bindFamilyMarkerCard(candidate: FamilyMarkerCandidate) {
        selectedPlacesDeviceId = candidate.deviceId
        familyPlacesController.refresh()
        val presentation = familyPresentationByDevice[candidate.deviceId]
        val location = ParentLocationData(
            parentId = candidate.deviceId,
            latitude = candidate.latitude,
            longitude = candidate.longitude,
            accuracy = candidate.accuracy,
            timestamp = candidate.timestamp ?: 0L,
            battery = candidate.battery,
            speed = null,
            bearing = null
        )
        binding.distanceText.text = mapDistance(location, candidate.deviceId == myId)
        binding.etaText.text = "—"
        bindPersonLocationCard(
            displayName = presentation?.displayName ?: candidate.title,
            avatarValue = presentation?.avatarValue ?: candidate.avatarValue,
            location = location
        )
        binding.movementStatusText.text = getString(resolveMovementStatusText(location))
        binding.pointMetaText.text = buildPointMetaText(location)
        binding.statsCard.visibility = if (isStatsCardCollapsed) View.GONE else View.VISIBLE
    }

    private fun showFamilyOverview() {
        val people = familyPresentationByDevice.values.distinctBy { it.memberId }
        val own = currentFamilyLocations.firstOrNull { it.deviceId == myId }?.let {
            ru.example.childwatch.designsystem.FamilyDistance.Point(it.latitude, it.longitude, it.timestamp, it.accuracy ?: 0f)
        } ?: ownDistancePoint
        val entries = people.map { person ->
            val location = currentFamilyLocations.firstOrNull { it.memberId == person.memberId }
            MapMemberStrip.Entry(person.memberId, person.displayName, person.avatarValue,
                location?.timestamp?.let(::formatRelativeTimestamp) ?: getString(R.string.map_location_unavailable),
                location == null || isStale(location.timestamp), if (mapOptions.distances())
                ru.example.childwatch.designsystem.FamilyDistance.text(this, location?.let {
                    ru.example.childwatch.designsystem.FamilyDistance.Point(it.latitude, it.longitude, it.timestamp, it.accuracy ?: 0f)
                }, own, person.memberId == liveSelfMemberId) else null, familySpeedText(person.memberId, location?.timestamp))
        }
        ru.example.childwatch.designsystem.MapFamilyOverview.show(this, entries,
            MapMemberStrip.AvatarBinder { image, avatar, name -> FamilyAvatarRenderer.bind(image, avatar, name) }) { entry ->
            val location = currentFamilyLocations.firstOrNull { it.memberId == entry.id }
            if (location != null) selectFamilyLocation(location) else
                Toast.makeText(this, ru.example.childwatch.designsystem.R.string.cw_distance_person_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun mapDistance(location: ParentLocationData, samePerson: Boolean = false): String =
        ru.example.childwatch.designsystem.FamilyDistance.text(this,
            ru.example.childwatch.designsystem.FamilyDistance.Point(location.latitude, location.longitude,
                location.timestamp, location.accuracy), ownDistancePoint, samePerson)

    private fun bindLinkedStats(
        linkedLocation: ParentLocationData,
        distanceMeters: Float? = null,
        etaText: String? = null
    ) {
        selectedPlacesDeviceId = linkedLocation.parentId
        binding.distanceText.text = mapDistance(linkedLocation, linkedLocation.parentId == myId)
        binding.etaText.text = if (ru.example.childwatch.designsystem.FamilyDistance.canMeasure(
            ru.example.childwatch.designsystem.FamilyDistance.Point(linkedLocation.latitude,
                linkedLocation.longitude, linkedLocation.timestamp, linkedLocation.accuracy), ownDistancePoint))
            etaText ?: "—" else "—"
        binding.movementStatusText.text = getString(resolveMovementStatusText(linkedLocation))
        binding.pointMetaText.text = buildPointMetaText(linkedLocation)
        val presentation = familyPresentationByDevice[linkedPersonDeviceId()]
        familyPlacesController.refresh()
        bindPersonLocationCard(
            displayName = presentation?.displayName ?: otherMarkerTitle(),
            avatarValue = presentation?.avatarValue,
            location = linkedLocation
        )
        binding.statsCard.visibility = if (isStatsCardCollapsed) View.GONE else View.VISIBLE
    }

    private fun bindPersonLocationCard(
        displayName: String,
        avatarValue: String?,
        location: ParentLocationData,
        batterySnapshot: ru.example.childwatch.designsystem.BatterySnapshot? = null
    ) {
        binding.mapPersonName.text = displayName.trim().ifBlank { otherMarkerTitle() }
        FamilyAvatarRenderer.bind(binding.mapPersonAvatar, avatarValue)
        binding.mapPersonStatus.text = getString(resolveMovementStatusText(location))

        val battery = batterySnapshot?.label(this) ?: location.battery?.takeIf { it in 0..100 }?.let { "$it%" }
            ?: getString(R.string.map_person_battery_unknown)
        val accuracy = location.accuracy.takeIf { it.isFinite() && it > 0f }?.let {
            getString(R.string.map_person_accuracy_meters, it.toInt().coerceAtLeast(1))
        } ?: getString(R.string.map_person_accuracy_unknown)
        val updated = normalizeTimestampMillis(location.timestamp)?.let(::formatRelativeTimestamp)
            ?: getString(R.string.map_location_unavailable)

        binding.mapPersonSummaryText.text = getString(
            R.string.map_person_summary,
            battery,
            accuracy,
            updated
        )
        binding.mapPersonCoordinatesText.text = if (
            isValidCoordinate(location.latitude, location.longitude)
        ) {
            getString(
                R.string.map_person_coordinates,
                location.latitude,
                location.longitude
            )
        } else {
            getString(R.string.map_person_coordinates_waiting)
        }
        loadPersonAddress(location)
    }

    private var personAddressJob: Job? = null
    private var personAddressLocationKey: String? = null

    private fun loadPersonAddress(location: ParentLocationData) {
        if (!isValidCoordinate(location.latitude, location.longitude)) {
            personAddressJob?.cancel()
            personAddressLocationKey = null
            binding.mapPersonAddressText.text = getString(R.string.map_person_address_unavailable)
            return
        }

        val key = String.format(
            Locale.US,
            "%.5f,%.5f",
            location.latitude,
            location.longitude
        )
        if (personAddressLocationKey == key && personAddressJob?.isActive == true) return
        personAddressLocationKey = key
        personAddressJob?.cancel()
        binding.mapPersonAddressText.text = getString(R.string.map_person_address_waiting)
        personAddressJob = lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Geocoder.isPresent()) return@runCatching null
                    Geocoder(this@DualLocationMapActivity, Locale.getDefault())
                        .getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()
                        ?.getAddressLine(0)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }.getOrNull()
            }
            if (personAddressLocationKey == key) {
                binding.mapPersonAddressText.text = address
                    ?: getString(R.string.map_person_address_unavailable)
            }
        }
    }

    private fun resolveMovementStatusText(linkedLocation: ParentLocationData): Int {
        val timestamp = normalizeTimestampMillis(linkedLocation.timestamp)
            ?: return R.string.map_stats_status_unknown
        val now = System.currentTimeMillis()
        if (now - timestamp > 45_000L) return R.string.map_stats_motion_stale

        // Match the current phone, not merely the selected person: an old phone's
        // track must never supply movement for its replacement.
        val motionId = familyMotionDevices.entries.firstOrNull {
            it.value == linkedLocation.parentId
        }?.key
        val fixes = motionId?.let { familyTrailFixes[it] }.orEmpty()
            .filter { it.timestampMs <= timestamp }.toMutableList()
        val currentFix = MapRouteSegments.Fix(
            linkedLocation.latitude, linkedLocation.longitude, timestamp,
            linkedLocation.accuracy, linkedLocation.speedMps, linkedLocation.speedAccuracyMps
        )
        // Family cards omit sensor fields; retain the same fix's original quality.
        val matchingFix = fixes.any {
            it.timestampMs == timestamp && it.latitude == linkedLocation.latitude &&
                it.longitude == linkedLocation.longitude && it.accuracyMeters == linkedLocation.accuracy
        }
        if (!matchingFix) {
            fixes.removeAll { it.timestampMs == timestamp }
            fixes += currentFix
        }
        val speedKmh = ru.example.childwatch.designsystem.MapSpeed.latest(
            fixes.sortedBy { it.timestampMs }, timestamp, now
        ) ?: return R.string.map_stats_status_unknown
        return when {
            speedKmh >= 6f * 3.6 -> R.string.map_stats_status_transit
            speedKmh >= MOVING_SPEED_THRESHOLD_MPS * 3.6 -> R.string.map_stats_status_moving
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
                if (linkedLocation.timestamp.let { isStale(it) }) {
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
        if (showAllContacts || reason == MapDiagnosticReason.NONE) {
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

    private data class ContactPoint(
        val contact: Child,
        val location: ParentLocationData
    )

    private fun cacheKeyContact(deviceId: String): String = "$mapNamespace::map_cache_contact_$deviceId"

    private fun loadAllContactsLocations() {
        loadLocationsJob = lifecycleScope.launch {
            val familyId = liveFamilyId ?: runCatching {
                val directory = familyDirectoryRepository.load()
                val resolver = ru.example.childwatch.profile.ParentEffectiveContextResolver(this@DualLocationMapActivity)
                val preferred = resolver.resolveFamilyId()
                directory.takeIf {
                    it.source == ParentFamilyDirectorySource.SERVER
                }?.directory?.also { liveSelfMemberId = it.selfMemberId }?.family?.id ?: networkClient.getAuthenticatedIdentity()
                    .takeIf { it.isSuccessful }
                    ?.body()
                    ?.memberships
                    ?.let { memberships ->
                        if (!preferred.isNullOrBlank()) memberships.firstOrNull { it.familyId == preferred }
                        else memberships.singleOrNull()
                    }
                    ?.also { liveSelfMemberId = it.memberId }
                    ?.familyId
            }.getOrNull()?.also { liveFamilyId = it }
            if (familyId.isNullOrBlank()) {
                loadLegacyContactsLocations()
                return@launch
            }
            val locations = networkClient.getFamilyLiveLocations(familyId)
            binding.loadingIndicator.visibility = View.GONE
            if (locations == null) {
                loadLegacyContactsLocations()
                return@launch
            }
            withContext(Dispatchers.IO) { preloadMapPhotos(locations.map { it.avatarKey }) }
            if (isFinishing || isDestroyed) return@launch
            displayFamilyLiveLocations(locations)
            binding.errorCard.visibility = if (locations.isEmpty()) View.VISIBLE else View.GONE
            if (locations.isEmpty()) binding.errorText.text = getString(R.string.map_family_no_locations)
        }
    }

    private suspend fun preloadMapPhotos(values: Collection<String?>) = kotlinx.coroutines.coroutineScope {
        values.filter { FamilyAvatarRenderer.isUploadedValue(it) }.distinct().map { value ->
            async(Dispatchers.IO) { FamilyAvatarRenderer.preloadMapPhoto(applicationContext, value) }
        }.forEach { it.await() }
    }

    private fun displayFamilyLiveLocations(locations: List<FamilyLiveLocation>) {
        if (!isMapReady || isFinishing || isDestroyed) return
        val visible = locations.filter { isValidCoordinate(it.latitude, it.longitude) }
        val now = System.currentTimeMillis()
        visible.forEach { location ->
            val motionId = location.memberId
            val previousDevice = familyMotionDevices.put(motionId, location.deviceId)
            if (previousDevice != null && previousDevice != location.deviceId) {
                familyTrailJobs.remove(motionId)?.cancel()
                familyTrailFixes.remove(motionId)
                familyTrailAllowed.remove(motionId)
                familyTrailRequestedAt.remove(motionId)
            }
            val timestamp = normalizeTimestampMillis(location.timestamp) ?: return@forEach
            if (now - timestamp !in 0..120_000L) return@forEach
            val track = familyTrailFixes.getOrPut(location.memberId) { mutableListOf() }
            if (track.lastOrNull()?.timestampMs != timestamp) {
                track += MapRouteSegments.Fix(location.latitude, location.longitude,
                    timestamp, location.accuracy ?: 0f, location.speedMps, location.speedAccuracyMps)
            }
            track.removeAll { now - it.timestampMs > 30 * 60_000L }
        }
        val hasNewMember = visible.any { candidate ->
            currentFamilyLocations.none { it.memberId == candidate.memberId }
        }
        currentFamilyLocations = visible
        renderFamilyMotion()
        if (mapOptions.speeds() || (mapOptions.trails() && mapOptions.allTrails())) visible.forEach { loadSelectedFamilyTrail(it.memberId) }
        val activeIds = visible.mapTo(mutableSetOf()) { it.memberId }
        contactAccuracyOverlays.keys.filter { it !in activeIds }.forEach { memberId ->
            contactAccuracyOverlays.remove(memberId)?.let(mapView.overlays::remove)
        }
        contactMarkers.values.forEach(mapView.overlays::remove)
        contactMarkers.clear()
        myMarker?.let(mapView.overlays::remove)
        otherMarker?.let(mapView.overlays::remove)
        myMarker = null
        otherMarker = null
        connectionLine?.let(mapView.overlays::remove)
        connectionLine = null
        clearLiveAccuracyOverlays()
        clearFamilyMarkers()
        lastMyPoint = null
        val points = mutableListOf<GeoPoint>()
        visible.forEach { location ->
            val point = GeoPoint(location.latitude, location.longitude)
            points += point
            val accent = participantAccentColor(location.deviceId, location.role,
                emphasizeSelf = location.memberId == liveSelfMemberId)
            contactAccuracyOverlays.remove(location.memberId)?.let(mapView.overlays::remove)
            addAccuracyOverlay(point, location.accuracy, accent)?.let {
                contactAccuracyOverlays[location.memberId] = it
            }
            if (location.memberId == liveSelfMemberId) lastMyPoint = point
        }
        familyLivePoints = points
        drawSelectedFamilyTrail()
        val selected = selectedFamilyMemberId?.let { selectedId ->
            visible.firstOrNull { it.memberId == selectedId }
        }
        if (selected != null) selectFamilyLocation(selected, centerMap = false)
        else binding.statsCard.visibility = View.GONE
        if (points.isNotEmpty() && (!familyInitiallyCentered || (autoFitEnabled && hasNewMember))) {
            safeZoomToBoundingBox(points, points.firstOrNull())
            familyInitiallyCentered = true
        }
        placeFamilyMarkers(visible)
        refreshMapFollow()
        mapView.invalidate()
    }

    private fun placeFamilyMarkers(locations: List<FamilyLiveLocation>) {
        if (mapFollow.active() && !locations.any { mapFollow.matches(followScope(), it.memberId, it.deviceId) }) stopMapFollow()
        if (mapFollow.active()) refreshMapFollow()
        val motionScope = networkClient.resolveConfiguredServerUrl() + "\n" + (ru.example.childwatch.profile.ParentEffectiveContextResolver(this).resolveFamilyId() ?: liveFamilyId).orEmpty()
        val motionEnabled = mapOptions.motion() && !isViewingHistory &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        markerMotion.retain(locations.mapTo(mutableSetOf()) { it.memberId })
        contactMarkers.values.forEach(mapView.overlays::remove)
        contactMarkers.clear()
        val icons = locations.map { location ->
            val accent = participantAccentColor(location.deviceId, location.role,
                emphasizeSelf = location.memberId == liveSelfMemberId)
            val portrait = FamilyAvatarRenderer.drawable(this, location.avatarKey)
                ?: ContextCompat.getDrawable(this, ContactIcons.resolve(0, location.role))?.mutate()?.also {
                    DrawableCompat.setTint(it, accent)
                }
            MapAvatarIcon.create(this, portrait, accent, isStale(location.timestamp), familyMarkerSpeedText(location.memberId, location.timestamp))
        }
        val truePoints = locations.map { GeoPoint(it.latitude, it.longitude) }
        locations.forEachIndexed { index, location ->
            val marker = Marker(mapView).apply {
                setInfoWindow(null) // Participant details use the closeable app card.
                position = truePoints[index]
                title = location.displayName
                snippet = buildMarkerSnippet(getString(R.string.map_location_label), location.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = icons[index]
                setOnMarkerClickListener { _, _ ->
                    selectFamilyLocation(location)
                    true
                }
            }
            contactMarkers[location.memberId] = marker
            mapView.overlays.add(marker)
            markerMotion.update(motionScope, location.memberId,
                ru.example.childwatch.designsystem.MapMarkerMotionPolicy.Fix(
                    location.deviceId, location.latitude, location.longitude,
                    normalizeTimestampMillis(location.timestamp) ?: 0L, (location.accuracy ?: 0f).toDouble()),
                motionEnabled,
                { latitude, longitude ->
                    marker.position = GeoPoint(latitude, longitude)
                    followMarkerPosition(location.memberId, location.deviceId, latitude, longitude)
                },
                { mapView.invalidate() })
        }
        selectedFamilyMemberId?.let { contactMarkers[it] }?.let { marker ->
            mapView.overlays.remove(marker)
            mapView.overlays.add(marker)
        }
    }

    private fun selectFamilyLocation(location: FamilyLiveLocation, centerMap: Boolean = true) {
        if (centerMap) { leaveHistory(); expandStatsCard() }
        if (selectedFamilyMemberId != location.memberId) { historyRequestToken++; clearHistoryCursor() }
        if (mapFollow.active() && !mapFollow.matches(followScope(), location.memberId, location.deviceId)) stopMapFollow()
        selectedFamilyMemberId = location.memberId
        binding.historyButton.visibility = View.VISIBLE
        updateTimelineButtonVisibility()
        contactMarkers[selectedFamilyMemberId]?.let { marker -> mapView.overlays.remove(marker); mapView.overlays.add(marker) }
        selectedPlacesDeviceId = location.deviceId
        drawSelectedFamilyTrail()
        loadSelectedFamilyTrail(location.memberId)
        val point = GeoPoint(location.latitude, location.longitude)
        if (centerMap) {
            autoFitEnabled = false
            updateAutoFitUi()
            mapView.controller.setCenter(point)
            mapView.invalidate()
        }
        val selected = ParentLocationData(
            parentId = location.deviceId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy ?: 0f,
            timestamp = location.timestamp,
            battery = null,
            speed = null,
            bearing = null
        )
        val myPoint = currentFamilyLocations.firstOrNull { it.deviceId == myId }
        binding.distanceText.text = ru.example.childwatch.designsystem.FamilyDistance.text(this,
            ru.example.childwatch.designsystem.FamilyDistance.Point(location.latitude, location.longitude,
                location.timestamp, location.accuracy ?: 0f),
            myPoint?.let { ru.example.childwatch.designsystem.FamilyDistance.Point(it.latitude, it.longitude,
                it.timestamp, it.accuracy ?: 0f) } ?: ownDistancePoint,
            location.memberId == liveSelfMemberId || location.deviceId == myId)
        binding.etaText.text = "—"
        bindPersonLocationCard(location.displayName, location.avatarKey, selected, location.batterySnapshot)
        familyPlacesController.refresh()
        binding.movementStatusText.text = familySpeedText(location.memberId, location.timestamp).orEmpty()
        binding.pointMetaText.text = buildPointMetaText(selected)
        binding.statsCard.visibility = if (isStatsCardCollapsed) View.GONE else View.VISIBLE
        renderFamilyMotion()
    }


    private fun familySpeedValue(id: String, time: Long?): Pair<Double?, Boolean> {
        val timestamp = time?.let(::normalizeTimestampMillis) ?: 0L
        val now = System.currentTimeMillis()
        if (timestamp <= 0L || timestamp > now || now - timestamp > 45_000L) return null to false
        val point = currentFamilyLocations.firstOrNull { it.memberId == id &&
            it.timestamp?.let(::normalizeTimestampMillis) == timestamp }
        val direct = point?.let {
            ru.example.childwatch.designsystem.MapSpeed.measured(
                ru.example.childwatch.designsystem.MapRouteSegments.Fix(it.latitude, it.longitude, timestamp,
                    it.accuracy ?: 0f, it.speedMps, it.speedAccuracyMps))
        }
        if (direct != null) return direct to true
        val fixes = familyTrailFixes[id].orEmpty().sortedBy { it.timestampMs }
        val speed = ru.example.childwatch.designsystem.MapSpeed.latest(fixes, timestamp, now)
        val measured = fixes.lastOrNull()?.takeIf { it.timestampMs == timestamp }?.let { ru.example.childwatch.designsystem.MapSpeed.measured(it) }
        // An overlapping GPS uncertainty area is not a measured stationary speed.
        return (if (speed == 0.0 && measured == null) null else speed) to (measured != null)
    }

    private fun familyMarkerSpeedText(id: String, time: Long?): String? {
        if (!mapOptions.speeds()) return null
        val (speed, measured) = familySpeedValue(id, time)
        return speed?.let {
            if (measured) getString(ru.example.childwatch.designsystem.R.string.cw_map_speed_measured, Math.round(it))
            else ru.example.childwatch.designsystem.MapSpeed.text(this, it)
        }
    }

    private fun familySpeedText(id: String, time: Long?): String? {
        if (!mapOptions.speeds()) return null
        val timestamp = time?.let(::normalizeTimestampMillis) ?: 0L
        if (timestamp > 0L && System.currentTimeMillis() - timestamp > 45_000L)
            return getString(ru.example.childwatch.designsystem.R.string.cw_map_speed_stale)
        return familyMarkerSpeedText(id, time) ?: getString(ru.example.childwatch.designsystem.R.string.cw_map_speed_unknown)
    }

    private fun renderFamilyMotion() {
        mapView.removeCallbacks(repositionFamilyMarkers)
        mapView.post(repositionFamilyMarkers)
        MapMemberStrip.render(
            binding.familyStripContent,
            currentFamilyLocations.map { location ->
                MapMemberStrip.Entry(
                    location.memberId,
                    location.displayName.ifBlank { getString(R.string.map_title_other_device) },
                    location.avatarKey,
                    formatRelativeTimestamp(location.timestamp),
                    isStale(location.timestamp),
                    if (mapOptions.distances()) ru.example.childwatch.designsystem.FamilyDistance.compactText(this,
                        ru.example.childwatch.designsystem.FamilyDistance.Point(location.latitude, location.longitude, location.timestamp, location.accuracy ?: 0f),
                        currentFamilyLocations.firstOrNull { it.deviceId == myId }?.let {
                            ru.example.childwatch.designsystem.FamilyDistance.Point(it.latitude, it.longitude, it.timestamp, it.accuracy ?: 0f)
                        } ?: ownDistancePoint, location.memberId == liveSelfMemberId || location.deviceId == myId) else null,
                    familySpeedText(location.memberId, location.timestamp)
                )
            },
            MapMemberStrip.AvatarBinder { view, avatar, name ->
                FamilyAvatarRenderer.bind(view, avatar, name)
            },
            true,
            selectedFamilyMemberId
        ) { entry ->
            currentFamilyLocations.firstOrNull { it.memberId == entry.id }?.let(::selectFamilyLocation)
        }
        binding.familyStrip.visibility = if (mapOptions.familyVisible() && currentFamilyLocations.isNotEmpty()) View.VISIBLE else View.GONE
        currentFamilyLocations.firstOrNull { it.memberId == selectedFamilyMemberId }?.let { binding.movementStatusText.text = familySpeedText(it.memberId, it.timestamp).orEmpty() }
    }

    private fun loadSelectedFamilyTrail(memberId: String) {
        val familyId = liveFamilyId ?: return
        if (!mapOptions.trails() && !mapOptions.speeds()) return
        val now = System.currentTimeMillis()
        if (now - (familyTrailRequestedAt[memberId] ?: 0L) < 30_000L) return
        familyTrailRequestedAt[memberId] = now
        if (familyTrailJobs[memberId]?.isActive == true) return
        val motionDevice = familyMotionDevices[memberId]
        val trailServer = networkClient.resolveConfiguredServerUrl()
        familyTrailJobs[memberId] = lifecycleScope.launch {
            val history = networkClient.getFamilyTrail(familyId, memberId, motionDevice)
            if (motionDevice != familyMotionDevices[memberId]) return@launch
            if (trailServer != networkClient.resolveConfiguredServerUrl() || (familyId != (ru.example.childwatch.profile.ParentEffectiveContextResolver(this@DualLocationMapActivity).resolveFamilyId()?.takeIf { it.isNotBlank() } ?: liveFamilyId))) return@launch
            if (history == null) {
                familyTrailAllowed.remove(memberId); familyTrailFixes.remove(memberId); renderFamilyMotion(); drawSelectedFamilyTrail(); return@launch
            }
            familyTrailAllowed.add(memberId)
            if (isFinishing || isDestroyed) return@launch
            val live = familyTrailFixes[memberId].orEmpty()
            val fixes = (history.mapNotNull { point ->
                normalizeTimestampMillis(point.timestamp)?.let { timestamp ->
                    MapRouteSegments.Fix(point.latitude, point.longitude, timestamp, point.accuracy, point.speedMps, point.speedAccuracyMps)
                }
            } + live).filter { now - it.timestampMs in 0..30 * 60_000L }
                .distinctBy { Triple(it.timestampMs, it.latitude, it.longitude) }
                .sortedBy { it.timestampMs }
                .takeLast(600)
            familyTrailFixes[memberId] = fixes.toMutableList()
            renderFamilyMotion()
            if (mapOptions.allTrails() || selectedFamilyMemberId == memberId) drawSelectedFamilyTrail()
        }
    }

    private fun applySpeedColors(line: Polyline, segment: List<MapRouteSegments.Fix>) {
        val paint = Paint(line.outlinePaint)
        val speeds = segment.indices.map { ru.example.childwatch.designsystem.MapSpeed.estimate(segment, it) }
        line.outlinePaintLists.add(object : org.osmdroid.views.overlay.PaintList {
            override fun getPaint(): Paint? = null
            override fun getPaint(index: Int, x0: Float, y0: Float, x1: Float, y1: Float): Paint {
                val first = speeds[index.coerceIn(0, speeds.lastIndex)]
                val second = speeds[(index + 1).coerceIn(0, speeds.lastIndex)]
                paint.shader = null
                paint.color = ru.example.childwatch.designsystem.MapSpeed.color(this@DualLocationMapActivity, null)
                if (first != null && second != null) {
                    val a = ru.example.childwatch.designsystem.MapSpeed.color(this@DualLocationMapActivity, first)
                    val b = ru.example.childwatch.designsystem.MapSpeed.color(this@DualLocationMapActivity, second)
                    paint.color = a
                    if (a != b && (x0 != x1 || y0 != y1)) paint.shader = android.graphics.LinearGradient(x0, y0, x1, y1, a, b, android.graphics.Shader.TileMode.CLAMP)
                }
                return paint
            }
        })
    }

    private fun routeCasing(line: Polyline) = Polyline(mapView).apply {
        setPoints(line.actualPoints)
        outlinePaint.color = Color.WHITE
        outlinePaint.strokeWidth = 8f * resources.displayMetrics.density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
    }

    private fun drawSelectedFamilyTrail() {
        ensureHistoryContext()
        if (isViewingHistory) return
        familyTrailLines.forEach(mapView.overlays::remove)
        familyTrailLines.clear()
        if (!mapOptions.trails()) { mapView.invalidate(); return }
        val targets = if (mapOptions.allTrails()) currentFamilyLocations else
            currentFamilyLocations.filter { it.memberId == selectedFamilyMemberId }
        targets.forEach { location ->
        val memberId = location.memberId
        if (memberId !in familyTrailAllowed) return@forEach
        val fixes = familyTrailFixes[memberId] ?: return@forEach
        val color = participantAccentColor(location.deviceId, location.role,
            emphasizeSelf = memberId == liveSelfMemberId)
        MapRouteSegments.split(fixes.sortedBy { it.timestampMs }).filter { it.size >= 2 }.forEachIndexed { index, segment ->
            val line = Polyline(mapView).apply {
                id = "family_live_trail_${memberId}_$index"
                setPoints((if (mapOptions.speedColors()) segment else MapRouteSegments.simplify(segment)).map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = color
                outlinePaint.alpha = 255
                outlinePaint.strokeWidth = (if (memberId == selectedFamilyMemberId) 5f else 3.5f) * resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            if (mapOptions.speedColors()) applySpeedColors(line, segment)
            val casing = routeCasing(line)
            familyTrailLines += casing
            familyTrailLines += line
            mapView.overlays.add(0, line)
            mapView.overlays.add(0, casing)
        }
        }
        mapView.invalidate()
    }

    private fun loadLegacyContactsLocations() {
        markerMotion.clear()
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
                    myLocationAccuracy = myLocation.accuracy
                    saveCachedLocation(
                        cacheKeyMy(),
                        myLocation.latitude,
                        myLocation.longitude,
                        myLocation.time,
                        if (myLocation.hasSpeed()) myLocation.speed else null,
                        myLocation.accuracy
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
                            resolved.speed,
                            resolved.accuracy
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
            clearLiveAccuracyOverlays()
            clearFamilyMarkers()
            contactMarkers.values.forEach { mapView.overlays.remove(it) }
            contactMarkers.clear()
            currentFamilyLocations = emptyList()
            binding.familyStrip.visibility = View.GONE
            clearContactAccuracyOverlays()

            val geoPoints = mutableListOf<GeoPoint>()

            // My location (if available)
            val myLat = myLatitude
            val myLon = myLongitude
            if (myLat != null && myLon != null && isValidCoordinate(myLat, myLon)) {
                val myPoint = GeoPoint(myLat, myLon)
                lastMyPoint = myPoint
                geoPoints.add(myPoint)
                myMarker = Marker(mapView).apply {
                    setInfoWindow(null) // Participant details use the closeable app card.
                    position = myPoint
                    title = selfMarkerTitle()
                    snippet = buildMarkerSnippet(getString(R.string.map_my_location), null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = createParticipantMarkerDrawable(
                        iconRes = ContactIcons.resolve(0, myRole),
                        accentColor = if (myRole == ROLE_PARENT) selfParentAccentColor() else childAccentColor(),
                        title = selfMarkerTitle()
                    )
                }
                mapView.overlays.add(myMarker)
                myAccuracyOverlay = addAccuracyOverlay(
                    center = myPoint,
                    accuracy = myLocationAccuracy,
                    accentColor = if (myRole == ROLE_PARENT) selfParentAccentColor() else childAccentColor()
                )
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
                    setInfoWindow(null) // Participant details use the closeable app card.
                    position = geo
                    title = contact.alias ?: contact.name
                    snippet = buildMarkerSnippet(getString(R.string.map_location_label), location.timestamp)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = createParticipantMarkerDrawable(
                        iconRes = ContactIcons.resolve(contact.iconId, contact.role),
                        accentColor = participantAccentColor(contact.deviceId, contact.role, emphasizeSelf = contact.deviceId == myId),
                        title = contact.alias ?: contact.name,
                        stale = isStale(location.timestamp)
                    )
                }
                mapView.overlays.add(marker)
                contactMarkers[contact.deviceId] = marker
                addAccuracyOverlay(
                    center = geo,
                    accuracy = location.accuracy,
                    accentColor = participantAccentColor(
                        contact.deviceId,
                        contact.role,
                        emphasizeSelf = contact.deviceId == myId
                    )
                )?.let { contactAccuracyOverlays[contact.deviceId] = it }
            }

            binding.statsCard.visibility = View.GONE

            if (autoFitEnabled && geoPoints.isNotEmpty() && !familyInitiallyCentered) {
                safeZoomToBoundingBox(geoPoints, geoPoints.firstOrNull())
                familyInitiallyCentered = true
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
        ROLE_PARENT -> participantNameResolver.resolveOwnParentDisplayName()
        ROLE_CHILD -> getString(R.string.map_title_me_child)
        else -> getString(R.string.map_title_me)
    }

    private fun otherMarkerTitle(): String = when (myRole) {
        ROLE_PARENT -> participantNameResolver.resolveFocusedChildDisplayName(
            historyTargetId() ?: resolvedOtherId.ifBlank { otherId }
        )
        ROLE_CHILD -> getString(R.string.map_title_parent)
        else -> getString(R.string.map_title_other_device)
    }

    private fun formatEtaDistance(distanceMeters: Float): String {
        return if (distanceMeters < 1000f) {
            getString(R.string.map_distance_meters_clean, distanceMeters.toInt())
        } else {
            getString(R.string.map_distance_km_clean, distanceMeters / 1000f)
        }
    }

    private fun formatEtaStatus(etaInfo: ru.example.childwatch.database.repository.ETAInfo): String {
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

    private fun otherLocationUnavailableMessage(): String = when (myRole) {
        ROLE_PARENT -> getString(R.string.map_child_location_unavailable)
        ROLE_CHILD -> getString(R.string.map_parent_location_unavailable)
        else -> getString(R.string.map_location_unavailable)
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
        return if (showAllContacts || isLiveModeActive()) {
            LIVE_MODE_REFRESH_INTERVAL
        } else {
            AUTO_REFRESH_INTERVAL
        }
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

        val cadenceText = if (active) {
            getString(R.string.map_live_mode_active)
        } else {
            getString(R.string.map_updated_adaptive)
        }
        binding.updateCadenceText.text = cadenceText
    }

    private fun showPlacesMenu() {
        val actions = arrayOf(
            getString(R.string.map_places_add_here),
            getString(R.string.map_places_manage)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_places_menu_title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showAddPlaceDialog()
                    1 -> showManagePlacesDialog()
                }
            }
            .show()
    }

    private fun showAddPlaceDialog() {
        val point = lastOtherPoint
        val targetId = historyTargetId().orEmpty()
        if (point == null || targetId.isBlank()) {
            Toast.makeText(this, getString(R.string.map_places_requires_child_point), Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.map_places_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(getString(R.string.map_places_default_name))
            setSelection(text?.length ?: 0)
            setPadding(48, 32, 48, 16)
        }
        var selectedRadiusIndex = 1
        val radiusLabels = PLACE_RADIUS_OPTIONS.map { getString(R.string.map_places_radius_meters, it) }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_places_add_title)
            .setView(input)
            .setSingleChoiceItems(radiusLabels, selectedRadiusIndex) { _, which ->
                selectedRadiusIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.map_places_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifBlank {
                    getString(R.string.map_places_default_name)
                }
                val radius = PLACE_RADIUS_OPTIONS[selectedRadiusIndex].toFloat()
                lifecycleScope.launch {
                    database.geofenceDao().insert(
                        Geofence(
                            name = name,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            radius = radius,
                            deviceId = targetId,
                            isActive = true
                        )
                    )
                    Toast.makeText(
                        this@DualLocationMapActivity,
                        getString(R.string.map_places_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showManagePlacesDialog() {
        if (placesForCurrentChild.isEmpty()) {
            Toast.makeText(this, getString(R.string.map_places_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val items = placesForCurrentChild.map { place ->
            val state = if (place.isActive) {
                getString(R.string.map_places_state_on)
            } else {
                getString(R.string.map_places_state_off)
            }
            "${place.name} • ${place.radius.toInt()} м • $state"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_places_manage)
            .setItems(items) { _, which ->
                val selected = placesForCurrentChild[which]
                showPlaceActions(selected)
            }
            .show()
    }

    private fun showPlaceActions(place: Geofence) {
        val actions = mutableListOf<String>()
        actions += if (place.isActive) {
            getString(R.string.map_places_toggle_off)
        } else {
            getString(R.string.map_places_toggle_on)
        }
        actions += getString(R.string.map_places_delete)

        android.app.AlertDialog.Builder(this)
            .setTitle(place.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch {
                        database.geofenceDao().setActive(place.id, !place.isActive)
                        Toast.makeText(
                            this@DualLocationMapActivity,
                            if (place.isActive) getString(R.string.map_places_toggled_off)
                            else getString(R.string.map_places_toggled_on),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> lifecycleScope.launch {
                        database.geofenceDao().delete(place)
                        Toast.makeText(
                            this@DualLocationMapActivity,
                            getString(R.string.map_places_deleted),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun renderPlaceOverlays(places: List<Geofence>) {
        if (!::mapView.isInitialized) return
        placeOverlays.values.forEach { (polygon, marker) ->
            mapView.overlays.remove(polygon)
            mapView.overlays.remove(marker)
        }
        placeOverlays.clear()

        places.filter { it.isActive }.forEach { place ->
            val center = GeoPoint(place.latitude, place.longitude)
            val polygon = Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(center, place.radius.toDouble())
                outlinePaint.color = Color.parseColor("#1E88E5")
                outlinePaint.strokeWidth = 4f
                fillPaint.color = Color.parseColor("#331E88E5")
            }
            val marker = Marker(mapView).apply {
                position = center
                title = place.name
                snippet = getString(R.string.map_places_radius_meters, place.radius.toInt())
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(0, polygon)
            mapView.overlays.add(marker)
            placeOverlays[place.id] = polygon to marker
        }
        mapView.invalidate()
    }

    private fun currentPlaceStatusText(): String? {
        val point = lastOtherPoint ?: return null
        val matched = placesForCurrentChild
            .asSequence()
            .filter { it.isActive }
            .filter { isPointInsidePlace(point, it) }
            .map { it.name }
            .toList()
        if (matched.isEmpty()) return null
        return getString(R.string.map_places_status_inside, matched.joinToString(", "))
    }

    private fun isPointInsidePlace(point: GeoPoint, place: Geofence): Boolean {
        return calculateDistance(
            point.latitude,
            point.longitude,
            place.latitude,
            place.longitude
        ) <= place.radius
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
        if (showAllContacts) return
        if (limitedMode) {
            binding.toolbar.subtitle = getString(R.string.map_limited_mode_subtitle)
            return
        }

        val myPart = myTimestamp?.let { "${selfMarkerTitle()}: ${formatRelativeTimestamp(it)}" }
        val otherPart = otherTimestamp?.let { "${otherMarkerTitle()}: ${formatRelativeTimestamp(it)}" }
        val livePart = if (isLiveModeActive()) getString(R.string.map_live_mode_active) else null
        val placePart = currentPlaceStatusText()
        binding.toolbar.subtitle = listOfNotNull(myPart, otherPart, placePart, livePart)
            .joinToString(" | ")
            .ifBlank { null }
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
        if (isViewingHistory) return
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
                delay(currentAutoRefreshInterval())
                updateLiveModeUi()
                if (isMapReady && dependenciesReady) {
                    loadLocations()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        setupPickupEntry()
        binding.root.post {
            if (::mapView.isInitialized && !isFinishing && !isDestroyed) {
                mapView.onResume()
            }
        }
        updateLiveModeUi()
        if (isMapReady && dependenciesReady) {
            startAutoRefresh()
        }
    }
    
    override fun onPause() {
        stopMapFollow()
        markerMotion.clear()
        pickupController.pause()
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
        autoRefreshJob?.cancel()
        loadLocationsJob?.cancel()
        familyTrailJobs.values.forEach { it.cancel() }
    }
    
    override fun onDestroy() {
        markerMotion.clear()
        pickupController.dispose()
        leaveHistory()
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
