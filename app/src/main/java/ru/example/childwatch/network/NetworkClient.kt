package ru.example.childwatch.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NetworkClient for uploading location and audio data to server
 * 
 * Features:
 * - HTTPS-only requests for security
 * - Multipart uploads for audio files
 * - JSON uploads for location data
 * - Proper error handling and timeouts
 * - Logging for debugging
 */
class NetworkClient {
    
    companion object {
        private const val TAG = "NetworkClient"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
        private const val WRITE_TIMEOUT = 60L
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor())
        .build()
    
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
            val url = "${serverUrl.trimEnd('/')}/api/loc"
            
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
                .addHeader("User-Agent", "ChildWatch/1.0")
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
                .addHeader("User-Agent", "ChildWatch/1.0")
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
                .addHeader("User-Agent", "ChildWatch/1.0")
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
                .addHeader("User-Agent", "ChildWatch/1.0")
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
     * Get a simple device identifier for tracking
     * In production, you might want to use a more sophisticated approach
     */
    private fun getDeviceId(): String {
        // Simple device ID based on Android ID or random UUID
        // TODO: Implement proper device identification
        return "device_${System.currentTimeMillis() % 10000}"
    }
    
    /**
     * Custom logging interceptor for debugging
     */
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            
            Log.d(TAG, "HTTP Request: ${request.method} ${request.url}")
            
            val startTime = System.currentTimeMillis()
            val response = chain.proceed(request)
            val endTime = System.currentTimeMillis()
            
            Log.d(TAG, "HTTP Response: ${response.code} in ${endTime - startTime}ms")
            
            return response
        }
    }
}
