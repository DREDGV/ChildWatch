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
     * Get device status history snapshots for app/activity timeline.
     */
    @GET("api/device/status/history/{deviceId}")
    suspend fun getDeviceStatusHistory(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 60
    ): Response<DeviceStatusHistoryResponse>

    @GET("api/relationships/children/{parentDeviceId}")
    suspend fun getLinkedChildren(
        @Path("parentDeviceId") parentDeviceId: String
    ): Response<LinkedChildrenResponse>

    @GET("api/relationships/parents/{childDeviceId}")
    suspend fun getLinkedParents(
        @Path("childDeviceId") childDeviceId: String
    ): Response<LinkedParentsResponse>

    @POST("api/relationships/link")
    suspend fun linkParentChild(
        @Body request: ParentChildLinkRequest
    ): Response<GenericResponse>

    @POST("api/relationships/unlink")
    suspend fun unlinkParentChild(
        @Body request: ParentChildUnlinkRequest
    ): Response<GenericResponse>

    /**
     * Get chat message history
     */
    @GET("api/chat/history/{deviceId}")
    suspend fun getChatHistory(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 100
    ): Response<ChatHistoryResponse>

    /**
     * Get archived audio recordings for a device
     */
    @GET("api/media/audio/{deviceId}")
    suspend fun getAudioGallery(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<AudioGalleryResponse>

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

data class DeviceRecentApp(
    val packageName: String?,
    val appName: String?,
    val lastUsed: Long?,
    val totalTimeInForeground: Long? = null,
    val isSystemApp: Boolean? = null
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
    val recentApps: List<DeviceRecentApp>? = emptyList(),
    val raw: Map<String, Any?>?
)

data class DeviceStatusHistoryResponse(
    val success: Boolean,
    val deviceId: String,
    val count: Int,
    val statuses: List<DeviceStatusHistoryItem> = emptyList()
)

data class DeviceStatusHistoryItem(
    val batteryLevel: Int?,
    val isCharging: Boolean?,
    val chargingType: String?,
    val currentAppName: String?,
    val currentAppPackage: String?,
    val timestamp: Long?,
    val recentApps: List<DeviceRecentApp>? = emptyList(),
    val raw: Map<String, Any?>? = null
)

data class ParentChildLinkRequest(
    val parentDeviceId: String,
    val childDeviceId: String,
    val relationRole: String = "guardian",
    val displayName: String? = null
)

data class ParentChildUnlinkRequest(
    val parentDeviceId: String,
    val childDeviceId: String
)

data class LinkedChildrenResponse(
    val success: Boolean,
    val parentDeviceId: String,
    val count: Int,
    val children: List<LinkedChildLink> = emptyList()
)

data class LinkedParentsResponse(
    val success: Boolean,
    val childDeviceId: String,
    val count: Int,
    val parents: List<LinkedParentLink> = emptyList()
)

data class LinkedChildLink(
    val parentDeviceId: String,
    val childDeviceId: String,
    val relationRole: String?,
    val displayName: String?,
    val createdBy: String?,
    val isActive: Int? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val childDeviceName: String?,
    val childDeviceType: String?,
    val childAppVersion: String?
)

data class LinkedParentLink(
    val parentDeviceId: String,
    val childDeviceId: String,
    val relationRole: String?,
    val displayName: String?,
    val createdBy: String?,
    val isActive: Int? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val parentDeviceName: String?,
    val parentDeviceType: String?,
    val parentAppVersion: String?
)

data class ChatHistoryResponse(
    val success: Boolean,
    val deviceId: String,
    val count: Int,
    val messages: List<ChatMessageData>
)

data class ChatMessageData(
    val id: String,
    val sender: String,
    val senderRole: String? = null,
    val senderDeviceId: String? = null,
    val senderDisplayName: String? = null,
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

data class AudioGalleryResponse(
    val success: Boolean,
    val audioFiles: List<AudioFileData> = emptyList(),
    val count: Int = 0
)

data class AudioFileData(
    val id: Long,
    val filename: String,
    val fileSize: Long,
    val mimeType: String,
    val duration: Long? = null,
    val timestamp: Long,
    val createdAt: String?,
    val downloadUrl: String
)
