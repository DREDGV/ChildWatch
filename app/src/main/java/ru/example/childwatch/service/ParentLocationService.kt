package ru.example.childwatch.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import ru.example.childwatch.R
import ru.example.childwatch.network.WebSocketManager

/**
 * ParentLocationService - отправляет локацию родителя ребёнку через WebSocket
 * 
 * Работает в фоне и периодически отправляет координаты родителя,
 * если включена настройка "Делиться моей локацией"
 */
class ParentLocationService : Service() {
    
    companion object {
        private const val TAG = "ParentLocationService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "parent_location_channel"
        private const val LOCATION_UPDATE_INTERVAL = 30_000L // 30 секунд
        
        fun start(context: Context) {
            val intent = Intent(context, ParentLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, ParentLocationService::class.java))
        }
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ParentLocationService created")
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        setupLocationUpdates()
    }
    
    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    sendLocationToChild(location)
                }
            }
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL / 2)
            setMaxUpdateDelayMillis(LOCATION_UPDATE_INTERVAL * 2)
        }.build()
        
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null
            )
            Log.d(TAG, "Location updates started")
        } else {
            Log.w(TAG, "Location permission not granted")
        }
    }
    
    private fun sendLocationToChild(location: Location) {
        serviceScope.launch {
            try {
                val prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
                val parentId = prefs.getString("device_id", null) ?: return@launch
                val serverUrl = prefs.getString("server_url", null)
                
                val locationData = org.json.JSONObject().apply {
                    put("parentId", parentId)
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    put("timestamp", System.currentTimeMillis())
                    put("speed", location.speed.takeIf { it > 0 } ?: 0f)
                    put("bearing", location.bearing.takeIf { it > 0 } ?: 0f)
                }
                
                // Отправить через WebSocket
                if (WebSocketManager.isConnected()) {
                    WebSocketManager.getClient()?.emit("parent_location", locationData)
                    Log.d(TAG, "Parent location sent: ${location.latitude}, ${location.longitude}")
                } else {
                    Log.w(TAG, "WebSocket not connected, skipping location send")
                }

                // Дополнительно отправим на сервер REST для fallback карты
                try {
                    if (!serverUrl.isNullOrEmpty()) {
                        ru.example.childwatch.network.NetworkClient(this@ParentLocationService)
                            .uploadParentLocation(
                                parentId = parentId,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracy = location.accuracy,
                                timestamp = System.currentTimeMillis(),
                                speed = location.speed.takeIf { it > 0 } ?: 0f,
                                bearing = location.bearing.takeIf { it > 0 } ?: 0f,
                                batteryLevel = null
                            )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload parent location via REST", e)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending parent location", e)
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Передача локации родителя",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о работе службы передачи локации"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, ru.example.childwatch.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Передача локации")
            .setContentText("Ваша локация передаётся ребёнку")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        Log.d(TAG, "ParentLocationService destroyed")
    }
}
