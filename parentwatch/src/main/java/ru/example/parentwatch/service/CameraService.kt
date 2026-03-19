package ru.example.parentwatch.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera Service for background photo capture.
 *
 * The previous implementation opened the first matching lens and forced a fixed 1920x1080 JPEG
 * capture without warming the camera pipeline. On some devices that produces black frames, and on
 * others the alternate lens crashes because that output size is unsupported. This version chooses
 * a real backward-compatible camera, picks a supported JPEG size, warms the session with a dummy
 * preview surface, and guarantees the callback is delivered once.
 */
class CameraService(private val context: Context) {

    companion object {
        private const val TAG = "CameraService"
        private const val MAX_JPEG_WIDTH = 1920
        private const val MAX_JPEG_HEIGHT = 1080
        private const val PREVIEW_WARMUP_MS = 350L
        private const val CAPTURE_TIMEOUT_MS = 5000L
    }

    enum class CameraFacing {
        FRONT,
        BACK
    }

    private data class CameraConfig(
        val cameraId: String,
        val jpegSize: Size,
        val sensorOrientation: Int
    )

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var captureCallback: ((File?) -> Unit)? = null
    private var captureInProgress = false
    private var resultDelivered = false
    private var captureTriggered = false

    fun initialize() {
        if (cameraManager == null) {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        }
        startBackgroundThread()
    }

    fun hasCameraFacing(facing: CameraFacing): Boolean {
        if (cameraManager == null) initialize()
        return resolveCameraConfig(facing) != null
    }

    /**
     * Capture photo from specified camera.
     */
    @SuppressLint("MissingPermission")
    fun capturePhoto(cameraFacing: CameraFacing, callback: (File?) -> Unit) {
        initialize()

        synchronized(this) {
            if (captureInProgress) {
                Log.w(TAG, "Capture already in progress, ignoring new request for $cameraFacing")
                callback(null)
                return
            }
            captureInProgress = true
            resultDelivered = false
            captureTriggered = false
            captureCallback = callback
        }

        cleanupCaptureObjects()

        val config = resolveCameraConfig(cameraFacing)
        if (config == null) {
            Log.e(TAG, "No suitable camera found for facing: $cameraFacing")
            deliverResult(null)
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            Log.e(TAG, "Background handler is not available")
            deliverResult(null)
            return
        }

        Log.d(
            TAG,
            "Opening camera ${config.cameraId} facing=$cameraFacing size=${config.jpegSize.width}x${config.jpegSize.height}"
        )

        try {
            cameraManager?.openCamera(
                config.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(config)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Camera disconnected: ${config.cameraId}")
                        deliverResult(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera open error: id=${config.cameraId} code=$error")
                        deliverResult(null)
                    }
                },
                handler
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission not granted", e)
            deliverResult(null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error while opening ${config.cameraId}", e)
            deliverResult(null)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected camera open error", e)
            deliverResult(null)
        }
    }

    private fun resolveCameraConfig(facing: CameraFacing): CameraConfig? {
        return try {
            val manager = cameraManager ?: return null
            val targetFacing = when (facing) {
                CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
                CameraFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
            }

            manager.cameraIdList
                .mapNotNull { cameraId ->
                    val characteristics = manager.getCameraCharacteristics(cameraId)
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (lensFacing != targetFacing) return@mapNotNull null

                    val capabilities =
                        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                            ?: intArrayOf()
                    val isBackwardCompatible = capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                    )
                    if (!isBackwardCompatible) {
                        Log.d(TAG, "Skipping non-backward-compatible camera: $cameraId")
                        return@mapNotNull null
                    }

                    val streamConfigMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?: return@mapNotNull null
                    val jpegSize = chooseJpegSize(streamConfigMap.getOutputSizes(ImageFormat.JPEG))
                        ?: return@mapNotNull null
                    val sensorOrientation =
                        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                    CameraConfig(cameraId, jpegSize, sensorOrientation)
                }
                .sortedByDescending { it.jpegSize.width.toLong() * it.jpegSize.height.toLong() }
                .firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving camera config for $facing", e)
            null
        }
    }

    private fun chooseJpegSize(outputSizes: Array<Size>?): Size? {
        if (outputSizes.isNullOrEmpty()) return null

        val maxPixels = MAX_JPEG_WIDTH.toLong() * MAX_JPEG_HEIGHT.toLong()
        return outputSizes
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
            .firstOrNull { it.width.toLong() * it.height.toLong() <= maxPixels }
            ?: outputSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun createCaptureSession(config: CameraConfig) {
        val handler = backgroundHandler
        val camera = cameraDevice
        if (handler == null || camera == null) {
            deliverResult(null)
            return
        }

        try {
            imageReader = ImageReader.newInstance(
                config.jpegSize.width,
                config.jpegSize.height,
                ImageFormat.JPEG,
                2
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image == null) {
                    Log.w(TAG, "ImageReader returned null image")
                    return@setOnImageAvailableListener
                }

                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    if (bytes.isEmpty()) {
                        Log.e(TAG, "Captured JPEG is empty")
                        deliverResult(null)
                    } else {
                        val photoFile = createOutputFile()
                        FileOutputStream(photoFile).use { output ->
                            output.write(bytes)
                        }
                        Log.d(TAG, "Photo saved: ${photoFile.absolutePath} (${bytes.size} bytes)")
                        deliverResult(photoFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving captured image", e)
                    deliverResult(null)
                } finally {
                    image.close()
                }
            }, handler)

            previewTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(config.jpegSize.width, config.jpegSize.height)
            }
            previewSurface = Surface(previewTexture)

            val photoSurface = imageReader?.surface
            val preview = previewSurface
            if (photoSurface == null || preview == null) {
                Log.e(TAG, "Capture surfaces are not ready")
                deliverResult(null)
                return
            }

            camera.createCaptureSession(
                listOf(preview, photoSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreviewAndCapture(session, config)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed for ${config.cameraId}")
                        deliverResult(null)
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
            deliverResult(null)
        }
    }

    private fun startPreviewAndCapture(session: CameraCaptureSession, config: CameraConfig) {
        val handler = backgroundHandler
        val camera = cameraDevice
        val preview = previewSurface
        if (handler == null || camera == null || preview == null) {
            deliverResult(null)
            return
        }

        try {
            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }

            val fallbackCapture = Runnable {
                if (!captureTriggered && !resultDelivered) {
                    Log.d(TAG, "3A warmup timeout, capturing with fallback timer")
                    captureStillImage(session, config)
                }
            }

            session.setRepeatingRequest(
                previewRequest.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (!captureTriggered && is3aReady(result)) {
                            handler.removeCallbacks(fallbackCapture)
                            Log.d(TAG, "3A converged, capturing still image")
                            captureStillImage(session, config)
                        }
                    }
                },
                handler
            )

            handler.postDelayed(fallbackCapture, PREVIEW_WARMUP_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview warmup", e)
            deliverResult(null)
        }
    }

    private fun captureStillImage(session: CameraCaptureSession, config: CameraConfig) {
        val handler = backgroundHandler
        val camera = cameraDevice
        val photoSurface = imageReader?.surface
        if (handler == null || camera == null || photoSurface == null) {
            deliverResult(null)
            return
        }

        if (captureTriggered) return
        captureTriggered = true

        try {
            session.stopRepeating()
        } catch (_: Exception) {
        }

        try {
            val captureBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(photoSurface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    set(
                        CaptureRequest.JPEG_ORIENTATION,
                        normalizeJpegOrientation(config.sensorOrientation)
                    )
                }

            handler.postDelayed({
                if (!resultDelivered) {
                    Log.e(TAG, "Capture timed out waiting for image")
                    deliverResult(null)
                }
            }, CAPTURE_TIMEOUT_MS)

            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Still capture completed for ${config.cameraId}")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Still capture failed: ${failure.reason}")
                        deliverResult(null)
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing still image", e)
            deliverResult(null)
        }
    }

    private fun is3aReady(result: TotalCaptureResult): Boolean {
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)

        val aeReady = aeState == null ||
            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

        val afReady = afState == null ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ||
            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED

        return aeReady && afReady
    }

    private fun normalizeJpegOrientation(sensorOrientation: Int): Int {
        return ((sensorOrientation % 360) + 360) % 360
    }

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val photoDir = File(context.getExternalFilesDir(null), "photos")
        if (!photoDir.exists()) {
            photoDir.mkdirs()
        }
        return File(photoDir, "PHOTO_$timestamp.jpg")
    }

    @Synchronized
    private fun deliverResult(photoFile: File?) {
        if (resultDelivered) return
        resultDelivered = true
        val callback = captureCallback
        captureCallback = null
        captureInProgress = false
        captureTriggered = false
        cleanupCaptureObjects()
        callback?.invoke(photoFile)
    }

    private fun cleanupCaptureObjects() {
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session", e)
        } finally {
            captureSession = null
        }

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device", e)
        } finally {
            cameraDevice = null
        }

        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing image reader", e)
        } finally {
            imageReader = null
        }

        try {
            previewSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing preview surface", e)
        } finally {
            previewSurface = null
        }

        try {
            previewTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing preview texture", e)
        } finally {
            previewTexture = null
        }
    }

    fun cleanup() {
        cleanupCaptureObjects()
    }

    fun release() {
        cleanupCaptureObjects()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true && backgroundHandler != null) return

        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }
}
