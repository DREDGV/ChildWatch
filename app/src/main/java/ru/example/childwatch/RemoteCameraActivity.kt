package ru.example.childwatch

import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import ru.example.childwatch.network.WebSocketManager
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.remote.RemotePhotoAdapter
import ru.example.childwatch.remote.RemotePhotoCache
import ru.example.childwatch.remote.RemotePhotoItem
import ru.example.childwatch.utils.SecureSettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var childNameText: TextView
    private lateinit var takePhotoButton: MaterialButton
    private lateinit var cameraToggleGroup: MaterialButtonToggleGroup
    private lateinit var cameraBackButton: MaterialButton
    private lateinit var cameraFrontButton: MaterialButton
    private lateinit var photosRecyclerView: RecyclerView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var galleryMetaText: TextView
    private lateinit var refreshGalleryButton: MaterialButton
    private lateinit var photoAdapter: RemotePhotoAdapter

    private var childId: String? = null
    private var childName: String? = null
    private val networkClient by lazy { NetworkClient(applicationContext) }
    private val dateFormatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private var photoReceivedListener: ((String, String, Long) -> Unit)? = null
    private var photoErrorListener: ((String, String) -> Unit)? = null
    private var photoQueuedListener: ((String, String, String, Long) -> Unit)? = null
    private var retryJob: Job? = null
    private var responseTimeoutJob: Job? = null
    private var pendingRequestId: String? = null
    private var selectedCameraFacing: String = "back"
    private var resolvedGalleryDeviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_camera)

        // Get child device info from intent
        childId = intent.getStringExtra(EXTRA_CHILD_ID)
        childName = intent.getStringExtra(EXTRA_CHILD_NAME)

        if (childId == null) {
            Toast.makeText(this, getString(R.string.remote_camera_missing_child_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupToolbar()
        setupButtons()
        loadPhotos()
        ensureWebSocketReady()
    }

    private fun initViews() {
        try {
            toolbar = findViewById(R.id.toolbar)
            statusText = findViewById(R.id.statusText)
            childNameText = findViewById(R.id.childNameText)
            takePhotoButton = findViewById(R.id.takePhotoButton)
            cameraToggleGroup = findViewById(R.id.cameraToggleGroup)
            cameraBackButton = findViewById(R.id.cameraBackButton)
            cameraFrontButton = findViewById(R.id.cameraFrontButton)
            photosRecyclerView = findViewById(R.id.photosRecyclerView)
            progressIndicator = findViewById(R.id.progressIndicator)
            emptyStateLayout = findViewById(R.id.emptyStateLayout)
            galleryMetaText = findViewById(R.id.galleryMetaText)
            refreshGalleryButton = findViewById(R.id.refreshGalleryButton)

            photoAdapter = RemotePhotoAdapter(
                onPhotoSave = { photoItem -> downloadAndSavePhoto(photoItem) },
                onPhotoShare = { photoItem -> sharePhoto(photoItem) }
            )
            photosRecyclerView.apply {
                layoutManager = GridLayoutManager(this@RemoteCameraActivity, 2)
                adapter = photoAdapter
            }

            // Display child name if available
            childNameText.text = if (!childName.isNullOrBlank()) {
                getString(R.string.remote_camera_device_label, childName)
            } else {
                getString(R.string.remote_camera_device_id, childId)
            }
            cameraToggleGroup.check(cameraBackButton.id)
            selectedCameraFacing = "back"
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views", e)
            Toast.makeText(
                this,
                getString(R.string.remote_camera_ui_error, e.message ?: "unknown"),
                Toast.LENGTH_SHORT
            ).show()
            finish()
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
        cameraToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedCameraFacing = if (checkedId == cameraFrontButton.id) "front" else "back"
            updateStatus(
                if (selectedCameraFacing == "front") getString(R.string.remote_camera_selected_front)
                else getString(R.string.remote_camera_selected_back)
            )
        }

        takePhotoButton.setOnClickListener {
            takePhoto()
        }

        refreshGalleryButton.setOnClickListener {
            loadPhotos()
        }
    }

    /**
     * Send take_photo command to child device (uses back camera by default)
     */
    private fun takePhoto() {
        Log.d(TAG, "Taking photo for child: $childId")
        updateStatus(getString(R.string.remote_camera_status_connecting))
        disableButtons()
        ensureWebSocketReady {
            sendPhotoRequestWithRetry()
        }
    }

    private fun ensureWebSocketReady(onReady: () -> Unit = {}) {
        val targetId = childId ?: return
        val serverUrl = SecureSettingsManager(this).getServerUrl().trim()
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
                    updateStatus(getString(R.string.remote_camera_connected))
                    onReady()
                }
            },
            onError = { error ->
                runOnUiThread {
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

    private fun registerPhotoListeners() {
        if (photoReceivedListener == null) {
            photoReceivedListener = photoReceivedListener@{ photoBase64, requestId, timestamp ->
                if (pendingRequestId != requestId) return@photoReceivedListener
                clearPendingRequest()
                runOnUiThread {
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
                    updateStatus(getString(R.string.remote_camera_error_format, error))
                    AudioPlaybackService.restoreIfNeeded(this@RemoteCameraActivity)
                    Toast.makeText(
                        this,
                        getString(R.string.remote_camera_error_format, error),
                        Toast.LENGTH_SHORT
                    ).show()
                    enableButtons()
                }
            }
            WebSocketManager.addPhotoErrorListener(photoErrorListener!!)
        }

        if (photoQueuedListener == null) {
            photoQueuedListener = photoQueuedListener@{ requestId, _, camera, _ ->
                if (pendingRequestId != requestId) return@photoQueuedListener
                retryJob?.cancel()
                retryJob = null
                runOnUiThread {
                    updateStatus(
                        if (camera == "front") getString(R.string.remote_photo_status_queued_front)
                        else getString(R.string.remote_photo_status_queued_back)
                    )
                }
                startResponseTimeout(requestId)
            }
            WebSocketManager.addPhotoQueuedListener(photoQueuedListener!!)
        }
    }

    private fun sendPhotoRequestWithRetry() {
        val targetId = childId ?: return
        val requestId = java.util.UUID.randomUUID().toString()
        clearPendingRequest()
        pendingRequestId = requestId
        val camera = selectedCameraFacing
        updateStatus(
            if (camera == "front") getString(R.string.remote_camera_sending_front)
            else getString(R.string.remote_camera_sending_back)
        )

        val delays = listOf(0L, 3000L, 7000L)
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            for ((index, delayMs) in delays.withIndex()) {
                if (pendingRequestId == null) return@launch
                if (delayMs > 0) {
                    delay(delayMs)
                }
                if (pendingRequestId == null) return@launch
                WebSocketManager.requestPhoto(
                    targetDevice = targetId,
                    cameraFacing = camera,
                    requestId = requestId,
                    onSuccess = {
                        Log.d(TAG, "Photo request sent (attempt ${index + 1}, camera=$camera)")
                    },
                    onError = { error ->
                        Log.e(TAG, "Photo request failed: $error")
                    }
                )
            }
            if (pendingRequestId != null) {
                clearPendingRequest()
                runOnUiThread {
                    updateStatus(getString(R.string.remote_camera_request_timeout))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_no_response),
                        Toast.LENGTH_SHORT
                    ).show()
                    enableButtons()
                }
            }
        }
    }

    private fun startResponseTimeout(requestId: String) {
        responseTimeoutJob?.cancel()
        responseTimeoutJob = lifecycleScope.launch {
            delay(20000L)
            if (pendingRequestId != requestId) return@launch
            clearPendingRequest()
            runOnUiThread {
                updateStatus(getString(R.string.remote_camera_request_timeout))
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
        retryJob?.cancel()
        retryJob = null
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
                        timestamp
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
        photosRecyclerView.visibility = View.GONE
        emptyStateLayout.visibility = View.GONE

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
                        photoAdapter.submitList(emptyList())
                        emptyStateLayout.visibility = View.VISIBLE
                        photosRecyclerView.visibility = View.GONE
                        galleryMetaText.text = getString(R.string.remote_camera_gallery_subtitle_empty)
                        updateStatus(getString(R.string.remote_camera_no_photos))
                    } else {
                        resolvedGalleryDeviceId = resolvedDeviceId
                        val serverUrl = SecureSettingsManager(this@RemoteCameraActivity).getServerUrl().trim()
                        if (serverUrl.isBlank()) {
                            emptyStateLayout.visibility = View.VISIBLE
                            photosRecyclerView.visibility = View.GONE
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

                        photoAdapter.submitList(items)
                        photosRecyclerView.visibility = View.VISIBLE
                        emptyStateLayout.visibility = View.GONE
                        galleryMetaText.text = getString(
                            R.string.remote_camera_gallery_subtitle_loaded,
                            items.size,
                            dateFormatter.format(Date(photos.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()))
                        )
                        updateStatus(getString(R.string.remote_camera_gallery_updated))
                    }
                } else {
                    Log.e(TAG, "Failed to load photos: ${response.code()}")
                    emptyStateLayout.visibility = View.VISIBLE
                    photosRecyclerView.visibility = View.GONE
                    galleryMetaText.text = getString(R.string.remote_camera_gallery_subtitle_empty)
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
                emptyStateLayout.visibility = View.VISIBLE
                photosRecyclerView.visibility = View.GONE
                galleryMetaText.text = getString(R.string.remote_camera_gallery_subtitle_empty)
                updateStatus(getString(R.string.remote_camera_load_error))
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
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val secureSettings = SecureSettingsManager(this)

        return buildList {
            resolvedGalleryDeviceId?.let(::add)
            childId?.let(::add)
            secureSettings.getChildDeviceId()?.let(::add)
            prefs.getString("child_device_id", null)?.let(::add)
            prefs.getString("selected_device_id", null)?.let(::add)
            legacyPrefs.getString("child_device_id", null)?.let(::add)
            legacyPrefs.getString("selected_device_id", null)?.let(::add)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearPendingRequest()
        photoReceivedListener?.let { WebSocketManager.removePhotoReceivedListener(it) }
        photoReceivedListener = null
        photoErrorListener?.let { WebSocketManager.removePhotoErrorListener(it) }
        photoErrorListener = null
        photoQueuedListener?.let { WebSocketManager.removePhotoQueuedListener(it) }
        photoQueuedListener = null
        Log.d(TAG, "RemoteCameraActivity destroyed")
    }

    /**
     * Download and save photo to device storage
     */
    private fun downloadAndSavePhoto(photoItem: RemotePhotoItem) {
        lifecycleScope.launch {
            try {
                updateStatus(getString(R.string.remote_camera_downloading))
                
                // Download photo
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val request = Request.Builder()
                    .url(photoItem.fullImageUrl)
                    .build()
                
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                if (!response.isSuccessful) {
                    updateStatus(getString(R.string.remote_camera_download_failed))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_error_format, response.code.toString()),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                
                val bytes = response.body?.bytes() ?: run {
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
                
                // Download to cache
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val request = Request.Builder()
                    .url(photoItem.fullImageUrl)
                    .build()
                
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                if (!response.isSuccessful) {
                    updateStatus(getString(R.string.remote_camera_download_failed))
                    Toast.makeText(
                        this@RemoteCameraActivity,
                        getString(R.string.remote_camera_download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                
                val bytes = response.body?.bytes() ?: return@launch
                
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
    }

    private fun disableButtons() {
        takePhotoButton.isEnabled = false
        cameraBackButton.isEnabled = false
        cameraFrontButton.isEnabled = false
    }

    private fun enableButtons() {
        takePhotoButton.isEnabled = true
        cameraBackButton.isEnabled = true
        cameraFrontButton.isEnabled = true
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
}

