package ru.example.parentwatch.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
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
                put("appVersion", "1.0.0")
            }

            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            Log.d(TAG, "Registering device: $deviceId")

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")

                    val authToken = json.optString("authToken")
                    val refreshToken = json.optString("refreshToken")

                    if (authToken.isNotEmpty()) {
                        prefs.edit()
                            .putString("auth_token", authToken)
                            .putString("refresh_token", refreshToken)
                            .apply()

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
     * Upload location to server
     */
    suspend fun uploadLocation(
        serverUrl: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float
    ): Boolean = withContext(Dispatchers.IO) {
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

            Log.d(TAG, "Uploading location: lat=$latitude, lng=$longitude")

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
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            return@withContext false
        }
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
                                    timestamp = cmdObj.getLong("timestamp")
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
            val token = prefs.getString("auth_token", null)

            val request = if (token != null) {
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "ParentWatch/3.1.0")
                    .build()
            } else {
                original.newBuilder()
                    .header("User-Agent", "ParentWatch/3.1.0")
                    .build()
            }

            return chain.proceed(request)
        }
    }
}

/**
 * Data class for streaming command
 */
data class StreamCommand(
    val id: String,
    val type: String,
    val timestamp: Long
)
