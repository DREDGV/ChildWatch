package ru.example.childwatch

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import ru.example.childwatch.network.WebSocketManager
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.remote.RemotePhotoAdapter
import ru.example.childwatch.remote.RemotePhotoItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var photosRecyclerView: RecyclerView
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var photoAdapter: RemotePhotoAdapter

    private var childId: String? = null
    private var childName: String? = null
    private val networkClient by lazy { NetworkClient(applicationContext) }
    private val dateFormatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

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
    }

    private fun initViews() {
        try {
            toolbar = findViewById(R.id.toolbar)
            statusText = findViewById(R.id.statusText)
            childNameText = findViewById(R.id.childNameText)
            takePhotoButton = findViewById(R.id.takePhotoButton)
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
        takePhotoButton.setOnClickListener {
            takePhoto()
        }
    }

    /**
     * Send take_photo command to child device (uses back camera by default)
     */
    private fun takePhoto() {
        Log.d(TAG, "Taking photo for child: $childId")
        
        // Check WebSocket connection
        if (!WebSocketManager.isConnected()) {
            Toast.makeText(
                this,
                "Нет связи с устройством ребенка. Подключите устройство.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Update UI
        updateStatus("Отправка команды...")
        disableButtons()

        // Send command via WebSocket (always use back camera)
        sendTakePhotoCommand(childId!!)
    }

    /**
     * Send take_photo command via WebSocket
     */
    private fun sendTakePhotoCommand(deviceId: String) {
        try {
            val commandData = JSONObject().apply {
                put("camera", "back")  // Always use back camera
                put("deviceId", deviceId)
            }

            WebSocketManager.sendCommand(
                "take_photo",
                commandData,
                onSuccess = {
                    Log.d(TAG, "✅ take_photo command sent successfully")
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
                    Log.e(TAG, "❌ Failed to send command: $error")
                    runOnUiThread {
                        updateStatus("Ошибка отправки")
                        Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_SHORT).show()
                        enableButtons()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending command", e)
            runOnUiThread {
                updateStatus("Ошибка")
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                enableButtons()
            }
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
                        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
                        val serverUrl = prefs.getString("server_url", "https://childwatch-production.up.railway.app")
                            ?: "https://childwatch-production.up.railway.app"

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

    private fun updateStatus(status: String) {
        statusText.text = status
    }

    private fun disableButtons() {
        takePhotoButton.isEnabled = false
    }

    private fun enableButtons() {
        takePhotoButton.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RemoteCameraActivity destroyed")
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
