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
import org.json.JSONObject
import ru.example.childwatch.network.WebSocketManager
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.remote.RemotePhotoAdapter
import ru.example.childwatch.remote.RemotePhotoItem
import ru.example.childwatch.utils.SecureSettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

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
    private lateinit var photoAdapter: RemotePhotoAdapter

    private var childId: String? = null
    private var childName: String? = null
    private val networkClient by lazy { NetworkClient(applicationContext) }
    private val dateFormatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private var photoReceivedListener: ((String, String, Long) -> Unit)? = null
    private var photoErrorListener: ((String, String) -> Unit)? = null
    private var retryJob: Job? = null
    private var pendingRequestId: String? = null
    private var selectedCameraFacing: String = "back"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_camera)

        // Get child device info from intent
        childId = intent.getStringExtra(EXTRA_CHILD_ID)
        childName = intent.getStringExtra(EXTRA_CHILD_NAME)

        if (childId == null) {
            Toast.makeText(this, "Ошибка: не указан ID устройства ребенка", Toast.LENGTH_SHORT).show()
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

            photoAdapter = RemotePhotoAdapter()
            photosRecyclerView.apply {
                layoutManager = GridLayoutManager(this@RemoteCameraActivity, 2)
                adapter = photoAdapter
            }

            // Display child name if available
            childNameText.text = if (childName != null) {
                "Устройство: $childName"
            } else {
                "ID: $childId"
            }
            cameraToggleGroup.check(cameraBackButton.id)
            selectedCameraFacing = "back"
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views", e)
            Toast.makeText(this, "Ошибка загрузки интерфейса: ${e.message}", Toast.LENGTH_SHORT).show()
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
                if (selectedCameraFacing == "front") "Выбрана фронтальная камера"
                else "Выбрана основная камера"
            )
        }

        takePhotoButton.setOnClickListener {
            takePhoto()
        }
    }

    /**
     * Send take_photo command to child device (uses back camera by default)
     */
    private fun takePhoto() {
        Log.d(TAG, "Taking photo for child: $childId")
        updateStatus("Connecting...")
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
                    updateStatus("Подключено")
                    onReady()
                }
            },
            onError = { error ->
                runOnUiThread {
                    updateStatus("Ошибка подключения")
                    Toast.makeText(this, "Ошибка подключения: $error", Toast.LENGTH_SHORT).show()
                    enableButtons()
                }
            }
        )
    }

    private fun registerPhotoListeners() {
        if (photoReceivedListener == null) {
            photoReceivedListener = photoReceivedListener@{ photoBase64, requestId, timestamp ->
                if (pendingRequestId != requestId) return@photoReceivedListener
                pendingRequestId = null
                retryJob?.cancel()
                runOnUiThread {
                    updateStatus("Фото получено")
                    enableButtons()
                    openPhotoPreview(photoBase64, timestamp)
                    scheduleGalleryRefresh()
                }
            }
            WebSocketManager.addPhotoReceivedListener(photoReceivedListener!!)
        }

        if (photoErrorListener == null) {
            photoErrorListener = photoErrorListener@{ requestId, error ->
                if (pendingRequestId != requestId) return@photoErrorListener
                pendingRequestId = null
                retryJob?.cancel()
                runOnUiThread {
                    updateStatus("Ошибка: $error")
                    Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_SHORT).show()
                    enableButtons()
                }
            }
            WebSocketManager.addPhotoErrorListener(photoErrorListener!!)
        }
    }

    private fun sendPhotoRequestWithRetry() {
        val targetId = childId ?: return
        val requestId = java.util.UUID.randomUUID().toString()
        pendingRequestId = requestId
        val camera = selectedCameraFacing
        updateStatus(
            if (camera == "front") "Sending request (front camera)..."
            else "Sending request (back camera)..."
        )

        val delays = listOf(0L, 3000L, 7000L, 12000L)
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
                pendingRequestId = null
                runOnUiThread {
                    updateStatus("Request timeout")
                    Toast.makeText(this@RemoteCameraActivity, "No response from device", Toast.LENGTH_SHORT).show()
                    enableButtons()
                }
            }
        }
    }

    private fun scheduleGalleryRefresh() {
        lifecycleScope.launch {
            val delays = listOf(2000L, 4000L, 8000L, 12000L, 16000L)
            for (delayMs in delays) {
                delay(delayMs)
                loadPhotos()
            }
        }
    }

    /**
     * Send take_photo command via WebSocket
     */
    private fun sendTakePhotoCommand(deviceId: String) {
        try {
            // Build command payload (camera facing and deviceId)
            val payload = JSONObject().apply {
                put("camera", "back")  // Always use back camera
            }

            // Send command with proper structure: type + data + deviceId
            WebSocketManager.sendCommand(
                commandType = "take_photo",
                data = payload,
                onSuccess = {
                    Log.d(TAG, "✅ take_photo command sent successfully to device: $deviceId")
                    runOnUiThread {
                        updateStatus("Команда отправлена")
                        Toast.makeText(this, "Команда отправлена устройству", Toast.LENGTH_SHORT).show()
                        enableButtons()
                        
                        // Reload photos after a delay to show the new photo
                        statusText.postDelayed({
                            loadPhotos()
                        }, 3000)
                    }
                },
                onError = { error ->
                    Log.e(TAG, "❌ Failed to send take_photo command: $error")
                    runOnUiThread {
                        updateStatus("Ошибка отправки")
                        Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_SHORT).show()
                        enableButtons()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending take_photo command", e)
            runOnUiThread {
                updateStatus("Ошибка")
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                enableButtons()
            }
        }
    }

    private fun openPhotoPreview(photoBase64: String, timestamp: Long) {
        try {
            val intent = Intent(this, PhotoPreviewActivity::class.java).apply {
                putExtra(PhotoPreviewActivity.EXTRA_PHOTO_BASE64, photoBase64)
                putExtra(PhotoPreviewActivity.EXTRA_PHOTO_TIMESTAMP, timestamp)
                putExtra(PhotoPreviewActivity.EXTRA_DEVICE_NAME, childName ?: "Child Device")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening photo preview", e)
        }
    }

    /**
     * Load photos from server
     */
    private fun loadPhotos() {
        Log.d(TAG, "Loading photos for device: $childId")
        val deviceId = childId ?: return
        
        // Show loading state
        progressIndicator.visibility = View.VISIBLE
        photosRecyclerView.visibility = View.GONE
        emptyStateLayout.visibility = View.GONE

        updateStatus("Загрузка галереи...")

        lifecycleScope.launch {
            try {
                val response = networkClient.getRemotePhotos(deviceId, limit = 30)
                progressIndicator.visibility = View.GONE

                if (response.isSuccessful) {
                    val body = response.body()
                    val photos = body?.photoFiles.orEmpty()

                    if (photos.isEmpty()) {
                        photoAdapter.submitList(emptyList())
                        emptyStateLayout.visibility = View.VISIBLE
                        photosRecyclerView.visibility = View.GONE
                        updateStatus("Фото пока нет")
                    } else {
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
                        updateStatus("Галерея обновлена")
                    }
                } else {
                    Log.e(TAG, "Failed to load photos: ${response.code()}")
                    emptyStateLayout.visibility = View.VISIBLE
                    photosRecyclerView.visibility = View.GONE
                    updateStatus("Не удалось получить фото")
                    Toast.makeText(this@RemoteCameraActivity, "Ошибка загрузки фотографий", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                progressIndicator.visibility = View.GONE
                emptyStateLayout.visibility = View.VISIBLE
                photosRecyclerView.visibility = View.GONE
                updateStatus("Ошибка загрузки")
                Toast.makeText(this@RemoteCameraActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        retryJob?.cancel()
        photoReceivedListener?.let { WebSocketManager.removePhotoReceivedListener(it) }
        photoReceivedListener = null
        photoErrorListener?.let { WebSocketManager.removePhotoErrorListener(it) }
        photoErrorListener = null
        Log.d(TAG, "RemoteCameraActivity destroyed")
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
            "${width}×${height}"
        } else {
            null
        }
        val sizeLabel = when {
            sizeBytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f МБ", sizeBytes / 1024f / 1024f)
            sizeBytes >= 1024 -> "${sizeBytes / 1024} КБ"
            else -> "${sizeBytes} Б"
        }

        return listOfNotNull(formattedDate, resolution, sizeLabel).joinToString(" • ")
    }
}
