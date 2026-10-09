package ru.example.childwatch.network
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import ru.childwatch.shared.chat.*
/** Inherits the same authenticated Retrofit client as text chat. */
interface ChatMediaApi {
    @GET("api/chat/v2/capabilities") suspend fun getChatCapabilities(): Response<ChatV2CapabilitiesResponse>
    @Multipart @POST("api/chat/v2/conversations/{conversationId}/attachments")
    suspend fun uploadChatAttachment(@Path("conversationId") conversationId: String,
        @Part file: MultipartBody.Part, @Part("attachmentType") type: RequestBody,
        @Part("clientUploadId") clientUploadId: RequestBody,
        @Part("durationMs") duration: RequestBody?): Response<ChatV2AttachmentResponse>
    @Streaming @GET("api/chat/v2/conversations/{conversationId}/attachments/{attachmentId}/content")
    suspend fun downloadChatAttachment(@Path("conversationId") conversationId: String,
        @Path("attachmentId") attachmentId: String): Response<ResponseBody>
    @DELETE("api/chat/v2/conversations/{conversationId}/attachments/{attachmentId}")
    suspend fun cancelChatAttachment(@Path("conversationId") conversationId: String,
        @Path("attachmentId") attachmentId: String): Response<ChatV2AttachmentCancelResponse>
}
