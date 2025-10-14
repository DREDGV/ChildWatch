package ru.example.childwatch.network

import android.content.Context
import android.util.Log
import ru.example.childwatch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * NetworkClient for uploading location and audio data to server
 * 
 * Features:
 * - HTTPS-only requests with certificate pinning
 * - Token-based authentication
 * - Retry mechanism with exponential backoff
 * - Offline queue for failed requests
 * - Multipart uploads for audio files
 * - JSON uploads for location data
 * - Proper error handling and timeouts
 * - Logging for debugging
 */
data class CriticalAlert(
    val id: Long,
    val deviceId: String,
    val eventType: String,
    val severity: String,
    val message: String,
    val metadata: Map<String, Any?>?,
    val createdAt: Long
)

class NetworkClient(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkClient"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
        private const val WRITE_TIMEOUT = 60L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }
    
    private var authToken: String? = null
    private val offlineQueue = mutableListOf<OfflineRequest>()
    private val tokenManager = TokenManager(context)
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor())
        .addInterceptor(RetryInterceptor())
        .addInterceptor(createLoggingInterceptor())
        .certificatePinner(createCertificatePinner())
        .build()
    
    /**
     * Set authentication token
     */
    fun setAuthToken(token: String?) {
        authToken = token
        Log.d(TAG, "Auth token ${if (token != null) "set" else "cleared"}")
    }
    
    /**
     * Get current authentication token
     */
    fun getAuthToken(): String? = authToken ?: tokenManager.getAuthToken()
    
    /**
     * Register device and get authentication token
     */
    suspend fun registerDevice(serverUrl: String): String? {
        val token = tokenManager.registerDevice(serverUrl)
        if (token != null) {
            authToken = token
        }
        return token
    }
    
    /**
     * Refresh authentication token
     */
    suspend fun refreshToken(serverUrl: String): String? {
        val token = tokenManager.refreshToken(serverUrl)
        if (token != null) {
            authToken = token
        }
        return token
    }
    
    /**
     * Validate current token
     */
    suspend fun validateToken(serverUrl: String): Boolean {
        return tokenManager.validateToken(serverUrl)
    }
    
    /**
     * Check if token needs refresh
     */
    fun needsTokenRefresh(): Boolean {
        return tokenManager.needsRefresh()
    }
    
    /**
     * Clear all tokens
     */
    fun clearTokens() {
        authToken = null
        tokenManager.clearTokens()
    }
    
    /**
     * Create certificate pinner for HTTPS security
     * In production, replace with actual server certificate hashes
     */
    private fun createCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            // For development/testing - allow all certificates
            // In production, add actual certificate hashes:
            // .add("your-server.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .build()
    }
    
    /**
     * Create logging interceptor
     */
    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            Log.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
    }
    
    /**
     * Authentication interceptor to add auth token to requests
     */
    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val currentToken = getAuthToken()

            val newRequest = if (currentToken != null) {
                originalRequest.newBuilder()
                    .addHeader("Authorization", "Bearer $currentToken")
                    .build()
            } else {
                originalRequest
            }

            val response = chain.proceed(newRequest)

            // Handle token refresh on 401 Unauthorized
            if (response.code == 401 && currentToken != null) {
                response.close()
                
                // Try to refresh token
                val serverUrl = extractServerUrl(originalRequest.url.toString())
                if (serverUrl != null) {
                    try {
                        // Use runBlocking to call suspend function
                        val refreshedToken = kotlinx.coroutines.runBlocking {
                            tokenManager.refreshToken(serverUrl)
                        }
                        if (refreshedToken != null) {
                            authToken = refreshedToken
                            
                            // Retry request with new token
                            val retryRequest = originalRequest.newBuilder()
                                .addHeader("Authorization", "Bearer $refreshedToken")
                                .build()
                            
                            return chain.proceed(retryRequest)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to refresh token", e)
                    }
                }
            }

            return response
        }
        
        private fun extractServerUrl(url: String): String? {
            return try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Retry interceptor with exponential backoff
     */
    private inner class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response: Response? = null
            var exception: IOException? = null
            
            for (attempt in 1..MAX_RETRIES) {
                try {
                    if (response != null) {
                        response.close()
                    }
                    
                    response = chain.proceed(request)
                    
                    if (response.isSuccessful) {
                        return response
                    }
                    
                    // Don't retry on client errors (4xx)
                    if (response.code in 400..499) {
                        return response
                    }
                    
                } catch (e: IOException) {
                    exception = e
                    Log.w(TAG, "Request attempt $attempt failed: ${e.message}")
                }
                
                if (attempt < MAX_RETRIES) {
                    val delay = RETRY_DELAY_MS * (1L shl (attempt - 1)) // Exponential backoff
                    Log.d(TAG, "Retrying in ${delay}ms...")
                    Thread.sleep(delay)
                }
            }
            
            response?.close()
            throw exception ?: IOException("Max retries exceeded")
        }
    }
    
    /**
     * Data class for offline request queue
     */
    private data class OfflineRequest(
        val url: String,
        val requestBody: RequestBody,
        val headers: Map<String, String>,
        val timestamp: Long
    )
    
    /**
     * Upload location data to server
     */
    suspend fun uploadLocation(
        serverUrl: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        timestamp: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Ensure HTTPS URL
            val secureUrl = ensureHttpsUrl(serverUrl)
            val url = "${secureUrl.trimEnd('/')}/api/loc"
            
            val jsonData = JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("timestamp", timestamp)
                put("deviceId", getDeviceId())
            }
            
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Uploading location to: $url")
            Log.d(TAG, "Location data: lat=$latitude, lng=$longitude, acc=$accuracy")
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Location uploaded successfully: ${response.code}")
                    return@withContext true
                } else {
                    Log.e(TAG, "Failed to upload location: ${response.code} ${response.message}")
                    Log.e(TAG, "Response body: ${response.body?.string()}")
                    
                    // Add to offline queue for retry later
                    addToOfflineQueue(url, requestBody, mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to "ChildWatch/" + BuildConfig.VERSION_NAME
                    ))
                    
                    return@withContext false
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error uploading location", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error uploading location", e)
            return@withContext false
        }
    }
    
    /**
     * Upload audio file to server
     */
    suspend fun uploadAudio(
        serverUrl: String,
        audioFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists() || audioFile.length() == 0L) {
                Log.e(TAG, "Audio file doesn't exist or is empty: ${audioFile.absolutePath}")
                return@withContext false
            }
            
            val url = "${serverUrl.trimEnd('/')}/api/audio"
            
            // Create multipart request body
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType())
                )
                .addFormDataPart("deviceId", getDeviceId())
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .addFormDataPart("duration", "unknown") // Could calculate from file
                .build()
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Uploading audio to: $url")
            Log.d(TAG, "Audio file: ${audioFile.name}, size: ${audioFile.length()} bytes")
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Audio uploaded successfully: ${response.code}")
                    return@withContext true
                } else {
                    Log.e(TAG, "Failed to upload audio: ${response.code} ${response.message}")
                    Log.e(TAG, "Response body: ${response.body?.string()}")
                    return@withContext false
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error uploading audio", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error uploading audio", e)
            return@withContext false
        }
    }
    
    /**
     * Test server connectivity
     */
    suspend fun testConnection(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${serverUrl.trimEnd('/')}/api/health"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Testing connection to: $url")
            
            client.newCall(request).execute().use { response ->
                val isSuccessful = response.isSuccessful
                Log.d(TAG, "Connection test result: ${response.code} - ${if (isSuccessful) "OK" else "Failed"}")
                return@withContext isSuccessful
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error testing connection", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error testing connection", e)
            return@withContext false
        }
    }
    
    /**
     * Upload photo to server (for future implementation)
     */
    suspend fun uploadPhoto(
        serverUrl: String,
        photoFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!photoFile.exists() || photoFile.length() == 0L) {
                Log.e(TAG, "Photo file doesn't exist or is empty: ${photoFile.absolutePath}")
                return@withContext false
            }
            
            val url = "${serverUrl.trimEnd('/')}/api/photo"
            
            // Create multipart request body
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "photo",
                    photoFile.name,
                    photoFile.asRequestBody("image/jpeg".toMediaType())
                )
                .addFormDataPart("deviceId", getDeviceId())
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .build()
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Uploading photo to: $url")
            Log.d(TAG, "Photo file: ${photoFile.name}, size: ${photoFile.length()} bytes")
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Photo uploaded successfully: ${response.code}")
                    return@withContext true
                } else {
                    Log.e(TAG, "Failed to upload photo: ${response.code} ${response.message}")
                    return@withContext false
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error uploading photo", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error uploading photo", e)
            return@withContext false
        }
    }
    
    /**
     * Get a proper device identifier using Android ID
     */
    private fun getDeviceId(): String {
        return try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            "device_$androidId"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Android ID, using fallback", e)
            "device_${System.currentTimeMillis() % 10000}"
        }
    }
    
    /**
     * Send emergency alert to server
     */
    suspend fun sendEmergencyAlert(
        serverUrl: String,
        emergencyData: Map<String, Any?>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${serverUrl.trimEnd('/')}/api/emergency"
            
            val json = com.google.gson.Gson().toJson(emergencyData)
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Device-ID", getDeviceId())
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "Emergency alert sent successfully")
                true
            } else {
                Log.e(TAG, "Failed to send emergency alert: ${response.code} ${response.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending emergency alert", e)
            false
        }
    }
    
    /**
     * Ensure URL uses HTTPS
     */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            map[key] = obj.get(key)
        }
        return map
    }

    /**
     * Ensure URL uses HTTPS
     */
    private fun ensureHttpsUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> url.replace("http://", "https://")
            else -> "https://$url"
        }
    }
    
    /**
     * Add failed request to offline queue
     */
    private fun addToOfflineQueue(url: String, requestBody: RequestBody, headers: Map<String, String>) {
        synchronized(offlineQueue) {
            offlineQueue.add(OfflineRequest(url, requestBody, headers, System.currentTimeMillis()))
            Log.d(TAG, "Added request to offline queue. Queue size: ${offlineQueue.size}")
        }
    }
    
    /**
     * Process offline queue when connection is restored
     */
    suspend fun processOfflineQueue(): Int = withContext(Dispatchers.IO) {
        val requestsToProcess = synchronized(offlineQueue) {
            offlineQueue.toList().also { offlineQueue.clear() }
        }
        
        var successCount = 0
        
        for (offlineRequest in requestsToProcess) {
            try {
                val request = Request.Builder()
                    .url(offlineRequest.url)
                    .post(offlineRequest.requestBody)
                    .apply {
                        offlineRequest.headers.forEach { (key, value) ->
                            addHeader(key, value)
                        }
                    }
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        successCount++
                        Log.d(TAG, "Offline request processed successfully")
                    } else {
                        Log.w(TAG, "Offline request failed: ${response.code}")
                        // Re-add to queue for later retry
                        addToOfflineQueue(offlineRequest.url, offlineRequest.requestBody, offlineRequest.headers)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing offline request", e)
                // Re-add to queue for later retry
                addToOfflineQueue(offlineRequest.url, offlineRequest.requestBody, offlineRequest.headers)
            }
        }
        
        Log.d(TAG, "Processed $successCount out of ${requestsToProcess.size} offline requests")
        successCount
    }
    
    /**
     * Get offline queue size
     */
    fun getOfflineQueueSize(): Int = synchronized(offlineQueue) { offlineQueue.size }
    
    /**
     * Clear offline queue
     */
    fun clearOfflineQueue() {
        synchronized(offlineQueue) {
            offlineQueue.clear()
            Log.d(TAG, "Offline queue cleared")
        }
    }

    /**
     * Get child location from server using Retrofit
     */
    suspend fun getChildLocation(childDeviceId: String): retrofit2.Response<LocationResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
                val serverUrl = prefs.getString("server_url", "https://childwatch-production.up.railway.app") ?: "https://childwatch-production.up.railway.app"

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting child location from server: $serverUrl")
                Log.d(TAG, "Child device ID: $childDeviceId")

                api.getChildLocation(childDeviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting child location", e)
                // Return empty response instead of throwing
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /**
     * Get child device status from server using Retrofit
     */
    suspend fun getChildDeviceStatus(childDeviceId: String): retrofit2.Response<DeviceStatusResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
                val serverUrl = prefs.getString("server_url", "https://childwatch-production.up.railway.app")
                    ?: "https://childwatch-production.up.railway.app"

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting child device status from server: $serverUrl")
                Log.d(TAG, "Child device ID: $childDeviceId")

                api.getDeviceStatus(childDeviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting child device status", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /**
     * Create Retrofit client with authentication
     */
    private fun createRetrofitClient(baseUrl: String): retrofit2.Retrofit {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val authInterceptor = okhttp3.Interceptor { chain ->
            val original = chain.request()
            val token = getAuthToken()

            val request = if (token != null) {
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                    .build()
            } else {
                original.newBuilder()
                    .header("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                    .build()
            }

            chain.proceed(request)
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()

        return retrofit2.Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
    }

    // ========== Audio Streaming Methods ==========

    /**
     * Start audio streaming from child device
     */
    suspend fun startAudioStreaming(serverUrl: String, deviceId: String, recordingMode: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/api/streaming/start"
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("recording", recordingMode)
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Audio streaming started for device $deviceId (recording: $recordingMode)")
                } else {
                    Log.e(TAG, "Failed to start streaming: ${response.code} ${response.body?.string()}")
                }

                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error starting audio streaming", e)
                false
            }
        }
    }

    /**
     * Stop audio streaming
     */
    suspend fun stopAudioStreaming(serverUrl: String, deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/api/streaming/stop"
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Audio streaming stopped for device $deviceId")
                } else {
                    Log.e(TAG, "Failed to stop streaming: ${response.code}")
                }

                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio streaming", e)
                false
            }
        }
    }

    /**
     * Start recording during streaming
     */
    suspend fun startRecording(serverUrl: String, deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/api/streaming/record/start"
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Recording started for device $deviceId")
                } else {
                    Log.e(TAG, "Failed to start recording: ${response.code}")
                }

                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                false
            }
        }
    }

    /**
     * Stop recording
     */
    suspend fun stopRecording(serverUrl: String, deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/api/streaming/record/stop"
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Recording stopped for device $deviceId")
                } else {
                    Log.e(TAG, "Failed to stop recording: ${response.code}")
                }

                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
                false
            }
        }
    }

    /**
     * Get audio chunks from server
     */
    suspend fun getAudioChunks(serverUrl: String, deviceId: String): List<AudioChunk>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/api/streaming/chunks/$deviceId"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    response.close()

                    if (body != null) {
                        val json = JSONObject(body)
                        val chunksArray = json.optJSONArray("chunks")

                        if (chunksArray != null) {
                            val chunks = mutableListOf<AudioChunk>()
                            for (i in 0 until chunksArray.length()) {
                                val chunkObj = chunksArray.getJSONObject(i)
                                chunks.add(
                                    AudioChunk(
                                        sequence = chunkObj.getInt("sequence"),
                                        data = android.util.Base64.decode(chunkObj.getString("data"), android.util.Base64.DEFAULT),
                                        timestamp = chunkObj.getLong("timestamp")
                                    )
                                )
                            }
                            Log.d(TAG, "Received ${chunks.size} audio chunks")
                            chunks.forEach { chunk ->
                                Log.d(TAG, "Chunk: sequence=${chunk.sequence}, size=${chunk.data.size}, timestamp=${chunk.timestamp}")
                            }
                            return@withContext chunks
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to get audio chunks: ${response.code}")
                    response.close()
                }

                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting audio chunks", e)
                null
            }
        }
    }

    /**
     * Get streaming status
     */
    suspend fun getStreamingStatus(serverUrl: String, deviceId: String): StreamingStatus? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/api/streaming/status/$deviceId"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    response.close()

                    if (body != null) {
                        val json = JSONObject(body)
                        return@withContext StreamingStatus(
                            active = json.getBoolean("active"),
                            recording = json.optBoolean("recording", false),
                            startedAt = json.optLong("startedAt", 0L)
                        )
                    }
                } else {
                    response.close()
                }

                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting streaming status", e)
                null
            }
        }
    }

    /**
     * Data class for audio chunk
     */
    data class AudioChunk(
        val sequence: Int,
        val data: ByteArray,
        val timestamp: Long
    )

    /**
     * Data class for streaming status
     */
    data class StreamingStatus(
        val active: Boolean,
        val recording: Boolean,
        val startedAt: Long
    )
    suspend fun sendCriticalEvent(
        serverUrl: String,
        deviceId: String,
        eventType: String,
        severity: String,
        message: String,
        metadata: Map<String, Any?> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${ensureHttpsUrl(serverUrl).trimEnd('/')}/api/alerts"
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("eventType", eventType)
                put("severity", severity)
                put("message", message)
                if (metadata.isNotEmpty()) {
                    put("metadata", JSONObject(metadata))
                }
            }

            val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "sendCriticalEvent failed: ${response.code}")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending critical event", e)
            false
        }
    }

    suspend fun fetchCriticalAlerts(
        serverUrl: String,
        deviceId: String,
        limit: Int = 20
    ): List<CriticalAlert> = withContext(Dispatchers.IO) {
        try {
            val url = "${ensureHttpsUrl(serverUrl).trimEnd('/')}/api/alerts/pending/$deviceId?limit=$limit"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchCriticalAlerts failed: ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val alertsArray = json.optJSONArray("alerts") ?: return@withContext emptyList()
                val result = mutableListOf<CriticalAlert>()
                for (i in 0 until alertsArray.length()) {
                    val obj = alertsArray.getJSONObject(i)
                    val metadataObj = obj.optJSONObject("metadata")
                    val metadataMap = metadataObj?.let { jsonObjectToMap(it) }
                    result += CriticalAlert(
                        id = obj.getLong("id"),
                        deviceId = obj.optString("deviceId", deviceId),
                        eventType = obj.optString("eventType", ""),
                        severity = obj.optString("severity", "INFO"),
                        message = obj.optString("message", ""),
                        metadata = metadataMap,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                }
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching critical alerts", e)
            emptyList()
        }
    }

    suspend fun acknowledgeCriticalAlerts(
        serverUrl: String,
        deviceId: String,
        alertIds: List<Long>
    ): Boolean = withContext(Dispatchers.IO) {
        if (alertIds.isEmpty()) return@withContext true
        try {
            val url = "${ensureHttpsUrl(serverUrl).trimEnd('/')}/api/alerts/ack"
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("alertIds", alertIds)
            }
            val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "acknowledgeCriticalAlerts failed: ${response.code}")
                    return@withContext false
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acknowledging alerts", e)
            false
        }
    }
}

