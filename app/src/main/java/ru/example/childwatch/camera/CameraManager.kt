package ru.example.childwatch.camera

import android.content.Context
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * Camera Manager for taking photos remotely
 * Handles background photo capture without UI
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        private const val IMAGE_WIDTH = 1920
        private const val IMAGE_HEIGHT = 1080
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    fun takePhoto(callback: (File?) -> Unit) {
        startBackgroundThread()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        
        try {
            // Get back camera ID
            val cameraId = getCameraId(cameraManager) ?: run {
                Log.e(TAG, "No back camera found")
                callback(null)
                return
            }

            // Setup image reader
            imageReader = ImageReader.newInstance(
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
                android.graphics.ImageFormat.JPEG,
                1
            )

            val outputFile = createOutputFile()

            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    image?.let {
                        saveImage(it, outputFile)
                        it.close()
                        cleanup()
                        callback(outputFile)
                    } ?: run {
                        cleanup()
                        callback(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                    cleanup()
                    callback(null)
                }
            }, backgroundHandler)

            // Open camera
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(outputFile, callback)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cleanup()
                        callback(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera error: $error")
                        cleanup()
                        callback(null)
                    }
                }, backgroundHandler)
            } catch (e: SecurityException) {
                Log.e(TAG, "Camera permission not granted", e)
                cleanup()
                callback(null)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo", e)
            cleanup()
            callback(null)
        }
    }

    private fun getCameraId(cameraManager: android.hardware.camera2.CameraManager): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera ID", e)
            null
        }
    }

    private fun createCaptureSession(outputFile: File, callback: (File?) -> Unit) {
        try {
            val surface = imageReader?.surface ?: run {
                callback(null)
                return
            }

            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        capturePhoto()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        cleanup()
                        callback(null)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
            cleanup()
            callback(null)
        }
    }

    private fun capturePhoto() {
        try {
            val surface = imageReader?.surface ?: return

            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(surface)

            // Set auto-focus and auto-exposure
            captureBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

            captureSession?.capture(
                captureBuilder?.build()!!,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Photo capture completed")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Photo capture failed: ${failure.reason}")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
        }
    }

    private fun saveImage(image: Image, file: File) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            FileOutputStream(file).use { output ->
                output.write(bytes)
            }

            Log.d(TAG, "Photo saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image", e)
        }
    }

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.getExternalFilesDir(null) ?: context.filesDir
        val photoDir = File(storageDir, "photos")
        if (!photoDir.exists()) {
            photoDir.mkdirs()
        }
        return File(photoDir, "photo_$timestamp.jpg")
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

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

    private fun cleanup() {
        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null

            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    fun isCameraAvailable(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            getCameraId(cameraManager) != null
        } catch (e: Exception) {
            false
        }
    }
}
