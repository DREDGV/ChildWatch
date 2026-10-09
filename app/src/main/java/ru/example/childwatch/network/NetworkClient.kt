package ru.example.childwatch.network

import android.content.Context
import android.util.Log
import ru.example.childwatch.BuildConfig
import ru.example.childwatch.utils.SecureSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import ru.childwatch.shared.onboarding.FamilyBootstrapRequest
import ru.childwatch.shared.onboarding.FamilyInvitationAcceptRequest
import ru.childwatch.shared.onboarding.FamilyInvitationCreateRequest
import ru.childwatch.shared.onboarding.FamilyInvitationResponse
import ru.childwatch.shared.onboarding.FamilyInvitationsResponse
import ru.childwatch.shared.onboarding.FamilyOnboardingResultResponse
import ru.childwatch.shared.onboarding.FamilyOnboardingSimpleResponse
import ru.childwatch.shared.onboarding.FamilyProfileConfirmationRequest
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
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

class NetworkClient(private val context: Context, private val expectedOwnScope: List<String?>? = null,
    private val expectedFamilyReadScope: List<String>? = null) {

    private data class OwnDeviceStatusScope(val identity: List<String?>)

    suspend fun uploadOwnDeviceStatus(deviceInfo: JSONObject, expectedScope: List<String?>): Boolean = withContext(Dispatchers.IO) {
        fun currentScope() = listOf(effectiveContextResolver.resolveServerUrl().trimEnd('/'),
            effectiveContextResolver.resolveFamilyId(), effectiveContextResolver.resolveOwnParentId())
        if (currentScope() != expectedScope || expectedScope.any { it.isNullOrBlank() }) return@withContext false
        try {
            val request = Request.Builder()
                .tag(OwnDeviceStatusScope::class.java, OwnDeviceStatusScope(expectedScope))
                .url(ensureHttpsUrl(expectedScope[0]!!).trimEnd('/') + "/api/device/status")
                .post(JSONObject().put("deviceInfo", deviceInfo).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful || currentScope() != expectedScope) return@withContext false
                val body = response.body?.string()?.let(::JSONObject) ?: return@withContext false
                body.optBoolean("success") && body.optString("deviceId") == expectedScope[2]
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Log.w(TAG, "Own device status upload failed", error); false }
    }
    
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
    private val secureSettings by lazy { SecureSettingsManager(context) }
    private val effectiveContextResolver by lazy { ParentEffectiveContextResolver(context) }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor())
        .addInterceptor(RetryInterceptor())
        .addInterceptor(createLoggingInterceptor())
        .certificatePinner(createCertificatePinner())
        .build()
    private val chatV2Apis = ConcurrentHashMap<String, ChildWatchApi>()

    /**
     * Chat v2 uses the primary authenticated OkHttp client. This is important:
     * its 401 handler refreshes and persists the Bearer token before retrying,
     * unlike the small legacy Retrofit helper kept below for compatibility.
     */
    fun getChatV2Api(serverUrl: String): ChildWatchApi {
        val baseUrl = ensureHttpsUrl(serverUrl.trim()).trimEnd('/') + "/"
        return chatV2Apis.getOrPut(baseUrl) {
            retrofit2.Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
                .create(ChildWatchApi::class.java)
        }
    }
    
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
    fun getAuthToken(): String? = tokenManager.getAuthToken()
    
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
     * Runs an authenticated request and recovers from a rejected credential.
     *
     * The server keeps sessions in memory and persists only token hashes, so a
     * saved refresh token can become unusable (after a server restart, a
     * redeploy, or a lost session). The OkHttp interceptor already tries one
     * refresh; when that also fails the app previously stayed locked out and
     * every family screen silently degraded - the invite button simply switched
     * itself off because the family directory answered 401.
     *
     * Re-registering the same device id is idempotent on the server and returns
     * a working credential pair, so the request is retried exactly once with it.
     */
    private suspend fun <T> withCredentialRecovery(
        call: suspend () -> retrofit2.Response<T>
    ): retrofit2.Response<T> {
        val first = call()
        if (first.code() != 401) return first

        Log.w(TAG, "Request rejected with 401; re-registering this device and retrying once")
        val serverUrl = getConfiguredServerUrl()?.takeIf { it.isNotBlank() } ?: return first
        val restored = runCatching { registerDevice(serverUrl) }.getOrNull()
        if (restored.isNullOrBlank()) {
            Log.e(TAG, "Credential recovery failed: device registration returned no token")
            return first
        }
        // The first response body must be closed before it is discarded.
        runCatching { first.errorBody()?.close() }
        return call()
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

    fun replaceDeviceIdentity(deviceId: String?) {
        if (!deviceId.isNullOrBlank() && tokenManager.getDeviceId() == deviceId.trim()) return
        authToken = null
        tokenManager.setDeviceId(deviceId)
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
            level = HttpLoggingInterceptor.Level.BASIC
        }
    }
    
    /**
     * Authentication interceptor to add auth token to requests
     */
    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            fun checkOwnStatusScope() {
                originalRequest.tag(DeviceStatusRequestScope::class.java)?.let { expected ->
                    if (expected.identity.size != 4 || expected.identity.any { it.isBlank() } ||
                        placeIdentity() != expected.identity) throw IOException("DEVICE_STATUS_CONTEXT_CHANGED")
                }
                expectedFamilyReadScope?.let { expected ->
                    if (expected.size != 4 || expected.any { it.isBlank() } || placeIdentity() != expected)
                        throw IOException("USAGE_CONTEXT_CHANGED")
                }
                originalRequest.tag(PlaceRequestScope::class.java)?.let { place ->
                    if (placeIdentity() != place.identity) throw IOException("PLACE_CONTEXT_CHANGED")
                }
                originalRequest.tag(PickupRequestScope::class.java)?.let { pickup ->
                    val actual = listOf(effectiveContextResolver.resolveServerUrl(), effectiveContextResolver.resolveFamilyId().orEmpty(),
                        effectiveContextResolver.resolveSelfMemberId().orEmpty(), effectiveContextResolver.resolveOwnParentId())
                    if (actual != pickup.identity) throw IOException("PICKUP_CONTEXT_CHANGED")
                }
                val expected = originalRequest.tag(OwnDeviceStatusScope::class.java)?.identity ?: expectedOwnScope ?: return
                val current = listOf(effectiveContextResolver.resolveServerUrl().trimEnd('/'),
                    effectiveContextResolver.resolveFamilyId(), effectiveContextResolver.resolveOwnParentId())
                if (current != expected) throw IOException("Own status context changed")
            }
            checkOwnStatusScope()
            val currentToken = getAuthToken()

            val newRequest = if (currentToken != null) {
                originalRequest.newBuilder()
                    .addHeader("Authorization", "Bearer $currentToken")
                    .build()
            } else {
                originalRequest
            }

            checkOwnStatusScope()
            val response = chain.proceed(newRequest)

            // Handle token refresh on 401 Unauthorized
            if (response.code == 401 && currentToken != null) {
                
                // Try to refresh token
                val serverUrl = extractServerUrl(originalRequest.url.toString())
                if (serverUrl != null) {
                    try {
                        // Use runBlocking to call suspend function
                        val refreshedToken = kotlinx.coroutines.runBlocking {
                            checkOwnStatusScope()
                            tokenManager.refreshToken(serverUrl)
                        }
                        if (refreshedToken != null) {
                            checkOwnStatusScope()
                            response.close()
                            authToken = refreshedToken
                            
                            // Retry request with new token
                            val retryRequest = originalRequest.newBuilder()
                                .addHeader("Authorization", "Bearer $refreshedToken")
                                .build()
                            
                            checkOwnStatusScope()
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

    private fun getConfiguredServerUrl(): String? {
        val url = effectiveContextResolver.resolveServerUrl().trim().ifBlank {
            secureSettings.getServerUrl().trim()
        }
        return url.takeIf { it.isNotBlank() }
    }

    private fun resolveOwnParentId(): String? {
        val parentId = effectiveContextResolver.resolveOwnParentId().trim()
        return parentId.takeIf { it.isNotBlank() }
    }
    
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
            val ownDeviceId = resolveOwnDeviceId()
            return@withContext uploadLocationPayload(
                secureUrl = secureUrl,
                deviceId = ownDeviceId,
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy,
                timestamp = timestamp,
                queueOnFailure = true
            )
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error uploading location", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error uploading location", e)
            return@withContext false
        }
    }
    
    /**
     * Upload parent location to server for "Where are parents?" feature
     */
    suspend fun uploadLocationHistory(server: String, family: String, own: String, points: org.json.JSONArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val data = JSONObject().put("familyId", family).put("ownDeviceId", own).put("kind", "parent").put("points", points)
            val request = Request.Builder().url(ensureHttpsUrl(server).trimEnd('/') + "/api/location/history")
                .post(data.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val result = response.body?.string()?.let { JSONObject(it) }
                response.isSuccessful && result?.optBoolean("success") == true && result?.optInt("accepted") == points.length()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.w(TAG, "Location history remains queued", failure); false }
    }

    suspend fun uploadParentLocation(
        parentId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        timestamp: Long,
        speed: Float? = null,
        bearing: Float? = null,
        batteryLevel: Int? = null,
        speedAccuracyMps: Float? = null,
        measurementElapsedRealtimeNanos: Long? = null,
        bootSessionId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getConfiguredServerUrl()
            
            if (serverUrl.isNullOrEmpty()) {
                Log.w(TAG, "Server URL not configured, skipping parent location upload")
                return@withContext false
            }
            
            val secureUrl = ensureHttpsUrl(serverUrl)
            val url = "${secureUrl.trimEnd('/')}/api/location/parent/$parentId"
            
            val jsonData = JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("timestamp", timestamp)
                put("provider", "fused")
                speed?.let { put("speed", it); put("speedMps", it) }
                speedAccuracyMps?.let { put("speedAccuracyMps", it) }
                measurementElapsedRealtimeNanos?.let { put("measurementElapsedRealtimeNanos", it.toString()) }
                bootSessionId?.let { put("bootSessionId", it) }
                bearing?.let { put("bearing", it) }
                batteryLevel?.let {
                    put("battery", it)
                    put("batteryLevel", it)
                }
            }
            
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Uploading parent location to: $url")
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Parent location uploaded successfully: ${response.code}")
                    return@withContext true
                } else if (response.code == 404) {
                    Log.w(TAG, "Parent location endpoint unavailable, falling back to generic /api/loc")
                    return@withContext uploadLocationPayload(
                        secureUrl = secureUrl,
                        deviceId = parentId,
                        latitude = latitude,
                        longitude = longitude,
                        accuracy = accuracy,
                        timestamp = timestamp,
                        queueOnFailure = false
                    )
                } else {
                    Log.w(TAG, "Failed to upload parent location: ${response.code}")
                    return@withContext false
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error uploading parent location", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error uploading parent location", e)
            return@withContext false
        }
    }
    
    /**
     * Get latest parent location from server
     */
    suspend fun getLatestParentLocation(parentId: String): ParentLocationData? = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getConfiguredServerUrl()
            
            if (serverUrl.isNullOrEmpty()) {
                Log.w(TAG, "Server URL not configured")
                return@withContext null
            }
            
            val secureUrl = ensureHttpsUrl(serverUrl)
            val url = "${secureUrl.trimEnd('/')}/api/location/parent/latest/$parentId"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Fetching latest parent location from: $url")
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        val jsonResponse = JSONObject(responseBody)
                        
                        if (jsonResponse.getBoolean("success")) {
                            val locationObj = jsonResponse.getJSONObject("location")
                            
                            return@withContext ParentLocationData(
                                parentId = locationObj.getString("parentId"),
                                latitude = locationObj.getDouble("latitude"),
                                longitude = locationObj.getDouble("longitude"),
                                accuracy = locationObj.optDouble("accuracy", 0.0).toFloat(),
                                timestamp = locationObj.getLong("timestamp"),
                                battery = locationObj.optInt("battery", 0),
                                speed = locationObj.optDouble("speed", 0.0).toFloat(),
                                bearing = locationObj.optDouble("bearing", 0.0).toFloat(),
                                speedMps = if (locationObj.isNull("speedMps")) null else locationObj.optDouble("speedMps").toFloat(),
                                speedAccuracyMps = if (locationObj.isNull("speedAccuracyMps")) null else locationObj.optDouble("speedAccuracyMps").toFloat(),
                                measurementElapsedRealtimeNanos = locationObj.optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
                                bootSessionId = locationObj.optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
                            )
                        } else {
                            Log.w(TAG, "Server returned success=false")
                            return@withContext null
                        }
                    } else {
                        Log.w(TAG, "Empty response body")
                        return@withContext null
                    }
                } else if (response.code == 404) {
                    Log.d(TAG, "No parent location data via parent endpoint, falling back to generic location endpoint")
                    return@withContext getLatestLocation(parentId)
                } else {
                    Log.w(TAG, "Failed to get parent location: ${response.code}")
                    return@withContext null
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error getting parent location", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error getting parent location", e)
            return@withContext null
        }
    }

    /**
     * Get latest child location from server
     */
    suspend fun getLatestLocation(deviceId: String): ParentLocationData? = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getConfiguredServerUrl()
            
            if (serverUrl.isNullOrEmpty()) {
                Log.w(TAG, "Server URL not configured")
                return@withContext null
            }
            
            val secureUrl = ensureHttpsUrl(serverUrl)
            val url = "${secureUrl.trimEnd('/')}/api/location/latest/$deviceId"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Fetching latest child location from: $url")
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        val jsonResponse = JSONObject(responseBody)
                        
                        if (jsonResponse.getBoolean("success")) {
                            val locationObj = jsonResponse.getJSONObject("location")
                            
                            return@withContext ParentLocationData(
                                parentId = deviceId,
                                latitude = locationObj.getDouble("latitude"),
                                longitude = locationObj.getDouble("longitude"),
                                accuracy = locationObj.optDouble("accuracy", 0.0).toFloat(),
                                timestamp = locationObj.getLong("timestamp"),
                                battery = null,
                                speed = null,
                                bearing = null,
                                speedMps = if (locationObj.isNull("speedMps")) null else locationObj.optDouble("speedMps").toFloat(),
                                speedAccuracyMps = if (locationObj.isNull("speedAccuracyMps")) null else locationObj.optDouble("speedAccuracyMps").toFloat(),
                                measurementElapsedRealtimeNanos = locationObj.optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
                                bootSessionId = locationObj.optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
                            )
                        } else {
                            Log.w(TAG, "Server returned success=false")
                            return@withContext null
                        }
                    } else {
                        Log.w(TAG, "Empty response body")
                        return@withContext null
                    }
                } else if (response.code == 404) {
                    Log.d(TAG, "No child location data available (404)")
                    return@withContext null
                } else {
                    Log.w(TAG, "Failed to get child location: ${response.code}")
                    return@withContext null
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error getting child location", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error getting child location", e)
            return@withContext null
        }
    }

    /** One authenticated snapshot for the family map, including adult phones. */
    suspend fun getFamilyLiveLocations(familyId: String): List<FamilyLiveLocation>? = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getConfiguredServerUrl()?.takeIf(String::isNotBlank)
                ?: return@withContext null
            val encodedFamilyId = java.net.URLEncoder.encode(familyId, "UTF-8")
            val url = "${ensureHttpsUrl(serverUrl).trimEnd('/')}/api/location/family/latest?familyId=$encodedFamilyId"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Family locations request failed: ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val root = JSONObject(body)
                if (!root.optBoolean("success")) return@withContext null
                val locations = root.optJSONArray("locations") ?: return@withContext emptyList()
                buildList {
                    for (index in 0 until locations.length()) {
                        val item = locations.optJSONObject(index) ?: continue
                        val memberId = item.optString("memberId").trim()
                        val deviceId = item.optString("deviceId").trim()
                        if (memberId.isBlank() || deviceId.isBlank()) continue
                        add(
                            FamilyLiveLocation(
                                memberId = memberId,
                                deviceId = deviceId,
                                displayName = item.optString("displayName").trim(),
                                role = item.optString("role").trim(),
                                avatarKey = item.optString("avatarKey").trim().takeIf(String::isNotBlank),
                                latitude = item.getDouble("latitude"),
                                longitude = item.getDouble("longitude"),
                                accuracy = if (item.isNull("accuracy")) null else item.optDouble("accuracy").toFloat(),
                                timestamp = item.getLong("timestamp"),
                                batterySnapshot = ru.example.childwatch.designsystem.BatterySnapshot.fromFamilyPoint(item),
                                speedMps = if (item.isNull("speedMps")) null else item.optDouble("speedMps").toFloat(),
                                speedAccuracyMps = if (item.isNull("speedAccuracyMps")) null else item.optDouble("speedAccuracyMps").toFloat(),
                                measurementElapsedRealtimeNanos = item.optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
                                bootSessionId = item.optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
                            )
                        )
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not load family locations", error)
            null
        }
    }

    /** Bounded server history for the selected person's live map trail. */
    suspend fun getFamilyTrail(familyId: String, memberId: String, expectedDeviceId: String? = null, from: Long? = null, to: Long? = null): List<ParentLocationData>? =
        withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()?.takeIf(String::isNotBlank)
                    ?: return@withContext null
                val family = java.net.URLEncoder.encode(familyId, "UTF-8")
                val member = java.net.URLEncoder.encode(memberId, "UTF-8")
                val url = "${ensureHttpsUrl(serverUrl).trimEnd('/')}/api/location/family/trail/$member?familyId=$family" +
                    if (from != null && to != null) "&from=$from&to=$to&deviceId=${java.net.URLEncoder.encode(expectedDeviceId.orEmpty(), "UTF-8")}" else ""
                val request = Request.Builder().url(url).get()
                    .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Family trail request failed: ${response.code}")
                        return@withContext null
                    }
                    val root = JSONObject(response.body?.string() ?: return@withContext null)
                    if (!root.optBoolean("success")) return@withContext null
                    if (from != null && (root.optLong("from", -1) != from || root.optLong("to", -1) != to)) return@withContext null
                    if (expectedDeviceId != null && root.has("deviceId") && root.optString("deviceId") != expectedDeviceId) return@withContext null
                    val points = root.optJSONArray("points") ?: return@withContext emptyList()
                    buildList {
                        for (index in 0 until points.length()) {
                            val point = points.optJSONObject(index) ?: continue
                            if (point.isNull("timestamp")) continue
                            add(ParentLocationData(
                                parentId = memberId,
                                latitude = point.getDouble("latitude"),
                                longitude = point.getDouble("longitude"),
                                accuracy = if (point.isNull("accuracy")) 0f else point.optDouble("accuracy").toFloat(),
                                timestamp = point.getLong("timestamp"),
                                battery = null, speed = null, bearing = null,
                                speedMps = if (point.isNull("speedMps")) null else point.optDouble("speedMps").toFloat(),
                                speedAccuracyMps = if (point.isNull("speedAccuracyMps")) null else point.optDouble("speedAccuracyMps").toFloat(),
                                measurementElapsedRealtimeNanos = point.optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
                                bootSessionId = point.optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
                            ))
                        }
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not load family trail", error)
                null
            }
        }

    suspend fun getFamilyHistoryPage(familyId: String, memberId: String, deviceId: String, from: Long, to: Long, cursor: String? = null): FamilyHistoryPage =
        withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()?.takeIf(String::isNotBlank)
                    ?: throw java.io.IOException("Invalid history response")
                val family = java.net.URLEncoder.encode(familyId, "UTF-8")
                val member = java.net.URLEncoder.encode(memberId, "UTF-8")
                val device = java.net.URLEncoder.encode(deviceId, "UTF-8")
                val continuation = cursor?.let { "&cursor=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                val url = "${ensureHttpsUrl(serverUrl).trimEnd('/')}/api/location/family/history/$member?familyId=$family&deviceId=$device&from=$from&to=$to$continuation"
                val request = Request.Builder().url(url).get()
                    .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HistoryReadException(response.code)
                    }
                    val root = JSONObject(response.body?.string() ?: throw java.io.IOException("Invalid history response"))
                    if (!root.optBoolean("success")) throw java.io.IOException("Invalid history response")
                    if (from != null && (root.optLong("from", -1) != from || root.optLong("to", -1) != to)) throw java.io.IOException("Invalid history response")
                    if (root.optString("deviceId") != deviceId || root.optString("memberId") != memberId || root.optString("familyId") != familyId) throw java.io.IOException("History context mismatch")
                    val points = root.optJSONArray("points") ?: throw java.io.IOException("Missing history points")
                    val data = buildList {
                        for (index in 0 until points.length()) {
                            val point = points.optJSONObject(index) ?: continue
                            if (point.isNull("timestamp")) continue
                            add(ParentLocationData(
                                parentId = memberId,
                                latitude = point.getDouble("latitude"),
                                longitude = point.getDouble("longitude"),
                                accuracy = if (point.isNull("accuracy")) 0f else point.optDouble("accuracy").toFloat(),
                                timestamp = point.getLong("timestamp"),
                                battery = null, speed = null, bearing = null,
                                speedMps = if (point.isNull("speedMps")) null else point.optDouble("speedMps").toFloat(),
                                speedAccuracyMps = if (point.isNull("speedAccuracyMps")) null else point.optDouble("speedAccuracyMps").toFloat(),
                                measurementElapsedRealtimeNanos = point.optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
                                bootSessionId = point.optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
                            ))
                        }
                    }
                    val more = root.getBoolean("hasMore")
                    val next = if (root.isNull("nextCursor")) null else root.optString("nextCursor").takeIf { it.isNotBlank() }
                    val revision = root.getString("revision")
                    if (revision.isBlank() || (more && (next == null || data.isEmpty()))) throw java.io.IOException("Invalid history continuation")
                    FamilyHistoryPage(data, next, more, revision)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }

    suspend fun getLocationPair(parentId: String, childId: String): LocationPairData? = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getConfiguredServerUrl()

            if (serverUrl.isNullOrEmpty()) {
                Log.w(TAG, "Server URL not configured")
                return@withContext null
            }

            val secureUrl = ensureHttpsUrl(serverUrl)
            val url = "${secureUrl.trimEnd('/')}/api/location/pair?parentId=$parentId&childId=$childId"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()

            Log.d(TAG, "Fetching location pair from: $url")

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code != 404) {
                        Log.w(TAG, "Failed to get location pair: ${response.code}")
                    }
                    return@withContext null
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    Log.w(TAG, "Empty location pair response body")
                    return@withContext null
                }

                val jsonResponse = JSONObject(responseBody)
                if (!jsonResponse.optBoolean("success")) {
                    Log.w(TAG, "Location pair response returned success=false")
                    return@withContext null
                }

                val pairObject = jsonResponse.optJSONObject("pair") ?: return@withContext null
                return@withContext LocationPairData(
                    parent = pairObject.optJSONObject("parent")?.toParentLocationData(
                        fallbackId = parentId,
                        idKey = "parentId"
                    ),
                    child = pairObject.optJSONObject("child")?.toParentLocationData(
                        fallbackId = childId,
                        idKey = "deviceId"
                    ),
                    serverTimestamp = jsonResponse.optLong("serverTimestamp", System.currentTimeMillis())
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error getting location pair", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error getting location pair", e)
            return@withContext null
        }
    }

    /**
     * Get location history for a device
     * @param deviceId Device ID to get history for
     * @param fromTimestamp Start timestamp (milliseconds), optional
     * @param toTimestamp End timestamp (milliseconds), optional
     * @param limit Maximum number of points to return (default 1000)
     * @return List of location points or null on error
     */
    suspend fun getLocationHistory(
        deviceId: String,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null,
        limit: Int = 1000
    ): List<ParentLocationData>? = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getConfiguredServerUrl()
            
            if (serverUrl.isNullOrEmpty()) {
                Log.w(TAG, "Server URL not configured")
                return@withContext null
            }
            
            val secureUrl = ensureHttpsUrl(serverUrl)
            var url = "${secureUrl.trimEnd('/')}/api/location/history/$deviceId?limit=$limit"
            
            // Add optional time range parameters
            if (fromTimestamp != null) {
                url += "&from=$fromTimestamp"
            }
            if (toTimestamp != null) {
                url += "&to=$toTimestamp"
            }
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()
            
            Log.d(TAG, "Fetching location history from: $url")
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        val jsonResponse = JSONObject(responseBody)
                        
                        if (jsonResponse.getBoolean("success")) {
                            val locationsArray = jsonResponse.getJSONArray("locations")
                            val locationList = mutableListOf<ParentLocationData>()
                            
                            for (i in 0 until locationsArray.length()) {
                                val locationObj = locationsArray.getJSONObject(i)
                                locationList.add(
                                    ParentLocationData(
                                        parentId = deviceId,
                                        latitude = locationObj.getDouble("latitude"),
                                        longitude = locationObj.getDouble("longitude"),
                                        accuracy = locationObj.optDouble("accuracy", 0.0).toFloat(),
                                        timestamp = locationObj.getLong("timestamp"),
                                        battery = null,
                                        speed = null,
                                        bearing = null,
                                        speedMps = if (locationObj.isNull("speedMps")) null else locationObj.optDouble("speedMps").toFloat(),
                                        speedAccuracyMps = if (locationObj.isNull("speedAccuracyMps")) null else locationObj.optDouble("speedAccuracyMps").toFloat(),
                                        measurementElapsedRealtimeNanos = locationObj.optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
                                        bootSessionId = locationObj.optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
                                    )
                                )
                            }
                            
                            Log.d(TAG, "Retrieved ${locationList.size} location points")
                            return@withContext locationList
                        } else {
                            Log.w(TAG, "Server returned success=false")
                            return@withContext null
                        }
                    } else {
                        Log.w(TAG, "Empty response body")
                        return@withContext null
                    }
                } else if (response.code == 404) {
                    Log.d(TAG, "No location history available (404)")
                    return@withContext emptyList()
                } else {
                    Log.w(TAG, "Failed to get location history: ${response.code}")
                    return@withContext null
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error getting location history", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error getting location history", e)
            return@withContext null
        }
    }

    suspend fun getParentLocationHistory(
        parentId: String,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null,
        limit: Int = 1000
    ): List<ParentLocationData>? = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getConfiguredServerUrl()

            if (serverUrl.isNullOrEmpty()) {
                Log.w(TAG, "Server URL not configured")
                return@withContext null
            }

            val secureUrl = ensureHttpsUrl(serverUrl)
            var url = "${secureUrl.trimEnd('/')}/api/location/parent/history/$parentId?limit=$limit"

            if (fromTimestamp != null) {
                url += "&from=$fromTimestamp"
            }
            if (toTimestamp != null) {
                url += "&to=$toTimestamp"
            }

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "ChildWatch/" + BuildConfig.VERSION_NAME)
                .build()

            Log.d(TAG, "Fetching parent location history from: $url")

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        val jsonResponse = JSONObject(responseBody)

                        if (jsonResponse.getBoolean("success")) {
                            val locationsArray = jsonResponse.getJSONArray("locations")
                            val locationList = mutableListOf<ParentLocationData>()

                            for (i in 0 until locationsArray.length()) {
                                val locationObj = locationsArray.getJSONObject(i)
                                locationList.add(
                                    ParentLocationData(
                                        parentId = locationObj.optString("parentId", parentId),
                                        latitude = locationObj.getDouble("latitude"),
                                        longitude = locationObj.getDouble("longitude"),
                                        accuracy = locationObj.optDouble("accuracy", 0.0).toFloat(),
                                        timestamp = locationObj.getLong("timestamp"),
                                        battery = locationObj.optInt("battery").takeIf { !locationObj.isNull("battery") },
                                        speed = locationObj.optDouble("speed", Double.NaN)
                                            .takeIf { !it.isNaN() }
                                            ?.toFloat(),
                                        bearing = locationObj.optDouble("bearing", Double.NaN)
                                            .takeIf { !it.isNaN() }
                                            ?.toFloat(),
                                        speedMps = if (locationObj.isNull("speedMps")) null else locationObj.optDouble("speedMps").toFloat(),
                                        speedAccuracyMps = if (locationObj.isNull("speedAccuracyMps")) null else locationObj.optDouble("speedAccuracyMps").toFloat(),
                                        measurementElapsedRealtimeNanos = locationObj.optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
                                        bootSessionId = locationObj.optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
                                    )
                                )
                            }

                            Log.d(TAG, "Retrieved ${locationList.size} parent location points")
                            return@withContext locationList
                        } else {
                            Log.w(TAG, "Server returned success=false")
                            return@withContext null
                        }
                    } else {
                        Log.w(TAG, "Empty response body")
                        return@withContext null
                    }
                } else if (response.code == 404) {
                    Log.d(TAG, "No parent location history via parent endpoint, falling back to generic history endpoint")
                    return@withContext getLocationHistory(
                        deviceId = parentId,
                        fromTimestamp = fromTimestamp,
                        toTimestamp = toTimestamp,
                        limit = limit
                    )
                } else {
                    Log.w(TAG, "Failed to get parent location history: ${response.code}")
                    return@withContext null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error getting parent location history", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error getting parent location history", e)
            return@withContext null
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
            val mimeType = when (audioFile.extension.lowercase()) {
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                else -> "application/octet-stream"
            }
            
            // Create multipart request body
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody(mimeType.toMediaType())
                )
                .addFormDataPart("deviceId", resolveOwnDeviceId())
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
     * This device's own identifier, as the server knows it.
     *
     * Exposed so screens can tell their own entry apart from the other side of a
     * conversation.
     */
    fun ownDeviceId(): String = resolveOwnDeviceId()

    private fun resolveOwnDeviceId(): String {
        val prefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        val resolvedFromSession = effectiveContextResolver.resolveOwnParentId().trim()
        if (resolvedFromSession.isNotBlank()) {
            return resolvedFromSession
        }
        val candidates = listOf(
            secureSettings.getDeviceId(),
            prefs.getString("device_id", null)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() } ?: getDeviceId()
    }

    private fun uploadLocationPayload(
        secureUrl: String,
        deviceId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        timestamp: Long,
        queueOnFailure: Boolean
    ): Boolean {
        val url = "${secureUrl.trimEnd('/')}/api/loc"
        val jsonData = JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy)
            put("timestamp", timestamp)
            put("deviceId", deviceId)
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
        Log.d(TAG, "Full JSON payload: ${jsonData}")

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Log.d(TAG, "Location uploaded successfully: ${response.code}")
                return true
            }

            Log.e(TAG, "Failed to upload location: ${response.code} ${response.message}")
            Log.e(TAG, "Response body: ${response.body?.string()}")

            if (queueOnFailure) {
                addToOfflineQueue(
                    url,
                    requestBody,
                    mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to "ChildWatch/" + BuildConfig.VERSION_NAME
                    )
                )
            }

            return false
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
     * Ensure URL uses HTTPS (but allow HTTP for local/test servers)
     */
    private fun ensureHttpsUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url
            // Allow HTTP for localhost, 127.0.0.1, and local IP addresses (192.168.x.x, 10.x.x.x, etc.)
            url.startsWith("http://localhost") -> url
            url.startsWith("http://127.0.0.1") -> url
            url.matches(Regex("http://192\\.168\\.\\d+\\.\\d+(:\\d+)?.*")) -> url
            url.matches(Regex("http://10\\.\\d+\\.\\d+\\.\\d+(:\\d+)?.*")) -> url
            url.matches(Regex("http://31\\.\\d+\\.\\d+\\.\\d+(:\\d+)?.*")) -> url // Allow 31.x.x.x (your server)
            url.startsWith("http://") -> url.replace("http://", "https://") // Force HTTPS for public servers
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
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get child location")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

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
     * Get child location history from server using Retrofit
     */
    suspend fun getLocationHistoryRetrofit(
        childDeviceId: String,
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int = 100
    ): retrofit2.Response<LocationHistoryResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get location history")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting location history from server: $serverUrl")
                Log.d(TAG, "Child device ID: $childDeviceId, limit: $limit")
                // Note: API uses limit and offset, not startTime/endTime

                api.getLocationHistory(childDeviceId, limit, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting location history", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /**
     * Get child device status from server using Retrofit
     */
    suspend fun getChildDeviceStatus(childDeviceId: String,
        expectedScope: List<String>? = null): retrofit2.Response<DeviceStatusResponse> {
        return withContext(Dispatchers.IO) {
            try {
                if (expectedScope != null && (expectedScope.size != 4 || expectedScope.any { it.isBlank() } ||
                        placeIdentity() != expectedScope)) throw IOException("DEVICE_STATUS_CONTEXT_CHANGED")
                val serverUrl = expectedScope?.getOrNull(0) ?: getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get device status")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting child device status from server: $serverUrl")
                Log.d(TAG, "Child device ID: $childDeviceId")

                suspend fun read() = api.getDeviceStatus(childDeviceId,
                    purpose = if (expectedFamilyReadScope != null) "app_usage" else null,
                    familyId = (expectedScope ?: expectedFamilyReadScope)?.getOrNull(1),
                    actorMemberId = (expectedScope ?: expectedFamilyReadScope)?.getOrNull(2),
                    expectedScope = expectedScope?.let(::DeviceStatusRequestScope))
                // Scoped read uses existing credentials only; never registers a changed identity.
                val response = if (expectedScope != null) read() else withCredentialRecovery { read() }
                if (expectedScope != null && placeIdentity() != expectedScope) throw IOException("DEVICE_STATUS_CONTEXT_CHANGED")
                response
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error getting child device status", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /**
     * Get child device status history from server using Retrofit.
     */
    suspend fun getChildDeviceStatusHistory(
        childDeviceId: String,
        limit: Int = 60
    ): retrofit2.Response<DeviceStatusHistoryResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get device status history")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting child device status history from server: $serverUrl")
                Log.d(TAG, "Child device ID: $childDeviceId, limit=$limit")

                withCredentialRecovery { api.getDeviceStatusHistory(childDeviceId, limit,
                    purpose = if (expectedFamilyReadScope != null) "app_usage" else null,
                    familyId = expectedFamilyReadScope?.getOrNull(1), actorMemberId = expectedFamilyReadScope?.getOrNull(2)) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error getting child device status history", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /** A missing HTTP route is legacy support; transport failures must never become 404. */
    suspend fun getAttentionSignalStatus(requestId: String, targetDeviceId: String): JSONObject? = withContext(Dispatchers.IO) {
        val scope = expectedFamilyReadScope ?: return@withContext null
        if (scope.size != 4 || scope.any(String::isBlank)) return@withContext null
        if (placeIdentity() != scope) throw ru.childwatch.shared.attention.android.AttentionSignalStatusAccessDenied()
        val api = createRetrofitClient(scope[0]).create(ChildWatchApi::class.java)
        val response = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            api.getAttentionSignalStatus(requestId, targetDeviceId, scope[1], scope[2])
        } ?: return@withContext null
        if (placeIdentity() != scope || response.code() in setOf(401, 403))
            throw ru.childwatch.shared.attention.android.AttentionSignalStatusAccessDenied()
        if (!response.isSuccessful) return@withContext null
        response.body()?.let { JSONObject(it) }?.takeIf {
            it.optBoolean("success") && it.optString("requestId") == requestId &&
                it.optString("targetDeviceId") == targetDeviceId && it.optString("familyId") == scope[1] &&
                it.optString("actorMemberId") == scope[2]
        }
    }

    suspend fun getChildUsageDays(childDeviceId: String, limit: Int = 90): retrofit2.Response<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                val scope = expectedFamilyReadScope?.map(String::trim)
                if (scope == null || scope.size != 4 || scope.any(String::isBlank) || placeIdentity() != scope)
                    return@withContext retrofit2.Response.error(403, okhttp3.ResponseBody.create(null, "USAGE_CONTEXT_CHANGED"))
                val api = createRetrofitClient(scope[0]).create(ChildWatchApi::class.java)
                withCredentialRecovery { api.getDeviceUsageDays(childDeviceId, limit.coerceIn(1, 90), scope[1], scope[2]) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w(TAG, "Daily usage archive unavailable", error)
                retrofit2.Response.error(503, okhttp3.ResponseBody.create(null, "USAGE_ARCHIVE_UNAVAILABLE"))
            }
        }

    suspend fun getLinkedChildren(
        parentDeviceId: String
    ): retrofit2.Response<LinkedChildrenResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get linked children")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)
                api.getLinkedChildren(parentDeviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting linked children", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun getFamilies(): retrofit2.Response<FamiliesResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get families")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                withCredentialRecovery {
                    createRetrofitClient(serverUrl)
                        .create(ChildWatchApi::class.java)
                        .getFamilies()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting families", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun getAuthenticatedIdentity(): retrofit2.Response<AuthenticatedIdentityResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                createRetrofitClient(serverUrl)
                    .create(ChildWatchApi::class.java)
                    .getAuthenticatedIdentity()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting authenticated family identity", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun ensureOnboardingAuthentication(): Boolean = withContext(Dispatchers.IO) {
        val serverUrl = getConfiguredServerUrl()?.takeIf { it.isNotBlank() }
            ?: "http://31.28.27.96:3000".also(secureSettings::setServerUrl)
        if (!getAuthToken().isNullOrBlank()) return@withContext true
        registerDevice(serverUrl) != null
    }

    suspend fun bootstrapFamily(
        request: FamilyBootstrapRequest
    ): retrofit2.Response<FamilyOnboardingResultResponse> = withContext(Dispatchers.IO) {
        onboardingCall { api -> api.bootstrapFamily(request) }
    }

    suspend fun confirmOwnFamilyProfile(
        familyId: String,
        request: FamilyProfileConfirmationRequest
    ): retrofit2.Response<FamilyOnboardingResultResponse> = withContext(Dispatchers.IO) {
        onboardingCall { api -> api.confirmOwnFamilyProfile(familyId.trim(), request) }
    }

    suspend fun createFamilyInvitation(
        request: FamilyInvitationCreateRequest
    ): retrofit2.Response<FamilyInvitationResponse> = withContext(Dispatchers.IO) {
        onboardingCall { api -> api.createFamilyInvitation(request) }
    }

    suspend fun getActiveFamilyInvitations(
        familyId: String
    ): retrofit2.Response<FamilyInvitationsResponse> = withContext(Dispatchers.IO) {
        onboardingCall { api -> api.getActiveFamilyInvitations(familyId.trim()) }
    }

    suspend fun revokeFamilyInvitation(
        familyId: String,
        invitationId: String
    ): retrofit2.Response<FamilyOnboardingSimpleResponse> = withContext(Dispatchers.IO) {
        onboardingCall {
            api -> api.revokeFamilyInvitation(familyId.trim(), invitationId.trim())
        }
    }

    suspend fun getFamilyLegacyMigrationCandidates(
        familyId: String
    ): retrofit2.Response<ru.childwatch.shared.onboarding.FamilyLegacyMigrationCandidatesResponse> =
        withContext(Dispatchers.IO) {
            onboardingCall { api ->
                api.getFamilyLegacyMigrationCandidates(familyId.trim())
            }
        }

    suspend fun confirmFamilyLegacyProfile(
        familyId: String,
        memberId: String,
        request: ru.childwatch.shared.onboarding.FamilyLegacyProfileConfirmRequest
    ): retrofit2.Response<ru.childwatch.shared.onboarding.FamilyLegacyProfileConfirmResponse> =
        withContext(Dispatchers.IO) {
            onboardingCall { api ->
                api.confirmFamilyLegacyProfile(
                    familyId.trim(),
                    memberId.trim(),
                    request
                )
            }
        }

    suspend fun transferFamilyDevice(
        familyId: String,
        deviceId: String,
        targetMemberId: String
    ): retrofit2.Response<FamilyOnboardingResultResponse> =
        withContext(Dispatchers.IO) {
            onboardingCall { api ->
                api.transferFamilyDevice(
                    familyId.trim(),
                    deviceId.trim(),
                    ru.childwatch.shared.onboarding.FamilyDeviceTransferRequest(
                        targetMemberId = targetMemberId.trim(),
                        confirmed = true
                    )
                )
            }
        }

    suspend fun previewFamilyInvitation(
        token: String
    ): retrofit2.Response<FamilyInvitationResponse> = withContext(Dispatchers.IO) {
        onboardingCall { api -> api.previewFamilyInvitation(token.trim()) }
    }

    suspend fun acceptFamilyInvitation(
        token: String,
        deviceName: String? = android.os.Build.MODEL
    ): retrofit2.Response<FamilyOnboardingResultResponse> = withContext(Dispatchers.IO) {
        onboardingCall { api ->
            api.acceptFamilyInvitation(
                token.trim(),
                FamilyInvitationAcceptRequest(
                    deviceName = deviceName?.trim(),
                    clientKind = "PARENT_MONITOR"
                )
            )
        }
    }

    private suspend fun <T> onboardingCall(
        call: suspend (ChildWatchApi) -> retrofit2.Response<T>
    ): retrofit2.Response<T> {
        return try {
            if (!ensureOnboardingAuthentication()) {
                retrofit2.Response.error(
                    401,
                    okhttp3.ResponseBody.create(null, "Device registration failed")
                )
            } else {
                withCredentialRecovery {
                    val serverUrl = checkNotNull(getConfiguredServerUrl())
                    call(createRetrofitClient(serverUrl).create(ChildWatchApi::class.java))
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Family onboarding request failed", error)
            retrofit2.Response.error(
                503,
                okhttp3.ResponseBody.create(null, "Error: ${error.message}")
            )
        }
    }

    suspend fun getFamilyMembers(
        familyId: String
    ): retrofit2.Response<FamilyMembersResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                withCredentialRecovery {
                    createRetrofitClient(serverUrl)
                        .create(ChildWatchApi::class.java)
                        .getFamilyMembers(familyId.trim())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting family members", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun updateFamilyMemberProfile(
        familyId: String,
        memberId: String,
        displayName: String? = null,
        avatarKey: String? = null
    ): retrofit2.Response<UpdateFamilyMemberProfileResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                createRetrofitClient(serverUrl)
                    .create(ChildWatchApi::class.java)
                    .updateFamilyMemberProfile(
                        familyId = familyId.trim(),
                        memberId = memberId.trim(),
                        request = UpdateFamilyMemberProfileRequest(
                            displayName = displayName?.trim(),
                            avatarKey = avatarKey?.trim()
                        )
                    )
            } catch (e: Exception) {
                Log.e(TAG, "Error updating family member profile", e)
                retrofit2.Response.error(
                    404,
                    okhttp3.ResponseBody.create(null, "Error: ${e.message}")
                )
            }
        }
    }

    /**
     * Sends a picture the person chose and answers with the value to store.
     *
     * The bytes are read from [openBytes], which keeps the reading out of this
     * class and lets the caller refuse a file that is too large before anything
     * is held in memory. Failures — including the server's own refusal of a file
     * that is not really an image — are reported as a failed response rather than
     * an exception, so a screen can show a message instead of crashing.
     */
    suspend fun uploadAvatar(
        fileName: String,
        contentType: String,
        openBytes: () -> ByteArray
    ): retrofit2.Response<AvatarUploadResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val mediaType = contentType.toMediaTypeOrNull()
                    ?: "application/octet-stream".toMediaType()
                val part = MultipartBody.Part.createFormData(
                    name = "avatar",
                    filename = fileName,
                    body = openBytes().toRequestBody(mediaType)
                )

                withCredentialRecovery {
                    createRetrofitClient(serverUrl)
                        .create(ChildWatchApi::class.java)
                        .uploadAvatar(part)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading a profile picture", e)
                retrofit2.Response.error(
                    503,
                    okhttp3.ResponseBody.create(null, "Error: ${e.message}")
                )
            }
        }
    }

    /**
     * The server address this client is actually configured to talk to.
     *
     * Exposed so a caller that needs an address can use the same one the rest of the
     * application uses, instead of reading the setting again: two readers of one
     * setting are two chances to disagree about which server the family is on.
     */
    /** Authenticated family places; non-success is preserved for recovery UI. */
    private data class PlaceRequestScope(val identity: List<String>)
    private fun placeIdentity() = listOf(effectiveContextResolver.resolveServerUrl().trim(),
        effectiveContextResolver.resolveFamilyId().orEmpty().trim(),
        effectiveContextResolver.resolveSelfMemberId().orEmpty().trim(), effectiveContextResolver.resolveOwnParentId().trim())

    suspend fun familyPlacesRequest(familyId: String, method: String = "GET", suffix: String = "",
        body: JSONObject? = null, after: Long? = null, targetMemberId: String? = null,
        expectedScope: String? = null, before: Long? = null, snapshot: Long? = null): JSONObject = withContext(Dispatchers.IO) {
        val expected = expectedScope?.let { encoded -> org.json.JSONArray(encoded).let { array ->
            require(array.length() == 4) { "PLACE_CONTEXT_MISSING" }
            (0 until 4).map { array.getString(it) }
        } } ?: placeIdentity()
        if (expected.any { it.isBlank() } || expected[1] != familyId || placeIdentity() != expected)
            throw IllegalStateException("PLACE_CONTEXT_CHANGED")
        val configured = getConfiguredServerUrl()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Server is not configured")
        val url = (ensureHttpsUrl(configured).trimEnd('/') + "/api/family-places" + suffix).toHttpUrl().newBuilder()
            .addQueryParameter("familyId", familyId)
            .addQueryParameter("actorMemberId", expected[2])
        after?.let { url.addQueryParameter("after", it.toString()) }
        before?.let { url.addQueryParameter("before", it.toString()) }
        snapshot?.let { url.addQueryParameter("snapshot", it.toString()) }
        targetMemberId?.let { url.addQueryParameter("targetMemberId", it) }
        val request = Request.Builder().url(url.build()).tag(PlaceRequestScope::class.java, PlaceRequestScope(expected))
        when (method) {
            "POST" -> request.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            "PATCH" -> request.patch((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            "DELETE" -> request.delete()
            else -> request.get()
        }
        client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build().newCall(request.build()).execute().use { response ->
            if (placeIdentity() != expected) throw IllegalStateException("PLACE_CONTEXT_CHANGED")
            val json = response.body?.string()?.let(::JSONObject) ?: JSONObject()
            if (!response.isSuccessful || !json.optBoolean("success"))
                throw IllegalStateException(if (response.code == 403) "PLACE_PERMISSION_DENIED" else "PLACE_REQUEST_FAILED")
            if ((json.has("familyId") && json.getString("familyId") != familyId) ||
                (json.has("ownerMemberId") && json.getString("ownerMemberId") != expected[2]))
                throw IllegalStateException("PLACE_CONTEXT_CHANGED")
            json
        }
    }

    fun resolveConfiguredServerUrl(): String? = getConfiguredServerUrl()

    /** Pickup writes are never added to the generic offline upload queue. */
    private data class PickupRequestScope(val identity: List<String>)
    suspend fun pickupRequest(familyId: String, method: String = "GET", suffix: String = "",
        body: JSONObject? = null, expectedScope: String): JSONObject = withContext(Dispatchers.IO) {
        val expected = org.json.JSONArray(expectedScope).let { listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) }
        val actual = listOf(effectiveContextResolver.resolveServerUrl(), effectiveContextResolver.resolveFamilyId().orEmpty(),
            effectiveContextResolver.resolveSelfMemberId().orEmpty(), effectiveContextResolver.resolveOwnParentId())
        if (actual != expected || familyId != expected[1]) throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
        val configured = resolveConfiguredServerUrl()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("PICKUP_CONTEXT_MISSING")
        val base = ensureHttpsUrl(configured).trimEnd('/')
        val parsed = (base + "/api/pickups" + suffix).toHttpUrl()
            .newBuilder().addQueryParameter("familyId", familyId).addQueryParameter("actorMemberId", expected[2]).build()
        val builder = Request.Builder().url(parsed).tag(PickupRequestScope::class.java, PickupRequestScope(expected))
        if (method == "POST") builder.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        else builder.get()
        client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            if (!response.isSuccessful || !json.optBoolean("success"))
                throw IllegalStateException("PICKUP_HTTP_${response.code}:" + json.optString("code", "PICKUP_UNAVAILABLE"))
            val rows = json.optJSONArray("requests")
            fun validatePickup(row: JSONObject) {
                val validStatus = row.optString("status") in listOf("REQUESTED", "ACCEPTED", "EN_ROUTE", "ARRIVED", "HANDOFF", "COMPLETED", "CANCELLED", "EXPIRED")
                val latitude = row.optDouble("latitude", Double.NaN)
                val longitude = row.optDouble("longitude", Double.NaN)
                if (row.optString("familyId") != familyId || row.optString("id").isBlank() ||
                    row.optString("childMemberId").isBlank() || row.optLong("version") < 1L || !validStatus ||
                    !latitude.isFinite() || kotlin.math.abs(latitude) > 90 ||
                    !longitude.isFinite() || kotlin.math.abs(longitude) > 180 ||
                    row.optJSONArray("ownActionIds") == null)
                    throw IllegalStateException("PICKUP_INVALID_RESPONSE")
            }
            if (method == "GET" && (rows == null || json.optString("familyId") != familyId))
                throw IllegalStateException("PICKUP_INVALID_RESPONSE")
            if (method == "GET" && (json.optJSONObject("actor")?.optString("memberId") != expected[2] ||
                json.getJSONObject("actor").optString("role") !in listOf("CHILD", "PARENT", "GUARDIAN")))
                throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
            if (json.has("familyId") && json.getString("familyId") != familyId) throw IllegalStateException("PICKUP_CONTEXT_CHANGED")
            if (rows != null) for (i in 0 until rows.length()) {
                validatePickup(rows.getJSONObject(i))
            }
            if (method == "POST") {
                val saved = json.optJSONObject("request") ?: throw IllegalStateException("PICKUP_INVALID_RESPONSE")
                validatePickup(saved)
                val expectedId = if (suffix.isBlank()) body?.optString("requestId").orEmpty() else suffix.split('/').getOrNull(1).orEmpty()
                val operationId = body?.optString("actionId")?.takeIf { it.isNotBlank() } ?: body?.optString("requestId").orEmpty()
                val receipts = saved.getJSONArray("ownActionIds")
                if (saved.getString("id") != expectedId || operationId.isBlank() ||
                    (0 until receipts.length()).none { receipts.optString(it) == operationId })
                    throw IllegalStateException("PICKUP_INVALID_RESPONSE")
            }
            json
        }
    }

    /**
     * The release manifest: what the server has published for this application.
     *
     * [serverBase] is the address the caller already resolved — the same one the rest
     * of the application talks to. It is passed in rather than read again here,
     * because a second reader of the server setting is a second chance to disagree
     * about which server the family is on.
     *
     * Nothing is interpreted: the body is handed back as it arrived and the update
     * package decides what it means, which keeps version comparison in one readable
     * place. Failure is answered rather than thrown, because a check that cannot be
     * made must stay invisible to the person.
     */
    suspend fun fetchUpdateManifest(serverBase: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (serverBase.isBlank()) {
                return@withContext Result.failure<String>(
                    IOException("Server URL is not configured")
                )
            }

            val url = ensureHttpsUrl(serverBase.trim()).trimEnd('/') + "/updates/manifest"
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure<String>(
                        IOException("Manifest request answered ${response.code}")
                    )
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@withContext Result.failure<String>(
                        IOException("Manifest response was empty")
                    )
                }
                Result.success(body)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the release manifest", e)
            Result.failure<String>(e)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the release manifest", e)
            Result.failure<String>(e)
        }
    }

    /**
     * Removes a picture this device uploaded earlier.
     *
     * Only a path the server stored itself is accepted, so a stale value can
     * never delete anything else. Callers treat a failure as harmless: tidying
     * the file matters less than the profile change that replaced it.
     */
    suspend fun deleteUploadedAvatar(
        path: String
    ): retrofit2.Response<AvatarDeleteResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                withCredentialRecovery {
                    createRetrofitClient(serverUrl)
                        .create(ChildWatchApi::class.java)
                        .deleteUploadedAvatar(path.trim())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing an uploaded profile picture", e)
                retrofit2.Response.error(
                    503,
                    okhttp3.ResponseBody.create(null, "Error: ${e.message}")
                )
            }
        }
    }

    suspend fun getFamilyDevices(
        familyId: String
    ): retrofit2.Response<FamilyDevicesResponse> {        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                withCredentialRecovery {
                    createRetrofitClient(serverUrl)
                        .create(ChildWatchApi::class.java)
                        .getFamilyDevices(familyId.trim())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting family devices", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun getLinkedParents(
        childDeviceId: String
    ): retrofit2.Response<LinkedParentsResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get linked parents")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)
                api.getLinkedParents(childDeviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting linked parents", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun getFamilyPresence(
        childDeviceId: String
    ): retrofit2.Response<FamilyPresenceResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get family presence")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)
                api.getFamilyPresence(childDeviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting family presence", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun linkParentChild(
        parentDeviceId: String,
        childDeviceId: String,
        displayName: String? = null,
        parentDisplayName: String? = null,
        childDisplayName: String? = null,
        parentMarkerIconId: Int? = null,
        childMarkerIconId: Int? = null
    ): retrofit2.Response<GenericResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot link parent-child")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)
                api.linkParentChild(
                    ParentChildLinkRequest(
                        parentDeviceId = parentDeviceId,
                        childDeviceId = childDeviceId,
                        displayName = displayName,
                        parentDisplayName = parentDisplayName,
                        childDisplayName = childDisplayName,
                        parentMarkerIconId = parentMarkerIconId,
                        childMarkerIconId = childMarkerIconId
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error linking parent-child", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun unlinkParentChild(
        parentDeviceId: String,
        childDeviceId: String
    ): retrofit2.Response<GenericResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot unlink parent-child")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)
                api.unlinkParentChild(
                    ParentChildUnlinkRequest(
                        parentDeviceId = parentDeviceId,
                        childDeviceId = childDeviceId
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error unlinking parent-child", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /**
     * Get chat history for a device using authenticated Retrofit client.
     */
    suspend fun getChatHistory(
        deviceId: String,
        limit: Int = 100
    ): retrofit2.Response<ChatHistoryResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get chat history")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting chat history from server: $serverUrl")
                Log.d(TAG, "Device ID: $deviceId, limit: $limit")

                api.getChatHistory(deviceId, limit)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting chat history", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /**
     * Get captured photos for the child device
     */
    suspend fun getPhotoReadiness(target: String): JSONObject? = withContext(Dispatchers.IO) {
        val server = getConfiguredServerUrl()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val url = ensureHttpsUrl(server).trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/photo/readiness").addPathSegment(target).build()
        client.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.code == 401 || response.code == 403)
                    return@withContext JSONObject().put("permissionDenied", true)
                if (!response.isSuccessful) return@withContext null
                response.body?.string()?.let { JSONObject(it).takeIf { json -> json.optBoolean("success") } }
            }
    }

    suspend fun getRemotePhotoResult(target: String, requestId: String): JSONObject? = withContext(Dispatchers.IO) {
        val server = getConfiguredServerUrl()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val url = ensureHttpsUrl(server).trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/media/photo-result").addPathSegment(target).addPathSegment(requestId).build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (response.code == 403) return@withContext JSONObject().put("status", "error").put("error", "PHOTO_PERMISSION_DENIED")
            if (!response.isSuccessful) return@withContext null
            response.body?.string()?.let { JSONObject(it) }
        }
    }

    suspend fun getRemotePhotos(
        childDeviceId: String,
        limit: Int = 50,
        offset: Int = 0
    ): retrofit2.Response<PhotoGalleryResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get remote photos")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting remote photos from server: $serverUrl")
                Log.d(TAG, "Child device ID: $childDeviceId, limit=$limit, offset=$offset")

                api.getPhotoGallery(childDeviceId, limit, offset)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting remote photos", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    suspend fun downloadRemoteMediaBytes(absoluteUrl: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(absoluteUrl)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(
                            TAG,
                            "Failed to download remote media: ${response.code} ${response.message}"
                        )
                        return@withContext null
                    }
                    response.body?.bytes()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading remote media", e)
                null
            }
        }
    }

    /**
     * Get archived audio recordings for the current parent device.
     */
    suspend fun getRemoteAudio(
        deviceId: String,
        limit: Int = 50,
        offset: Int = 0
    ): retrofit2.Response<AudioGalleryResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = getConfiguredServerUrl()
                if (serverUrl.isNullOrBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot get remote audio")
                    return@withContext retrofit2.Response.error(
                        400,
                        okhttp3.ResponseBody.create(null, "Server URL not configured")
                    )
                }

                val retrofit = createRetrofitClient(serverUrl)
                val api = retrofit.create(ChildWatchApi::class.java)

                Log.d(TAG, "Getting remote audio from server: $serverUrl")
                Log.d(TAG, "Device ID: $deviceId, limit=$limit, offset=$offset")

                api.getAudioGallery(deviceId, limit, offset)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting remote audio", e)
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, "Error: ${e.message}"))
            }
        }
    }

    /**
     * Create Retrofit client with authentication
     */
    private fun createRetrofitClient(baseUrl: String): retrofit2.Retrofit {
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
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
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
    }

    // ========== Audio Streaming Methods ==========

    /**
     * Start audio streaming from child device
     */
    suspend fun startAudioStreaming(
        serverUrl: String,
        deviceId: String,
        recordingMode: Boolean = false,
        timeoutMinutes: Int = 30,
        sampleRate: Int = 24_000
    ): StreamingCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/api/streaming/start"
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("recording", recordingMode)
                    put("timeoutMinutes", timeoutMinutes)
                    put("sampleRate", sampleRate)
                    resolveOwnParentId()?.let { put("parentId", it) }
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val parsed = parseStreamingCommandResult(
                        deviceId = deviceId,
                        responseCode = response.code,
                        responseBody = body
                    )
                    if (parsed.success) {
                        Log.d(
                            TAG,
                            "Audio streaming started for device $deviceId (recording: $recordingMode)"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Audio streaming start rejected for $deviceId: code=${parsed.code} owner=${parsed.ownerDisplayName ?: parsed.ownerParentId}"
                        )
                    }
                    parsed
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error starting audio streaming", e)
                StreamingCommandResult(
                    success = false,
                    deviceId = deviceId,
                    code = "START_STREAM_EXCEPTION",
                    message = e.message
                )
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
                    resolveOwnParentId()?.let { put("parentId", it) }
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
                    resolveOwnParentId()?.let { put("parentId", it) }
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
                    resolveOwnParentId()?.let { put("parentId", it) }
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
                            active = json.optBoolean("active", json.optBoolean("streaming", false)),
                            streaming = json.optBoolean("streaming", json.optBoolean("active", false)),
                            recording = json.optBoolean("recording", false),
                            startedAt = json.optLong("startedAt", json.optLong("startTime", 0L)),
                            ownerParentId = json.optString("ownerParentId", json.optString("parentId", "")),
                            ownerDisplayName = json.optString("ownerDisplayName", ""),
                            durationMs = json.optLong("durationMs", json.optLong("duration", 0L)),
                            ownerStale = json.optBoolean("ownerStale", false)
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
    data class StreamingCommandResult(
        val success: Boolean,
        val deviceId: String,
        val busy: Boolean = false,
        val code: String? = null,
        val message: String? = null,
        val ownerParentId: String? = null,
        val ownerDisplayName: String? = null,
        val startedAt: Long = 0L,
        val durationMs: Long = 0L,
        val canRequestTakeover: Boolean = false
    )

    data class StreamingStatus(
        val active: Boolean,
        val streaming: Boolean,
        val recording: Boolean,
        val startedAt: Long,
        val ownerParentId: String = "",
        val ownerDisplayName: String = "",
        val durationMs: Long = 0L,
        val ownerStale: Boolean = false
    )

    private fun parseStreamingCommandResult(
        deviceId: String,
        responseCode: Int,
        responseBody: String?
    ): StreamingCommandResult {
        val fallbackError = StreamingCommandResult(
            success = responseCode in 200..299,
            deviceId = deviceId,
            code = if (responseCode in 200..299) null else "HTTP_$responseCode",
            message = responseBody
        )

        if (responseBody.isNullOrBlank()) {
            return fallbackError
        }

        return runCatching {
            val json = JSONObject(responseBody)
            StreamingCommandResult(
                success = json.optBoolean("success", responseCode in 200..299),
                deviceId = json.optString("deviceId", deviceId),
                busy = json.optBoolean("busy", false),
                code = json.optString("code").takeIf { it.isNotBlank() },
                message = json.optString("error").takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() },
                ownerParentId = json.optString("ownerParentId").takeIf { it.isNotBlank() },
                ownerDisplayName = json.optString("ownerDisplayName").takeIf { it.isNotBlank() },
                startedAt = json.optLong("startedAt", 0L),
                durationMs = json.optLong("durationMs", 0L),
                canRequestTakeover = json.optBoolean("canRequestTakeover", false)
            )
        }.getOrElse { fallbackError }
    }
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

/**
 * Data class for parent location from server
 */
data class ParentLocationData(
    val parentId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val battery: Int?,
    val speed: Float?,
    val bearing: Float?,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val measurementElapsedRealtimeNanos: Long? = null,
    val bootSessionId: String? = null
)

data class FamilyLiveLocation(
    val memberId: String,
    val deviceId: String,
    val displayName: String,
    val role: String,
    val avatarKey: String?,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val measurementElapsedRealtimeNanos: Long? = null,
    val bootSessionId: String? = null,
    val batterySnapshot: ru.example.childwatch.designsystem.BatterySnapshot? = null
)

data class LocationPairData(
    val parent: ParentLocationData?,
    val child: ParentLocationData?,
    val serverTimestamp: Long
)

private fun JSONObject.toParentLocationData(fallbackId: String, idKey: String): ParentLocationData {
    return ParentLocationData(
        parentId = optString(idKey, fallbackId).ifBlank { fallbackId },
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        accuracy = optDouble("accuracy", 0.0).toFloat(),
        timestamp = getLong("timestamp"),
        battery = if (has("battery") && !isNull("battery")) optInt("battery") else null,
        speed = if (has("speed") && !isNull("speed")) optDouble("speed", 0.0).toFloat() else null,
        bearing = if (has("bearing") && !isNull("bearing")) optDouble("bearing", 0.0).toFloat() else null,
        speedMps = if (isNull("speedMps")) null else optDouble("speedMps").toFloat(),
        speedAccuracyMps = if (isNull("speedAccuracyMps")) null else optDouble("speedAccuracyMps").toFloat(),
        measurementElapsedRealtimeNanos = optString("measurementElapsedRealtimeNanos").toLongOrNull()?.takeIf { it > 0 },
        bootSessionId = optString("bootSessionId").takeIf { it.isNotBlank() && it != "null" }
    )
}


data class FamilyHistoryPage(val points: List<ParentLocationData>, val nextCursor: String?, val hasMore: Boolean, val revision: String)
class HistoryReadException(val status: Int) : java.io.IOException("History response $status")
data class DeviceStatusRequestScope(val identity: List<String>)
