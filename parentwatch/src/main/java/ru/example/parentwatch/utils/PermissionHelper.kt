package ru.example.parentwatch.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Helper class for managing permissions
 * Handles runtime permission checks for location, audio, and other features
 * Includes proper background permission handling for Android 10+
 */
object PermissionHelper {
    
    const val REQUEST_CODE_ALL_PERMISSIONS = 1001
    const val REQUEST_CODE_BACKGROUND_LOCATION = 1002
    
    /**
     * Check if all required permissions are granted
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        val basicPermissions = getBasicPermissions()
        val backgroundLocationGranted = hasBackgroundLocationPermission(context)
        
        return basicPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } && backgroundLocationGranted
    }
    
    /**
     * Get list of basic required permissions (without background location)
     */
    fun getBasicPermissions(): List<String> {
        return listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }
    
    /**
     * Get list of required permissions based on Android version
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        permissions.addAll(getBasicPermissions())
        
        // Background location permission for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        return permissions
    }
    
    /**
     * Check if basic permissions are granted (without background location)
     */
    fun hasBasicPermissions(context: Context): Boolean {
        return getBasicPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if location permissions are granted
     */
    fun hasLocationPermissions(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val coarseLocation = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        return fineLocation && coarseLocation
    }
    
    /**
     * Check if background location permission is granted (Android 10+)
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }
    
    /**
     * Check if audio recording permission is granted
     */
    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if camera permission is granted (optional for photo capture)
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if phone call permission is granted (for SOS)
     */
    fun hasPhonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if SMS permission is granted (for SOS)
     */
    fun hasSMSPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Get permissions that are missing
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get denied permissions (alias for getMissingPermissions for compatibility)
     */
    fun getDeniedPermissions(context: Context): List<String> {
        return getMissingPermissions(context)
    }
    
    /**
     * Request all required permissions
     */
    fun requestAllRequiredPermissions(activity: Activity) {
        val missingPermissions = getMissingPermissions(activity)
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions.toTypedArray(),
                REQUEST_CODE_ALL_PERMISSIONS
            )
        }
    }
    
    /**
     * Request basic permissions first (without background location)
     */
    fun requestBasicPermissions(activity: Activity) {
        val missingBasicPermissions = getBasicPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingBasicPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingBasicPermissions.toTypedArray(),
                REQUEST_CODE_ALL_PERMISSIONS
            )
        }
    }
    
    /**
     * Request background location permission separately (Android 10+)
     * This should be called after basic permissions are granted
     */
    fun requestBackgroundLocationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!hasBackgroundLocationPermission(activity)) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_CODE_BACKGROUND_LOCATION
                )
            }
        }
    }
    
    /**
     * Open app settings for manual permission granting
     */
    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }
    
    /**
     * Check if user should be shown rationale for permission
     */
    fun shouldShowRequestPermissionRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
    
    /**
     * Get permission explanation text
     */
    fun getPermissionExplanation(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> 
                "Приложению нужен доступ к точному местоположению для отслеживания ребенка"
            Manifest.permission.ACCESS_COARSE_LOCATION -> 
                "Приложению нужен доступ к приблизительному местоположению для базового отслеживания"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> 
                "Приложению нужен доступ к местоположению в фоновом режиме для непрерывного отслеживания даже когда экран выключен"
            Manifest.permission.RECORD_AUDIO -> 
                "Приложению нужен доступ к микрофону для записи аудио и прослушки окружения ребенка"
            Manifest.permission.CAMERA -> 
                "Приложению нужен доступ к камере для фотографирования в экстренных ситуациях"
            Manifest.permission.CALL_PHONE -> 
                "Приложению нужен доступ к телефону для экстренных вызовов"
            Manifest.permission.SEND_SMS -> 
                "Приложению нужен доступ к SMS для отправки экстренных сообщений"
            else -> "Это разрешение необходимо для корректной работы приложения"
        }
    }
    
    /**
     * Get consequences of denying permission
     */
    fun getPermissionDenialConsequences(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> 
                "Без этого разрешения приложение не сможет отслеживать местоположение ребенка"
            Manifest.permission.ACCESS_COARSE_LOCATION -> 
                "Без этого разрешения приложение не сможет определять приблизительное местоположение"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> 
                "Без этого разрешения отслеживание остановится когда экран будет выключен или приложение свернуто"
            Manifest.permission.RECORD_AUDIO -> 
                "Без этого разрешения функция прослушки аудио будет недоступна"
            Manifest.permission.CAMERA -> 
                "Без этого разрешения функция экстренного фотографирования будет недоступна"
            Manifest.permission.CALL_PHONE -> 
                "Без этого разрешения экстренные вызовы будут недоступны"
            Manifest.permission.SEND_SMS -> 
                "Без этого разрешения экстренные SMS сообщения будут недоступны"
            else -> "Без этого разрешения некоторые функции приложения будут недоступны"
        }
    }
}
