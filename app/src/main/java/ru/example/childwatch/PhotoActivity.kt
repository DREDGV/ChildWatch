package ru.example.childwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import ru.example.childwatch.databinding.ActivityPhotoBinding
import ru.example.childwatch.network.WebSocketClient
import ru.example.childwatch.utils.PermissionHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Photo/Video Activity for camera capture
 * 
 * Features:
 * - Photo capture with camera
 * - Video recording
 * - Image/video preview
 * - File management
 * - Media sharing
 */
class PhotoActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "PhotoActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1003
        private const val REQUEST_IMAGE_CAPTURE = 1004
        private const val REQUEST_VIDEO_CAPTURE = 1005
    }
    
    private lateinit var binding: ActivityPhotoBinding
    private var currentPhotoPath: String? = null
    private var currentVideoUri: Uri? = null
    private var webSocketClient: WebSocketClient? = null
    private var childDeviceId: String? = null
    private var serverUrl: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get device info from SharedPreferences
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        childDeviceId = prefs.getString("child_device_id", null)
        serverUrl = prefs.getString("server_url", null)

        // Setup UI
        setupUI()
        updateUI()

        // Initialize WebSocket if device is configured
        if (childDeviceId != null && serverUrl != null) {
            initializeWebSocket()
        }
    }
    
    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Удаленная камера"
        
        // Photo button
        binding.takePhotoBtn.setOnClickListener {
            takePhoto()
        }
        
        // Video button (if exists in layout)
        binding.takeVideoBtn?.setOnClickListener {
            takeVideo()
        }
        
        // Preview button (if exists in layout)
        binding.previewBtn?.setOnClickListener {
            previewMedia()
        }
        
        // Clear button (if exists in layout)
        binding.clearBtn?.setOnClickListener {
            clearMedia()
        }
        
        // Back button
        binding.backBtn.setOnClickListener {
            finish()
        }
    }
    
    private fun takePhoto() {
        // Show dialog to choose camera (front or back)
        AlertDialog.Builder(this)
            .setTitle("Выбор камеры")
            .setMessage("Какую камеру использовать на устройстве ребенка?")
            .setPositiveButton("📸 Фронтальная") { _, _ ->
                sendTakePhotoCommand("front")
            }
            .setNegativeButton("📷 Основная") { _, _ ->
                sendTakePhotoCommand("back")
            }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun sendTakePhotoCommand(camera: String) {
        if (webSocketClient == null || childDeviceId == null) {
            Toast.makeText(this, "WebSocket не подключен", Toast.LENGTH_SHORT).show()
            return
        }

        binding.statusText?.text = "Отправка команды..."

        val commandData = JSONObject().apply {
            put("camera", camera)
        }

        webSocketClient?.sendCommand(
            commandType = "take_photo",
            data = commandData,
            onSuccess = {
                runOnUiThread {
                    binding.statusText?.text = "✅ Команда отправлена! Ожидайте фото..."
                    Toast.makeText(this, "Команда отправлена на устройство", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.statusText?.text = "❌ Ошибка отправки"
                    Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun initializeWebSocket() {
        try {
            binding.statusText?.text = "Подключение к серверу..."

            webSocketClient = WebSocketClient(serverUrl!!, childDeviceId!!)
            webSocketClient?.connect(
                onConnected = {
                    runOnUiThread {
                        binding.statusText?.text = "✅ Подключено к устройству"
                        binding.takePhotoBtn.isEnabled = true
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        binding.statusText?.text = "❌ Ошибка подключения"
                        Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_LONG).show()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebSocket", e)
            binding.statusText?.text = "❌ Ошибка инициализации"
        }
    }
    
    private fun takeVideo() {
        if (!PermissionHelper.hasCameraPermission(this)) {
            requestCameraPermission()
            return
        }
        
        try {
            val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30) // Максимум 30 секунд
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1) // Высокое качество
            }
            
            if (takeVideoIntent.resolveActivity(packageManager) != null) {
                startActivityForResult(takeVideoIntent, REQUEST_VIDEO_CAPTURE)
            } else {
                Toast.makeText(this, "Видеокамера недоступна", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error taking video", e)
            Toast.makeText(this, "Ошибка создания видео: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = getExternalFilesDir("Pictures")
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }
    
    private fun previewMedia() {
        when {
            currentPhotoPath != null -> {
                val photoFile = File(currentPhotoPath!!)
                if (photoFile.exists()) {
                    val photoUri = FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    openMedia(photoUri, "image/*")
                } else {
                    Toast.makeText(this, "Файл фото не найден", Toast.LENGTH_SHORT).show()
                }
            }
            currentVideoUri != null -> {
                openMedia(currentVideoUri!!, "video/*")
            }
            else -> {
                Toast.makeText(this, "Нет медиафайлов для просмотра", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun openMedia(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Нет приложения для просмотра", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun clearMedia() {
        currentPhotoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
        currentPhotoPath = null
        currentVideoUri = null
        updateUI()
        Toast.makeText(this, "Медиафайлы очищены", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateUI() {
        val hasPhoto = currentPhotoPath != null
        val hasVideo = currentVideoUri != null
        
        binding.previewBtn?.isEnabled = hasPhoto || hasVideo
        binding.clearBtn?.isEnabled = hasPhoto || hasVideo
        
        val statusText = when {
            hasPhoto && hasVideo -> "📸 Фото и 🎥 Видео готовы"
            hasPhoto -> "📸 Фото готово"
            hasVideo -> "🎥 Видео готово"
            else -> "Готов к съемке"
        }
        
        binding.statusText?.text = statusText
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение на камеру предоставлено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Разрешение на камеру необходимо для работы", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_IMAGE_CAPTURE -> {
                if (resultCode == RESULT_OK) {
                    Toast.makeText(this, "📸 Фото сохранено!", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Photo saved: $currentPhotoPath")
                } else {
                    currentPhotoPath = null
                    Toast.makeText(this, "Съемка фото отменена", Toast.LENGTH_SHORT).show()
                }
                updateUI()
            }
            REQUEST_VIDEO_CAPTURE -> {
                if (resultCode == RESULT_OK) {
                    currentVideoUri = data?.data
                    Toast.makeText(this, "🎥 Видео сохранено!", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Video saved: $currentVideoUri")
                } else {
                    currentVideoUri = null
                    Toast.makeText(this, "Съемка видео отменена", Toast.LENGTH_SHORT).show()
                }
                updateUI()
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.disconnect()
        webSocketClient?.cleanup()
    }
}