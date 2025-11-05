package ru.example.parentwatch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RemotePhotoService - Captures photos remotely via WebSocket command
 * 
 * Features:
 * - CameraX integration for reliable capture
 * - Background/foreground capture support
 * - Image compression (720p, JPEG 85%)
 * - WebSocket binary transfer
 * - Automatic cleanup
 */
class RemotePhotoService : LifecycleService() {
    
    companion object {
        private const val TAG = "RemotePhotoService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "remote_photo_channel"
        private const val MAX_IMAGE_WIDTH = 1280
        private const val MAX_IMAGE_HEIGHT = 720
        private const val JPEG_QUALITY = 85
        
        fun startService(context: Context) {
            val intent = Intent(context, RemotePhotoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, RemotePhotoService::class.java)
            context.stopService(intent)
        }
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var isCapturing = false
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RemotePhotoService onCreate")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Готов к захвату фото"))
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize camera if permission granted
        if (hasCameraPermission()) {
            initializeCamera()
        } else {
            Log.w(TAG, "Camera permission not granted")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val requestId = intent?.getStringExtra("request_id")
        val fromParent = intent?.getStringExtra("from_parent")
        
        if (requestId != null) {
            Log.d(TAG, "Photo capture requested: requestId=$requestId")
            capturePhoto(requestId, fromParent)
        }
        
        return START_STICKY
    }
    
    private fun initializeCamera() {
        if (!hasCameraPermission()) {
            Log.e(TAG, "Cannot initialize camera: permission not granted")
            return
        }
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                Log.d(TAG, "Camera initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: run {
            Log.w(TAG, "Camera provider not available")
            return
        }
        
        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()
            
            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            // ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
            
            // Bind use cases to lifecycle
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                imageCapture
            )
            
            Log.d(TAG, "Camera use cases bound successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
        }
    }
    
    private fun capturePhoto(requestId: String, fromParent: String?) {
        if (isCapturing) {
            Log.w(TAG, "Photo capture already in progress")
            return
        }
        
        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted")
            sendPhotoError(requestId, "Camera permission not granted")
            return
        }
        
        val capture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized")
            // Try to initialize now
            initializeCamera()
            
            // Wait a bit and try again
            serviceScope.launch {
                delay(1000)
                if (imageCapture != null) {
                    capturePhoto(requestId, fromParent)
                } else {
                    sendPhotoError(requestId, "Camera not available")
                }
            }
            return
        }
        
        isCapturing = true
        updateNotification("Захват фото...")
        
        Log.d(TAG, "Starting photo capture: requestId=$requestId")
        
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    serviceScope.launch {
                        try {
                            val bitmap = imageToBitmap(image)
                            image.close()
                            
                            if (bitmap != null) {
                                val compressedData = compressAndResize(bitmap)
                                bitmap.recycle()
                                
                                Log.d(TAG, "Photo captured successfully: ${compressedData.size} bytes")
                                sendPhotoToServer(requestId, compressedData, fromParent)
                                
                                updateNotification("Фото отправлено")
                            } else {
                                Log.e(TAG, "Failed to convert image to bitmap")
                                sendPhotoError(requestId, "Image processing failed")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing captured image", e)
                            sendPhotoError(requestId, "Processing error: ${e.message}")
                        } finally {
                            isCapturing = false
                            
                            // Reset notification after delay
                            serviceScope.launch {
                                delay(2000)
                                updateNotification("Готов к захвату фото")
                            }
                        }
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    sendPhotoError(requestId, "Capture failed: ${exception.message}")
                    isCapturing = false
                    updateNotification("Ошибка захвата")
                }
            }
        )
    }
    
    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    
    private fun compressAndResize(bitmap: Bitmap): ByteArray {
        // Calculate scale to fit within max dimensions
        val scale = minOf(
            MAX_IMAGE_WIDTH.toFloat() / bitmap.width,
            MAX_IMAGE_HEIGHT.toFloat() / bitmap.height,
            1.0f
        )
        
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()
        
        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        } else {
            bitmap
        }
        
        // Compress to JPEG
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        
        return outputStream.toByteArray()
    }
    
    private fun sendPhotoToServer(requestId: String, imageData: ByteArray, fromParent: String?) {
        // This will be called by WebSocketManager when it receives request_photo event
        // For now, broadcast locally so WebSocketManager can pick it up
        val intent = Intent("ru.example.parentwatch.PHOTO_CAPTURED").apply {
            putExtra("request_id", requestId)
            putExtra("image_data", imageData)
            putExtra("from_parent", fromParent)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(intent)
        
        Log.d(TAG, "Photo data broadcasted: requestId=$requestId, size=${imageData.size}")
    }
    
    private fun sendPhotoError(requestId: String, error: String) {
        val intent = Intent("ru.example.parentwatch.PHOTO_ERROR").apply {
            putExtra("request_id", requestId)
            putExtra("error", error)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(intent)
        
        Log.w(TAG, "Photo error broadcasted: requestId=$requestId, error=$error")
    }
    
    private fun hasCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Удалённое фото",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Служба удалённого захвата фотографий"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(message: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Удалённое фото")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Удалённое фото")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
    }
    
    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RemotePhotoService onDestroy")
        
        serviceScope.cancel()
        
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera", e)
        }
        
        cameraExecutor.shutdown()
    }
}
