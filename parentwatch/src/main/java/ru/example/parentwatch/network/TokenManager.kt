package ru.example.parentwatch.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TokenManager for handling device authentication tokens
 * 
 * Features:
 * - Token registration and validation
 * - Automatic token refresh
 * - Secure token storage
 * - Server communication for token management
 */
class TokenManager(private val context: Context) {
    
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "parentwatch_tokens"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        
        private const val TOKEN_REFRESH_THRESHOLD = 5 * 60 * 1000L // 5 minutes before expiry
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        migrateLegacyNetworkHelperTokens()
    }
    
    /**
     * Register device and get authentication token
     */
    suspend fun registerDevice(serverUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            val url = "${serverUrl.trimEnd('/')}/api/auth/register"
            
            val jsonData = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", android.os.Build.MODEL)
                put("deviceType", "android")
                put("appVersion", sanitizeVersionName(ru.example.parentwatch.BuildConfig.VERSION_NAME))
                put("timestamp", System.currentTimeMillis())
            }
            
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ChildWatch/" + sanitizeVersionName(ru.example.parentwatch.BuildConfig.VERSION_NAME))
                .build()

            Log.d(TAG, "Registering device: $deviceId")
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Device registration successful")
                    
                    val tokenData = JSONObject(responseBody ?: "{}")
                    val authToken = tokenData.optString("authToken")
                    val refreshToken = tokenData.optString("refreshToken")
                    val expiresIn = tokenData.optLong("expiresIn", 3600) * 1000L // Convert to milliseconds
                    
                    if (authToken.isNotEmpty()) {
                        saveTokens(authToken, refreshToken, expiresIn)
                        Log.d(TAG, "Tokens saved successfully")
                        return@withContext authToken
                    } else {
                        Log.e(TAG, "No auth token in response")
                        return@withContext null
                    }
                } else {
                    Log.e(TAG, "Device registration failed: ${response.code} ${response.message}")
                    Log.e(TAG, "Response body: ${response.body?.string()}")
                    return@withContext null
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error during device registration", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during device registration", e)
            return@withContext null
        }
    }
    
    /**
     * Refresh authentication token
     */
    suspend fun refreshToken(serverUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val refreshToken = getRefreshToken()
            if (refreshToken.isNullOrEmpty()) {
                Log.w(TAG, "No refresh token available")
                return@withContext null
            }
            
            val url = "${serverUrl.trimEnd('/')}/api/auth/refresh"
            
            val jsonData = JSONObject().apply {
                put("refreshToken", refreshToken)
                put("deviceId", getDeviceId())
            }
            
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ChildWatch/" + ru.example.parentwatch.BuildConfig.VERSION_NAME)
                .build()

            Log.d(TAG, "Refreshing token")
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Token refresh successful")
                    
                    val tokenData = JSONObject(responseBody ?: "{}")
                    val authToken = tokenData.optString("authToken")
                    val newRefreshToken = tokenData.optString("refreshToken")
                    val expiresIn = tokenData.optLong("expiresIn", 3600) * 1000L
                    
                    if (authToken.isNotEmpty()) {
                        saveTokens(authToken, newRefreshToken, expiresIn)
                        Log.d(TAG, "New tokens saved successfully")
                        return@withContext authToken
                    } else {
                        Log.e(TAG, "No auth token in refresh response")
                        return@withContext null
                    }
                } else {
                    Log.e(TAG, "Token refresh failed: ${response.code} ${response.message}")
                    Log.e(TAG, "Response body: ${response.body?.string()}")
                    return@withContext null
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error during token refresh", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during token refresh", e)
            return@withContext null
        }
    }
    
    /**
     * Validate current token
     */
    suspend fun validateToken(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val authToken = getAuthToken()
            if (authToken.isNullOrEmpty()) {
                Log.w(TAG, "No auth token available")
                return@withContext false
            }
            
            val url = "${serverUrl.trimEnd('/')}/api/auth/validate"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("User-Agent", "ChildWatch/" + ru.example.parentwatch.BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Validating token")
            
            httpClient.newCall(request).execute().use { response ->
                val isValid = response.isSuccessful
                Log.d(TAG, "Token validation result: $isValid")
                return@withContext isValid
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error during token validation", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during token validation", e)
            return@withContext false
        }
    }
    
    /**
     * Get current authentication token
     */
    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }
    
    /**
     * Get refresh token
     */
    fun getRefreshToken(): String? {
        return prefs.getString(KEY_REFRESH_TOKEN, null)
    }
    
    /**
     * Check if token needs refresh
     */
    fun needsRefresh(): Boolean {
        val expiryTime = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        val currentTime = System.currentTimeMillis()
        return expiryTime - currentTime < TOKEN_REFRESH_THRESHOLD
    }
    
    /**
     * Check if token is expired
     */
    fun isTokenExpired(): Boolean {
        val expiryTime = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        return System.currentTimeMillis() >= expiryTime
    }
    
    /**
     * Save tokens securely
     */
    fun saveTokens(authToken: String, refreshToken: String?, expiresIn: Long) {
        val expiryTime = System.currentTimeMillis() + expiresIn

        prefs.edit().apply {
            putString(KEY_AUTH_TOKEN, authToken)
            if (!refreshToken.isNullOrBlank()) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            putLong(KEY_TOKEN_EXPIRY, expiryTime)
        }.apply()
        
        Log.d(TAG, "Tokens saved. Expires at: ${java.util.Date(expiryTime)}")
    }

    /**
     * Older LocationService builds stored authentication in parentwatch_prefs,
     * while WebSocketClient read parentwatch_tokens. Copy that state once so an
     * upgraded phone can recover without waiting for the user to open the app.
     */
    private fun migrateLegacyNetworkHelperTokens() {
        if (!prefs.getString(KEY_AUTH_TOKEN, null).isNullOrBlank()) return

        val legacyPrefs = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
        val legacyAuthToken = legacyPrefs.getString(KEY_AUTH_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val legacyRefreshToken = legacyPrefs.getString(KEY_REFRESH_TOKEN, null)

        prefs.edit().apply {
            putString(KEY_AUTH_TOKEN, legacyAuthToken)
            if (!legacyRefreshToken.isNullOrBlank()) {
                putString(KEY_REFRESH_TOKEN, legacyRefreshToken)
            }
            // The old store did not retain an expiry. Mark it for immediate
            // refresh while still making it available for compatibility mode.
            putLong(KEY_TOKEN_EXPIRY, 0L)
        }.apply()
        Log.i(TAG, "Migrated legacy NetworkHelper authentication state")
    }
    
    /**
     * Clear all tokens
     */
    fun clearTokens() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .apply()

        Log.d(TAG, "All tokens cleared")
    }

    fun setDeviceId(deviceId: String?) {
        prefs.edit().apply {
            if (deviceId.isNullOrBlank()) {
                remove(KEY_DEVICE_ID)
            } else {
                putString(KEY_DEVICE_ID, deviceId.trim())
            }
        }.apply()

        Log.d(TAG, "TokenManager device ID ${if (deviceId.isNullOrBlank()) "cleared" else "set to ${deviceId.trim()}"}")
    }
    
    /**
     * Get device ID
     */
    private fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            val deviceId = "device_$androidId"
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            deviceId
        }
    }
    
    /**
     * Get token info for debugging
     */
    fun getTokenInfo(): Map<String, Any> {
        val authToken = getAuthToken()
        val expiryTime = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        val currentTime = System.currentTimeMillis()
        
        return mapOf(
            "hasToken" to !authToken.isNullOrEmpty(),
            "isExpired" to isTokenExpired(),
            "needsRefresh" to needsRefresh(),
            "expiresAt" to java.util.Date(expiryTime),
            "timeUntilExpiry" to (expiryTime - currentTime)
        )
    }

    private fun sanitizeVersionName(versionName: String): String {
        val match = Regex("""\d+\.\d+\.\d+""").find(versionName)
        return match?.value ?: versionName
    }
}


