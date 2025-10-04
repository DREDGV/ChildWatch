package ru.example.childwatch.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption utility for securing sensitive data in SharedPreferences
 * 
 * Features:
 * - AES-256-GCM encryption
 * - Android Keystore integration
 * - Automatic key management
 * - Secure random IV generation
 * - Data integrity verification
 */
object EncryptionHelper {
    
    private const val TAG = "EncryptionHelper"
    private const val KEYSTORE_ALIAS = "ChildWatchEncryptionKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    
    private var secretKey: SecretKey? = null
    
    /**
     * Initialize encryption helper
     */
    fun initialize(context: Context) {
        try {
            secretKey = getOrCreateSecretKey()
            Log.d(TAG, "Encryption helper initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption helper", e)
            throw RuntimeException("Encryption initialization failed", e)
        }
    }
    
    /**
     * Get or create secret key from Android Keystore
     */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        
        return if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            // Key exists, retrieve it
            val keyEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            keyEntry.secretKey
        } else {
            // Key doesn't exist, create new one
            createSecretKey()
        }
    }
    
    /**
     * Create new secret key in Android Keystore
     */
    private fun createSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
    
    /**
     * Encrypt string data
     */
    fun encrypt(data: String): String? {
        return try {
            val secretKey = this.secretKey ?: throw IllegalStateException("Encryption not initialized")
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            
            // Combine IV + encrypted data
            val combined = ByteArray(iv.size + encryptedData.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedData, 0, combined, iv.size, encryptedData.size)
            
            Base64.encodeToString(combined, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }
    
    /**
     * Decrypt string data
     */
    fun decrypt(encryptedData: String): String? {
        return try {
            val secretKey = this.secretKey ?: throw IllegalStateException("Encryption not initialized")
            
            val combined = Base64.decode(encryptedData, Base64.DEFAULT)
            
            // Extract IV and encrypted data
            val iv = ByteArray(GCM_IV_LENGTH)
            val encrypted = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.size)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val decryptedData = cipher.doFinal(encrypted)
            String(decryptedData, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }
    
    /**
     * Encrypt integer data
     */
    fun encryptInt(value: Int): String? {
        return encrypt(value.toString())
    }
    
    /**
     * Decrypt integer data
     */
    fun decryptInt(encryptedValue: String): Int? {
        return try {
            decrypt(encryptedValue)?.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt integer", e)
            null
        }
    }
    
    /**
     * Encrypt long data
     */
    fun encryptLong(value: Long): String? {
        return encrypt(value.toString())
    }
    
    /**
     * Decrypt long data
     */
    fun decryptLong(encryptedValue: String): Long? {
        return try {
            decrypt(encryptedValue)?.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt long", e)
            null
        }
    }
    
    /**
     * Encrypt boolean data
     */
    fun encryptBoolean(value: Boolean): String? {
        return encrypt(value.toString())
    }
    
    /**
     * Decrypt boolean data
     */
    fun decryptBoolean(encryptedValue: String): Boolean? {
        return try {
            decrypt(encryptedValue)?.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt boolean", e)
            null
        }
    }
    
    /**
     * Check if encryption is available
     */
    fun isEncryptionAvailable(): Boolean {
        return try {
            secretKey != null
        } catch (e: Exception) {
            Log.e(TAG, "Encryption not available", e)
            false
        }
    }
    
    /**
     * Generate secure random string
     */
    fun generateSecureRandomString(length: Int = 32): String {
        val random = SecureRandom()
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.DEFAULT).substring(0, length)
    }
    
    /**
     * Hash sensitive data with salt
     */
    fun hashWithSalt(data: String, salt: String? = null): Pair<String, String> {
        val actualSalt = salt ?: generateSecureRandomString(16)
        val combined = data + actualSalt
        
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray(Charsets.UTF_8))
        
        return Pair(
            Base64.encodeToString(hash, Base64.DEFAULT),
            actualSalt
        )
    }
    
    /**
     * Verify hashed data
     */
    fun verifyHash(data: String, hash: String, salt: String): Boolean {
        val (calculatedHash, _) = hashWithSalt(data, salt)
        return calculatedHash == hash
    }
}
