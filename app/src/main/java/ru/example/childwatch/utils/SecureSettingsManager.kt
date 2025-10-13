package ru.example.childwatch.utils

import android.content.Context
import android.util.Log

/**
 * Secure settings manager for ChildWatch application
 * 
 * Features:
 * - Encrypted storage of sensitive settings
 * - Settings validation and sanitization
 * - Default values management
 * - Settings migration and backup
 * - Security monitoring
 */
class SecureSettingsManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SecureSettingsManager"
        private const val PREFS_NAME = "childwatch_secure_settings"
        
        // Setting keys
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CHILD_DEVICE_ID = "child_device_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USER_CONSENT = "user_consent"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_LOCATION_ENABLED = "location_enabled"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_PHOTO_ENABLED = "photo_enabled"
        private const val KEY_LOCATION_INTERVAL = "location_interval"
        private const val KEY_AUDIO_DURATION = "audio_duration"
        private const val KEY_SERVICE_START_TIME = "service_start_time"
        private const val KEY_LAST_LOCATION_UPDATE = "last_location_update"
        private const val KEY_LAST_AUDIO_UPDATE = "last_audio_update"
        private const val KEY_LAST_PHOTO_UPDATE = "last_photo_update"
        private const val KEY_LAST_DEVICE_STATUS = "last_device_status_json"
        private const val KEY_LAST_DEVICE_STATUS_TIMESTAMP = "last_device_status_timestamp"
        private const val KEY_PRIVACY_LEVEL = "privacy_level"
        private const val KEY_DATA_RETENTION_DAYS = "data_retention_days"
        private const val KEY_ENCRYPTION_ENABLED = "encryption_enabled"
        private const val KEY_SECURITY_LEVEL = "security_level"
        
        // Default values
        private const val DEFAULT_SERVER_URL = "https://your-server.com"
        private const val DEFAULT_LOCATION_INTERVAL = 30000L // 30 seconds
        private const val DEFAULT_AUDIO_DURATION = 30 // 30 seconds
        private const val DEFAULT_PRIVACY_LEVEL = 1 // Medium privacy
        private const val DEFAULT_DATA_RETENTION_DAYS = 30
        private const val DEFAULT_SECURITY_LEVEL = 2 // Medium security
    }
    
    private val securePrefs = SecurePreferences(context, PREFS_NAME, true)
    
    init {
        // Migrate existing data if needed
        if (securePrefs.getEncryptionStatus().needsMigration) {
            securePrefs.migrateToEncryption()
        }
        
        Log.d(TAG, "SecureSettingsManager initialized")
    }
    
    // Authentication settings
    fun setAuthToken(token: String?) {
        securePrefs.putString(KEY_AUTH_TOKEN, token)
        Log.d(TAG, "Auth token ${if (token != null) "set" else "cleared"}")
    }
    
    fun getAuthToken(): String? = securePrefs.getString(KEY_AUTH_TOKEN)
    
    fun setRefreshToken(token: String?) {
        securePrefs.putString(KEY_REFRESH_TOKEN, token)
        Log.d(TAG, "Refresh token ${if (token != null) "set" else "cleared"}")
    }
    
    fun getRefreshToken(): String? = securePrefs.getString(KEY_REFRESH_TOKEN)
    
    fun setDeviceId(deviceId: String?) {
        securePrefs.putString(KEY_DEVICE_ID, deviceId)
        Log.d(TAG, "Device ID ${if (deviceId != null) "set" else "cleared"}")
    }
    
    fun getDeviceId(): String? = securePrefs.getString(KEY_DEVICE_ID)
    
    // Child device ID
    fun setChildDeviceId(childDeviceId: String) {
        securePrefs.putString(KEY_CHILD_DEVICE_ID, childDeviceId)
        Log.d(TAG, "Child device ID set to: $childDeviceId")
    }
    
    fun getChildDeviceId(): String? = securePrefs.getString(KEY_CHILD_DEVICE_ID)
    
    fun clearChildDeviceId() {
        securePrefs.remove(KEY_CHILD_DEVICE_ID)
        Log.d(TAG, "Child device ID cleared")
    }
    
    // Server settings
    fun setServerUrl(url: String?) {
        val sanitizedUrl = url?.trim()?.let { 
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it
            } else {
                "https://$it"
            }
        }
        securePrefs.putString(KEY_SERVER_URL, sanitizedUrl)
        Log.d(TAG, "Server URL set to: $sanitizedUrl")
    }
    
    fun getServerUrl(): String = securePrefs.getString(KEY_SERVER_URL) ?: DEFAULT_SERVER_URL
    
    // User consent
    fun setUserConsent(consent: Boolean) {
        securePrefs.putBoolean(KEY_USER_CONSENT, consent)
        Log.d(TAG, "User consent set to: $consent")
    }
    
    fun getUserConsent(): Boolean = securePrefs.getBoolean(KEY_USER_CONSENT, false)
    
    // Monitoring settings
    fun setMonitoringEnabled(enabled: Boolean) {
        securePrefs.putBoolean(KEY_MONITORING_ENABLED, enabled)
        Log.d(TAG, "Monitoring enabled set to: $enabled")
    }
    
    fun isMonitoringEnabled(): Boolean = securePrefs.getBoolean(KEY_MONITORING_ENABLED, false)
    
    fun setLocationEnabled(enabled: Boolean) {
        securePrefs.putBoolean(KEY_LOCATION_ENABLED, enabled)
        Log.d(TAG, "Location enabled set to: $enabled")
    }
    
    fun isLocationEnabled(): Boolean = securePrefs.getBoolean(KEY_LOCATION_ENABLED, true)
    
    fun setAudioEnabled(enabled: Boolean) {
        securePrefs.putBoolean(KEY_AUDIO_ENABLED, enabled)
        Log.d(TAG, "Audio enabled set to: $enabled")
    }
    
    fun isAudioEnabled(): Boolean = securePrefs.getBoolean(KEY_AUDIO_ENABLED, true)
    
    fun setPhotoEnabled(enabled: Boolean) {
        securePrefs.putBoolean(KEY_PHOTO_ENABLED, enabled)
        Log.d(TAG, "Photo enabled set to: $enabled")
    }
    
    fun isPhotoEnabled(): Boolean = securePrefs.getBoolean(KEY_PHOTO_ENABLED, true)
    
    // Timing settings
    fun setLocationInterval(intervalMs: Long) {
        val validInterval = when {
            intervalMs < 10000 -> 10000L // Minimum 10 seconds
            intervalMs > 300000 -> 300000L // Maximum 5 minutes
            else -> intervalMs
        }
        securePrefs.putLong(KEY_LOCATION_INTERVAL, validInterval)
        Log.d(TAG, "Location interval set to: ${validInterval}ms")
    }
    
    fun getLocationInterval(): Long = securePrefs.getLong(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
    
    fun setAudioDuration(durationSeconds: Int) {
        val validDuration = when {
            durationSeconds < 5 -> 5 // Minimum 5 seconds
            durationSeconds > 300 -> 300 // Maximum 5 minutes
            else -> durationSeconds
        }
        securePrefs.putInt(KEY_AUDIO_DURATION, validDuration)
        Log.d(TAG, "Audio duration set to: ${validDuration}s")
    }
    
    fun getAudioDuration(): Int = securePrefs.getInt(KEY_AUDIO_DURATION, DEFAULT_AUDIO_DURATION)
    
    // Service tracking
    fun setServiceStartTime(timestamp: Long) {
        securePrefs.putLong(KEY_SERVICE_START_TIME, timestamp)
        Log.d(TAG, "Service start time set to: $timestamp")
    }
    
    fun getServiceStartTime(): Long = securePrefs.getLong(KEY_SERVICE_START_TIME, 0L)
    
    fun setLastLocationUpdate(timestamp: Long) {
        securePrefs.putLong(KEY_LAST_LOCATION_UPDATE, timestamp)
        Log.d(TAG, "Last location update set to: $timestamp")
    }
    
    fun getLastLocationUpdate(): Long = securePrefs.getLong(KEY_LAST_LOCATION_UPDATE, 0L)
    
    fun setLastAudioUpdate(timestamp: Long) {
        securePrefs.putLong(KEY_LAST_AUDIO_UPDATE, timestamp)
        Log.d(TAG, "Last audio update set to: $timestamp")
    }
    
    fun getLastAudioUpdate(): Long = securePrefs.getLong(KEY_LAST_AUDIO_UPDATE, 0L)
    
    fun setLastPhotoUpdate(timestamp: Long) {
        securePrefs.putLong(KEY_LAST_PHOTO_UPDATE, timestamp)
        Log.d(TAG, "Last photo update set to: $timestamp")
    }
    
    fun getLastPhotoUpdate(): Long = securePrefs.getLong(KEY_LAST_PHOTO_UPDATE, 0L)

    // Device status cache
    fun setLastDeviceStatus(statusJson: String?) {
        securePrefs.putString(KEY_LAST_DEVICE_STATUS, statusJson)
        Log.d(TAG, "Last device status updated")
    }

    fun getLastDeviceStatus(): String? = securePrefs.getString(KEY_LAST_DEVICE_STATUS)

    fun setLastDeviceStatusTimestamp(timestamp: Long) {
        securePrefs.putLong(KEY_LAST_DEVICE_STATUS_TIMESTAMP, timestamp)
        Log.d(TAG, "Last device status timestamp set to: $timestamp")
    }

    fun getLastDeviceStatusTimestamp(): Long = securePrefs.getLong(KEY_LAST_DEVICE_STATUS_TIMESTAMP, 0L)
    
    // Privacy settings
    fun setPrivacyLevel(level: Int) {
        val validLevel = when {
            level < 1 -> 1 // Minimum privacy
            level > 3 -> 3 // Maximum privacy
            else -> level
        }
        securePrefs.putInt(KEY_PRIVACY_LEVEL, validLevel)
        Log.d(TAG, "Privacy level set to: $validLevel")
    }
    
    fun getPrivacyLevel(): Int = securePrefs.getInt(KEY_PRIVACY_LEVEL, DEFAULT_PRIVACY_LEVEL)
    
    fun setDataRetentionDays(days: Int) {
        val validDays = when {
            days < 1 -> 1 // Minimum 1 day
            days > 365 -> 365 // Maximum 1 year
            else -> days
        }
        securePrefs.putInt(KEY_DATA_RETENTION_DAYS, validDays)
        Log.d(TAG, "Data retention days set to: $validDays")
    }
    
    fun getDataRetentionDays(): Int = securePrefs.getInt(KEY_DATA_RETENTION_DAYS, DEFAULT_DATA_RETENTION_DAYS)
    
    // Security settings
    fun setEncryptionEnabled(enabled: Boolean) {
        securePrefs.putBoolean(KEY_ENCRYPTION_ENABLED, enabled)
        Log.d(TAG, "Encryption enabled set to: $enabled")
    }
    
    fun isEncryptionEnabled(): Boolean = securePrefs.getBoolean(KEY_ENCRYPTION_ENABLED, true)
    
    fun setSecurityLevel(level: Int) {
        val validLevel = when {
            level < 1 -> 1 // Minimum security
            level > 3 -> 3 // Maximum security
            else -> level
        }
        securePrefs.putInt(KEY_SECURITY_LEVEL, validLevel)
        Log.d(TAG, "Security level set to: $validLevel")
    }
    
    fun getSecurityLevel(): Int = securePrefs.getInt(KEY_SECURITY_LEVEL, DEFAULT_SECURITY_LEVEL)
    
    // Utility methods
    fun clearAllSettings() {
        securePrefs.clear()
        Log.i(TAG, "All settings cleared")
    }
    
    fun clearAuthTokens() {
        setAuthToken(null)
        setRefreshToken(null)
        Log.i(TAG, "Auth tokens cleared")
    }
    
    fun resetToDefaults() {
        setServerUrl(DEFAULT_SERVER_URL)
        setLocationInterval(DEFAULT_LOCATION_INTERVAL)
        setAudioDuration(DEFAULT_AUDIO_DURATION)
        setPrivacyLevel(DEFAULT_PRIVACY_LEVEL)
        setDataRetentionDays(DEFAULT_DATA_RETENTION_DAYS)
        setSecurityLevel(DEFAULT_SECURITY_LEVEL)
        setLocationEnabled(true)
        setAudioEnabled(true)
        setPhotoEnabled(true)
        setLastDeviceStatus(null)
        setLastDeviceStatusTimestamp(0)
        Log.i(TAG, "Settings reset to defaults")
    }
    
    fun getEncryptionStatus(): EncryptionStatus {
        return securePrefs.getEncryptionStatus()
    }
    
    fun getAllSettings(): Map<String, Any?> {
        return securePrefs.getAll()
    }
    
    fun exportSettings(): String {
        val settings = getAllSettings()
        val export = StringBuilder()
        export.appendLine("ChildWatch Settings Export")
        export.appendLine("Generated: ${System.currentTimeMillis()}")
        export.appendLine("Encryption: ${getEncryptionStatus().isEnabled}")
        export.appendLine("---")
        
        for ((key, value) in settings) {
            if (!key.contains("token") && !key.contains("password")) {
                export.appendLine("$key: $value")
            }
        }
        
        return export.toString()
    }
    
    fun validateSettings(): SettingsValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate server URL
        val serverUrl = getServerUrl()
        if (!serverUrl.startsWith("https://")) {
            warnings.add("Server URL should use HTTPS for security")
        }
        
        // Validate location interval
        val locationInterval = getLocationInterval()
        if (locationInterval < 10000) {
            errors.add("Location interval too frequent (minimum 10 seconds)")
        }
        
        // Validate audio duration
        val audioDuration = getAudioDuration()
        if (audioDuration < 5) {
            errors.add("Audio duration too short (minimum 5 seconds)")
        }
        
        // Validate privacy level
        val privacyLevel = getPrivacyLevel()
        if (privacyLevel < 1 || privacyLevel > 3) {
            errors.add("Invalid privacy level (must be 1-3)")
        }
        
        // Check encryption status
        val encryptionStatus = getEncryptionStatus()
        if (!encryptionStatus.isEnabled) {
            warnings.add("Encryption is disabled - sensitive data may not be protected")
        }
        
        return SettingsValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}

/**
 * Data class for settings validation result
 */
data class SettingsValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)
