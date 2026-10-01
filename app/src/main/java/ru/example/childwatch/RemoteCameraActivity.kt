package ru.example.childwatch

import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.graphics.drawable.Drawable
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.example.childwatch.network.WebSocketManager
import android.view.View
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.remote.RemotePhotoCache
import ru.example.childwatch.remote.RemotePhotoItem
import ru.example.childwatch.remote.RemotePhotoErrorMessages
import ru.example.childwatch.utils.SecureSettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.profile.ParentLinkedChildOptionsProvider
import ru.example.childwatch.profile.ParentLinkedChildOption
import ru.example.childwatch.profile.ParentTargetSelector
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.remote.RemotePhotoThumbnailAdapter
import ru.example.childwatch.remote.AuthenticatedMedia
import ru.example.childwatch.remote.SelectChildBottomSheet
import ru.example.childwatch.service.AudioPlaybackService

/**
 * RemoteCameraActivity - Remote photo capture for ParentMonitor
 * 
 * Features:
 * - Send take_photo commands to child device via WebSocket
 * - Display gallery of captured photos
 * - Support front and back camera
 */
class RemoteCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteCameraActivity"
        const val EXTRA_CHILD_ID = "childId"
        const val EXTRA_CHILD_NAME = "childName"
        private const val WEBSOCKET_READY_TIMEOUT_MS = 12_000L

        /**
         * Must stay comfortably longer than the server-side photo request TTL
         * (`PHOTO_REQUEST_TTL_MS` in server/managers/WebSocketManager.js), which
         * covers service start, Camera2 capture, JPEG encoding and transfer.
         * When this was shorter than the server deadline, the server gave up on
         * a request the parent was still legitimately waiting for, which is how
         * a capture could complete and then be discarded by the UI.
         */
        private const val PHOTO_RESPONSE_TIMEOUT_MS = 120_000L

        /**
         * After the UI timeout the request may still complete; keep accepting
         * its result for this long instead of dropping a photo that was already
         * captured and sent.
         */
        private const val PHOTO_LATE_DELIVERY_GRACE_MS = 120_000L
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var childNameText: TextView
    private lateinit var progressIndicator: CircularProgressIndicator

    // Новые элементы видоискателя
    private lateinit var imgLastPhoto: ImageView
    private lateinit var imgViewfinderPlaceholder: ImageView
    private lateinit var tvViewfinderHint: TextView
    private lateinit var pillChildSelector: View
    private lateinit var childAvatarImage: ImageView
    private lateinit var imgOnlineDot: ImageView
    private lateinit var tvTimestamp: TextView
    private lateinit var tvCameraLabel: TextView
    private lateinit var btnTakePhoto: View
    private lateinit var btnRefresh: View
    private lateinit var btnSwitchCamera: View
    private lateinit var rvRecentPhotos: RecyclerView
    private lateinit var tvPhotosEmptyHint: TextView
    private lateinit var thumbnailAdapter: RemotePhotoThumbnailAdapter

    private var personLocationStatus: ru.example.childwatch.location.PersonLocationStatus? = null
    private var childId: String? = null
    private var childName: String? = null
    private val networkClient by lazy { NetworkClient(applicationContext) }
    private val dateFormatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private var photoReceivedListener: ((String, String, Long) -> Unit)? = null
    private var photoErrorListener: ((String, String) -> Unit)? = null
    private var photoQueuedListener: ((String, String, String, Long) -> Unit)? = null
    private var photoRequestReceivedListener: ((String, String, Long) -> Unit)? = null
    private var photoBusyListener: ((String, String, String, String, Long) -> Unit)? = null
    private var connectionTimeoutJob: Job? = null
    private var responseTimeoutJob: Job? = null
    private var pendingScope: String? = null
    private var lateRecoveryJob: Job? = null
    private var waitingForUpload = false
    private var pendingRequestId: String? = null
    private var pendingStartedAt = 0L
    private var connectionAttempt = 0L
    private var galleryJob: Job? = null
    private var galleryRefreshJob: Job? = null
    private var readinessJob: Job? = null
    private var preflightJob: Job? = null
    private var cameraReadiness: org.json.JSONObject? = null
    private var galleryItems: List<RemotePhotoItem> = emptyList()
    private fun photoScope(): String = org.json.JSONArray().put(childId).put(effectiveContextResolver.resolveServerUrl())
        .put(effectiveContextResolver.resolveFamilyId()).put(effectiveContextResolver.resolveOwnParentId()).toString()

    /**
     * Request ids whose UI timeout already fired, mapped to the moment it fired.
     * A capture that finishes after the timeout is still a real photo, so its
     * result is accepted during the grace window instead of being discarded.
     */
    private val timedOutRequestIds = mutableMapOf<String, Pair<Long, String>>()
    private var selectedCameraFacing: String = "back"
    private var resolvedGalleryDeviceId: String? = null
    private lateinit var effectiveContextResolver: ParentEffectiveContextResolver
    private lateinit var participantNameResolver: ParentParticipantNameResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_camera)
        effectiveContextResolver = ParentEffectiveContextResolver(this)
        participantNameResolver = ParentParticipantNameResolver(this)

        // Get child device info from intent
        childId = intent.getStringExtra(EXTRA_CHILD_ID)?.takeIf { it.isNotBlank() }
            ?: effectiveContextResolver.resolveFocusedChildId().takeIf { it.isNotBlank() }
        childName = intent.getStringExtra(EXTRA_CHILD_NAME)
        // Restore an in-screen selection only within the same authenticated family/server.
        val restoredChild = savedInstanceState?.getString("photo_child")
        if (restoredChild != null) {
            val originalChild = childId
            childId = restoredChild
            if (savedInstanceState?.getString("photo_scope") == photoScope()) {
                childName = savedInstanceState?.getString("photo_child_name")
            } else childId = originalChild
        }

        if (!initViews()) return
        personLocationStatus = ru.example.childwatch.location.PersonLocationStatus(
            this, findViewById(R.id.personLocationText), networkClient) { childId }
        runCatching {
            setupToolbar()
            setupButtons()
            if (childId == null) {
                // A missing global selection is recoverable. Keeping this
                // screen open avoids looking like a crash and lets the user
                // select a person from the canonical family directory.
                updateStatus(getString(R.string.remote_camera_missing_child_id))
                btnTakePhoto.isEnabled = false
                btnTakePhoto.alpha = 0.4f
                showPersonSelector()
                return@runCatching
            }
            selectedCameraFacing = savedInstanceState?.getString("photo_camera") ?: "back"
            updateCameraLabel()
            loadPhotos()
            if (savedInstanceState?.getString("photo_scope") == photoScope()) {
                val request = savedInstanceState.getString("photo_request")
                val remaining = (savedInstanceState.getLong("photo_deadline") - System.currentTimeMillis()).coerceIn(0L, PHOTO_RESPONSE_TIMEOUT_MS)
                if (!request.isNullOrBlank() && remaining > 0) {
                    pendingScope = photoScope()
                    pendingRequestId = request
                    pendingStartedAt = android.os.SystemClock.elapsedRealtime() - (PHOTO_RESPONSE_TIMEOUT_MS - remaining)
                    disableButtons(); startResponseTimeout(request)
                }
            }
            ensureWebSocketReady()
        }.onFailure { error ->
            Log.e(TAG, "Remote photo screen startup failed", error)
            updateStatus(getString(R.string.remote_camera_ui_error, error.message ?: "unknown"))
            enableButtons()
            Toast.makeText(
                this,
                getString(R.string.remote_camera_ui_error, error.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun initViews(): Boolean {
        return try {
            toolbar = findViewById(R.id.toolbar)
            statusText = findViewById(R.id.statusText)
            progressIndicator = findViewById(R.id.progressIndicator)

            // Новые элементы видоискателя
            imgLastPhoto = findViewById(R.id.imgLastPhoto)
            imgViewfinderPlaceholder = findViewById(R.id.imgViewfinderPlaceholder)
            tvViewfinderHint = findViewById(R.id.tvViewfinderHint)
            pillChildSelector = findViewById(R.id.pillChildSelector)
            childAvatarImage = findViewById(R.id.imgChildAvatar)
            imgOnlineDot = findViewById(R.id.imgOnlineDot)
            tvTimestamp = findViewById(R.id.tvTimestamp)
            tvCameraLabel = findViewById(R.id.tvCameraLabel)
            btnTakePhoto = findViewById(R.id.btnTakePhoto)
            btnRefresh = findViewById(R.id.btnRefresh)
            btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
            rvRecentPhotos = findViewById(R.id.rvRecentPhotos)
            tvPhotosEmptyHint = findViewById(R.id.tvPhotosEmptyHint)

            // Перенаправить childNameText на видимую пилюлю
            childNameText = findViewById(R.id.tvChildName)

            thumbnailAdapter = RemotePhotoThumbnailAdapter(
                tokenProvider = { networkClient.getAuthToken() },
                onPhotoClick = { photoItem -> openRemotePhotoPreview(photoItem) },
                onPhotoActions = { item -> showPhotoActions(item) }
            )
            rvRecentPhotos.apply {
                layoutManager = object : GridLayoutManager(this@RemoteCameraActivity, 2) {
                    override fun canScrollVertically() = false
                }
                isNestedScrollingEnabled = false
                adapter = thumbnailAdapter
            }

            // Display child name if available
            val resolvedChildName = childName?.takeIf { it.isNotBlank() }
                ?: childId?.let { participantNameResolver.resolveFocusedChildDisplayName(it) }

            childNameText.text = resolvedChildName?.takeIf { it.isNotBlank() && it != childId }
                ?: getString(R.string.chat_partner_child)
            FamilyAvatarRenderer.bind(childAvatarImage, null)
            loadPersonPresentation()
            selectedCameraFacing = "back"
            updateCameraLabel()
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
            Log.e(TAG, "Error initializing views", e)
            Toast.makeText(
                this,
                getString(R.string.remote_camera_ui_error, e.message ?: "unknown"),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            false
        }
    }

    private fun loadPersonPresentation() {
        val targetId = childId ?: return
        val scope = photoScope()
        FamilyAvatarRenderer.bind(childAvatarImage, null, childName)
        lifecycleScope.launch {
            try {
                val person = ru.example.childwatch.profile.ParentFamilyDirectoryRepository(this@RemoteCameraActivity)
                    .load().directory.personByDeviceId(targetId) ?: return@launch
                if (scope != photoScope() || isFinishing || isDestroyed) return@launch
                childName = person.member.displayName.trim().ifBlank { getString(R.string.chat_partner_child) }
                childNameText.text = childName
                FamilyAvatarRenderer.bind(childAvatarImage, person.member.avatarKey, childName)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w(TAG, "Selected profile unavailable", error) }
        }
    }

    private fun setupToolbar() {
        if (supportActionBar == null) {
            setSupportActionBar(toolbar)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupButtons() {
        // Новые кнопки видоискателя
        btnTakePhoto.setOnClickListener {
            if (childId == null) showPersonSelector() else checkAndTakePhoto()
        }

        findViewById<View>(R.id.btnGalleryMore).setOnClickListener { showGallery() }
        tvPhotosEmptyHint.setOnClickListener { loadPhotos() }
        btnRefresh.setOnClickListener { loadPhotos(); refreshCameraReadiness() }
        statusText.tooltipText = getString(R.string.photo_camera_view_details)
        statusText.setOnClickListener { showCameraDetails() }
        (imgViewfinderPlaceholder.parent as View).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePlaceholderVisibility()
        }

        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.cameraToggle)
            .addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    selectedCameraFacing = if (id == R.id.cameraFront) "front" else "back"
                    updateCameraLabel()
                    if (pendingRequestId == null) renderCameraReadiness()
                }
            }
        btnSwitchCamera.setOnClickListener {
            selectedCameraFacing = if (selectedCameraFacing == "back") "front" else "back"
            updateStatus(
                if (selectedCameraFacing == "front") getString(R.string.remote_camera_selected_front)
                else getString(R.string.remote_camera_selected_back)
            )
            updateCameraLabel()
        }

        pillChildSelector.setOnClickListener { showPersonSelector() }
    }

    private fun showPersonSelector() {
        if (pendingRequestId != null) {
            Toast.makeText(this, R.string.family_target_switch_busy_photo, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val selector = ParentTargetSelector(this@RemoteCameraActivity)
            val options = runCatching { selector.load() }.getOrElse {
                Toast.makeText(
                    this@RemoteCameraActivity,
                    R.string.remote_camera_load_error,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (options.isEmpty()) {
                Toast.makeText(
                    this@RemoteCameraActivity,
                    R.string.remote_camera_missing_child_id,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (options.size == 1 && childId == null) {
                val option = options.single()
                selector.select(option)
                applySelectedPerson(option)
                return@launch
            }
            if (options.size == 1) {
                Toast.makeText(
                    this@RemoteCameraActivity,
                    R.string.family_target_only_one,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) {
                return@launch
            }
            val sheet = SelectChildBottomSheet().apply {
                children = options
                currentDeviceId = childId
                onChildSelected = { option ->
                    if (option.deviceId != childId) {
                        selector.select(option)
                        applySelectedPerson(option)
                    }
                }
            }
            runCatching {
                sheet.show(supportFragmentManager, "select_child")
            }.onFailure { error ->
                Log.w(TAG, "Cannot open family member selector", error)
                Toast.makeText(
                    this@RemoteCameraActivity,
                    R.string.remote_camera_load_error,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun applySelectedPerson(option: ParentLinkedChildOption) {
        readinessJob?.cancel(); preflightJob?.cancel(); cameraReadiness = null
        connectionAttempt++
        clearPendingRequest()
        unregisterPhotoListeners()
        galleryJob?.cancel(); galleryRefreshJob?.cancel()
        galleryItems = emptyList(); thumbnailAdapter.submitList(emptyList()); clearViewfinderPhoto()
        lateRecoveryJob?.cancel()
        timedOutRequestIds.clear()
        childId = option.deviceId
        personLocationStatus?.refresh()
        childName = option.displayName
        resolvedGalleryDeviceId = null
        childNameText.text = option.displayName
        FamilyAvatarRenderer.bind(childAvatarImage, option.avatarKey, option.displayName)
        enableButtons()
        updateStatus(getString(R.string.remote_camera_status_connecting))
        loadPhotos()
        refreshCameraReadiness()
        ensureWebSocketReady()
    }

    /**
     * Send take_photo command to child device (uses back camera by default)
     */
    override fun onStart() {
        super.onStart()
        if (::statusText.isInitialized && childId != null) { refreshCameraReadiness(); loadPhotos(announce = false) }
    }

    override fun onStop() {
        readinessJob?.cancel()
        preflightJob?.cancel()
        if (pendingRequestId == null && ::btnTakePhoto.isInitialized) {
            connectionAttempt++
            cancelConnectionTimeout()
            enableButtons()
        }
        super.onStop()
    }

    private fun renderCameraReadiness() {
        if (pendingRequestId == null && btnTakePhoto.isEnabled) {
            updateStatus(ru.example.childwatch.remote.PhotoReadinessSummary
                .describe(this, cameraReadiness, selectedCameraFacing).summary)
        }
    }

    private fun showCameraDetails() {
        val info = ru.example.childwatch.remote.PhotoReadinessSummary.describe(this, cameraReadiness, selectedCameraFacing)
        MaterialAlertDialogBuilder(this).setTitle(R.string.photo_camera_details_title)
            .setMessage(info.details).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun refreshCameraReadiness() {
        val target = childId ?: return
        val scope = photoScope()
        readinessJob?.cancel()
        readinessJob = lifecycleScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val state = try { networkClient.getPhotoReadiness(target) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { null }
                if (scope != photoScope()) return@launch
                cameraReadiness = state
                renderCameraReadiness()
                delay(30_000L)
            }
        }
    }

    private fun checkAndTakePhoto() {
        if (pendingRequestId != null || preflightJob?.isActive == true) return
        val target = childId ?: return
        val scope = photoScope()
        readinessJob?.cancel()
        disableButtons()
        updateStatus(getString(R.string.photo_camera_checking))
        preflightJob = lifecycleScope.launch {
            try {
                val state = try { networkClient.getPhotoReadiness(target) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { null }
                if (scope != photoScope() || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@launch
                cameraReadiness = state
                val info = ru.example.childwatch.remote.PhotoReadinessSummary.describe(this@RemoteCameraActivity, state, selectedCameraFacing)
                if (info.blocked) {
                    enableButtons(); renderCameraReadiness(); showCameraDetails()
                } else takePhoto()
            } finally {
                if (scope == photoScope()) {
                    if (pendingRequestId == null && connectionTimeoutJob?.isActive != true) enableButtons()
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) refreshCameraReadiness()
                }
            }
        }
    }

    private fun takePhoto() {
        Log.d(TAG, "Taking photo for child: $childId")
        val attempt = ++connectionAttempt
        updateStatus(getString(R.string.remote_camera_status_connecting))
        disableButtons()
        startConnectionTimeout()
        ensureWebSocketReady {
            if (attempt == connectionAttempt && !btnTakePhoto.isEnabled && !isFinishing && !isDestroyed) {
                cancelConnectionTimeout()
                sendPhotoRequest()
            }
        }
    }

    private fun ensureWebSocketReady(onReady: () -> Unit = {}) {
        val scope = photoScope()
        val attempt = connectionAttempt
        val targetId = childId ?: return
        val serverUrl = effectiveContextResolver.resolveServerUrl()
            .ifBlank { SecureSettingsManager(this).getServerUrl().trim() }
        if (serverUrl.isBlank()) {
            updateStatus(getString(R.string.server_url_missing))
            Toast.makeText(this, getString(R.string.server_url_missing), Toast.LENGTH_SHORT).show()
            enableButtons()
            return
        }

        WebSocketManager.initialize(this, serverUrl, targetId)
        registerPhotoListeners()

        WebSocketManager.ensureConnected(
            onReady = {
                runOnUiThread {
                    if (scope != photoScope() || attempt != connectionAttempt || isFinishing || isDestroyed) return@runOnUiThread
                    cancelConnectionTimeout()
                    updateStatus(getString(R.string.remote_camera_connected))
                    onReady()
                }
            },
            onError = { error ->
                runOnUiThread {
                    if (scope != photoScope() || attempt != connectionAttempt || isFinishing || isDestroyed) return@runOnUiThread
                    cancelConnectionTimeout()
                    connectionAttempt++
                    updateStatus(getString(R.string.remote_camera_connect_error))
                    Toast.makeText(
                        this,
                        getString(R.string.remote_camera_connect_error_with_reason, error),
                        Toast.LENGTH_SHORT
                    ).show()
                    enableButtons()
                }
            }
        )
    }

    /**
     * A Socket.IO connection can occasionally get stuck between the transport
     * connection and parent registration.  Without a timeout the photo screen
     * keeps its controls disabled forever and the command never reaches the
     * server.  Always return the screen to an actionable state in that case.
     */
    private fun startConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = lifecycleScope.launch {
            delay(WEBSOCKET_READY_TIMEOUT_MS)
            if (pendingRequestId != null || btnTakePhoto.isEnabled) return@launch

            connectionAttempt++
            Log.w(TAG, "Timed out waiting for WebSocket registration before photo request")
            updateStatus(getString(R.string.remote_camera_connect_error))
            Toast.makeText(
                this@RemoteCameraActivity,
                getString(R.string.remote_camera_connect_error_with_reason, "истекло время ожидания"),
                Toast.LENGTH_SHORT
            ).show()
            enableButtons()
        }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    private fun matchesRequest(requestId: String): Boolean = pendingRequestId == requestId && pendingScope == photoScope()

    private fun registerPhotoListeners() {
        if (photoReceivedListener == null) {
            photoReceivedListener = { image, requestId, timestamp -> runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val late = isLateDeliveryFor(requestId)
                if (!matchesRequest(requestId) && !late) return@runOnUiThread
                if (late && pendingRequestId != null && !matchesRequest(requestId)) {
                    clearLateDelivery(requestId)
                    loadPhotos(announce = false)
                    return@runOnUiThread
                }
                clearPendingRequest()
                clearLateDelivery(requestId)
                updateStatus(getString(R.string.remote_camera_photo_received))
                enableButtons()
                AudioPlaybackService.restoreIfNeeded(this)
                openPhotoPreview(image, timestamp)
                scheduleGalleryRefresh()
            } }
            WebSocketManager.addPhotoReceivedListener(photoReceivedListener!!)
        }
        if (photoErrorListener == null) {
            photoErrorListener = { requestId, error -> runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val late = isLateDeliveryFor(requestId)
                if (!matchesRequest(requestId) && !late) return@runOnUiThread
                if (late && pendingRequestId != null && !matchesRequest(requestId)) return@runOnUiThread
                val recoverable = error.contains("timeout", true) || error.contains("disconnected", true)
                if (recoverable) rememberTimedOutRequest(requestId) else clearLateDelivery(requestId)
                clearPendingRequest()
                if (recoverable) recoverLatePhoto(requestId)
                val problem = RemotePhotoErrorMessages.resolve(this, error)
                updateStatus(if (recoverable && waitingForUpload) getString(R.string.remote_photo_upload_deferred) else problem.status)
                AudioPlaybackService.restoreIfNeeded(this)
                if (problem.actionable) MaterialAlertDialogBuilder(this)
                    .setTitle(problem.title).setMessage(problem.message)
                    .setPositiveButton(R.string.remote_camera_recovery_action, null).show()
                else Toast.makeText(this, problem.message, Toast.LENGTH_SHORT).show()
                enableButtons()
            } }
            WebSocketManager.addPhotoErrorListener(photoErrorListener!!)
        }
        if (photoQueuedListener == null) {
            photoQueuedListener = { requestId, _, camera, _ -> runOnUiThread {
                if (isFinishing || isDestroyed || !matchesRequest(requestId)) return@runOnUiThread
                updateStatus(getString(if (camera == "front") R.string.remote_photo_status_queued_front else R.string.remote_photo_status_queued_back))
                startResponseTimeout(requestId)
            } }
            WebSocketManager.addPhotoQueuedListener(photoQueuedListener!!)
        }
        if (photoRequestReceivedListener == null) {
            photoRequestReceivedListener = { requestId, _, _ -> runOnUiThread {
                if (isFinishing || isDestroyed || !matchesRequest(requestId)) return@runOnUiThread
                updateStatus(getString(R.string.remote_photo_status_device_accepted))
                startResponseTimeout(requestId)
            } }
            WebSocketManager.addPhotoRequestReceivedListener(photoRequestReceivedListener!!)
        }
        if (photoBusyListener == null) {
            photoBusyListener = { requestId, _, _, owner, _ -> runOnUiThread {
                if (isFinishing || isDestroyed || !matchesRequest(requestId)) return@runOnUiThread
                clearPendingRequest()
                val ownerLabel = owner.ifBlank { getString(R.string.remote_camera_other_parent_fallback) }
                updateStatus(getString(R.string.remote_camera_busy_status, ownerLabel))
                AudioPlaybackService.restoreIfNeeded(this)
                enableButtons()
            } }
            WebSocketManager.addPhotoBusyListener(photoBusyListener!!)
        }
    }

    private fun sendPhotoRequest() {
        val targetId = childId ?: return
        val requestId = java.util.UUID.randomUUID().toString()
        clearPendingRequest()
        pendingScope = photoScope()
        waitingForUpload = false
        pendingRequestId = requestId
        pendingStartedAt = android.os.SystemClock.elapsedRealtime()
        val camera = selectedCameraFacing
        updateStatus(
            if (camera == "front") getString(R.string.remote_camera_sending_front)
            else getString(R.string.remote_camera_sending_back)
        )

        startResponseTimeout(requestId)
        WebSocketManager.requestPhoto(
            targetDevice = targetId,
            cameraFacing = camera,
            requestId = requestId,
            onSuccess = {
                Log.d(TAG, "Photo request sent once (camera=$camera, request=$requestId)")
            },
            onError = photoError@{ error ->
                Log.e(TAG, "Photo request failed before queueing: $error")
                if (pendingRequestId != requestId) return@photoError
                clearPendingRequest()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    connectionAttempt++
                    updateStatus(getString(R.string.remote_camera_connect_error))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_connect_error_with_reason, error),
                        Toast.LENGTH_SHORT
                    ).show()
                    enableButtons()
                }
            }
        )
    }

    private fun startResponseTimeout(requestId: String) {
        // Queue/acceptance duplicates must never extend the original deadline.
        if (responseTimeoutJob?.isActive == true) return
        val scope = photoScope()
        val target = childId ?: return
        val deadline = pendingStartedAt + PHOTO_RESPONSE_TIMEOUT_MS
        responseTimeoutJob = lifecycleScope.launch {
            while (pendingRequestId == requestId && photoScope() == scope && android.os.SystemClock.elapsedRealtime() < deadline) {
                findViewById<TextView>(R.id.tvRequestProgress).apply {
                    visibility = View.VISIBLE
                    text = getString(R.string.remote_photo_request_remaining, ((deadline - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0))
                }
                delay(3000)
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val result = kotlinx.coroutines.withTimeoutOrNull(minOf(8000L, remaining)) {
                    try { networkClient.getRemotePhotoResult(target, requestId) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                if (photoScope() != scope || pendingRequestId != requestId) return@launch
                if (result?.optString("status") == "uploading") {
                    waitingForUpload = true
                    updateStatus(getString(R.string.remote_photo_upload_queued_status))
                }
                if (result?.optString("status") == "error") {
                    pendingRequestId = null; responseTimeoutJob = null
                    cancelConnectionTimeout(); enableButtons()
                    val problem = RemotePhotoErrorMessages.resolve(this@RemoteCameraActivity, result.optString("error"))
                    updateStatus(problem.status)
                    MaterialAlertDialogBuilder(this@RemoteCameraActivity).setTitle(problem.title)
                        .setMessage(problem.message).setPositiveButton(android.R.string.ok, null).show()
                    AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
                    return@launch
                }
                if (result?.optString("status") == "ready") {
                    val photo = runCatching {
                        val file = result.getJSONObject("photo")
                        ru.example.childwatch.network.PhotoFileData(file.getLong("id"), file.getString("filename"),
                        file.optLong("fileSize"), file.optString("mimeType", "image/jpeg"), timestamp = file.getLong("timestamp"),
                        createdAt = null, downloadUrl = file.getString("downloadUrl"), thumbnailUrl = file.optString("thumbnailUrl"), requestId = requestId)
                    }.onFailure { Log.w(TAG, "Malformed durable photo result", it) }.getOrNull() ?: continue
                    pendingRequestId = null; responseTimeoutJob = null
                    cancelConnectionTimeout()
                    enableButtons(); AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
                    updateStatus(getString(R.string.remote_camera_photo_received))
                    loadPhotos(announce = false)
                    openRemotePhotoPreview(photoItem(photo))
                    return@launch
                }
            }
            if (pendingRequestId != requestId || photoScope() != scope) return@launch
            rememberTimedOutRequest(requestId)
            pendingRequestId = null; responseTimeoutJob = null
            cancelConnectionTimeout()
            updateStatus(getString(if (waitingForUpload) R.string.remote_photo_upload_deferred else R.string.remote_camera_request_timeout))
            recoverLatePhoto(requestId)
            AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
            enableButtons()
            loadPhotos(announce = false)
        }
    }

    private fun photoItem(file: ru.example.childwatch.network.PhotoFileData): RemotePhotoItem {
        val base = normalizeBaseUrl(effectiveContextResolver.resolveServerUrl())
        return RemotePhotoItem(file.id, file.filename, buildMetaInfo(file.timestamp, file.width, file.height, file.fileSize),
            buildAbsoluteUrl(base, file.thumbnailUrl ?: file.downloadUrl), buildAbsoluteUrl(base, file.downloadUrl), file.timestamp)
    }

    private fun rememberTimedOutRequest(requestId: String) {
        val now = System.currentTimeMillis()
        timedOutRequestIds[requestId] = now to photoScope()
        val staleBefore = now - PHOTO_LATE_DELIVERY_GRACE_MS
        timedOutRequestIds.entries.removeAll { it.value.first < staleBefore }
    }

    private fun isLateDeliveryFor(requestId: String): Boolean {
        val timedOutAt = timedOutRequestIds[requestId] ?: return false
        return timedOutAt.second == photoScope() && System.currentTimeMillis() - timedOutAt.first <= PHOTO_LATE_DELIVERY_GRACE_MS
    }

    private fun clearLateDelivery(requestId: String) {
        timedOutRequestIds.remove(requestId)
    }

    private fun recoverLatePhoto(requestId: String) {
        lateRecoveryJob?.cancel()
        val target = childId ?: return
        val scope = photoScope()
        lateRecoveryJob = lifecycleScope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + PHOTO_LATE_DELIVERY_GRACE_MS
            while (photoScope() == scope && android.os.SystemClock.elapsedRealtime() < deadline && isLateDeliveryFor(requestId)) {
                delay(6000)
                val result = kotlinx.coroutines.withTimeoutOrNull(8000) {
                    try { networkClient.getRemotePhotoResult(target, requestId) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                if (scope != photoScope()) return@launch
                if (result?.optString("status") == "ready") {
                    clearLateDelivery(requestId)
                    loadPhotos(announce = false)
                    if (pendingRequestId == null) updateStatus(getString(R.string.remote_photo_late_saved))
                    return@launch
                }
                if (result?.optString("status") == "error") return@launch
            }
        }
    }

    private fun clearPendingRequest() {
        pendingRequestId = null
        pendingScope = null
        cancelConnectionTimeout()
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
    }

    private fun scheduleGalleryRefresh() {
        galleryRefreshJob?.cancel()
        val scope = photoScope()
        galleryRefreshJob = lifecycleScope.launch {
            delay(2000)
            if (scope == photoScope()) loadPhotos()
        }
    }

    private fun openPhotoPreview(photoBase64: String, timestamp: Long) {
        val scope = photoScope()
        val target = childId.orEmpty()
        val name = childName
        lifecycleScope.launch {
            try {
                val cachedFile = withContext(Dispatchers.IO) {
                    RemotePhotoCache.saveBase64PhotoToCache(
                        this@RemoteCameraActivity,
                        photoBase64,
                        timestamp,
                        targetDeviceId = target
                    )
                }

                if (cachedFile == null) {
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_photo_preview_error),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val intent = Intent(this@RemoteCameraActivity, PhotoPreviewActivity::class.java).apply {
                    putExtra(PhotoPreviewActivity.EXTRA_PHOTO_FILE_PATH, cachedFile.absolutePath)
                    putExtra(PhotoPreviewActivity.EXTRA_PHOTO_TIMESTAMP, timestamp)
                    putExtra(
                        PhotoPreviewActivity.EXTRA_DEVICE_NAME,
                        name ?: getString(R.string.photo_preview_device_fallback)
                    )
                }
                if (scope != photoScope()) return@launch
                startActivity(intent)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error opening photo preview", e)
            }
        }
    }

    /**
     * Load photos from server
     */
    private fun loadPhotos(append: Boolean = false, announce: Boolean = true) {
        val target = childId ?: return
        val scope = photoScope()
        galleryJob?.cancel()
        progressIndicator.visibility = View.VISIBLE
        tvPhotosEmptyHint.visibility = View.GONE
        if (announce && pendingRequestId == null) updateStatus(getString(R.string.remote_camera_loading_gallery))
        galleryJob = lifecycleScope.launch {
            try {
                val response = networkClient.getRemotePhotos(target, limit = 30, offset = if (append) galleryItems.size else 0)
                if (scope != photoScope()) return@launch
                if (response.code() == 401 || response.code() == 403) {
                    galleryItems = emptyList()
                    thumbnailAdapter.submitList(emptyList())
                    clearViewfinderPhoto()
                    rvRecentPhotos.visibility = View.GONE
                    tvPhotosEmptyHint.setText(R.string.remote_photo_family_denied_hint)
                    tvPhotosEmptyHint.visibility = View.VISIBLE
                    return@launch
                }
                if (!response.isSuccessful) throw IllegalStateException("Gallery unavailable")
                val files = response.body()?.photoFiles ?: throw IllegalStateException("Empty gallery response")
                val newItems = files.map(::photoItem)
                galleryItems = ((if (append) galleryItems else emptyList()) + newItems).distinctBy { it.id }.sortedByDescending { it.timestamp }
                resolvedGalleryDeviceId = target
                thumbnailAdapter.submitList(galleryItems.take(4))
                rvRecentPhotos.visibility = if (galleryItems.isEmpty()) View.GONE else View.VISIBLE
                tvPhotosEmptyHint.visibility = if (galleryItems.isEmpty()) View.VISIBLE else View.GONE
                tvPhotosEmptyHint.text = if (galleryItems.isEmpty()) getString(R.string.remote_camera_gallery_subtitle_empty)
                    else getString(R.string.remote_photo_gallery_summary, galleryItems.size)
                findViewById<View>(R.id.btnGalleryMore).visibility = View.VISIBLE
                if (galleryItems.isEmpty()) clearViewfinderPhoto()
                else { showViewfinderPhoto(galleryItems.first()); updateViewfinderTimestamp(galleryItems.first().timestamp) }
                if (announce && pendingRequestId == null) updateStatus(getString(R.string.remote_camera_gallery_updated))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (scope != photoScope()) return@launch
                tvPhotosEmptyHint.setText(R.string.remote_photo_gallery_retry)
                tvPhotosEmptyHint.visibility = View.VISIBLE
                rvRecentPhotos.visibility = if (galleryItems.isEmpty()) View.GONE else View.VISIBLE
                if (announce && pendingRequestId == null) updateStatus(getString(R.string.remote_camera_load_error))
            } finally { if (scope == photoScope()) progressIndicator.visibility = View.GONE }
        }
    }

    private fun showGallery() {
        val target = childId ?: return
        val scope = photoScope()
        val usableHeight = resources.configuration.screenHeightDp / resources.configuration.fontScale.coerceAtLeast(1f)
        val pageSize = when {
            usableHeight >= 640 -> 6
            usableHeight >= 480 -> 4
            else -> 2
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val label = TextView(this).apply {
            textSize = 14f
            setPadding(dp(4), dp(8), dp(4), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val pageAdapter = RemotePhotoThumbnailAdapter(
            tokenProvider = { networkClient.getAuthToken() },
            onPhotoClick = { if (scope == photoScope()) openRemotePhotoPreview(it) },
            onPhotoActions = { if (scope == photoScope()) showPhotoActions(it) },
            heightDp = 88
        )
        val grid = RecyclerView(this).apply {
            layoutManager = object : GridLayoutManager(this@RemoteCameraActivity, 2) {
                override fun canScrollVertically() = false
            }
            adapter = pageAdapter
            isNestedScrollingEnabled = false
        }
        val row = android.widget.LinearLayout(this)
        val previous = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.remote_photo_gallery_previous)
        }
        val next = com.google.android.material.button.MaterialButton(this).apply { setText(R.string.remote_photo_gallery_next) }
        previous.minHeight = dp(48); next.minHeight = dp(48)
        row.addView(previous, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(next, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        val retry = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.remote_photo_gallery_retry_action)
            visibility = View.GONE
        }
        panel.addView(label)
        val loading = com.google.android.material.progressindicator.CircularProgressIndicator(this).apply {
            isIndeterminate = true
            contentDescription = getString(R.string.remote_camera_loading_gallery)
        }
        val empty = TextView(this).apply {
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setText(R.string.remote_photo_gallery_empty_guidance)
            visibility = View.GONE
        }
        val galleryFrame = android.widget.FrameLayout(this).apply {
            addView(grid, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(loading, android.widget.FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.CENTER))
            addView(retry, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        }
        panel.addView(galleryFrame, android.widget.LinearLayout.LayoutParams(-1, dp((pageSize / 2) * 96)))
        panel.addView(row)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remote_photo_gallery_person_title, childName))
            .setView(panel).setPositiveButton(R.string.remote_photo_gallery_close, null).create()
        var page = 0
        var pageJob: Job? = null
        fun loadPage(destination: Int) {
            pageJob?.cancel()
            previous.isEnabled = false; next.isEnabled = false
            retry.visibility = View.GONE
            grid.visibility = View.INVISIBLE
            empty.visibility = View.GONE
            loading.visibility = View.VISIBLE
            label.setText(R.string.remote_camera_loading_gallery)
            pageJob = lifecycleScope.launch {
                try {
                    val response = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        networkClient.getRemotePhotos(target, limit = pageSize + 1, offset = destination * pageSize)
                    } ?: throw java.io.IOException("Gallery request timed out")
                    if (scope != photoScope() || !dialog.isShowing) { dialog.dismiss(); return@launch }
                    if (response.code() == 401 || response.code() == 403) {
                        pageAdapter.submitList(emptyList())
                        loading.visibility = View.GONE
                        label.setText(R.string.remote_photo_family_denied_hint)
                        return@launch
                    }
                    if (!response.isSuccessful) throw IllegalStateException("Gallery unavailable")
                    val files = response.body()?.photoFiles ?: throw IllegalStateException("Missing gallery")
                    page = destination
                    pageAdapter.submitList(files.take(pageSize).map(::photoItem))
                    loading.visibility = View.GONE
                    grid.visibility = if (files.isEmpty()) View.INVISIBLE else View.VISIBLE
                    empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                    label.text = if (files.isEmpty()) getString(R.string.remote_camera_gallery_subtitle_empty)
                        else getString(R.string.remote_photo_gallery_page, page + 1)
                    previous.isEnabled = page > 0
                    next.isEnabled = files.size > pageSize
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (scope == photoScope() && dialog.isShowing) {
                        label.setText(R.string.remote_photo_gallery_retry)
                        loading.visibility = View.GONE
                        previous.isEnabled = page > 0
                        retry.visibility = View.VISIBLE
                        grid.visibility = View.INVISIBLE
                        retry.setOnClickListener { loadPage(destination) }
                    }
                }
            }
        }
        previous.setOnClickListener { loadPage((page - 1).coerceAtLeast(0)) }
        next.setOnClickListener { loadPage(page + 1) }
        dialog.setOnDismissListener { pageJob?.cancel() }
        dialog.show()
        loadPage(0)
    }

    private fun openRemotePhotoPreview(photoItem: RemotePhotoItem) {
        val scope = photoScope()
        val target = childId.orEmpty()
        val name = childName
        lifecycleScope.launch {
            try {
                updateStatus(getString(R.string.remote_camera_loading_gallery))
                val bytes = networkClient.downloadRemoteMediaBytes(photoItem.fullImageUrl)
                if (bytes == null || bytes.isEmpty()) {
                    updateStatus(getString(R.string.remote_camera_download_failed))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                if (scope != photoScope()) return@launch
                val cachedFile = withContext(Dispatchers.IO) {
                    RemotePhotoCache.saveBinaryPhotoToCache(
                        this@RemoteCameraActivity,
                        bytes,
                        photoItem.timestamp,
                        prefix = "remote_gallery",
                        targetDeviceId = target
                    )
                }

                if (cachedFile == null) {
                    updateStatus(getString(R.string.remote_photo_preview_error))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_photo_preview_error),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val intent = Intent(this@RemoteCameraActivity, PhotoPreviewActivity::class.java).apply {
                    putExtra(PhotoPreviewActivity.EXTRA_PHOTO_FILE_PATH, cachedFile.absolutePath)
                    putExtra(PhotoPreviewActivity.EXTRA_PHOTO_TIMESTAMP, photoItem.timestamp)
                    putExtra(
                        PhotoPreviewActivity.EXTRA_DEVICE_NAME,
                        name ?: getString(R.string.photo_preview_device_fallback)
                    )
                }
                if (scope != photoScope()) return@launch
                startActivity(intent)
                updateStatus(getString(R.string.remote_camera_done))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error opening remote gallery photo", e)
                updateStatus(getString(R.string.remote_photo_preview_error))
                Toast.makeText(
                    this@RemoteCameraActivity,
                    getString(R.string.remote_camera_error_format, e.message ?: "unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun resolveGalleryDeviceIds(): List<String> {
        return listOfNotNull(childId?.trim()?.takeIf(String::isNotBlank))
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("photo_scope", photoScope())
        state.putString("photo_child", childId)
        state.putString("photo_child_name", childName)
        state.putString("photo_request", pendingRequestId)
        state.putString("photo_camera", selectedCameraFacing)
        state.putLong("photo_deadline", System.currentTimeMillis() +
            (PHOTO_RESPONSE_TIMEOUT_MS - (android.os.SystemClock.elapsedRealtime() - pendingStartedAt)).coerceAtLeast(0))
    }

    override fun onDestroy() {
        connectionAttempt++
        super.onDestroy()
        clearPendingRequest()
        lateRecoveryJob?.cancel()
        unregisterPhotoListeners()
        Log.d(TAG, "RemoteCameraActivity destroyed")
    }

    private fun unregisterPhotoListeners() {
        photoReceivedListener?.let { WebSocketManager.removePhotoReceivedListener(it) }
        photoReceivedListener = null
        photoErrorListener?.let { WebSocketManager.removePhotoErrorListener(it) }
        photoErrorListener = null
        photoQueuedListener?.let { WebSocketManager.removePhotoQueuedListener(it) }
        photoQueuedListener = null
        photoRequestReceivedListener?.let { WebSocketManager.removePhotoRequestReceivedListener(it) }
        photoRequestReceivedListener = null
        photoBusyListener?.let { WebSocketManager.removePhotoBusyListener(it) }
        photoBusyListener = null
    }

    /**
     * Download and save photo to device storage
     */
    private fun showPhotoActions(item: RemotePhotoItem) {
        MaterialAlertDialogBuilder(this).setTitle(dateFormatter.format(Date(item.timestamp)))
            .setItems(arrayOf(getString(R.string.remote_photo_open_action), getString(R.string.remote_photo_save_action), getString(R.string.remote_photo_share_action))) { _, action ->
                when (action) { 0 -> openRemotePhotoPreview(item); 1 -> downloadAndSavePhoto(item); 2 -> sharePhoto(item) }
            }.show()
    }

    private fun downloadAndSavePhoto(photoItem: RemotePhotoItem) {
        val scope = photoScope()
        lifecycleScope.launch {
            try {
                updateStatus(getString(R.string.remote_camera_downloading))
                val bytes = networkClient.downloadRemoteMediaBytes(photoItem.fullImageUrl)

                if (bytes == null) {
                    updateStatus(getString(R.string.remote_camera_download_failed))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                if (bytes.isEmpty()) {
                    updateStatus(getString(R.string.remote_camera_save_empty))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_save_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                
                if (scope != photoScope()) return@launch
                val fileName = "CW_${photoItem.id}_${photoItem.timestamp}.jpg"
                withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChildWatch")
                            put(android.provider.MediaStore.Images.Media.DATE_TAKEN, photoItem.timestamp)
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw java.io.IOException("Cannot create gallery image")
                        try {
                            val stream = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Cannot open image")
                            stream.use { it.write(bytes) }
                            values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                        } catch (error: Exception) { contentResolver.delete(uri, null, null); throw error }
                    } else {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(this@RemoteCameraActivity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            throw SecurityException(getString(R.string.remote_photo_storage_permission))
                        val directory = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "ChildWatch")
                        if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException("Cannot create pictures directory")
                        val file = java.io.File(directory, fileName)
                        file.outputStream().use { it.write(bytes) }
                        android.media.MediaScannerConnection.scanFile(this@RemoteCameraActivity, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                    }
                }
                if (scope != photoScope()) return@launch
                updateStatus(getString(R.string.remote_camera_saved))
                Toast.makeText(
                    this@RemoteCameraActivity,
                    getString(R.string.remote_camera_saved_to_path, fileName),
                    Toast.LENGTH_LONG
                ).show()
                
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error downloading photo", e)
                updateStatus(getString(R.string.remote_camera_save_failed))
                Toast.makeText(
                    this@RemoteCameraActivity,
                    getString(R.string.remote_camera_error_format, e.message ?: "unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Share photo via other apps
     */
    private fun sharePhoto(photoItem: RemotePhotoItem) {
        val scope = photoScope()
        lifecycleScope.launch {
            try {
                updateStatus(getString(R.string.remote_camera_share_prep))
                val bytes = networkClient.downloadRemoteMediaBytes(photoItem.fullImageUrl)

                if (bytes == null || bytes.isEmpty()) {
                    updateStatus(getString(R.string.remote_camera_download_failed))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                
                if (scope != photoScope()) return@launch
                // Save to cache
                val cacheDir = java.io.File(cacheDir, "shared_photos")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                
                val cacheFile = java.io.File(cacheDir, "share_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    java.io.FileOutputStream(cacheFile).use { it.write(bytes) }
                }
                
                // Create share intent
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@RemoteCameraActivity,
                    "${packageName}.fileprovider",
                    cacheFile
                )
                
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.remote_camera_share_subject))
                    putExtra(
                        android.content.Intent.EXTRA_TEXT,
                        getString(R.string.remote_camera_share_body, photoItem.displayName)
                    )
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                if (scope != photoScope()) return@launch
                startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        getString(R.string.remote_camera_share_title)
                    )
                )
                updateStatus(getString(R.string.remote_camera_done))
                
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error sharing photo", e)
                updateStatus(getString(R.string.remote_camera_download_failed))
                Toast.makeText(
                    this@RemoteCameraActivity,
                    getString(R.string.remote_camera_error_format, e.message ?: "unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateStatus(status: String) {
        statusText.text = status
        // Обновить индикатор online/offline
        val isConnected = status == getString(R.string.remote_camera_connected) ||
                status == getString(R.string.remote_camera_photo_received) ||
                status == getString(R.string.remote_camera_gallery_updated) ||
                status == getString(R.string.remote_camera_done) ||
                status == getString(R.string.remote_camera_saved) ||
                status == getString(R.string.remote_camera_status_ready)
        imgOnlineDot.visibility = View.GONE // Server/gallery success does not prove the child's online state.
        imgOnlineDot.setImageResource(
            if (isConnected) R.drawable.ic_dot_online_green
            else R.drawable.ic_dot_offline_gray
        )
    }

    private fun disableButtons() {
        findViewById<View>(R.id.cameraBack).isEnabled = false
        findViewById<View>(R.id.cameraFront).isEnabled = false
        btnTakePhoto.isEnabled = false
        btnTakePhoto.alpha = 0.4f
        btnSwitchCamera.isEnabled = false
        btnSwitchCamera.alpha = 0.4f
        btnRefresh.isEnabled = false
        btnRefresh.alpha = 0.4f
        pillChildSelector.isEnabled = false
        pillChildSelector.alpha = 0.4f
    }

    private fun enableButtons() {
        findViewById<View>(R.id.cameraBack).isEnabled = true
        findViewById<View>(R.id.cameraFront).isEnabled = true
        if (pendingRequestId == null) findViewById<View>(R.id.tvRequestProgress).visibility = View.GONE
        btnTakePhoto.isEnabled = true
        btnTakePhoto.alpha = 1f
        btnSwitchCamera.isEnabled = true
        btnSwitchCamera.alpha = 1f
        btnRefresh.isEnabled = true
        btnRefresh.alpha = 1f
        pillChildSelector.isEnabled = true
        pillChildSelector.alpha = 1f
    }

    private fun normalizeBaseUrl(base: String): String {
        val trimmed = base.trim()
        val withScheme = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        return withScheme.trimEnd('/')
    }

    private fun buildAbsoluteUrl(base: String, path: String): String {
        return if (path.startsWith("http")) {
            path
        } else {
            val normalizedPath = if (path.startsWith('/')) path else "/$path"
            base + normalizedPath
        }
    }

    private fun buildMetaInfo(timestamp: Long, width: Int?, height: Int?, sizeBytes: Long): String {
        val formattedDate = dateFormatter.format(Date(timestamp))
        val resolution = if (width != null && height != null && width > 0 && height > 0) {
            "${width}x${height}"
        } else {
            null
        }
        val sizeLabel = when {
            sizeBytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f МБ", sizeBytes / 1024f / 1024f)
            sizeBytes >= 1024 -> "${sizeBytes / 1024} КБ"
            else -> "${sizeBytes} Б"
        }

        return listOfNotNull(formattedDate, resolution, sizeLabel).joinToString(" | ")
    }

    private fun updateCameraLabel() {
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.cameraToggle)
            .check(if (selectedCameraFacing == "front") R.id.cameraFront else R.id.cameraBack)
        tvCameraLabel.text = if (selectedCameraFacing == "front") {
            getString(R.string.remote_camera_hint_front)
        } else {
            getString(R.string.remote_camera_hint_back)
        }
    }

    private fun updateViewfinderTimestamp(timestamp: Long) {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val label = when {
            diff < 60_000 -> getString(R.string.remote_camera_time_just_now)
            diff < 3_600_000 -> getString(R.string.remote_camera_time_minutes, (diff / 60_000).toInt())
            diff < 86_400_000 -> getString(R.string.remote_camera_time_hours, (diff / 3_600_000).toInt())
            else -> dateFormatter.format(Date(timestamp))
        }
        tvTimestamp.text = label
    }

    private fun showViewfinderPhoto(photo: RemotePhotoItem) {
        imgLastPhoto.visibility = View.VISIBLE
        imgViewfinderPlaceholder.visibility = View.GONE
        tvViewfinderHint.visibility = View.GONE
        Glide.with(this)
            .load(AuthenticatedMedia.url(photo.previewUrl) { networkClient.getAuthToken() })
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
                ): Boolean {
                    imgLastPhoto.post {
                        imgLastPhoto.visibility = View.GONE
                        updatePlaceholderVisibility()
                        tvViewfinderHint.visibility = View.VISIBLE
                        tvViewfinderHint.setText(R.string.remote_camera_preview_unavailable)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean = false
            })
            .fitCenter()
            .into(imgLastPhoto)
    }

    private fun updatePlaceholderVisibility() {
        val frame = imgViewfinderPlaceholder.parent as View
        imgViewfinderPlaceholder.visibility = if (imgLastPhoto.visibility != View.VISIBLE &&
            frame.height >= (200 * resources.displayMetrics.density).toInt()) View.VISIBLE else View.GONE
    }

    private fun clearViewfinderPhoto() {
        Glide.with(this).clear(imgLastPhoto)
        imgLastPhoto.visibility = View.GONE
        updatePlaceholderVisibility()
        tvViewfinderHint.visibility = View.GONE
        tvViewfinderHint.setText(R.string.remote_camera_empty_preview)
        tvTimestamp.text = ""
    }
}
