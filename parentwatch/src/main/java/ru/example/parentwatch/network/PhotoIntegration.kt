package ru.example.parentwatch.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import ru.example.parentwatch.service.RemotePhotoService
import java.util.UUID

/**
 * PhotoIntegration - Manages remote photo capture via WebSocket
 * 
 * Bridges between WebSocket events and RemotePhotoService:
 * 1. Receives request_photo event from server
 * 2. Starts RemotePhotoService to capture photo
 * 3. Listens for PHOTO_CAPTURED broadcast
 * 4. Sends photo data back via WebSocket
 */
class PhotoIntegration(
    private val context: Context,
    private val webSocketClient: WebSocketClient
) {
    companion object {
        private const val TAG = "PhotoIntegration"
    }
    
    private val photoCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "ru.example.parentwatch.PHOTO_CAPTURED" -> {
                    val requestId = intent.getStringExtra("request_id") ?: return
                    val imageData = intent.getByteArrayExtra("image_data") ?: return
                    val fromParent = intent.getStringExtra("from_parent")
                    val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                    
                    Log.d(TAG, "📷 Photo captured: requestId=$requestId, size=${imageData.size} bytes")
                    
                    sendPhotoToServer(requestId, imageData, fromParent, timestamp)
                }
                
                "ru.example.parentwatch.PHOTO_ERROR" -> {
                    val requestId = intent.getStringExtra("request_id") ?: return
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    
                    Log.w(TAG, "❌ Photo capture error: requestId=$requestId, error=$error")
                    
                    sendPhotoError(requestId, error)
                }
            }
        }
    }
    
    fun register() {
        val filter = IntentFilter().apply {
            addAction("ru.example.parentwatch.PHOTO_CAPTURED")
            addAction("ru.example.parentwatch.PHOTO_ERROR")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(photoCaptureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(photoCaptureReceiver, filter)
        }
        
        Log.d(TAG, "✅ PhotoIntegration registered")
    }
    
    fun unregister() {
        try {
            context.unregisterReceiver(photoCaptureReceiver)
            Log.d(TAG, "✅ PhotoIntegration unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
    
    fun handlePhotoRequest(requestId: String, fromParent: String) {
        try {
            Log.d(TAG, "📷 Handling photo request: requestId=$requestId")
            
            val intent = Intent(context, RemotePhotoService::class.java).apply {
                putExtra("request_id", requestId)
                putExtra("from_parent", fromParent)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            Log.d(TAG, "✅ RemotePhotoService started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start RemotePhotoService", e)
            sendPhotoError(requestId, "Failed to start camera service: ${e.message}")
        }
    }
    
    private fun sendPhotoToServer(
        requestId: String,
        imageData: ByteArray,
        targetParent: String?,
        timestamp: Long
    ) {
        try {
            // Convert to Base64 for transmission
            val base64Image = Base64.encodeToString(imageData, Base64.NO_WRAP)
            
            val photoData = JSONObject().apply {
                put("requestId", requestId)
                put("image", base64Image)
                put("targetParent", targetParent)
                put("timestamp", timestamp)
                put("metadata", JSONObject().apply {
                    put("size", imageData.size)
                    put("format", "jpeg")
                    put("quality", 85)
                })
            }
            
            webSocketClient.emit("photo", photoData)
            Log.d(TAG, "📤 Photo sent to server: requestId=$requestId, size=${imageData.size} bytes")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending photo to server", e)
            sendPhotoError(requestId, "Failed to send photo: ${e.message}")
        }
    }
    
    private fun sendPhotoError(requestId: String, error: String) {
        try {
            val errorData = JSONObject().apply {
                put("requestId", requestId)
                put("error", error)
                put("timestamp", System.currentTimeMillis())
            }
            
            webSocketClient.emit("photo_error", errorData)
            Log.d(TAG, "📤 Photo error sent to server: requestId=$requestId")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending photo error", e)
        }
    }
}
