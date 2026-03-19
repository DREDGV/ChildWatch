package ru.example.parentwatch.service

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.network.WebSocketManager
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
        private const val MAX_PREVIEW_DIMENSION = 960
        private const val PREVIEW_JPEG_QUALITY = 72

        fun start(context: Context, serverUrl: String, deviceId: String) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_TARGET_DEVICE, targetDevice)
                putExtra(EXTRA_CAMERA_FACING, cameraFacing)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
    private var listenersRegistered = false
    private val requestLock = Any()
    private val activePhotoRequests = mutableSetOf<String>()
    private val recentPhotoRequests = ArrayDeque<String>()
    private val recentPhotoRequestSet = mutableSetOf<String>()
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
        startForeground(NOTIFICATION_ID, createNotification())

        cameraService = CameraService(this)
        cameraService?.initialize()

        networkClient = NetworkClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL)
        deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)

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
        AudioStreamingService.pauseCaptureForPhoto(this)
        updateNotification(R.string.photo_capture_capturing)
        service.capturePhoto(facing) { photoFile ->
            if (photoFile != null) {
                Log.d(TAG, "Photo captured: ${photoFile.absolutePath}")
                AudioStreamingService.resumeIfDesired(this)
                uploadPhoto(photoFile)
            } else {
                Log.e(TAG, "Photo capture failed")
                AudioStreamingService.resumeIfDesired(this)
                updateNotification(R.string.photo_capture_capture_error)
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
            return
        }

        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted for photo request")
            sendPhotoError(requestId, "Camera permission denied")
            updateNotification(R.string.photo_capture_no_camera_access)
            return
        }

        if (!beginPhotoRequest(requestId)) {
            Log.d(TAG, "Duplicate or already handled photo request ignored: $requestId")
            return
        }

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
            completePhotoRequestAfterError(requestId, "Requested camera not available")
            updateNotification(R.string.photo_capture_capture_error)
            return
        }

        updateNotification(R.string.photo_capture_request_capturing)
        AudioStreamingService.pauseCaptureForPhoto(this)

        service.capturePhoto(requestedFacing) { photoFile ->
            if (photoFile != null) {
                Log.d(TAG, "Photo captured for request: $requestId")
                sendPhotoViaWebSocket(photoFile, requestId)
            } else {
                Log.e(TAG, "Photo capture failed for request: $requestId")
                AudioStreamingService.resumeIfDesired(this)
                completePhotoRequestAfterError(requestId, "Failed to capture photo")
                updateNotification(R.string.photo_capture_capture_error)
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
        val fallback = if (preferred == CameraService.CameraFacing.BACK) {
            CameraService.CameraFacing.FRONT
        } else {
            CameraService.CameraFacing.BACK
        }

        return when {
            service.hasCameraFacing(preferred) -> preferred
            service.hasCameraFacing(fallback) -> {
                Log.w(TAG, "Requested camera $preferredFacing is unavailable, using $fallback")
                fallback
            }
            else -> null
        }
    }

    private fun beginPhotoRequest(requestId: String): Boolean {
        synchronized(requestLock) {
            if (requestId in activePhotoRequests || requestId in recentPhotoRequestSet) {
                return false
            }
            activePhotoRequests.add(requestId)
            return true
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
        serviceScope.launch(Dispatchers.IO) {
            try {
                val client = WebSocketManager.getClient()
                if (client == null || !client.isReady()) {
                    throw IllegalStateException("WebSocket client is not ready")
                }

                val base64 = buildPreviewBase64(photoFile)
                    ?: throw IllegalStateException("Failed to encode preview photo")
                
                val data = org.json.JSONObject().apply {
                    put("photo", base64)
                    put("requestId", requestId)
                    put("timestamp", System.currentTimeMillis())
                    put("deviceId", deviceId)
                }
                
                client.emit("photo", data)
                Log.d(TAG, "Photo preview sent via WebSocket: requestId=$requestId, base64Length=${base64.length}")
                
                withContext(Dispatchers.Main) {
                    updateNotification(R.string.photo_capture_sent)
                }

                val uploadSuccess = uploadPhotoForGallery(photoFile)
                if (uploadSuccess) {
                    Log.d(TAG, "Photo uploaded for gallery after preview send: requestId=$requestId")
                } else {
                    Log.w(TAG, "Photo preview delivered, but gallery upload failed: requestId=$requestId")
                }
                
                // Clean up
                photoFile.delete()
                delay(2000)
                updateNotification(R.string.photo_capture_ready)
                finishPhotoRequest(requestId)
                AudioStreamingService.resumeIfDesired(this@PhotoCaptureService)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending photo via WebSocket", e)
                val errorSent = sendPhotoError(requestId, e.message ?: "Unknown error")
                withContext(Dispatchers.Main) {
                    updateNotification(R.string.photo_capture_send_error)
                }
                if (errorSent) {
                    finishPhotoRequest(requestId)
                } else {
                    abandonPhotoRequest(requestId)
                }
                AudioStreamingService.resumeIfDesired(this@PhotoCaptureService)
            }
        }
    }

    private suspend fun uploadPhotoForGallery(photoFile: File): Boolean {
        val safeServerUrl = serverUrl
        if (safeServerUrl.isNullOrBlank()) {
            return false
        }

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
                    networkHelper?.uploadPhoto(
                        serverUrl!!,
                        deviceId!!,
                        photoFile
                    ) ?: false
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
        Log.d(TAG, "PhotoCaptureService destroyed")
        WebSocketManager.removeCommandListener(commandListener)
        listenersRegistered = false
        cameraService?.release()
        cameraService = null

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
