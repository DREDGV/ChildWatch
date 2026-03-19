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
import java.util.Date
import java.util.Locale

class PhotoPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoPreviewBinding
    private var currentBitmap: Bitmap? = null
    private var photoTimestamp: Long = 0
    private var deviceName: String = ""

    companion object {
        const val EXTRA_PHOTO_BASE64 = "photo_base64"
        const val EXTRA_PHOTO_FILE_PATH = "photo_file_path"
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
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadPhotoFromIntent() {
        val photoFilePath = intent.getStringExtra(EXTRA_PHOTO_FILE_PATH)
        val photoBase64 = intent.getStringExtra(EXTRA_PHOTO_BASE64)
        photoTimestamp = intent.getLongExtra(EXTRA_PHOTO_TIMESTAMP, System.currentTimeMillis())
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
            ?: getString(R.string.photo_preview_device_fallback)

        if (photoFilePath.isNullOrEmpty() && photoBase64.isNullOrEmpty()) {
            showError(getString(R.string.photo_preview_no_data))
            return
        }

        try {
            binding.loadingProgress.isVisible = true
            binding.errorLayout.isVisible = false
            binding.photoImageView.isVisible = true

            currentBitmap = when {
                !photoFilePath.isNullOrEmpty() -> decodeBitmapFromFile(photoFilePath)
                !photoBase64.isNullOrEmpty() -> decodeBitmapFromBytes(Base64.decode(photoBase64, Base64.DEFAULT))
                else -> null
            }

            if (currentBitmap != null) {
                binding.photoImageView.setImageBitmap(currentBitmap)
                binding.loadingProgress.isVisible = false
                updatePhotoInfo()
            } else {
                showError(getString(R.string.photo_preview_decode_error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photo", e)
            showError(getString(R.string.photo_preview_load_error, e.message ?: "unknown"))
        }
    }

    private fun decodeBitmapFromFile(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        val metrics = resources.displayMetrics
        val sampleSize = calculateInSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            metrics.widthPixels.coerceAtLeast(1080),
            metrics.heightPixels.coerceAtLeast(1920)
        )

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeBitmapFromBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val metrics = resources.displayMetrics
        val sampleSize = calculateInSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            metrics.widthPixels.coerceAtLeast(1080),
            metrics.heightPixels.coerceAtLeast(1920)
        )

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        while (width / inSampleSize >= reqWidth * 2 && height / inSampleSize >= reqHeight * 2) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun updatePhotoInfo() {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        binding.photoTimestamp.text = dateFormat.format(Date(photoTimestamp))
        binding.photoDeviceName.text = deviceName
    }

    private fun setupButtons() {
        binding.saveButton.setOnClickListener { savePhotoToGallery() }
        binding.shareButton.setOnClickListener { sharePhoto() }
        binding.retryButton.setOnClickListener { loadPhotoFromIntent() }
    }

    private fun savePhotoToGallery() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.photo_preview_save_empty), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "RemotePhoto_${System.currentTimeMillis()}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                    Toast.makeText(this, getString(R.string.photo_preview_saved), Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Photo saved: $uri")
                }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val childWatchDir = File(picturesDir, "ChildWatch")
                if (!childWatchDir.exists()) {
                    childWatchDir.mkdirs()
                }

                val file = File(childWatchDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }

                val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaScanIntent.data = Uri.fromFile(file)
                sendBroadcast(mediaScanIntent)

                Toast.makeText(this, getString(R.string.photo_preview_saved), Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Photo saved: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photo", e)
            Toast.makeText(
                this,
                getString(R.string.photo_preview_save_failed, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sharePhoto() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.photo_preview_share_empty), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val cachePath = File(cacheDir, "shared_photos")
            cachePath.mkdirs()
            val file = File(cachePath, "remote_photo_${System.currentTimeMillis()}.jpg")

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.photo_preview_share_text, deviceName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.photo_preview_share_title)))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing photo", e)
            Toast.makeText(
                this,
                getString(R.string.photo_preview_share_failed, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
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
