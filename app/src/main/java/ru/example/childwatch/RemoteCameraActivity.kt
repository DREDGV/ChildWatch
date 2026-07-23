package ru.example.childwatch

import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
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
        private const val PHOTO_RESPONSE_TIMEOUT_MS = 30_000L
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var childNameText: TextView
    private lateinit var progressIndicator: CircularProgressIndicator

    // Новые элементы видоискателя
    private lateinit var imgLastPhoto: ImageView
    private lateinit var imgViewfinderPlaceholder: ImageView
    private lateinit var pillChildSelector: View
    private lateinit var childAvatarImage: ImageView
    private lateinit var imgOnlineDot: ImageView
    private lateinit var tvTimestamp: TextView
    private lateinit var tvCameraLabel: TextView
    private lateinit var btnTakePhoto: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var btnSwitchCamera: ImageView
    private lateinit var rvRecentPhotos: RecyclerView
    private lateinit var tvPhotosEmptyHint: TextView
    private lateinit var thumbnailAdapter: RemotePhotoThumbnailAdapter

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
    private var pendingRequestId: String? = null
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

        if (!initViews()) return
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
            loadPhotos()
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
                onPhotoClick = { photoItem -> openRemotePhotoPreview(photoItem) }
            )
            rvRecentPhotos.apply {
                layoutManager = LinearLayoutManager(
                    this@RemoteCameraActivity,
                    LinearLayoutManager.HORIZONTAL,
                    false
                )
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
        } catch (e: Exception) {
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
        lifecycleScope.launch {
            val option = runCatching {
                ParentLinkedChildOptionsProvider(this@RemoteCameraActivity)
                    .getOptions()
                    .firstOrNull { it.deviceId == targetId }
            }.getOrNull() ?: return@launch
            childNameText.text = option.displayName.trim()
                .ifBlank { getString(R.string.chat_partner_child) }
            FamilyAvatarRenderer.bind(childAvatarImage, option.avatarKey)
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
            if (childId == null) showPersonSelector() else takePhoto()
        }

        btnRefresh.setOnClickListener { loadPhotos() }

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
        clearPendingRequest()
        unregisterPhotoListeners()
        childId = option.deviceId
        childName = option.displayName
        resolvedGalleryDeviceId = null
        childNameText.text = option.displayName
        FamilyAvatarRenderer.bind(childAvatarImage, option.avatarKey)
        enableButtons()
        updateStatus(getString(R.string.remote_camera_status_connecting))
        loadPhotos()
        ensureWebSocketReady()
    }

    /**
     * Send take_photo command to child device (uses back camera by default)
     */
    private fun takePhoto() {
        Log.d(TAG, "Taking photo for child: $childId")
        updateStatus(getString(R.string.remote_camera_status_connecting))
        disableButtons()
        startConnectionTimeout()
        ensureWebSocketReady {
            cancelConnectionTimeout()
            sendPhotoRequest()
        }
    }

    private fun ensureWebSocketReady(onReady: () -> Unit = {}) {
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
                    cancelConnectionTimeout()
                    updateStatus(getString(R.string.remote_camera_connected))
                    onReady()
                }
            },
            onError = { error ->
                runOnUiThread {
                    cancelConnectionTimeout()
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

    private fun registerPhotoListeners() {
        if (photoReceivedListener == null) {
            photoReceivedListener = photoReceivedListener@{ photoBase64, requestId, timestamp ->
                if (pendingRequestId != requestId) return@photoReceivedListener
                clearPendingRequest()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateStatus(getString(R.string.remote_camera_photo_received))
                    enableButtons()
                    AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
                    openPhotoPreview(photoBase64, timestamp)
                    scheduleGalleryRefresh()
                }
            }
            WebSocketManager.addPhotoReceivedListener(photoReceivedListener!!)
        }

        if (photoErrorListener == null) {
            photoErrorListener = photoErrorListener@{ requestId, error ->
                if (pendingRequestId != requestId) return@photoErrorListener
                clearPendingRequest()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Log.w(TAG, "Remote photo failed: request=$requestId error=$error")
                    val uiError = RemotePhotoErrorMessages.resolve(this, error)
                    updateStatus(uiError.status)
                    AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
                    if (uiError.actionable) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(uiError.title)
                            .setMessage(uiError.message)
                            .setPositiveButton(R.string.remote_camera_recovery_action, null)
                            .show()
                    } else {
                        Toast.makeText(this, uiError.message, Toast.LENGTH_SHORT).show()
                    }
                    enableButtons()
                }
            }
            WebSocketManager.addPhotoErrorListener(photoErrorListener!!)
        }

        if (photoQueuedListener == null) {
            photoQueuedListener = photoQueuedListener@{ requestId, _, camera, _ ->
                if (pendingRequestId != requestId) return@photoQueuedListener
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateStatus(
                        if (camera == "front") getString(R.string.remote_photo_status_queued_front)
                        else getString(R.string.remote_photo_status_queued_back)
                    )
                }
                startResponseTimeout(requestId)
            }
            WebSocketManager.addPhotoQueuedListener(photoQueuedListener!!)
        }

        if (photoRequestReceivedListener == null) {
            photoRequestReceivedListener = photoRequestReceivedListener@{ requestId, _, _ ->
                if (pendingRequestId != requestId) return@photoRequestReceivedListener
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateStatus(getString(R.string.remote_photo_status_device_accepted))
                    // The child has the work now; retain the response timeout
                    // so controls are still guaranteed to recover.
                    startResponseTimeout(requestId)
                }
            }
            WebSocketManager.addPhotoRequestReceivedListener(photoRequestReceivedListener!!)
        }

        if (photoBusyListener == null) {
            photoBusyListener = photoBusyListener@{ requestId, _, _, ownerDisplayName, _ ->
                if (pendingRequestId != requestId) return@photoBusyListener
                clearPendingRequest()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val ownerLabel = ownerDisplayName.ifBlank {
                        getString(R.string.remote_camera_other_parent_fallback)
                    }
                    updateStatus(getString(R.string.remote_camera_busy_status, ownerLabel))
                    AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_busy_status, ownerLabel),
                        Toast.LENGTH_LONG
                    ).show()
                    enableButtons()
                }
            }
            WebSocketManager.addPhotoBusyListener(photoBusyListener!!)
        }
    }

    private fun sendPhotoRequest() {
        val targetId = childId ?: return
        val requestId = java.util.UUID.randomUUID().toString()
        clearPendingRequest()
        pendingRequestId = requestId
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
        responseTimeoutJob?.cancel()
        responseTimeoutJob = lifecycleScope.launch {
            delay(PHOTO_RESPONSE_TIMEOUT_MS)
            if (pendingRequestId != requestId) return@launch
            clearPendingRequest()
            runOnUiThread {
                updateStatus(getString(R.string.remote_camera_request_timeout))
                AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
                Toast.makeText(
                    this@RemoteCameraActivity,
                    getString(R.string.remote_camera_no_response),
                    Toast.LENGTH_SHORT
                ).show()
                enableButtons()
            }
        }
    }

    private fun clearPendingRequest() {
        pendingRequestId = null
        cancelConnectionTimeout()
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
    }

    private fun scheduleGalleryRefresh() {
        lifecycleScope.launch {
            val delays = if (AudioPlaybackService.isPlaying || AudioPlaybackService.isSessionDesired(this@RemoteCameraActivity)) {
                listOf(4000L)
            } else {
                listOf(2000L, 4000L, 8000L, 12000L, 16000L)
            }
            for (delayMs in delays) {
                delay(delayMs)
                loadPhotos()
            }
        }
    }

    private fun openPhotoPreview(photoBase64: String, timestamp: Long) {
        lifecycleScope.launch {
            try {
                val cachedFile = withContext(Dispatchers.IO) {
                    RemotePhotoCache.saveBase64PhotoToCache(
                        this@RemoteCameraActivity,
                        photoBase64,
                        timestamp,
                        targetDeviceId = childId.orEmpty()
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
                        childName ?: getString(R.string.photo_preview_device_fallback)
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening photo preview", e)
            }
        }
    }

    /**
     * Load photos from server
     */
    private fun loadPhotos() {
        Log.d(TAG, "Loading photos for device: $childId")
        val deviceIds = resolveGalleryDeviceIds()
        if (deviceIds.isEmpty()) {
            return
        }

        // Show loading state
        progressIndicator.visibility = View.VISIBLE
        rvRecentPhotos.visibility = View.GONE
        tvPhotosEmptyHint.visibility = View.GONE

        updateStatus(getString(R.string.remote_camera_loading_gallery))

        lifecycleScope.launch {
            try {
                val (response, resolvedDeviceId) = fetchRemotePhotos(deviceIds)
                progressIndicator.visibility = View.GONE

                if (response.isSuccessful) {
                    val body = response.body()
                    val photos = body?.photoFiles.orEmpty()

                    if (photos.isEmpty()) {
                        resolvedGalleryDeviceId = null
                        thumbnailAdapter.submitList(emptyList())
                        clearViewfinderPhoto()
                        tvPhotosEmptyHint.visibility = View.VISIBLE
                        rvRecentPhotos.visibility = View.GONE
                        updateStatus(getString(R.string.remote_camera_no_photos))
                    } else {
                        resolvedGalleryDeviceId = resolvedDeviceId
                        val serverUrl = SecureSettingsManager(this@RemoteCameraActivity).getServerUrl().trim()
                        if (serverUrl.isBlank()) {
                            tvPhotosEmptyHint.visibility = View.VISIBLE
                            rvRecentPhotos.visibility = View.GONE
                            updateStatus(getString(R.string.server_url_missing))
                            Toast.makeText(this@RemoteCameraActivity, getString(R.string.server_url_missing), Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val normalizedBase = normalizeBaseUrl(serverUrl)

                        val items = photos.map { file ->
                            val previewUrl = buildAbsoluteUrl(normalizedBase, file.thumbnailUrl ?: file.downloadUrl)
                            val fullUrl = buildAbsoluteUrl(normalizedBase, file.downloadUrl)
                            RemotePhotoItem(
                                id = file.id,
                                displayName = file.filename,
                                metaInfo = buildMetaInfo(file.timestamp, file.width, file.height, file.fileSize),
                                previewUrl = previewUrl,
                                fullImageUrl = fullUrl
                            )
                        }

                        thumbnailAdapter.submitList(items)
                        rvRecentPhotos.visibility = View.VISIBLE
                        tvPhotosEmptyHint.visibility = View.GONE
                        updateStatus(getString(R.string.remote_camera_gallery_updated))

                        // Обновить таймстемп на видоискателе
                        val latestIndex = photos.indices.maxByOrNull { photos[it].timestamp }
                        if (latestIndex != null) {
                            showViewfinderPhoto(items[latestIndex])
                            updateViewfinderTimestamp(photos[latestIndex].timestamp)
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to load photos: ${response.code()}")
                    tvPhotosEmptyHint.visibility = View.VISIBLE
                    rvRecentPhotos.visibility = View.GONE
                    updateStatus(getString(R.string.remote_camera_fetch_failed))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_load_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                progressIndicator.visibility = View.GONE
                tvPhotosEmptyHint.visibility = View.VISIBLE
                rvRecentPhotos.visibility = View.GONE
                updateStatus(getString(R.string.remote_camera_load_error))
                Toast.makeText(
                    this@RemoteCameraActivity,
                    getString(R.string.remote_camera_error_format, e.message ?: "unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openRemotePhotoPreview(photoItem: RemotePhotoItem) {
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

                val cachedFile = withContext(Dispatchers.IO) {
                    RemotePhotoCache.saveBinaryPhotoToCache(
                        this@RemoteCameraActivity,
                        bytes,
                        System.currentTimeMillis(),
                        prefix = "remote_gallery",
                        targetDeviceId = childId.orEmpty()
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
                    putExtra(PhotoPreviewActivity.EXTRA_PHOTO_TIMESTAMP, System.currentTimeMillis())
                    putExtra(
                        PhotoPreviewActivity.EXTRA_DEVICE_NAME,
                        childName ?: getString(R.string.photo_preview_device_fallback)
                    )
                }
                startActivity(intent)
                updateStatus(getString(R.string.remote_camera_done))
            } catch (e: Exception) {
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

    private suspend fun fetchRemotePhotos(
        deviceIds: List<String>
    ): Pair<retrofit2.Response<ru.example.childwatch.network.PhotoGalleryResponse>, String?> {
        var lastSuccessfulResponse: retrofit2.Response<ru.example.childwatch.network.PhotoGalleryResponse>? = null
        var lastSuccessfulDeviceId: String? = null
        var lastErrorResponse: retrofit2.Response<ru.example.childwatch.network.PhotoGalleryResponse>? = null

        for (deviceId in deviceIds) {
            Log.d(TAG, "Trying gallery fetch for deviceId=$deviceId")
            val response = networkClient.getRemotePhotos(deviceId, limit = 30)
            if (response.isSuccessful) {
                val photos = response.body()?.photoFiles.orEmpty()
                if (photos.isNotEmpty()) {
                    return response to deviceId
                }
                lastSuccessfulResponse = response
                lastSuccessfulDeviceId = deviceId
            } else {
                lastErrorResponse = response
            }
        }

        lastSuccessfulResponse?.let { response ->
            return response to lastSuccessfulDeviceId
        }
        lastErrorResponse?.let { response ->
            return response to null
        }

        val fallbackResponse = networkClient.getRemotePhotos(deviceIds.first(), limit = 30)
        return fallbackResponse to deviceIds.first()
    }

    private fun resolveGalleryDeviceIds(): List<String> {
        return listOfNotNull(childId?.trim()?.takeIf(String::isNotBlank))
    }

    override fun onDestroy() {
        super.onDestroy()
        clearPendingRequest()
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
    private fun downloadAndSavePhoto(photoItem: RemotePhotoItem) {
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
                
                // Save to DCIM/ChildWatch/
                val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DCIM
                )
                val childWatchDir = java.io.File(picturesDir, "ChildWatch")
                if (!childWatchDir.exists()) {
                    childWatchDir.mkdirs()
                }
                
                val fileName = "CW_${System.currentTimeMillis()}.jpg"
                val photoFile = java.io.File(childWatchDir, fileName)
                
                withContext(Dispatchers.IO) {
                    java.io.FileOutputStream(photoFile).use { it.write(bytes) }
                }
                
                // Scan for gallery
                android.media.MediaScannerConnection.scanFile(
                    this@RemoteCameraActivity,
                    arrayOf(photoFile.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
                
                updateStatus(getString(R.string.remote_camera_saved))
                Toast.makeText(
                    this@RemoteCameraActivity,
                    getString(R.string.remote_camera_saved_to_path, fileName),
                    Toast.LENGTH_LONG
                ).show()
                
            } catch (e: Exception) {
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
                
                startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        getString(R.string.remote_camera_share_title)
                    )
                )
                updateStatus(getString(R.string.remote_camera_done))
                
            } catch (e: Exception) {
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
        imgOnlineDot.setImageResource(
            if (isConnected) R.drawable.ic_dot_online_green
            else R.drawable.ic_dot_offline_gray
        )
    }

    private fun disableButtons() {
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
        Glide.with(this)
            .load(photo.previewUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(R.drawable.ic_photo_placeholder)
            .error(R.drawable.ic_photo_placeholder)
            .centerCrop()
            .into(imgLastPhoto)
    }

    private fun clearViewfinderPhoto() {
        Glide.with(this).clear(imgLastPhoto)
        imgLastPhoto.visibility = View.GONE
        imgViewfinderPlaceholder.visibility = View.VISIBLE
        tvTimestamp.text = ""
    }
}
