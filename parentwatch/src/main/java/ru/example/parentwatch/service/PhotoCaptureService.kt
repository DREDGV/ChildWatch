package ru.example.parentwatch.service

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.network.NetworkHelper
import ru.example.parentwatch.network.WebSocketManager
import java.io.File

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

        fun start(context: Context, serverUrl: String, deviceId: String) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                putExtra("server_url", serverUrl)
                putExtra("device_id", deviceId)
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
    private var networkHelper: NetworkHelper? = null
    private var serverUrl: String? = null
    private var deviceId: String? = null
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
        startForeground(NOTIFICATION_ID, createNotification("РћР¶РёРґР°РЅРёРµ РєРѕРјР°РЅРґ..."))

        cameraService = CameraService(this)
        cameraService?.initialize()

        networkHelper = NetworkHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("server_url")
        deviceId = intent?.getStringExtra("device_id")

        if (serverUrl != null && deviceId != null) {
            setupWebSocketListener()
        }

        return START_STICKY
    }

    /**
     * Setup WebSocket listener for photo commands
     */
    private fun setupWebSocketListener() {
        try {
            // Add command listener to WebSocketManager
            WebSocketManager.addCommandListener(commandListener)

            // Add photo request listener (for request_photo event)
            WebSocketManager.setPhotoRequestCallback { requestId, targetDevice, cameraFacing ->
                Log.d(TAG, "рџ“ё Received photo request: requestId=$requestId, target=$targetDevice, camera=$cameraFacing")
                handlePhotoRequest(requestId, targetDevice, cameraFacing)
            }

            Log.d(TAG, "Photo capture service ready - listening for commands")
            updateNotification("Р“РѕС‚РѕРІ Рє Р·Р°С…РІР°С‚Сѓ С„РѕС‚Рѕ")
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
            updateNotification("Нет доступа к камере")
            return
        }

        Log.d(TAG, "Taking photo with $cameraFacing camera")
        updateNotification("Р—Р°С…РІР°С‚ С„РѕС‚Рѕ...")

        val facing = when (cameraFacing.lowercase()) {
            "back" -> CameraService.CameraFacing.BACK
            else -> CameraService.CameraFacing.FRONT
        }

        cameraService?.capturePhoto(facing) { photoFile ->
            if (photoFile != null) {
                Log.d(TAG, "Photo captured: ${photoFile.absolutePath}")
                uploadPhoto(photoFile)
            } else {
                Log.e(TAG, "Photo capture failed")
                updateNotification("РћС€РёР±РєР° Р·Р°С…РІР°С‚Р°")
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
            updateNotification("Нет доступа к камере")
            return
        }

        updateNotification("Захват фото по запросу...")

        capturePhotoWithFallback(preferredFacing = cameraFacing) { photoFile ->
            if (photoFile != null) {
                Log.d(TAG, "Photo captured for request: $requestId")
                sendPhotoViaWebSocket(photoFile, requestId)
            } else {
                Log.e(TAG, "Photo capture failed for request: $requestId")
                sendPhotoError(requestId, "Failed to capture photo")
                updateNotification("Ошибка захвата")
            }
        }
    }

    private fun capturePhotoWithFallback(preferredFacing: String = "back", onResult: (File?) -> Unit) {
        val service = cameraService
        if (service == null) {
            onResult(null)
            return
        }
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

        service.capturePhoto(preferred) { primaryPhoto ->
            if (primaryPhoto != null) {
                onResult(primaryPhoto)
                return@capturePhoto
            }

            Log.w(TAG, "Primary camera capture failed ($preferred), retrying with $fallback")
            service.capturePhoto(fallback) { secondaryPhoto ->
                onResult(secondaryPhoto)
            }
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
                val bytes = photoFile.readBytes()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                
                val data = org.json.JSONObject().apply {
                    put("photo", base64)
                    put("requestId", requestId)
                    put("timestamp", System.currentTimeMillis())
                    put("deviceId", deviceId)
                }
                
                WebSocketManager.getClient()?.emit("photo", data)
                Log.d(TAG, "вњ… Photo sent via WebSocket: ${bytes.size} bytes, requestId=$requestId")
                
                withContext(Dispatchers.Main) {
                    updateNotification("Р¤РѕС‚Рѕ РѕС‚РїСЂР°РІР»РµРЅРѕ")
                }
                
                // Clean up
                photoFile.delete()
                delay(2000)
                updateNotification("Р“РѕС‚РѕРІ Рє Р·Р°С…РІР°С‚Сѓ С„РѕС‚Рѕ")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending photo via WebSocket", e)
                sendPhotoError(requestId, e.message ?: "Unknown error")
                withContext(Dispatchers.Main) {
                    updateNotification("РћС€РёР±РєР° РѕС‚РїСЂР°РІРєРё")
                }
            }
        }
    }
    
    /**
     * Send photo error via WebSocket
     */
    private fun sendPhotoError(requestId: String, error: String) {
        try {
            val data = org.json.JSONObject().apply {
                put("requestId", requestId)
                put("error", error)
                put("deviceId", deviceId)
            }
            
            WebSocketManager.getClient()?.emit("photo_error", data)
            Log.d(TAG, "Photo error sent: $error")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending photo error", e)
        }
    }

    /**
     * Upload photo to server (HTTP)
     */
    private fun uploadPhoto(photoFile: File) {
        serviceScope.launch {
            try {
                updateNotification("Р—Р°РіСЂСѓР·РєР° С„РѕС‚Рѕ...")

                val success = withContext(Dispatchers.IO) {
                    networkHelper?.uploadPhoto(
                        serverUrl!!,
                        deviceId!!,
                        photoFile
                    ) ?: false
                }

                if (success) {
                    Log.d(TAG, "Photo uploaded successfully")
                    updateNotification("Р¤РѕС‚Рѕ РѕС‚РїСЂР°РІР»РµРЅРѕ")
                } else {
                    Log.e(TAG, "Photo upload failed")
                    updateNotification("РћС€РёР±РєР° Р·Р°РіСЂСѓР·РєРё")
                }

                // Return to ready state after delay
                delay(3000)
                updateNotification("Р“РѕС‚РѕРІ Рє Р·Р°С…РІР°С‚Сѓ С„РѕС‚Рѕ")

            } catch (e: Exception) {
                Log.e(TAG, "Error uploading photo", e)
                updateNotification("РћС€РёР±РєР°: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PhotoCaptureService destroyed")
        WebSocketManager.removeCommandListener(commandListener)
        cameraService?.release()
        cameraService = null

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Р—Р°С…РІР°С‚ С„РѕС‚Рѕ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "РЈРІРµРґРѕРјР»РµРЅРёСЏ Рѕ Р·Р°С…РІР°С‚Рµ С„РѕС‚Рѕ"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ParentWatch - РљР°РјРµСЂР°")
            .setContentText(contentText)
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
}

