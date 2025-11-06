package ru.example.childwatch

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import ru.example.childwatch.databinding.ActivityPhotoPreviewBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * PhotoPreviewActivity - Display remotely captured photos
 * 
 * Features:
 * - Full-screen photo viewer with zoom
 * - Save to gallery
 * - Share functionality
 * - Photo metadata display (timestamp, device)
 */
class PhotoPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoPreviewBinding
    private var currentBitmap: Bitmap? = null
    private var photoTimestamp: Long = 0
    private var deviceName: String = ""

    companion object {
        const val EXTRA_PHOTO_BASE64 = "photo_base64"
        const val EXTRA_PHOTO_TIMESTAMP = "photo_timestamp"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val TAG = "PhotoPreviewActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadPhotoFromIntent()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadPhotoFromIntent() {
        val photoBase64 = intent.getStringExtra(EXTRA_PHOTO_BASE64)
        photoTimestamp = intent.getLongExtra(EXTRA_PHOTO_TIMESTAMP, System.currentTimeMillis())
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Unknown Device"

        if (photoBase64.isNullOrEmpty()) {
            showError("No photo data received")
            return
        }

        try {
            binding.loadingProgress.isVisible = true
            binding.errorLayout.isVisible = false

            // Decode Base64 to Bitmap
            val imageBytes = Base64.decode(photoBase64, Base64.DEFAULT)
            currentBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            if (currentBitmap != null) {
                binding.photoImageView.setImageBitmap(currentBitmap)
                binding.loadingProgress.isVisible = false
                updatePhotoInfo()
            } else {
                showError("Failed to decode photo")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photo", e)
            showError("Error loading photo: ${e.message}")
        }
    }

    private fun updatePhotoInfo() {
        // Format timestamp
        val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
        val timeText = dateFormat.format(Date(photoTimestamp))
        
        binding.photoTimestamp.text = timeText
        binding.photoDeviceName.text = deviceName
    }

    private fun setupButtons() {
        binding.saveButton.setOnClickListener {
            savePhotoToGallery()
        }

        binding.shareButton.setOnClickListener {
            sharePhoto()
        }

        binding.retryButton.setOnClickListener {
            loadPhotoFromIntent()
        }
    }

    private fun savePhotoToGallery() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No photo to save", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "RemotePhoto_${System.currentTimeMillis()}.jpg"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChildWatch")
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    }
                    Toast.makeText(this, "Photo saved to gallery", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Photo saved: $uri")
                }
            } else {
                // Legacy storage for older Android versions
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val childWatchDir = File(picturesDir, "ChildWatch")
                if (!childWatchDir.exists()) {
                    childWatchDir.mkdirs()
                }

                val file = File(childWatchDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }

                // Notify media scanner
                val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaScanIntent.data = Uri.fromFile(file)
                sendBroadcast(mediaScanIntent)

                Toast.makeText(this, "Photo saved to gallery", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Photo saved: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photo", e)
            Toast.makeText(this, "Failed to save photo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePhoto() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No photo to share", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Save to cache directory
            val cachePath = File(cacheDir, "shared_photos")
            cachePath.mkdirs()
            val file = File(cachePath, "remote_photo_${System.currentTimeMillis()}.jpg")

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }

            // Create share intent
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Remote photo from $deviceName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share photo"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing photo", e)
            Toast.makeText(this, "Failed to share photo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showError(message: String) {
        binding.loadingProgress.isVisible = false
        binding.photoImageView.isVisible = false
        binding.errorLayout.isVisible = true
        binding.errorText.text = message
        Log.e(TAG, message)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        currentBitmap = null
    }
}
