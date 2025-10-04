package ru.example.childwatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import ru.example.childwatch.databinding.ActivityPhotoBinding
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup UI
        setupUI()
        updateUI()
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
        if (!PermissionHelper.hasCameraPermission(this)) {
            requestCameraPermission()
            return
        }
        
        try {
            val photoFile = createImageFile()
            currentPhotoPath = photoFile.absolutePath
            
            val photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (takePictureIntent.resolveActivity(packageManager) != null) {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
            } else {
                Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo", e)
            Toast.makeText(this, "Ошибка создания фото: ${e.message}", Toast.LENGTH_SHORT).show()
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
}