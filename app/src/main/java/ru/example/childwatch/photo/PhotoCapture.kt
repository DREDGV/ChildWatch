package ru.example.childwatch.photo

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.view.Surface
import ru.example.childwatch.utils.PermissionHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PhotoCapture utility for taking photos on demand using Camera2 API
 * 
 * Features:
 * - Real camera capture using Camera2 API
 * - Background photo capture (works in foreground service)
 * - Image processing and optimization
 * - Error handling and fallbacks
 * - Permission management
 * - Privacy indicator awareness (Android 12+)
 * 
 * Note: On Android 12+ (API 31+), a green indicator will appear when camera is active
 */
class PhotoCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "PhotoCapture"
        private const val MAX_IMAGE_SIZE = 1920 // Max width/height
        private const val JPEG_QUALITY = 85 // Compression quality
        private const val CAPTURE_TIMEOUT_SECONDS = 10L
    }
    
    /**
     * Take a photo and save it to a temporary file
     * Uses Camera2 API for real photo capture
     * @return File containing the photo, or null if failed
     */
    suspend fun takePhoto(): File? = withContext(Dispatchers.IO) {
        try {
            if (!PermissionHelper.hasCameraPermission(context)) {
                Log.e(TAG, "Camera permission not granted")
                return@withContext null
            }
            
            if (!isCameraAvailable()) {
                Log.e(TAG, "Camera not available on this device")
                return@withContext null
            }
            
            Log.d(TAG, "Starting real photo capture with Camera2 API")
            
            // Capture real photo using Camera2
            val photoFile = captureRealPhoto()
            
            if (photoFile != null && photoFile.exists() && photoFile.length() > 0) {
                Log.d(TAG, "Photo captured: ${photoFile.name}, size: ${photoFile.length()} bytes")
                photoFile
            } else {
                Log.e(TAG, "Photo capture failed")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo", e)
            null
        }
    }
    
    /**
     * Capture real photo using Camera2 API
     * Works in background by using a dummy SurfaceTexture
     */
    private fun captureRealPhoto(): File? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: run {
            Log.e(TAG, "No camera found")
            return null
        }
        
        Log.d(TAG, "Using camera: $cameraId")
        
        // Create dummy SurfaceTexture (required for Camera2 API)
        val dummyTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(1920, 1080)
        }
        val dummySurface = Surface(dummyTexture)
        
        // Create ImageReader for capturing JPEG
        val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
        val photoFile = createTempPhotoFile()
        
        val latch = CountDownLatch(1)
        var captureSuccess = false
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        
        // Set up image available listener
        imageReader.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        
                        // Save to file
                        FileOutputStream(photoFile).use { it.write(bytes) }
                        captureSuccess = true
                        Log.d(TAG, "Image data written to file: ${bytes.size} bytes")
                    } finally {
                        image.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                latch.countDown()
            }
        }, null)
        
        try {
            // Open camera
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d(TAG, "Camera opened successfully")
                    
                    try {
                        // Create capture session with both dummy surface and image reader
                        camera.createCaptureSession(
                            listOf(dummySurface, imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    Log.d(TAG, "Capture session configured")
                                    
                                    try {
                                        // Create capture request
                                        val captureRequest = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        ).apply {
                                            // Add image reader surface for JPEG output
                                            addTarget(imageReader.surface)
                                            // Add dummy surface for Camera2 API requirement
                                            addTarget(dummySurface)
                                            
                                            // Set capture parameters
                                            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                                            set(CaptureRequest.JPEG_ORIENTATION, 0)
                                        }
                                        
                                        // Capture photo
                                        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult
                                            ) {
                                                Log.d(TAG, "Capture completed")
                                            }
                                            
                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: CaptureFailure
                                            ) {
                                                Log.e(TAG, "Capture failed: ${failure.reason}")
                                                latch.countDown()
                                            }
                                        }, null)
                                        
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error creating capture request", e)
                                        latch.countDown()
                                    }
                                }
                                
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Capture session configuration failed")
                                    latch.countDown()
                                }
                            },
                            null
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating capture session", e)
                        latch.countDown()
                    }
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    latch.countDown()
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    latch.countDown()
                }
            }, null)
            
            // Wait for capture to complete (with timeout)
            val completed = latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!completed) {
                Log.e(TAG, "Capture timeout after ${CAPTURE_TIMEOUT_SECONDS}s")
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            latch.countDown()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during capture", e)
            latch.countDown()
        } finally {
            // Clean up resources
            try {
                captureSession?.close()
                cameraDevice?.close()
                dummySurface.release()
                dummyTexture.release()
                imageReader.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up camera resources", e)
            }
        }
        
        return if (captureSuccess && photoFile.exists() && photoFile.length() > 0) {
            Log.d(TAG, "Real photo captured successfully: ${photoFile.absolutePath}")
            photoFile
        } else {
            Log.e(TAG, "Photo capture failed or file is empty")
            photoFile.delete()
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
     * Get supported resolutions for camera
     */
    private fun getSupportedResolutions(characteristics: CameraCharacteristics): List<String> {
        return try {
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)
            
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
