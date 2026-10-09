package ru.example.childwatch.chat.v2

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import ru.example.childwatch.designsystem.ChatAttachmentPrivateStore
import ru.example.childwatch.designsystem.ChatAttachmentInputPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import ru.childwatch.shared.chat.*
import ru.example.childwatch.database.dao.ChatMediaCapabilitiesDao
import ru.example.childwatch.database.entity.ChatMediaCapabilitiesEntity
import ru.example.childwatch.database.dao.ChatAttachmentDraftV2Dao
import ru.example.childwatch.database.entity.ChatAttachmentDraftV2Entity
import ru.example.childwatch.network.ChatMediaApi
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Durable private media drafts. A process restart retries the same upload and message IDs. */
class ChatAttachmentService internal constructor(
    private val context: Context,
    private val repository: ChatV2Repository,
    private val api: ChatMediaApi,
    private val drafts: ChatAttachmentDraftV2Dao,
    private val capabilityCache: ChatMediaCapabilitiesDao,
    private val scopeProvider: () -> ChatV2ReceiptScope?,
    private val clock: () -> Long
) {
    private val gson = Gson()
    companion object {
        private val uploadMutex = Mutex()
        private val active = ConcurrentHashMap<String, Job>()
        internal fun matchesScope(draft: ChatAttachmentDraftV2Entity, scope: ChatV2ReceiptScope?): Boolean =
            scope != null && scope.isComplete() && draft.serverUrl == scope.server &&
                draft.familyId == scope.family && draft.actorMemberId == scope.member && draft.deviceId == scope.device
    }
    fun currentScopeKey(): String? = scopeProvider()?.takeIf { it.isComplete() }?.let {
        gson.toJson(listOf(it.server, it.family, it.member, it.device))
    }
    private fun fileScope(draft: ChatAttachmentDraftV2Entity) =
        gson.toJson(listOf(draft.serverUrl, draft.familyId, draft.actorMemberId, draft.deviceId, draft.conversationId))
    fun observe(conversationId: String) = drafts.observe(conversationId).map { rows ->
        rows.filter { matchesScope(it, scopeProvider()) }
    }
    suspend fun getDraft(id: String): ChatAttachmentDraftV2Entity? = drafts.get(id)?.takeIf { matchesScope(it, scopeProvider()) }
    suspend fun capabilities(): ChatV2CapabilitiesResponse {
        val scope = scopeProvider() ?: throw IllegalStateException("CONTEXT_UNAVAILABLE")
        val scopeKey = currentScopeKey() ?: throw IllegalStateException("CONTEXT_UNAVAILABLE")
        val response = try { api.getChatCapabilities() } catch (error: IOException) {
            check(scope == scopeProvider()) { "CONTEXT_CHANGED" }
            val cached = capabilityCache.get(scopeKey)
            if (cached != null && ChatMediaCapabilityPolicy.canUseCached(cached.capturedAt, clock())) {
                return gson.fromJson(cached.capabilitiesJson, ChatV2CapabilitiesResponse::class.java)
            }
            throw error
        }
        check(scope == scopeProvider()) { "CONTEXT_CHANGED" }
        if (response.code() in setOf(401, 403)) {
            capabilityCache.clear(scopeKey)
            throw IllegalStateException("ATTACHMENTS_ACCESS_DENIED")
        }
        if (response.code() == 404) {
            capabilityCache.clear(scopeKey)
            return ChatV2CapabilitiesResponse()
        }
        val body = response.body()?.takeIf { response.isSuccessful && it.success }
            ?: throw IOException("CAPABILITIES_HTTP_${response.code()}")
        if (body.attachments && body.attachmentTypes.isNotEmpty())
            capabilityCache.put(ChatMediaCapabilitiesEntity(scopeKey, gson.toJson(body), clock()))
        else capabilityCache.clear(scopeKey)
        return body
    }
    private fun checkScope(draft: ChatAttachmentDraftV2Entity) {
        check(matchesScope(draft, scopeProvider())) { "CONTEXT_CHANGED" }
    }
    private fun localFile(draft: ChatAttachmentDraftV2Entity): File {
        val file = File(draft.localPath).canonicalFile
        val scopeHash = MessageDigest.getInstance("SHA-256").digest(fileScope(draft).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.filesDir, "chat-attachments/$scopeHash/drafts").canonicalFile
        check(file.parentFile == directory) { "ATTACHMENT_PATH_INVALID" }
        return file
    }
    /** Copy once into app-private storage; URI permission alone is not reliable after restart. */
    suspend fun createDraft(conversationId: String, uri: Uri, filename: String, mimeType: String,
        type: String, durationMs: Long? = null): ChatAttachmentDraftV2Entity = withContext(Dispatchers.IO) {
        val scope = scopeProvider() ?: throw IllegalStateException("CONTEXT_UNAVAILABLE")
        val conversation = repository.getCachedConversation(conversationId)
            ?: throw IllegalArgumentException("CONVERSATION_UNAVAILABLE")
        check(conversation.familyId == scope.family) { "CONTEXT_CHANGED" }
        val capability = capabilities()
        check(capability.attachments && type in capability.attachmentTypes) { "ATTACHMENTS_SERVER_UNAVAILABLE" }
        val limit = minOf(ChatAttachmentPolicy.maxBytes(type),
            if (type == "FILE") capability.maxFileBytes else capability.maxImageBytes)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val id = UUID.randomUUID().toString()
        val copyScope = gson.toJson(listOf(scope.server, scope.family, scope.member, scope.device, conversationId))
        val job = currentCoroutineContext()[Job]
        val staged = ChatAttachmentPrivateStore.copyDocument(context, uri, copyScope,
            if (type in setOf("FILE", "VOICE")) ChatAttachmentInputPolicy.Mode.FILE else ChatAttachmentInputPolicy.Mode.IMAGE,
            java.util.function.BooleanSupplier { job?.isActive == false })
        try {
            require(staged.sizeBytes <= limit) { "ATTACHMENT_SIZE_LIMIT" }
            val actualMime = if (staged.mimeType == "application/octet-stream") mimeType else staged.mimeType
            ChatAttachmentPolicy.validate(type, staged.sizeBytes, actualMime, durationMs)
            check(scope == scopeProvider()) { "CONTEXT_CHANGED" }
            val now = clock()
            val draft = ChatAttachmentDraftV2Entity(id, conversationId, scope.server, scope.family,
                scope.member, scope.device, staged.file.absolutePath, uri.toString(), staged.displayName,
                actualMime, staged.sizeBytes, staged.sha256, type,
                durationMs, createdAt = now, updatedAt = now)
            check(drafts.insert(draft) != -1L) { "DRAFT_CONFLICT" }
            draft
        } catch (error: Exception) { staged.file.delete(); throw error }
    }
    suspend fun send(id: String, caption: String, senderName: String, senderRole: ConversationMemberRole) {
        val draft = drafts.get(id) ?: throw IllegalArgumentException("DRAFT_UNAVAILABLE")
        checkScope(draft)
        check(draft.state !in setOf("CANCELLED", "ENQUEUED")) { "DRAFT_ALREADY_FINISHED" }
        if (caption.isNotBlank()) require(ChatTextPolicy.validate(caption) is ChatTextValidation.Valid) { "CAPTION_SIZE_LIMIT" }
        drafts.update(draft.copy(caption = caption, senderName = senderName, senderRole = senderRole.name,
            state = "QUEUED", errorCode = null, nextAttemptAt = clock(), updatedAt = clock()))
        retry(id)
    }
    suspend fun retry(id: String) = uploadMutex.withLock {
        val draft = drafts.get(id) ?: return@withLock
        checkScope(draft)
        if (draft.state in setOf("DRAFT", "CANCELLED", "ENQUEUED")) return@withLock
        val job = currentCoroutineContext()[Job]
        if (job != null) active[id] = job
        try {
            val capability = capabilities()
            check(capability.attachments && draft.attachmentType in capability.attachmentTypes) { "ATTACHMENTS_SERVER_UNAVAILABLE" }
            require(draft.sizeBytes <= minOf(ChatAttachmentPolicy.maxBytes(draft.attachmentType),
                if (draft.attachmentType == "FILE") capability.maxFileBytes else capability.maxImageBytes)) { "ATTACHMENT_SIZE_LIMIT" }
            var attachment = draft.attachmentJson?.let { gson.fromJson(it, ChatV2AttachmentDto::class.java) }
            if (attachment == null) {
                val file = localFile(draft)
                check(file.exists() && file.length() == draft.sizeBytes) { "ATTACHMENT_LOCAL_UNAVAILABLE" }
                check(hash(file, job) == draft.sha256) { "ATTACHMENT_CHECKSUM_FAILED" }
                drafts.update(draft.copy(state = "UPLOADING", errorCode = null, updatedAt = clock()))
                var lastProgress = 0L
                val body = object : RequestBody() {
                    override fun contentType() = draft.mimeType.toMediaType()
                    override fun contentLength() = file.length()
                    override fun writeTo(sink: BufferedSink) {
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024); var sent = 0L
                            while (true) {
                                if (job?.isActive == false) throw IOException("UPLOAD_CANCELLED")
                                val count = input.read(buffer); if (count < 0) break
                                sink.write(buffer, 0, count); sent += count
                                if (clock() - lastProgress >= 250 || sent == file.length()) {
                                    lastProgress = clock()
                                    try {
                                        runBlocking { drafts.get(id)?.takeIf { it.state != "CANCELLED" }?.let {
                                            drafts.update(it.copy(progressBytes = sent, updatedAt = clock()))
                                        } }
                                    } catch (error: Exception) {
                                        // OkHttp's worker expects I/O failures, not uncaught Room/cancellation exceptions.
                                        throw IOException("UPLOAD_PROGRESS_INTERRUPTED", error)
                                    }
                                }
                            }
                        }
                    }
                }
                checkScope(draft)
                val response = api.uploadChatAttachment(draft.conversationId,
                    MultipartBody.Part.createFormData("file", draft.filename, body),
                    draft.attachmentType.toRequestBody("text/plain".toMediaType()),
                    id.toRequestBody("text/plain".toMediaType()),
                    draft.durationMs?.toString()?.toRequestBody("text/plain".toMediaType()))
                checkScope(draft)
                attachment = response.body()?.attachment?.takeIf { response.isSuccessful && response.body()?.success == true }
                    ?: throw IOException("ATTACHMENT_HTTP_${response.code()}")
                check(ChatAttachmentPolicy.verifyMetadata(draft.attachmentType, draft.sizeBytes, draft.sha256, attachment)) {
                    "ATTACHMENT_METADATA_INVALID"
                }
                val latest = drafts.get(id) ?: throw IllegalStateException("DRAFT_UNAVAILABLE")
                check(latest.state != "CANCELLED") { "DRAFT_CANCELLED" }
                drafts.update(latest.copy(attachmentJson = gson.toJson(attachment), state = "UPLOADED", updatedAt = clock()))
            }
            checkScope(draft)
            repository.enqueueMessage(draft.conversationId, draft.caption, draft.senderName,
                ConversationMemberRole.valueOf(draft.senderRole), draft.actorMemberId, draft.deviceId,
                id, listOf(attachment), draft.attachmentType)
            drafts.get(id)?.let { drafts.update(it.copy(state = "ENQUEUED", errorCode = null, updatedAt = clock())) }
            // The authenticated outbox is durable; retain source until server acceptance for retry.
        } catch (error: Exception) {
            withContext(NonCancellable) {
                drafts.get(id)?.takeIf { it.state != "CANCELLED" }?.let {
                    val attempts = it.attemptCount + 1
                    drafts.update(it.copy(state = if (ChatV2RetryPolicy.isExhausted(attempts)) "FAILED" else "RETRY",
                        errorCode = if (error is CancellationException) "UPLOAD_INTERRUPTED" else "ATTACHMENT_SEND_FAILED",
                        attemptCount = attempts, nextAttemptAt = ChatV2RetryPolicy.nextAttemptAt(clock(), attempts), updatedAt = clock()))
                }
            }
            throw error
        } finally { if (job != null) active.remove(id, job) }
    }
    suspend fun flushPending() {
        val scope = scopeProvider() ?: return
        drafts.pending(scope.server, scope.family, scope.member, scope.device, clock()).forEach {
            try { retry(it.clientMessageId) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        }
    }
    suspend fun cancel(id: String) {
        val original = drafts.get(id) ?: return
        checkScope(original)
        check(original.state != "ENQUEUED" && original.state != "SENT") { "MESSAGE_ALREADY_ENQUEUED" }
        active[id]?.cancel(CancellationException("DRAFT_CANCELLED"))
        uploadMutex.withLock {
            val draft = drafts.get(id) ?: return@withLock
            checkScope(draft)
            check(draft.state != "ENQUEUED" && draft.state != "SENT") { "MESSAGE_ALREADY_ENQUEUED" }
            drafts.update(draft.copy(state = "CANCELLED", updatedAt = clock()))
            draft.attachmentJson?.let {
                val attachment = gson.fromJson(it, ChatV2AttachmentDto::class.java)
                runCatching { api.cancelChatAttachment(draft.conversationId, attachment.attachmentId) }
            }
            localFile(draft).delete()
        }
    }
    internal suspend fun accepted(id: String) {
        val draft = drafts.get(id) ?: return
        checkScope(draft)
        drafts.update(draft.copy(state = "SENT", updatedAt = clock()))
        localFile(draft).delete()
    }
    suspend fun download(conversationId: String, attachment: ChatV2AttachmentDto,
        onProgress: ((Long, Long) -> Unit)? = null): File = withContext(Dispatchers.IO) {
        val scope = scopeProvider() ?: throw IllegalStateException("CONTEXT_UNAVAILABLE")
        check(repository.getCachedConversation(conversationId)?.familyId == scope.family) { "CONTEXT_CHANGED" }
        ChatAttachmentPolicy.validate(attachment.type, attachment.sizeBytes, attachment.mimeType, attachment.durationMs)
        val response = api.downloadChatAttachment(conversationId, attachment.attachmentId)
        check(scope == scopeProvider()) { "CONTEXT_CHANGED" }
        val body = response.body()?.takeIf { response.isSuccessful } ?: throw IOException("ATTACHMENT_UNAVAILABLE")
        val directory = File(context.cacheDir, "chat-attachments").apply { mkdirs() }
        val file = File(directory, UUID.randomUUID().toString() + ".bin")
        try {
            body.use {
                it.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var size = 0L; var lastProgress = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        size += count; require(size <= attachment.sizeBytes) { "ATTACHMENT_SIZE_INVALID" }
                        output.write(buffer, 0, count)
                        if (clock() - lastProgress >= 250 || size == attachment.sizeBytes) {
                            lastProgress = clock(); onProgress?.invoke(size, attachment.sizeBytes)
                        }
                    }
                } }
            }
            check(file.length() == attachment.sizeBytes && hash(file, currentCoroutineContext()[Job]).equals(attachment.sha256, true)) { "ATTACHMENT_CHECKSUM_FAILED" }
            check(scope == scopeProvider()) { "CONTEXT_CHANGED" }
            file
        } catch (error: Exception) { file.delete(); throw error }
    }
    private fun hash(file: File, job: Job? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) { job?.ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
