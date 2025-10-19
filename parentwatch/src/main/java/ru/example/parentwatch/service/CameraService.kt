package ru.example.parentwatch.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Camera Service for background photo capture
 *
 * Features:
 * - Background photo capture without preview
 * - Front/Back camera selection
 * - Silent capture (no shutter sound on some devices)
 * - Auto-save to storage
 */
class CameraService(private val context: Context) {

    companion object {
        private const val TAG = "CameraService"
        private const val IMAGE_WIDTH = 1920
        private const val IMAGE_HEIGHT = 1080
    }

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var captureCallback: ((File?) -> Unit)? = null

    enum class CameraFacing {
        FRONT,
        BACK
    }

    /**
     * Initialize camera service
     */
    fun initialize() {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()
    }

    /**
     * Capture photo from specified camera
     */
    @SuppressLint("MissingPermission")
    fun capturePhoto(cameraFacing: CameraFacing, callback: (File?) -> Unit) {
        this.captureCallback = callback

        try {
            val cameraId = getCameraId(cameraFacing)
            if (cameraId == null) {
                Log.e(TAG, "No camera found for facing: $cameraFacing")
                callback(null)
                return
            }

            Log.d(TAG, "Opening camera: $cameraId")
            cameraManager?.openCamera(cameraId, stateCallback, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error", e)
            callback(null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission not granted", e)
            callback(null)
        }
    }

    /**
     * Get camera ID for specified facing
     */
    private fun getCameraId(facing: CameraFacing): String? {
        try {
            val cameraIdList = cameraManager?.cameraIdList ?: return null

            for (cameraId in cameraIdList) {
                val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics?.get(CameraCharacteristics.LENS_FACING)

                val targetFacing = when (facing) {
                    CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
                    CameraFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
                }

                if (lensFacing == targetFacing) {
                    return cameraId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera ID", e)
        }
        return null
    }

    /**
     * Camera state callback
     */
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened successfully")
            cameraDevice = camera
            takePicture()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            cleanup()
            captureCallback?.invoke(null)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            cleanup()
            captureCallback?.invoke(null)
        }
    }

    /**
     * Take picture using camera
     */
    private fun takePicture() {
        try {
            // Setup image reader
            imageReader = ImageReader.newInstance(
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
                ImageFormat.JPEG,
                1
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    saveImage(image)
                    image.close()
                }
            }, backgroundHandler)

            // Create capture session
            val surfaces = listOf(imageReader?.surface)
            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Capture session configured")
                        captureImage(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        cleanup()
                        captureCallback?.invoke(null)
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error taking picture", e)
            cleanup()
            captureCallback?.invoke(null)
        }
    }

    /**
     * Capture image from session
     */
    private fun captureImage(session: CameraCaptureSession) {
        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(imageReader?.surface!!)

            // Set capture parameters for best quality
            captureBuilder?.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            captureBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            session.capture(
                captureBuilder?.build()!!,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Image capture completed")
                        // Cleanup after short delay to ensure image is saved
                        backgroundHandler?.postDelayed({
                            cleanup()
                        }, 1000)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Image capture failed")
                        cleanup()
                        captureCallback?.invoke(null)
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing image", e)
            cleanup()
            captureCallback?.invoke(null)
        }
    }

    /**
     * Save captured image to file
     */
    private fun saveImage(image: android.media.Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Create file with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoDir = File(context.getExternalFilesDir(null), "photos")
            if (!photoDir.exists()) {
                photoDir.mkdirs()
            }

            val photoFile = File(photoDir, "PHOTO_$timestamp.jpg")
            FileOutputStream(photoFile).use { output ->
                output.write(bytes)
            }

            Log.d(TAG, "Photo saved: ${photoFile.absolutePath}")
            captureCallback?.invoke(photoFile)

        } catch (e: Exception) {
            Log.e(TAG, "Error saving image", e)
            captureCallback?.invoke(null)
        }
    }

    /**
     * Cleanup camera resources
     */
    fun cleanup() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Release all resources
     */
    fun release() {
        cleanup()
        stopBackgroundThread()
    }

    /**
     * Start background thread for camera operations
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    /**
     * Stop background thread
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
}
