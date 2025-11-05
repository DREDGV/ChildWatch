package ru.example.childwatch.network

import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API interface for ChildWatch server communication
 */
interface ChildWatchApi {

    /**
     * Get latest location of a child device
     */
    @GET("api/location/latest/{deviceId}")
    suspend fun getChildLocation(@Path("deviceId") deviceId: String): Response<LocationResponse>

    /**
     * Get location history of a child device
     */
    @GET("api/location/history/{deviceId}")
    suspend fun getLocationHistory(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<LocationHistoryResponse>

    /**
     * Get device info
     */
    @GET("api/device/info")
    suspend fun getDeviceInfo(): Response<DeviceInfoResponse>

    /**
     * Get latest device status snapshot
     */
    @GET("api/device/status/{deviceId}")
    suspend fun getDeviceStatus(@Path("deviceId") deviceId: String): Response<DeviceStatusResponse>

    /**
     * Get chat message history
     */
    @GET("api/chat/history/{deviceId}")
    suspend fun getChatHistory(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 100
    ): Response<ChatHistoryResponse>

    /**
     * Mark chat messages as read
     */
    @POST("api/chat/mark-read/{deviceId}")
    suspend fun markChatMessagesAsRead(@Path("deviceId") deviceId: String): Response<GenericResponse>

    /**
     * Get gallery of photos captured on the child device
     */
    @GET("api/media/photos/{deviceId}")
    suspend fun getPhotoGallery(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<PhotoGalleryResponse>
}

/**
 * Data classes for API responses
 */
data class LocationResponse(
    val success: Boolean,
    val deviceId: String,
    val location: LocationData?
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val recordedAt: String
)

data class LocationHistoryResponse(
    val success: Boolean,
    val deviceId: String,
    val count: Int,
    val limit: Int,
    val offset: Int,
    val locations: List<LocationData>
)

data class DeviceInfoResponse(
    val success: Boolean,
    val device: DeviceInfo
)

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val appVersion: String
)

data class DeviceStatusResponse(
    val success: Boolean,
    val deviceId: String,
    val status: DeviceStatus?
)

data class DeviceStatus(
    val batteryLevel: Int?,
    val isCharging: Boolean?,
    val chargingType: String?,
    val temperature: Double?,
    val voltage: Double?,
    val health: String?,
    val manufacturer: String?,
    val model: String?,
    val androidVersion: String?,
    val sdkVersion: Int?,
    val currentAppName: String?,
    val currentAppPackage: String?,
    val timestamp: Long?,
    val raw: Map<String, Any?>?
)

data class ChatHistoryResponse(
    val success: Boolean,
    val deviceId: String,
    val count: Int,
    val messages: List<ChatMessageData>
)

data class ChatMessageData(
    val id: Long,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val isRead: Boolean,
    val createdAt: String
)

data class GenericResponse(
    val success: Boolean,
    val message: String? = null,
    val deviceId: String? = null
)

data class PhotoGalleryResponse(
    val success: Boolean,
    val photoFiles: List<PhotoFileData> = emptyList(),
    val count: Int = 0
)

data class PhotoFileData(
    val id: Long,
    val filename: String,
    val fileSize: Long,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val timestamp: Long,
    val createdAt: String?,
    val downloadUrl: String,
    val thumbnailUrl: String?
)
