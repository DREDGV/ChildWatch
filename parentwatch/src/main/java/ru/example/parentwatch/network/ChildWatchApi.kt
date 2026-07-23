package ru.example.parentwatch.network

import retrofit2.Response
import retrofit2.http.*
import ru.childwatch.shared.onboarding.FamilyBootstrapRequest
import ru.childwatch.shared.onboarding.FamilyInvitationAcceptRequest
import ru.childwatch.shared.onboarding.FamilyInvitationCreateRequest
import ru.childwatch.shared.onboarding.FamilyInvitationResponse
import ru.childwatch.shared.onboarding.FamilyOnboardingResultResponse
import ru.childwatch.shared.onboarding.FamilyOnboardingSimpleResponse
import ru.childwatch.shared.chat.ChatV2ConversationsResponse
import ru.childwatch.shared.chat.ChatV2DirectConversationRequest
import ru.childwatch.shared.chat.ChatV2DirectConversationResponse
import ru.childwatch.shared.chat.ChatV2MessagesResponse
import ru.childwatch.shared.chat.ChatV2ReceiptRequest
import ru.childwatch.shared.chat.ChatV2ReceiptResponse
import ru.childwatch.shared.chat.ChatV2SendMessageRequest
import ru.childwatch.shared.chat.ChatV2SendMessageResponse

/**
 * Retrofit API interface for ChildWatch server communication
 */
interface ChildWatchApi {

    /** Canonical authenticated family identity for this physical device. */
    @GET("api/me")
    suspend fun getAuthenticatedIdentity(): Response<AuthenticatedIdentityResponse>

    @POST("api/family-onboarding/bootstrap")
    suspend fun bootstrapFamily(
        @Body request: FamilyBootstrapRequest
    ): Response<FamilyOnboardingResultResponse>

    @POST("api/family-onboarding/invitations")
    suspend fun createFamilyInvitation(
        @Body request: FamilyInvitationCreateRequest
    ): Response<FamilyInvitationResponse>

    @GET("api/family-onboarding/invitations/{token}")
    suspend fun previewFamilyInvitation(
        @Path("token") token: String
    ): Response<FamilyInvitationResponse>

    @POST("api/family-onboarding/invitations/{token}/accept")
    suspend fun acceptFamilyInvitation(
        @Path("token") token: String,
        @Body request: FamilyInvitationAcceptRequest
    ): Response<FamilyOnboardingResultResponse>

    @DELETE("api/family-onboarding/families/{familyId}/invitations/{invitationId}")
    suspend fun revokeFamilyInvitation(
        @Path("familyId") familyId: String,
        @Path("invitationId") invitationId: String
    ): Response<FamilyOnboardingSimpleResponse>

    /** Human family profiles. Device ids are resolved separately through bindings. */
    @GET("api/families/{familyId}/members")
    suspend fun getFamilyMembers(
        @Path("familyId") familyId: String
    ): Response<FamilyMembersResponse>

    /** Device-to-person bindings for a canonical family. */
    @GET("api/families/{familyId}/devices")
    suspend fun getFamilyDevices(
        @Path("familyId") familyId: String
    ): Response<FamilyDevicesResponse>

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
     * Get parents linked to this child device
     */
    @GET("api/relationships/parents/{childDeviceId}")
    suspend fun getLinkedParents(
        @Path("childDeviceId") childDeviceId: String
    ): Response<LinkedParentsResponse>

    @GET("api/relationships/presence/{childDeviceId}")
    suspend fun getFamilyPresence(
        @Path("childDeviceId") childDeviceId: String
    ): Response<FamilyPresenceResponse>

    /**
     * Create or update a parent-child server-side link
     */
    @POST("api/relationships/link")
    suspend fun linkParentChild(
        @Body request: ParentChildLinkRequest
    ): Response<GenericResponse>

    /**
     * Deactivate an existing parent-child server-side link
     */
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

    @GET("api/chat/v2/conversations")
    suspend fun getChatV2Conversations(): Response<ChatV2ConversationsResponse>

    @POST("api/chat/v2/conversations/direct")
    suspend fun createChatV2DirectConversation(
        @Body request: ChatV2DirectConversationRequest
    ): Response<ChatV2DirectConversationResponse>

    @GET("api/chat/v2/conversations/{conversationId}/messages")
    suspend fun getChatV2Messages(
        @Path("conversationId") conversationId: String,
        @Query("beforeSequence") beforeSequence: Long? = null,
        @Query("limit") limit: Int = 50
    ): Response<ChatV2MessagesResponse>

    @POST("api/chat/v2/conversations/{conversationId}/messages")
    suspend fun sendChatV2Message(
        @Path("conversationId") conversationId: String,
        @Body request: ChatV2SendMessageRequest
    ): Response<ChatV2SendMessageResponse>

    @POST("api/chat/v2/conversations/{conversationId}/receipts")
    suspend fun sendChatV2Receipt(
        @Path("conversationId") conversationId: String,
        @Body request: ChatV2ReceiptRequest
    ): Response<ChatV2ReceiptResponse>

    /**
     * Mark chat messages as read
     */
    @POST("api/chat/mark-read/{deviceId}")
    suspend fun markChatMessagesAsRead(@Path("deviceId") deviceId: String): Response<GenericResponse>
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

data class AuthenticatedIdentityResponse(
    val success: Boolean,
    val device: AuthenticatedDeviceData,
    val memberships: List<AuthenticatedMembershipData> = emptyList()
)

data class AuthenticatedDeviceData(
    val deviceId: String,
    val displayName: String,
    val platform: String? = null,
    val appVersion: String? = null
)

data class AuthenticatedMembershipData(
    val familyId: String,
    val memberId: String,
    val family: AuthenticatedFamilyData,
    val member: AuthenticatedMemberData,
    val binding: AuthenticatedBindingData
)

data class AuthenticatedFamilyData(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class AuthenticatedMemberData(
    val id: String,
    val familyId: String,
    val displayName: String,
    val role: String,
    val avatarKey: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class AuthenticatedBindingData(
    val id: String,
    val familyId: String,
    val memberId: String,
    val deviceId: String,
    val displayName: String,
    val platform: String? = null,
    val lastSeenAt: Long? = null,
    val memberBindingSource: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class FamilyMembersResponse(
    val success: Boolean,
    val familyId: String,
    val members: List<FamilyMemberData> = emptyList()
)

data class FamilyMemberData(
    val id: String,
    val familyId: String,
    val displayName: String,
    val role: String,
    val avatarKey: String? = null,
    val isActive: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class FamilyDevicesResponse(
    val success: Boolean,
    val familyId: String,
    val devices: List<FamilyDeviceData> = emptyList()
)

data class FamilyDeviceData(
    val id: String,
    val familyId: String,
    val memberId: String,
    val deviceId: String,
    val displayName: String,
    val platform: String? = null,
    val lastSeenAt: Long? = null,
    val memberBindingSource: String? = null,
    val isActive: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
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

data class LinkedParentsResponse(
    val success: Boolean,
    val childDeviceId: String? = null,
    val count: Int = 0,
    val parents: List<LinkedParentLink> = emptyList()
)

data class LinkedParentLink(
    val parentDeviceId: String,
    val childDeviceId: String? = null,
    val relationRole: String? = null,
    val displayName: String? = null,
    val parentDisplayName: String? = null,
    val childDisplayName: String? = null,
    val parentMarkerIconId: Int? = null,
    val childMarkerIconId: Int? = null,
    val createdBy: String? = null,
    val isActive: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val parentDeviceName: String? = null,
    val parentDeviceType: String? = null,
    val parentAppVersion: String? = null
)

data class FamilyPresenceResponse(
    val success: Boolean,
    val childDeviceId: String,
    val participants: List<FamilyPresenceParticipant> = emptyList(),
    val onlineCount: Int = 0,
    val totalCount: Int = 0
)

data class FamilyPresenceParticipant(
    val role: String,
    val deviceId: String,
    val displayName: String,
    val isOnline: Boolean
)

data class ParentChildLinkRequest(
    val parentDeviceId: String,
    val childDeviceId: String,
    val relationRole: String = "guardian",
    val displayName: String? = null,
    val parentDisplayName: String? = null,
    val childDisplayName: String? = null,
    val parentMarkerIconId: Int? = null,
    val childMarkerIconId: Int? = null
)

data class ParentChildUnlinkRequest(
    val parentDeviceId: String,
    val childDeviceId: String
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
