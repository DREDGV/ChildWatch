package ru.example.parentwatch.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.network.NetworkHelper

/**
 * Foreground service for continuous location tracking
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_tracking"
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 seconds
        private const val LOCATION_FASTEST_INTERVAL = 15000L // 15 seconds

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var networkHelper: NetworkHelper
    private lateinit var prefs: SharedPreferences

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isTracking = false
    private var deviceId: String? = null
    private var serverUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        networkHelper = NetworkHelper(this)

        createNotificationChannel()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }

        return START_STICKY
    }

    private fun startTracking() {
        if (isTracking) {
            Log.d(TAG, "Already tracking")
            return
        }

        // Load settings
        deviceId = prefs.getString("device_id", null)
        serverUrl = prefs.getString("server_url", "http://10.0.2.2:3000")

        if (deviceId == null) {
            Log.e(TAG, "Device ID not set")
            stopSelf()
            return
        }

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Register device
        serviceScope.launch {
            val registered = networkHelper.registerDevice(serverUrl!!, deviceId!!)
            if (registered) {
                Log.d(TAG, "Device registered")
                startLocationUpdates()
            } else {
                Log.e(TAG, "Failed to register device")
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            }
        }

        isTracking = true
        prefs.edit().putBoolean("service_running", true).apply()
    }

    private fun stopTracking() {
        if (!isTracking) return

        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(true)
        stopSelf()

        isTracking = false
        prefs.edit().putBoolean("service_running", false).apply()

        Log.d(TAG, "Tracking stopped")
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        )
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        Log.d(TAG, "Location updates started")
    }

    private fun handleLocationUpdate(location: Location) {
        Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}")

        // Upload to server
        serviceScope.launch {
            val success = networkHelper.uploadLocation(
                serverUrl!!,
                location.latitude,
                location.longitude,
                location.accuracy
            )

            if (success) {
                updateNotification("Последнее обновление: ${System.currentTimeMillis()}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String = "Отслеживание активно"): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }
}
