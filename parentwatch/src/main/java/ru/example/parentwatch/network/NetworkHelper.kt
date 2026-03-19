package ru.example.parentwatch.network

import android.content.Context
import ru.example.parentwatch.BuildConfig
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Network helper for ParentWatch
 * Handles device registration and location uploads
 */
class NetworkHelper(private val context: Context) {

    companion object {
        private const val TAG = "NetworkHelper"
    }

    private val prefs = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
    private val refreshLock = Any()
    @Volatile private var isRefreshingToken = false

    private val authClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor())
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    /**
     * Register device on server
     */
    suspend fun registerDevice(serverUrl: String, deviceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${serverUrl.trimEnd('/')}/api/auth/register"

            val jsonData = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", android.os.Build.MODEL)
                put("deviceType", "android")
                put("appVersion", BuildConfig.VERSION_NAME)
            }

            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            Log.d(TAG, "Registering device: $deviceId")

            authClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")

                    val authToken = json.optString("authToken")
                    val refreshToken = json.optString("refreshToken")

                    if (authToken.isNotEmpty()) {
                        saveTokens(authToken, refreshToken)

                        Log.d(TAG, "Device registered successfully")
                        return@withContext true
                    }
                }

                Log.e(TAG, "Registration failed: ${response.code}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            return@withContext false
        }
    }

    /**
     * Upload location with device info to server
     */
    suspend fun uploadLocationWithDeviceInfo(
        serverUrl: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        deviceInfo: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        val maxRetries = 3

        repeat(maxRetries) { attempt ->
            try {
                val url = "${serverUrl.trimEnd('/')}/api/loc"

                val jsonData = JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy)
                    put("timestamp", System.currentTimeMillis())
                    put("deviceInfo", deviceInfo) // Include device info
                }

                val requestBody = jsonData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                if (attempt > 0) {
                    Log.d(TAG, "Retry attempt $attempt: Uploading location with device info")
                } else {
                    Log.d(TAG, "Uploading location with device info: lat=$latitude, lng=$longitude")
                }

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Location with device info uploaded successfully")

                        // Save last update time
                        prefs.edit()
                            .putLong("last_update", System.currentTimeMillis())
                            .apply()

                        return@withContext true
                    } else {
                        Log.e(TAG, "Upload failed: ${response.code}")
                        if (attempt < maxRetries - 1) {
                            kotlinx.coroutines.delay(1000L * (attempt + 1))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload error (attempt ${attempt + 1})", e)
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
        }

        return@withContext false
    }

    /**
     * Upload device status snapshot without creating a new location point.
     */
    suspend fun uploadDeviceStatus(
        serverUrl: String,
        deviceInfo: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        val maxRetries = 3

        repeat(maxRetries) { attempt ->
            try {
                val url = "${serverUrl.trimEnd('/')}/api/device/status"
                val jsonData = JSONObject().apply {
                    put("deviceInfo", deviceInfo)
                }

                val requestBody = jsonData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                if (attempt > 0) {
                    Log.d(TAG, "Retry attempt $attempt: Uploading device status")
                } else {
                    Log.d(TAG, "Uploading device status snapshot")
                }

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        prefs.edit()
                            .putLong("last_status_update", System.currentTimeMillis())
                            .apply()
                        return@withContext true
                    } else {
                        Log.e(TAG, "Device status upload failed: ${response.code}")
                        if (attempt < maxRetries - 1) {
                            kotlinx.coroutines.delay(1000L * (attempt + 1))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Device status upload error (attempt ${attempt + 1})", e)
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
        }

        return@withContext false
    }

    /**
     * Upload location to server with retry (for network change resilience)
     */
    suspend fun uploadLocation(
        serverUrl: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float
    ): Boolean = withContext(Dispatchers.IO) {
        val maxRetries = 3

        repeat(maxRetries) { attempt ->
            try {
                val url = "${serverUrl.trimEnd('/')}/api/loc"

                val jsonData = JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy)
                    put("timestamp", System.currentTimeMillis())
                }

                val requestBody = jsonData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                if (attempt > 0) {
                    Log.d(TAG, "Retry attempt $attempt: Uploading location")
                } else {
                    Log.d(TAG, "Uploading location: lat=$latitude, lng=$longitude")
                }

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Location uploaded successfully")

                        // Save last update time
                        prefs.edit()
                            .putLong("last_update", System.currentTimeMillis())
                            .apply()

                        return@withContext true
                    } else {
                        Log.e(TAG, "Upload failed: ${response.code}")
                        if (attempt < maxRetries - 1) {
                            kotlinx.coroutines.delay(1000L * (attempt + 1)) // 1s, 2s
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload error (attempt ${attempt + 1})", e)
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1)) // 1s, 2s delay
                }
            }
        }

        return@withContext false
    }

    /**
     * Get streaming commands for device
     */
    suspend fun getStreamingCommands(serverUrl: String, deviceId: String): List<StreamCommand> = withContext(Dispatchers.IO) {
        try {
            val url = "${serverUrl.trimEnd('/')}/api/streaming/commands/$deviceId"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            Log.d(TAG, "Getting streaming commands for: $deviceId")

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")

                    val commandsArray = json.optJSONArray("commands")
                    val commands = mutableListOf<StreamCommand>()

                    if (commandsArray != null) {
                        for (i in 0 until commandsArray.length()) {
                            val cmdObj = commandsArray.getJSONObject(i)
                            commands.add(
                                StreamCommand(
                                    id = cmdObj.getString("id"),
                                    type = cmdObj.getString("type"),
                                    timestamp = cmdObj.getLong("timestamp"),
                                    data = cmdObj.optJSONObject("data")
                                )
                            )
                        }
                    }

                    Log.d(TAG, "Received ${commands.size} commands")
                    return@withContext commands
                }

                Log.e(TAG, "Get commands failed: ${response.code}")
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get commands error", e)
            return@withContext emptyList()
        }
    }

    /**
     * Upload audio chunk to server
     */
    suspend fun uploadAudioChunk(
        serverUrl: String,
        deviceId: String,
        audioData: ByteArray,
        sequence: Int,
        recording: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${serverUrl.trimEnd('/')}/api/streaming/chunk"

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceId", deviceId)
                .addFormDataPart("sequence", sequence.toString())
                .addFormDataPart("recording", recording.toString())
                .addFormDataPart(
                    "audio",
                    "chunk_$sequence.pcm",
                    audioData.toRequestBody("audio/pcm".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            Log.d(TAG, "Uploading audio chunk: sequence=$sequence, size=${audioData.size}, recording=$recording")

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Audio chunk uploaded successfully")
                    return@withContext true
                } else {
                    Log.e(TAG, "Upload chunk failed: ${response.code}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload chunk error", e)
            return@withContext false
        }
    }

    /**
     * Interceptor to add auth token to requests
     */
    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val token = getAuthToken()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", "ParentWatch/" + BuildConfig.VERSION_NAME)

            if (token != null) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val request = requestBuilder.build()
            val response = chain.proceed(request)

            if (response.code == 401 && original.header("X-Auth-Retry") == null) {
                response.close()
                val baseUrl = extractServerUrl(original.url.toString())
                val refreshedToken = if (baseUrl != null) refreshAuthTokenBlocking(baseUrl) else null
                if (!refreshedToken.isNullOrBlank()) {
                    val retryRequest = original.newBuilder()
                        .header("Authorization", "Bearer $refreshedToken")
                        .header("User-Agent", "ParentWatch/" + BuildConfig.VERSION_NAME)
                        .header("X-Auth-Retry", "1")
                        .build()
                    return chain.proceed(retryRequest)
                }
            }

            return response
        }
    }

    private fun getAuthToken(): String? = prefs.getString("auth_token", null)

    private fun saveTokens(authToken: String, refreshToken: String?) {
        val editor = prefs.edit().putString("auth_token", authToken)
        if (!refreshToken.isNullOrBlank()) {
            editor.putString("refresh_token", refreshToken)
        }
        editor.apply()
    }

    private fun resolveDeviceId(): String? {
        return prefs.getString("device_id", null)
            ?: prefs.getString("child_device_id", null)
    }

    private fun extractServerUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshAuthTokenBlocking(serverUrl: String): String? {
        val deviceId = resolveDeviceId()
        if (deviceId.isNullOrBlank()) {
            Log.w(TAG, "Cannot refresh token: deviceId missing")
            return null
        }

        synchronized(refreshLock) {
            if (isRefreshingToken) {
                return getAuthToken()
            }
            isRefreshingToken = true
        }

        try {
            val refreshToken = prefs.getString("refresh_token", null)
            if (refreshToken.isNullOrBlank()) {
                Log.w(TAG, "No refresh token, attempting re-register")
                return registerDeviceBlocking(serverUrl, deviceId)
            }

            val url = "${serverUrl.trimEnd('/')}/api/auth/refresh"
            val jsonData = JSONObject().apply {
                put("refreshToken", refreshToken)
                put("deviceId", deviceId)
            }
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            authClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")
                    val authToken = json.optString("authToken")
                    val newRefreshToken = json.optString("refreshToken")
                    if (authToken.isNotEmpty()) {
                        saveTokens(authToken, newRefreshToken)
                        Log.d(TAG, "Token refreshed successfully")
                        return authToken
                    }
                } else {
                    Log.w(TAG, "Token refresh failed: ${response.code}")
                    if (response.code == 401) {
                        return registerDeviceBlocking(serverUrl, deviceId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
        } finally {
            synchronized(refreshLock) {
                isRefreshingToken = false
            }
        }
        return null
    }

    private fun registerDeviceBlocking(serverUrl: String, deviceId: String): String? {
        try {
            val url = "${serverUrl.trimEnd('/')}/api/auth/register"
            val jsonData = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", android.os.Build.MODEL)
                put("deviceType", "android")
                put("appVersion", BuildConfig.VERSION_NAME)
            }
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            authClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")
                    val authToken = json.optString("authToken")
                    val refreshToken = json.optString("refreshToken")
                    if (authToken.isNotEmpty()) {
                        saveTokens(authToken, refreshToken)
                        Log.d(TAG, "Device re-registered successfully")
                        return authToken
                    }
                } else {
                    Log.e(TAG, "Re-register failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Re-register error", e)
        }
        return null
    }

    /**
     * Upload photo to server
     */
    suspend fun uploadPhoto(serverUrl: String, deviceId: String, photoFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${serverUrl.trimEnd('/')}/api/photo"

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceId", deviceId)
                .addFormDataPart(
                    "photo",
                    photoFile.name,
                    photoFile.asRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            Log.d(TAG, "Uploading photo: ${photoFile.name}")

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Photo uploaded successfully")
                    return@withContext true
                } else {
                    Log.e(TAG, "Photo upload failed: ${response.code}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading photo", e)
            return@withContext false
        }
    }
}

/**
 * Data class for streaming command
 */
data class StreamCommand(
    val id: String,
    val type: String,
    val timestamp: Long,
    val data: JSONObject? = null
)


