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
