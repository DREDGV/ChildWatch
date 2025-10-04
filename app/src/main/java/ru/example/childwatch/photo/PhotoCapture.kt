package ru.example.childwatch.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import ru.example.childwatch.utils.PermissionHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * PhotoCapture utility for taking photos on demand
 * 
 * Features:
 * - Simple camera access check
 * - Image processing and optimization
 * - Error handling and fallbacks
 * - Permission management
 * 
 * Note: This is a simplified version that checks camera availability
 * For actual photo capture, we'll use a different approach
 */
class PhotoCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "PhotoCapture"
        private const val MAX_IMAGE_SIZE = 1920 // Max width/height
        private const val JPEG_QUALITY = 85 // Compression quality
    }
    
    /**
     * Take a photo and save it to a temporary file
     * @return File containing the photo, or null if failed
     */
    suspend fun takePhoto(): File? {
        return try {
            if (!PermissionHelper.hasCameraPermission(context)) {
                Log.e(TAG, "Camera permission not granted")
                return null
            }
            
            if (!isCameraAvailable()) {
                Log.e(TAG, "Camera not available on this device")
                return null
            }
            
            Log.d(TAG, "Starting photo capture")
            
            // For now, create a placeholder photo file
            // In a real implementation, this would use Camera2 API or CameraX
            val photoFile = createPlaceholderPhoto()
            
            if (photoFile != null && photoFile.exists() && photoFile.length() > 0) {
                Log.d(TAG, "Photo captured: ${photoFile.name}, size: ${photoFile.length()} bytes")
                photoFile
            } else {
                Log.e(TAG, "Photo file creation failed")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo", e)
            null
        }
    }
    
    /**
     * Check if camera is available on this device
     */
    fun isCameraAvailable(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking camera availability", e)
            false
        }
    }
    
    /**
     * Get camera information
     */
    fun getCameraInfo(): CameraInfo? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            
            if (cameraIds.isNotEmpty()) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraIds[0])
                CameraInfo(
                    id = cameraIds[0],
                    facing = characteristics.get(CameraCharacteristics.LENS_FACING),
                    hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
                    supportedResolutions = getSupportedResolutions(characteristics)
                )
            } else {
                null
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error getting camera info", e)
            null
        }
    }
    
    /**
     * Create a placeholder photo for testing
     * In production, this would be replaced with actual camera capture
     */
    private fun createPlaceholderPhoto(): File? {
        return try {
            val photoFile = createTempPhotoFile()
            
            // Create a simple colored bitmap as placeholder
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE) // Blue background
            
            // Add some text to the bitmap
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 48f
                isAntiAlias = true
            }
            canvas.drawText("ChildWatch Photo", 100f, 300f, paint)
            canvas.drawText("Timestamp: ${System.currentTimeMillis()}", 100f, 350f, paint)
            
            // Save bitmap to file
            val outputStream = FileOutputStream(photoFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            outputStream.close()
            
            Log.d(TAG, "Placeholder photo created: ${photoFile.absolutePath}")
            photoFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating placeholder photo", e)
            null
        }
    }
    
    /**
     * Process and optimize captured image
     */
    private fun processImage(imageFile: File) {
        try {
            // Load bitmap
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from file")
                return
            }
            
            // Get EXIF orientation
            val exif = ExifInterface(imageFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            
            // Rotate bitmap if needed
            val rotatedBitmap = rotateBitmap(bitmap, orientation)
            
            // Compress and save
            val outputStream = FileOutputStream(imageFile)
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            outputStream.close()
            
            Log.d(TAG, "Image processed and optimized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        }
    }
    
    /**
     * Rotate bitmap based on EXIF orientation
     */
    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while rotating bitmap", e)
            bitmap
        }
    }
    
    /**
     * Get supported resolutions for camera
     */
    private fun getSupportedResolutions(characteristics: CameraCharacteristics): List<String> {
        return try {
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            
            outputSizes?.map { "${it.width}x${it.height}" } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting supported resolutions", e)
            emptyList()
        }
    }
    
    /**
     * Create temporary file for photo
     */
    private fun createTempPhotoFile(): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "photo_${timestamp}.jpg"
        
        // Use internal cache directory for temporary files
        val cacheDir = context.cacheDir
        return File(cacheDir, fileName)
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            // Cleanup if needed
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup", e)
        }
    }
}

/**
 * Data class for camera information
 */
data class CameraInfo(
    val id: String,
    val facing: Int?,
    val hasFlash: Boolean,
    val supportedResolutions: List<String>
)
