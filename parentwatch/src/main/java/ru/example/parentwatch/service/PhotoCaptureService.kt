package ru.example.parentwatch.service

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.utils.AppVisibilityTracker
import ru.example.parentwatch.utils.RemoteLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque

/**
 * Foreground Service for remote photo capture
 *
 * Features:
 * - Listen for take_photo commands via WebSocket
 * - Capture photo using CameraService
 * - Upload photo to server
 * - Run as foreground service for reliability
 */
class PhotoCaptureService : Service() {

    companion object {
        private const val TAG = "PhotoCaptureService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "photo_capture_channel"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_TARGET_DEVICE = "target_device"
        private const val EXTRA_CAMERA_FACING = "camera_facing"
        private const val ACTION_PREPARE_CAMERA_FOREGROUND =
            "ru.example.parentwatch.PREPARE_CAMERA_FOREGROUND"
        private const val MAX_PREVIEW_DIMENSION = 960
        private const val PREVIEW_JPEG_QUALITY = 72
        private const val DISPATCH_DEDUP_WINDOW_MS = 60_000L
        private const val MAX_DISPATCH_HISTORY = 64
        private val dispatchLock = Any()
        private val dispatchedRequests = LinkedHashMap<String, Long>()
        @Volatile private var activeInstance: PhotoCaptureService? = null

        /**
         * Starts the photo service, tolerating a refusal by the system.
         *
         * Android 12+ rejects a foreground service start made while the app is
         * in the background, and an unguarded call would close the application
         * instead of just failing to capture a photo.
         */
        fun start(context: Context, serverUrl: String, deviceId: String) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                action = ACTION_PREPARE_CAMERA_FOREGROUND
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "Photo service start refused by the system", error)
            }
        }

        fun dispatchPhotoRequest(
            context: Context,
            serverUrl: String,
            deviceId: String,
            requestId: String,
            targetDevice: String,
            cameraFacing: String
        ) {
            if (!claimDispatch(requestId)) {
                Log.d(TAG, "Duplicate photo dispatch suppressed before service start: $requestId")
                return
            }
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_TARGET_DEVICE, targetDevice)
                putExtra(EXTRA_CAMERA_FACING, cameraFacing)
            }

            try {
                val runningService = activeInstance
                if (runningService != null) {
                    Handler(Looper.getMainLooper()).post {
                        if (activeInstance === runningService) {
                            runningService.dispatchInProcess(
                                serverUrl = serverUrl,
                                deviceId = deviceId,
                                requestId = requestId,
                                targetDevice = targetDevice,
                                cameraFacing = cameraFacing
                            )
                        } else {
                            reportDispatchError(context, serverUrl, deviceId,
                                requestId,
                                IllegalStateException("Photo service stopped before dispatch")
                            )
                        }
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {
                reportDispatchError(context, serverUrl, deviceId, requestId, error)
                throw error
            }
        }

        private fun claimDispatch(requestId: String): Boolean = synchronized(dispatchLock) {
            if (requestId.isBlank()) return@synchronized false
            val now = System.currentTimeMillis()
            val previous = dispatchedRequests[requestId]
            if (previous != null && now - previous < DISPATCH_DEDUP_WINDOW_MS) {
                return@synchronized false
            }
            dispatchedRequests[requestId] = now
            while (dispatchedRequests.size > MAX_DISPATCH_HISTORY) {
                val oldest = dispatchedRequests.entries.firstOrNull()?.key ?: break
                dispatchedRequests.remove(oldest)
            }
            true
        }

        private fun reportDispatchError(context: Context, server: String, own: String, requestId: String, error: Throwable) {
            val reason = if (
                error is SecurityException ||
                error.javaClass.simpleName.contains("ForegroundServiceStartNotAllowed", true)
            ) {
                "camera_background_restricted"
            } else {
                "photo_service_start_failed"
            }
            ru.example.parentwatch.utils.CameraDiagnostics.recordOutcome(context, server, own, reason)
            CoroutineScope(Dispatchers.IO).launch {
                NetworkClient(context.applicationContext).reportPhotoFailure(server, own, requestId, reason)
            }
            runCatching {
                WebSocketManager.getClient()?.emit("photo_error", JSONObject().apply {
                    put("requestId", requestId)
                    put("error", reason)
                    put("timestamp", System.currentTimeMillis())
                })
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PhotoCaptureService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var cameraService: CameraService? = null
    private var networkClient: NetworkClient? = null
    private var serverUrl: String? = null
    private var deviceId: String? = null
    private lateinit var effectiveContextResolver: ChildEffectiveContextResolver
    private var listenersRegistered = false
    private var cameraForegroundPrimed = false
    private var foregroundPromotionSucceeded = false
    private var captureWatchdog: Job? = null
    private val requestLock = Any()
    private val activePhotoRequests = mutableSetOf<String>()
    private val recentPhotoRequests = ArrayDeque<String>()
    private val recentPhotoRequestSet = mutableSetOf<String>()
    private enum class PhotoRequestClaim {
        STARTED,
        DUPLICATE,
        BUSY
    }
    private val commandListener: (String, JSONObject?) -> Unit = { command, data ->
        when (command) {
            "take_photo" -> {
                val cameraFacing = data?.optString("camera", "front") ?: "front"
                Log.d(TAG, "Photo command received: camera=$cameraFacing")
                handleTakePhotoCommand(cameraFacing)
            }
            else -> {
                Log.d(TAG, "Ignoring command: $command")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PhotoCaptureService created")

        createNotificationChannel()
        foregroundPromotionSucceeded = promoteToCameraForeground()

        cameraService = CameraService(this)
        cameraService?.initialize()

        networkClient = NetworkClient(this)
        effectiveContextResolver = ChildEffectiveContextResolver(this)
        activeInstance = this
    }

    private fun dispatchInProcess(
        serverUrl: String,
        deviceId: String,
        requestId: String,
        targetDevice: String,
        cameraFacing: String
    ) {
        this.serverUrl = serverUrl
        this.deviceId = deviceId
        setupWebSocketListener()
        handlePhotoRequest(requestId, targetDevice, cameraFacing)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val effectiveContext = effectiveContextResolver.resolveEffectiveContext()
        serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL)
            ?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.serverUrl?.takeIf { it.isNotBlank() }
            ?: serverUrl
        deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: effectiveContext?.ownChildDeviceId?.takeIf { it.isNotBlank() }
            ?: deviceId

        if (!foregroundPromotionSucceeded) {
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
            if (!requestId.isNullOrBlank()) reportDispatchError(this, serverUrl.orEmpty(), deviceId.orEmpty(), requestId,
                SecurityException("Camera foreground promotion refused"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_PREPARE_CAMERA_FOREGROUND || AppVisibilityTracker.isVisible()) {
            val primed = promoteToCameraForeground() && AppVisibilityTracker.isVisible()
            if (primed && !cameraForegroundPrimed) {
                cameraForegroundPrimed = true
                Log.i(TAG, "PhotoCaptureService camera access primed while app is visible")
                RemoteLogger.info(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "Photo camera foreground access primed"
                )
            }
        }

        if (serverUrl != null && deviceId != null) {
            setupWebSocketListener()
        }

        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        if (!requestId.isNullOrBlank()) {
            val targetDevice = intent.getStringExtra(EXTRA_TARGET_DEVICE).orEmpty()
            val cameraFacing = intent.getStringExtra(EXTRA_CAMERA_FACING).orEmpty().ifBlank { "back" }
            handlePhotoRequest(requestId, targetDevice, cameraFacing)
        }

        return START_STICKY
    }

    private fun promoteToCameraForeground(): Boolean {
        val notification = createNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to promote PhotoCaptureService for camera access", error)
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Photo camera foreground promotion failed",
                meta = mapOf("error" to (error.message ?: error.javaClass.simpleName))
            )
        }.isSuccess
    }

    /**
     * Setup WebSocket listener for photo commands
     */
    private fun setupWebSocketListener() {
        if (listenersRegistered) return
        try {
            // Add command listener to WebSocketManager
            WebSocketManager.addCommandListener(commandListener)
            listenersRegistered = true

            Log.d(TAG, "Photo capture service ready - listening for commands")
            updateNotification(R.string.photo_capture_ready)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up listener", e)
        }
    }

    /**
     * Handle take photo command
     */
    fun handleTakePhotoCommand(cameraFacing: String = "front") {
        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted for take_photo command")
            updateNotification(R.string.photo_capture_no_camera_access)
            return
        }

        val service = cameraService
        if (service == null) {
            Log.e(TAG, "Camera service not initialized")
            updateNotification(R.string.photo_capture_capture_error)
            return
        }

        val facing = resolveRequestedFacing(service, cameraFacing)
        if (facing == null) {
            Log.e(TAG, "No available camera for requested facing=$cameraFacing")
            updateNotification(R.string.photo_capture_capture_error)
            return
        }

        Log.d(TAG, "Taking photo with $facing camera")
        pauseAudioForPhoto()
        updateNotification(R.string.photo_capture_capturing)
        service.capturePhoto(facing) { photoFile ->
            if (photoFile != null) {
                Log.d(TAG, "Photo captured: ${photoFile.absolutePath}")
                resumeAudioAfterPhoto()
                uploadPhoto(photoFile)
            } else {
                val failureReason = service.consumeLastFailureReason()
                    ?: "Failed to capture photo"
                Log.e(TAG, "Photo capture failed: $failureReason")
                resumeAudioAfterPhoto()
                updateNotification(
                    getString(
                        R.string.photo_capture_error_with_reason,
                        failureReason
                    )
                )
            }
        }
    }

    /**
     * Handle photo request from parent via WebSocket
     */
    private fun handlePhotoRequest(requestId: String, targetDevice: String, cameraFacing: String) {
        Log.d(TAG, "Handling photo request: $requestId for device: $targetDevice")

        val myDeviceId = deviceId ?: ""
        if (targetDevice.isNotEmpty() && targetDevice != myDeviceId) {
            Log.d(TAG, "Photo request not for this device (target=$targetDevice, me=$myDeviceId)")
            sendPhotoError(requestId, "photo_target_mismatch")
            return
        }

        val captureServer = serverUrl.orEmpty()
        val captureFamily = effectiveContextResolver.resolveFamilyId().orEmpty()
        if (ru.example.parentwatch.photo.PhotoUploadWorker.hasQueued(this, captureServer, captureFamily, myDeviceId, requestId)) {
            notifyPhotoRequestAccepted(requestId)
            return // The image already exists; a reconnect must not open the camera again.
        }

        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted for photo request")
            sendPhotoError(requestId, "Camera permission denied")
            updateNotification(R.string.photo_capture_no_camera_access)
            return
        }

        when (beginPhotoRequest(requestId)) {
            PhotoRequestClaim.DUPLICATE -> {
                Log.d(TAG, "Duplicate or already handled photo request ignored: $requestId")
                notifyPhotoRequestAccepted(requestId)
                return
            }
            PhotoRequestClaim.BUSY -> {
                Log.w(TAG, "Camera already has another active photo request")
                sendPhotoError(requestId, "camera_in_use")
                return
            }
            PhotoRequestClaim.STARTED -> Unit
        }

        // The parent should not have to guess whether the command merely
        // reached the server or was received by the child service.
        notifyPhotoRequestAccepted(requestId)

        val service = cameraService
        if (service == null) {
            Log.e(TAG, "Camera service not initialized for request: $requestId")
            completePhotoRequestAfterError(requestId, "Camera service unavailable")
            updateNotification(R.string.photo_capture_capture_error)
            return
        }

        val requestedFacing = resolveRequestedFacing(service, cameraFacing)
        if (requestedFacing == null) {
            Log.e(TAG, "No camera available for request: $requestId")
            completePhotoRequestAfterError(requestId, "camera_not_available")
            updateNotification(R.string.photo_capture_capture_error)
            return
        }

        updateNotification(R.string.photo_capture_request_capturing)
        pauseAudioForPhoto()

        captureWatchdog?.cancel()
        captureWatchdog = serviceScope.launch {
            delay(30_000)
            service.cancelCapture("photo_capture_timeout")
        }
        service.capturePhoto(requestedFacing) { photoFile ->
            captureWatchdog?.cancel(); captureWatchdog = null
            if (serverUrl != captureServer || deviceId != myDeviceId ||
                effectiveContextResolver.resolveServerUrl().trimEnd('/') != captureServer.trimEnd('/') ||
                effectiveContextResolver.resolveChildDeviceId() != myDeviceId ||
                effectiveContextResolver.resolveFamilyId().orEmpty() != captureFamily) {
                resumeAudioAfterPhoto()
                finishPhotoRequest(requestId)
                Log.w(TAG, "Captured image belongs to the previous connection; no cross-profile delivery")
                return@capturePhoto
            }
            if (photoFile != null) {
                Log.d(TAG, "Photo captured for request: $requestId")
                // The camera no longer needs the microphone. Resume listening before JPEG
                // encoding/gallery upload so a slow network cannot mute audio for several seconds.
                resumeAudioAfterPhoto()
                sendPhotoViaWebSocket(photoFile, requestId)
            } else {
                val failureReason = service.consumeLastFailureReason()
                    ?: "Failed to capture photo"
                Log.e(TAG, "Photo capture failed for request: $requestId, reason=$failureReason")
                resumeAudioAfterPhoto()
                completePhotoRequestAfterError(requestId, failureReason)
                updateNotification(
                    getString(
                        R.string.photo_capture_error_with_reason,
                        failureReason
                    )
                )
            }
        }
    }

    private fun resolveRequestedFacing(
        service: CameraService,
        preferredFacing: String
    ): CameraService.CameraFacing? {
        val preferred = if (preferredFacing.equals("front", ignoreCase = true)) {
            CameraService.CameraFacing.FRONT
        } else {
            CameraService.CameraFacing.BACK
        }
        // A named camera must never silently become the opposite camera.
        return preferred.takeIf { service.hasCameraFacing(it) }
    }

    private fun beginPhotoRequest(requestId: String): PhotoRequestClaim {
        synchronized(requestLock) {
            if (requestId in activePhotoRequests || requestId in recentPhotoRequestSet) {
                return PhotoRequestClaim.DUPLICATE
            }
            if (activePhotoRequests.isNotEmpty()) {
                return PhotoRequestClaim.BUSY
            }
            activePhotoRequests.add(requestId)
            return PhotoRequestClaim.STARTED
        }
    }

    private fun finishPhotoRequest(requestId: String) {
        synchronized(requestLock) {
            activePhotoRequests.remove(requestId)
            if (requestId.isNotBlank()) {
                recentPhotoRequestSet.add(requestId)
                recentPhotoRequests.addLast(requestId)
                while (recentPhotoRequests.size > 64) {
                    val evicted = recentPhotoRequests.removeFirst()
                    recentPhotoRequestSet.remove(evicted)
                }
            }
        }
    }

    private fun abandonPhotoRequest(requestId: String) {
        synchronized(requestLock) {
            activePhotoRequests.remove(requestId)
        }
    }

    private fun completePhotoRequestAfterError(requestId: String, error: String) {
        val errorSent = sendPhotoError(requestId, error)
        if (errorSent) {
            finishPhotoRequest(requestId)
        } else {
            abandonPhotoRequest(requestId)
        }
    }

    private fun notifyPhotoRequestAccepted(requestId: String) {
        runCatching {
            val client = WebSocketManager.getClient()
            if (client == null || !client.isReady()) {
                Log.w(TAG, "Cannot acknowledge photo request: WebSocket is not ready")
                return
            }
            client.emit("photo_request_received", org.json.JSONObject().apply {
                put("requestId", requestId)
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            })
            Log.d(TAG, "Photo request accepted: $requestId")
        }.onFailure { error ->
            Log.w(TAG, "Unable to acknowledge photo request", error)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    /**
     * Send photo via WebSocket as base64
     */
    private fun sendPhotoViaWebSocket(photoFile: File, requestId: String) {
        val captureServer = serverUrl
        val captureDevice = deviceId
        val captureFamily = effectiveContextResolver.resolveFamilyId().orEmpty()
        val capturedAt = photoFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (captureServer.isNullOrBlank() || captureDevice.isNullOrBlank()) throw IllegalStateException("photo_context_missing")
                if (captureServer.trimEnd('/') != effectiveContextResolver.resolveServerUrl().trimEnd('/') ||
                    captureDevice != effectiveContextResolver.resolveChildDeviceId() ||
                    captureFamily != effectiveContextResolver.resolveFamilyId().orEmpty()) throw IllegalStateException("photo_target_mismatch")
                ru.example.parentwatch.photo.PhotoUploadWorker.enqueue(this@PhotoCaptureService,
                    captureServer, captureFamily, captureDevice, requestId, photoFile, capturedAt)
                photoFile.delete()
                withContext(Dispatchers.Main) { updateNotification(R.string.photo_capture_upload_queued) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                sendPhotoError(requestId, error.message ?: "photo_upload_failed")
                withContext(Dispatchers.Main) { updateNotification(R.string.photo_capture_send_error) }
            } finally { finishPhotoRequest(requestId) }
        }
    }

    private suspend fun uploadPhotoForGallery(photoFile: File): Boolean {
        val safeServerUrl = serverUrl?.takeIf { it.isNotBlank() } ?: return false
        return networkClient?.uploadPhoto(safeServerUrl, photoFile) ?: false
    }

    private fun buildPreviewBase64(photoFile: File): String? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(photoFile.absolutePath, bounds)

        val sampleSize = calculateSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            MAX_PREVIEW_DIMENSION,
            MAX_PREVIEW_DIMENSION
        )

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize
        }

        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath, options)
            ?: return if (photoFile.length() <= 512 * 1024) {
                android.util.Base64.encodeToString(photoFile.readBytes(), android.util.Base64.NO_WRAP)
            } else {
                null
            }

        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output)
                android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        while (width / inSampleSize > reqWidth * 2 || height / inSampleSize > reqHeight * 2) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }
    
    /**
     * Send photo error via WebSocket
     */
    private fun sendPhotoError(requestId: String, error: String): Boolean {
        val sourceServer = serverUrl; val sourceDevice = deviceId
        if (!sourceServer.isNullOrBlank() && !sourceDevice.isNullOrBlank())
            ru.example.parentwatch.utils.CameraDiagnostics.recordOutcome(this, sourceServer, sourceDevice, error)
        if (!sourceServer.isNullOrBlank() && !sourceDevice.isNullOrBlank()) serviceScope.launch(Dispatchers.IO) {
            networkClient?.reportPhotoFailure(sourceServer, sourceDevice, requestId, error)
        }
        try {
            val client = WebSocketManager.getClient()
            if (client == null || !client.isReady()) {
                Log.w(TAG, "Cannot send photo error, WebSocket client is not ready")
                return false
            }

            val data = org.json.JSONObject().apply {
                put("requestId", requestId)
                put("error", error)
                put("deviceId", deviceId)
            }
            
            client.emit("photo_error", data)
            Log.d(TAG, "Photo error sent: $error")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending photo error", e)
            return false
        }
    }

    /**
     * Upload photo to server (HTTP)
     */
    private fun uploadPhoto(photoFile: File) {
        serviceScope.launch {
            try {
                updateNotification(R.string.photo_capture_uploading)

                val success = withContext(Dispatchers.IO) {
                    uploadPhotoForGallery(photoFile)
                }

                if (success) {
                    Log.d(TAG, "Photo uploaded successfully")
                    updateNotification(R.string.photo_capture_sent)
                } else {
                    Log.e(TAG, "Photo upload failed")
                    updateNotification(R.string.photo_capture_upload_error)
                }

                // Return to ready state after delay
                delay(3000)
                updateNotification(R.string.photo_capture_ready)

            } catch (e: Exception) {
                Log.e(TAG, "Error uploading photo", e)
                updateNotification(
                    getString(
                        R.string.photo_capture_error_with_reason,
                        e.message ?: getString(R.string.photo_capture_unknown_error)
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) {
            activeInstance = null
        }
        Log.d(TAG, "PhotoCaptureService destroyed")
        WebSocketManager.removeCommandListener(commandListener)
        listenersRegistered = false
        captureWatchdog?.cancel()
        cameraService?.release()
        cameraService = null

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pauseAudioForPhoto() {
        LocationService.pauseAudioCaptureForPhoto(this)
        AudioStreamingService.pauseCaptureForPhoto(this)
    }

    private fun resumeAudioAfterPhoto() {
        LocationService.resumeAudioCaptureAfterPhoto(this)
        AudioStreamingService.resumeCaptureAfterPhoto(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.photo_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.photo_capture_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String? = null): Notification {
        val resolvedContentText = contentText ?: getString(R.string.photo_capture_waiting_commands)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.photo_capture_notification_title))
            .setContentText(resolvedContentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(messageRes: Int) {
        updateNotification(getString(messageRes))
    }
}
