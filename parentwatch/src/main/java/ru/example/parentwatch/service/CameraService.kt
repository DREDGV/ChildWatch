package ru.example.parentwatch.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import ru.example.parentwatch.utils.AppVisibilityTracker
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
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
        private const val RETRY_BACKOFF_MS = 300L
        const val ERROR_BACKGROUND_CAMERA_RESTRICTED = "background_camera_restricted"
        const val ERROR_CAMERA_PERMISSION_DENIED = "camera_permission_denied"
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

    private data class CaptureAttempt(
        val facing: CameraFacing,
        val config: CameraConfig,
        val usePreviewWarmup: Boolean
    ) {
        val description: String
            get() = "${facing.name.lowercase(Locale.US)}:${config.cameraId}:${if (usePreviewWarmup) "warm" else "direct"}"
    }

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
    private var activeAttemptToken = 0
    private var lastFailureReason: String? = null
    private val captureAttempts = ArrayDeque<CaptureAttempt>()

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

    fun consumeLastFailureReason(): String? = synchronized(this) {
        val reason = lastFailureReason
        lastFailureReason = null
        reason
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
            activeAttemptToken = 0
            lastFailureReason = null
            captureAttempts.clear()
        }

        cleanupCaptureObjects()
        buildCaptureAttempts(cameraFacing).forEach { captureAttempts.addLast(it) }
        if (captureAttempts.isEmpty()) {
            lastFailureReason = "No suitable camera found for ${cameraFacing.name.lowercase(Locale.US)} capture"
            Log.e(TAG, lastFailureReason!!)
            finalizeResult(null)
            return
        }

        startNextAttempt()
    }

    private fun buildCaptureAttempts(preferredFacing: CameraFacing): List<CaptureAttempt> {
        val attempts = mutableListOf<CaptureAttempt>()
        val alternateFacing = if (preferredFacing == CameraFacing.FRONT) {
            CameraFacing.BACK
        } else {
            CameraFacing.FRONT
        }

        fun enqueue(facing: CameraFacing, config: CameraConfig?) {
            if (config == null) return
            attempts += CaptureAttempt(facing, config, usePreviewWarmup = true)
            attempts += CaptureAttempt(facing, config, usePreviewWarmup = false)
        }

        val preferredConfig = resolveCameraConfig(preferredFacing)
        enqueue(preferredFacing, preferredConfig)

        val alternateConfig = resolveCameraConfig(alternateFacing)
        if (alternateConfig != null && alternateConfig.cameraId != preferredConfig?.cameraId) {
            enqueue(alternateFacing, alternateConfig)
        }

        return attempts
    }

    @SuppressLint("MissingPermission")
    private fun startNextAttempt() {
        val attempt: CaptureAttempt
        val attemptToken: Int

        synchronized(this) {
            if (!captureInProgress || resultDelivered) return
            if (captureAttempts.isEmpty()) {
                Log.e(TAG, "All photo capture attempts failed: ${lastFailureReason ?: "unknown error"}")
                finalizeResult(null)
                return
            }
            captureTriggered = false
            attempt = captureAttempts.removeFirst()
            activeAttemptToken += 1
            attemptToken = activeAttemptToken
        }

        cleanupCaptureObjects()

        val handler = backgroundHandler
        if (handler == null) {
            handleAttemptFailure(attemptToken, "Background handler is not available")
            return
        }

        Log.d(
            TAG,
            "Opening camera attempt=$attemptToken ${attempt.description} size=${attempt.config.jpegSize.width}x${attempt.config.jpegSize.height}"
        )

        try {
            cameraManager?.openCamera(
                attempt.config.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        val accepted = synchronized(this@CameraService) {
                            if (!captureInProgress || resultDelivered ||
                                attemptToken != activeAttemptToken
                            ) {
                                false
                            } else {
                                cameraDevice = camera
                                true
                            }
                        }
                        if (!accepted) {
                            Log.w(TAG, "Closing camera from stale onOpened callback: ${attempt.description}")
                            runCatching { camera.close() }
                            return
                        }
                        createCaptureSession(attempt, attemptToken)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        closeCallbackCamera(camera)
                        handleAttemptFailure(
                            attemptToken,
                            "Camera disconnected during ${attempt.description}"
                        )
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        closeCallbackCamera(camera)
                        val backgroundRestricted =
                            error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED &&
                                !AppVisibilityTracker.isVisible()
                        handleAttemptFailure(
                            attemptToken,
                            if (backgroundRestricted) {
                                ERROR_BACKGROUND_CAMERA_RESTRICTED
                            } else {
                                "Camera open error for ${attempt.description}: ${cameraDeviceErrorName(error)}"
                            },
                            retryable = !backgroundRestricted &&
                                error != CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
                        )
                    }
                },
                handler
            )
        } catch (e: SecurityException) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val backgroundRestricted = hasPermission && !AppVisibilityTracker.isVisible()
            handleAttemptFailure(
                attemptToken,
                if (backgroundRestricted) {
                    ERROR_BACKGROUND_CAMERA_RESTRICTED
                } else {
                    ERROR_CAMERA_PERMISSION_DENIED
                },
                e,
                retryable = false
            )
        } catch (e: CameraAccessException) {
            val backgroundRestricted =
                e.reason == CameraAccessException.CAMERA_DISABLED &&
                    !AppVisibilityTracker.isVisible()
            handleAttemptFailure(
                attemptToken,
                if (backgroundRestricted) {
                    ERROR_BACKGROUND_CAMERA_RESTRICTED
                } else {
                    "Camera access error while opening ${attempt.description}: ${cameraAccessReasonName(e.reason)}"
                },
                e,
                retryable = e.reason != CameraAccessException.CAMERA_DISABLED
            )
        } catch (e: Exception) {
            handleAttemptFailure(
                attemptToken,
                "Unexpected camera open error for ${attempt.description}",
                e
            )
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

    private fun createCaptureSession(attempt: CaptureAttempt, attemptToken: Int) {
        val handler = backgroundHandler
        val camera = cameraDevice
        if (handler == null || camera == null) {
            handleAttemptFailure(
                attemptToken,
                "Camera or handler became unavailable during ${attempt.description}"
            )
            return
        }

        try {
            imageReader = ImageReader.newInstance(
                attempt.config.jpegSize.width,
                attempt.config.jpegSize.height,
                ImageFormat.JPEG,
                2
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image == null) {
                    Log.w(TAG, "ImageReader returned null image for ${attempt.description}")
                    return@setOnImageAvailableListener
                }

                try {
                    if (!isAttemptActive(attemptToken)) {
                        return@setOnImageAvailableListener
                    }
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    if (bytes.isEmpty()) {
                        handleAttemptFailure(
                            attemptToken,
                            "Captured JPEG is empty for ${attempt.description}"
                        )
                    } else {
                        val photoFile = createOutputFile()
                        FileOutputStream(photoFile).use { output ->
                            output.write(bytes)
                        }
                        Log.d(TAG, "Photo saved: ${photoFile.absolutePath} (${bytes.size} bytes)")
                        deliverAttemptSuccess(attemptToken, photoFile)
                    }
                } catch (e: Exception) {
                    handleAttemptFailure(
                        attemptToken,
                        "Error saving captured image for ${attempt.description}",
                        e
                    )
                } finally {
                    image.close()
                }
            }, handler)

            val photoSurface = imageReader?.surface
            if (photoSurface == null) {
                handleAttemptFailure(
                    attemptToken,
                    "Photo surface is not ready for ${attempt.description}"
                )
                return
            }

            val surfaces = mutableListOf(photoSurface)
            if (attempt.usePreviewWarmup) {
                previewTexture = SurfaceTexture(0).apply {
                    setDefaultBufferSize(attempt.config.jpegSize.width, attempt.config.jpegSize.height)
                }
                previewSurface = Surface(previewTexture)
                val preview = previewSurface
                if (preview == null) {
                    handleAttemptFailure(
                        attemptToken,
                        "Preview surface is not ready for ${attempt.description}"
                    )
                    return
                }
                surfaces.add(0, preview)
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val accepted = synchronized(this@CameraService) {
                            if (!captureInProgress || resultDelivered ||
                                attemptToken != activeAttemptToken
                            ) {
                                false
                            } else {
                                captureSession = session
                                true
                            }
                        }
                        if (!accepted) {
                            Log.w(TAG, "Closing session from stale onConfigured callback: ${attempt.description}")
                            runCatching { session.close() }
                            return
                        }
                        if (attempt.usePreviewWarmup) {
                            startPreviewAndCapture(session, attempt, attemptToken)
                        } else {
                            Log.d(TAG, "Running direct JPEG capture for ${attempt.description}")
                            captureStillImage(session, attempt.config, attempt.description, attemptToken)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runCatching { session.close() }
                        handleAttemptFailure(
                            attemptToken,
                            "Capture session configuration failed for ${attempt.description}"
                        )
                    }
                },
                handler
            )
        } catch (e: Exception) {
            handleAttemptFailure(
                attemptToken,
                "Error creating capture session for ${attempt.description}",
                e
            )
        }
    }

    private fun startPreviewAndCapture(
        session: CameraCaptureSession,
        attempt: CaptureAttempt,
        attemptToken: Int
    ) {
        val handler = backgroundHandler
        val camera = cameraDevice
        val preview = previewSurface
        if (handler == null || camera == null || preview == null) {
            handleAttemptFailure(
                attemptToken,
                "Preview prerequisites became unavailable for ${attempt.description}"
            )
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
                if (!captureTriggered && !resultDelivered && isAttemptActive(attemptToken)) {
                    Log.d(TAG, "3A warmup timeout, capturing with fallback timer for ${attempt.description}")
                    captureStillImage(session, attempt.config, attempt.description, attemptToken)
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
                        if (!captureTriggered && isAttemptActive(attemptToken) && is3aReady(result)) {
                            handler.removeCallbacks(fallbackCapture)
                            Log.d(TAG, "3A converged, capturing still image for ${attempt.description}")
                            captureStillImage(session, attempt.config, attempt.description, attemptToken)
                        }
                    }
                },
                handler
            )

            handler.postDelayed(fallbackCapture, PREVIEW_WARMUP_MS)
        } catch (e: Exception) {
            handleAttemptFailure(
                attemptToken,
                "Error starting preview warmup for ${attempt.description}",
                e
            )
        }
    }

    private fun captureStillImage(
        session: CameraCaptureSession,
        config: CameraConfig,
        attemptDescription: String,
        attemptToken: Int
    ) {
        val handler = backgroundHandler
        val camera = cameraDevice
        val photoSurface = imageReader?.surface
        if (handler == null || camera == null || photoSurface == null) {
            handleAttemptFailure(
                attemptToken,
                "Still capture prerequisites became unavailable for $attemptDescription"
            )
            return
        }

        if (captureTriggered || !isAttemptActive(attemptToken)) return
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
                if (!resultDelivered && isAttemptActive(attemptToken)) {
                    handleAttemptFailure(
                        attemptToken,
                        "Capture timed out waiting for image for $attemptDescription"
                    )
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
                        Log.d(TAG, "Still capture completed for $attemptDescription")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        handleAttemptFailure(
                            attemptToken,
                            "Still capture failed for $attemptDescription: reason=${failure.reason}"
                        )
                    }
                },
                handler
            )
        } catch (e: Exception) {
            handleAttemptFailure(
                attemptToken,
                "Error capturing still image for $attemptDescription",
                e
            )
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

    private fun isAttemptActive(attemptToken: Int): Boolean = synchronized(this) {
        captureInProgress && !resultDelivered && attemptToken == activeAttemptToken
    }

    private fun deliverAttemptSuccess(attemptToken: Int, photoFile: File) {
        val shouldDeliver = synchronized(this) {
            captureInProgress && !resultDelivered && attemptToken == activeAttemptToken
        }

        if (!shouldDeliver) {
            photoFile.delete()
            return
        }

        lastFailureReason = null
        finalizeResult(photoFile)
    }

    private fun handleAttemptFailure(
        attemptToken: Int,
        message: String,
        throwable: Throwable? = null,
        retryable: Boolean = true
    ) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }

        val shouldRetry = synchronized(this) {
            if (!captureInProgress || resultDelivered || attemptToken != activeAttemptToken) {
                return
            }
            lastFailureReason = message
            captureTriggered = false
            activeAttemptToken += 1
            retryable && captureAttempts.isNotEmpty()
        }

        cleanupCaptureObjects()

        if (shouldRetry) {
            Log.w(TAG, "Retrying photo capture after failure: $message")
            backgroundHandler?.postDelayed({ startNextAttempt() }, RETRY_BACKOFF_MS)
                ?: startNextAttempt()
        } else {
            finalizeResult(null)
        }
    }

    private fun closeCallbackCamera(camera: CameraDevice) {
        synchronized(this) {
            if (cameraDevice === camera) {
                cameraDevice = null
            }
        }
        runCatching { camera.close() }
            .onFailure { Log.w(TAG, "Error closing callback camera", it) }
    }

    private fun cameraDeviceErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera_in_use"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "max_cameras_in_use"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera_disabled"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera_device_error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera_service_error"
        else -> "unknown_error_$error"
    }

    private fun cameraAccessReasonName(reason: Int): String = when (reason) {
        CameraAccessException.CAMERA_DISABLED -> "camera_disabled"
        CameraAccessException.CAMERA_DISCONNECTED -> "camera_disconnected"
        CameraAccessException.CAMERA_ERROR -> "camera_error"
        CameraAccessException.CAMERA_IN_USE -> "camera_in_use"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "max_cameras_in_use"
        else -> "unknown_reason_$reason"
    }

    @Synchronized
    private fun finalizeResult(photoFile: File?) {
        if (resultDelivered) return
        resultDelivered = true
        val callback = captureCallback
        captureCallback = null
        captureInProgress = false
        captureTriggered = false
        captureAttempts.clear()
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
