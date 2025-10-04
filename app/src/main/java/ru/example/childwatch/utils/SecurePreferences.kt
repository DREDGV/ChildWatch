package ru.example.childwatch.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Secure SharedPreferences wrapper with encryption
 * 
 * Features:
 * - Automatic encryption/decryption of sensitive data
 * - Fallback to plain text for non-sensitive data
 * - Key management and rotation
 * - Data integrity verification
 */
class SecurePreferences(
    private val context: Context,
    private val prefsName: String,
    private val encryptSensitive: Boolean = true
) {
    
    companion object {
        private const val TAG = "SecurePreferences"
        private const val KEY_ENCRYPTION_ENABLED = "encryption_enabled"
        private const val KEY_DATA_VERSION = "data_version"
        private const val CURRENT_DATA_VERSION = 1
        
        // Keys that should be encrypted
        private val SENSITIVE_KEYS = setOf(
            "auth_token",
            "refresh_token", 
            "device_id",
            "server_url",
            "user_consent",
            "last_location_lat",
            "last_location_lng",
            "last_audio_file",
            "last_photo_file",
            "service_start_time",
            "last_location_update",
            "last_audio_update",
            "last_photo_update",
            "monitoring_settings",
            "privacy_settings"
        )
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val encryptionHelper = EncryptionHelper
    
    init {
        // Initialize encryption if needed
        if (encryptSensitive) {
            try {
                encryptionHelper.initialize(context)
                prefs.edit().putBoolean(KEY_ENCRYPTION_ENABLED, true).apply()
                prefs.edit().putInt(KEY_DATA_VERSION, CURRENT_DATA_VERSION).apply()
                Log.d(TAG, "SecurePreferences initialized with encryption")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize encryption, falling back to plain text", e)
                prefs.edit().putBoolean(KEY_ENCRYPTION_ENABLED, false).apply()
            }
        }
    }
    
    /**
     * Check if encryption is enabled
     */
    fun isEncryptionEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENCRYPTION_ENABLED, false) && 
               encryptionHelper.isEncryptionAvailable()
    }
    
    /**
     * Check if key should be encrypted
     */
    private fun isSensitiveKey(key: String): Boolean {
        return SENSITIVE_KEYS.contains(key) || key.contains("token") || key.contains("password")
    }
    
    /**
     * Put string value
     */
    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        
        if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = encryptionHelper.encrypt(value)
            if (encrypted != null) {
                prefs.edit().putString("enc_$key", encrypted).apply()
                Log.d(TAG, "Encrypted string for key: $key")
            } else {
                Log.w(TAG, "Encryption failed for key: $key, storing as plain text")
                prefs.edit().putString(key, value).apply()
            }
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }
    
    /**
     * Get string value
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        return if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = prefs.getString("enc_$key", null)
            if (encrypted != null) {
                val decrypted = encryptionHelper.decrypt(encrypted)
                if (decrypted != null) {
                    Log.d(TAG, "Decrypted string for key: $key")
                    decrypted
                } else {
                    Log.w(TAG, "Decryption failed for key: $key, trying plain text")
                    prefs.getString(key, defaultValue)
                }
            } else {
                // Try plain text as fallback
                prefs.getString(key, defaultValue)
            }
        } else {
            prefs.getString(key, defaultValue)
        }
    }
    
    /**
     * Put integer value
     */
    fun putInt(key: String, value: Int) {
        if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = encryptionHelper.encryptInt(value)
            if (encrypted != null) {
                prefs.edit().putString("enc_$key", encrypted).apply()
                Log.d(TAG, "Encrypted int for key: $key")
            } else {
                Log.w(TAG, "Encryption failed for key: $key, storing as plain text")
                prefs.edit().putInt(key, value).apply()
            }
        } else {
            prefs.edit().putInt(key, value).apply()
        }
    }
    
    /**
     * Get integer value
     */
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = prefs.getString("enc_$key", null)
            if (encrypted != null) {
                val decrypted = encryptionHelper.decryptInt(encrypted)
                if (decrypted != null) {
                    Log.d(TAG, "Decrypted int for key: $key")
                    decrypted
                } else {
                    Log.w(TAG, "Decryption failed for key: $key, trying plain text")
                    prefs.getInt(key, defaultValue)
                }
            } else {
                prefs.getInt(key, defaultValue)
            }
        } else {
            prefs.getInt(key, defaultValue)
        }
    }
    
    /**
     * Put long value
     */
    fun putLong(key: String, value: Long) {
        if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = encryptionHelper.encryptLong(value)
            if (encrypted != null) {
                prefs.edit().putString("enc_$key", encrypted).apply()
                Log.d(TAG, "Encrypted long for key: $key")
            } else {
                Log.w(TAG, "Encryption failed for key: $key, storing as plain text")
                prefs.edit().putLong(key, value).apply()
            }
        } else {
            prefs.edit().putLong(key, value).apply()
        }
    }
    
    /**
     * Get long value
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = prefs.getString("enc_$key", null)
            if (encrypted != null) {
                val decrypted = encryptionHelper.decryptLong(encrypted)
                if (decrypted != null) {
                    Log.d(TAG, "Decrypted long for key: $key")
                    decrypted
                } else {
                    Log.w(TAG, "Decryption failed for key: $key, trying plain text")
                    prefs.getLong(key, defaultValue)
                }
            } else {
                prefs.getLong(key, defaultValue)
            }
        } else {
            prefs.getLong(key, defaultValue)
        }
    }
    
    /**
     * Put boolean value
     */
    fun putBoolean(key: String, value: Boolean) {
        if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = encryptionHelper.encryptBoolean(value)
            if (encrypted != null) {
                prefs.edit().putString("enc_$key", encrypted).apply()
                Log.d(TAG, "Encrypted boolean for key: $key")
            } else {
                Log.w(TAG, "Encryption failed for key: $key, storing as plain text")
                prefs.edit().putBoolean(key, value).apply()
            }
        } else {
            prefs.edit().putBoolean(key, value).apply()
        }
    }
    
    /**
     * Get boolean value
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return if (isEncryptionEnabled() && isSensitiveKey(key)) {
            val encrypted = prefs.getString("enc_$key", null)
            if (encrypted != null) {
                val decrypted = encryptionHelper.decryptBoolean(encrypted)
                if (decrypted != null) {
                    Log.d(TAG, "Decrypted boolean for key: $key")
                    decrypted
                } else {
                    Log.w(TAG, "Decryption failed for key: $key, trying plain text")
                    prefs.getBoolean(key, defaultValue)
                }
            } else {
                prefs.getBoolean(key, defaultValue)
            }
        } else {
            prefs.getBoolean(key, defaultValue)
        }
    }
    
    /**
     * Remove key
     */
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
        if (isEncryptionEnabled() && isSensitiveKey(key)) {
            prefs.edit().remove("enc_$key").apply()
        }
    }
    
    /**
     * Clear all data
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Check if key exists
     */
    fun contains(key: String): Boolean {
        return if (isEncryptionEnabled() && isSensitiveKey(key)) {
            prefs.contains("enc_$key") || prefs.contains(key)
        } else {
            prefs.contains(key)
        }
    }
    
    /**
     * Get all keys
     */
    fun getAll(): Map<String, *> {
        val allPrefs = prefs.all
        val result = mutableMapOf<String, Any?>()
        
        for ((key, value) in allPrefs) {
            if (key.startsWith("enc_")) {
                val originalKey = key.substring(4)
                if (isSensitiveKey(originalKey)) {
                    // Decrypt sensitive keys
                    val decrypted = when (value) {
                        is String -> encryptionHelper.decrypt(value)
                        else -> value
                    }
                    result[originalKey] = decrypted
                }
            } else if (!isSensitiveKey(key)) {
                // Include non-sensitive keys as-is
                result[key] = value
            }
        }
        
        return result
    }
    
    /**
     * Migrate plain text data to encrypted
     */
    fun migrateToEncryption() {
        if (!isEncryptionEnabled()) {
            Log.w(TAG, "Encryption not enabled, cannot migrate")
            return
        }
        
        val allPrefs = prefs.all
        val editor = prefs.edit()
        
        for ((key, value) in allPrefs) {
            if (isSensitiveKey(key) && !key.startsWith("enc_")) {
                when (value) {
                    is String -> {
                        val encrypted = encryptionHelper.encrypt(value)
                        if (encrypted != null) {
                            editor.putString("enc_$key", encrypted)
                            editor.remove(key)
                            Log.d(TAG, "Migrated string key: $key")
                        }
                    }
                    is Int -> {
                        val encrypted = encryptionHelper.encryptInt(value)
                        if (encrypted != null) {
                            editor.putString("enc_$key", encrypted)
                            editor.remove(key)
                            Log.d(TAG, "Migrated int key: $key")
                        }
                    }
                    is Long -> {
                        val encrypted = encryptionHelper.encryptLong(value)
                        if (encrypted != null) {
                            editor.putString("enc_$key", encrypted)
                            editor.remove(key)
                            Log.d(TAG, "Migrated long key: $key")
                        }
                    }
                    is Boolean -> {
                        val encrypted = encryptionHelper.encryptBoolean(value)
                        if (encrypted != null) {
                            editor.putString("enc_$key", encrypted)
                            editor.remove(key)
                            Log.d(TAG, "Migrated boolean key: $key")
                        }
                    }
                }
            }
        }
        
        editor.apply()
        Log.i(TAG, "Migration to encryption completed")
    }
    
    /**
     * Get encryption status
     */
    fun getEncryptionStatus(): EncryptionStatus {
        return EncryptionStatus(
            isEnabled = isEncryptionEnabled(),
            isAvailable = encryptionHelper.isEncryptionAvailable(),
            dataVersion = prefs.getInt(KEY_DATA_VERSION, 0),
            currentVersion = CURRENT_DATA_VERSION
        )
    }
}

/**
 * Data class for encryption status
 */
data class EncryptionStatus(
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    val dataVersion: Int,
    val currentVersion: Int
) {
    val needsMigration: Boolean
        get() = isEnabled && dataVersion < currentVersion
}
