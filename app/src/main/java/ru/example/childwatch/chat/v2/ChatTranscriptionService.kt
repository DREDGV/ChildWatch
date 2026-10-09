package ru.example.childwatch.chat.v2

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import retrofit2.Response
import ru.childwatch.shared.chat.*
import ru.example.childwatch.database.dao.ChatTranscriptionV2Dao
import ru.example.childwatch.database.entity.ChatAttachmentDraftV2Entity
import ru.example.childwatch.database.entity.ChatTranscriptionV2Entity
import ru.example.childwatch.network.ChatMediaApi
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Durable recognition of one existing private recording. Never sends text or changes its voice draft. */
class ChatTranscriptionService internal constructor(
    private val context: Context,
    private val attachments: ChatAttachmentService,
    private val api: ChatMediaApi,
    private val jobs: ChatTranscriptionV2Dao,
    private val clock: () -> Long
) {
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val active = ConcurrentHashMap<String, Job>()
    }
    fun currentScopeKey(): String? = attachments.currentScopeKey()
    fun observe(draftId: String) = jobs.observe(currentScopeKey().orEmpty(), draftId).map {
        it?.takeIf { row -> row.scopeKey == currentScopeKey() }
    }
    suspend fun get(draftId: String): ChatTranscriptionV2Entity? {
        val key = currentScopeKey() ?: return null
        return jobs.get(key, draftId)?.takeIf { key == currentScopeKey() }
    }
    private fun checkScope(key: String) { check(key == currentScopeKey()) { "CONTEXT_CHANGED" } }
    private fun lock(key: String, id: String) = locks.getOrPut(key + ":" + id) { Mutex() }
    private fun operationKey(key: String, id: String) = key + ":" + id
    private suspend fun source(id: String, key: String): ChatAttachmentDraftV2Entity {
        checkScope(key)
        val draft = attachments.getDraft(id) ?: error("TRANSCRIPTION_SOURCE_UNAVAILABLE")
        ChatTranscriptionPolicy.validateSource(draft.attachmentType, draft.sizeBytes, draft.mimeType,
            draft.durationMs, draft.sha256)
        check(draft.state !in setOf("CANCELLED", "SENT")) { "TRANSCRIPTION_SOURCE_UNAVAILABLE" }
        checkScope(key)
        return draft
    }
    private fun sourceFile(draft: ChatAttachmentDraftV2Entity): File {
        val file = File(draft.localPath).canonicalFile
        val scope = Gson().toJson(listOf(draft.serverUrl, draft.familyId, draft.actorMemberId,
            draft.deviceId, draft.conversationId))
        val hash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.filesDir, "chat-attachments/$hash/drafts").canonicalFile
        check(file.parentFile == directory && file.isFile && file.length() == draft.sizeBytes) {
            "TRANSCRIPTION_SOURCE_UNAVAILABLE"
        }
        return file
    }
    private suspend fun initialize(draft: ChatAttachmentDraftV2Entity, key: String): ChatTranscriptionV2Entity {
        val existing = jobs.get(key, draft.clientMessageId)
        if (existing != null) {
            check(existing.sourceSha256.equals(draft.sha256, true)) { "TRANSCRIPTION_SOURCE_CHANGED" }
            return existing
        }
        val row = ChatTranscriptionV2Entity(key, draft.clientMessageId, draft.conversationId,
            draft.serverUrl, draft.familyId, draft.actorMemberId, draft.deviceId, draft.sha256,
            createdAt=clock(), updatedAt=clock())
        jobs.insert(row)
        return jobs.get(key, draft.clientMessageId) ?: error("TRANSCRIPTION_DRAFT_UNAVAILABLE")
    }
    /** A retry first recovers by stable client ID; an uncertain POST never creates a second job. */
    suspend fun request(draftId: String): ChatTranscriptionV2Entity? {
        val key = currentScopeKey() ?: error("CONTEXT_UNAVAILABLE")
        return lock(key, draftId).withLock {
            val draft = source(draftId, key)
            val initial = initialize(draft, key)
            if (ChatTranscriptionPolicy.terminal(initial.state)) return@withLock initial
            val job = currentCoroutineContext()[Job]
            val activeKey = operationKey(key, draftId)
            if (job != null) active[activeKey] = job
            try {
                val caps = attachments.capabilities()
                checkScope(key)
                check(caps.transcription) { caps.transcriptionReason ?: "TRANSCRIPTION_SERVER_UNAVAILABLE" }
                require(draft.durationMs!! <= caps.transcriptionMaxDurationMs) { "VOICE_DURATION_LIMIT" }
                val lookup = api.getChatTranscription(draft.conversationId, draftId)
                checkScope(key)
                if (lookup.isSuccessful) return@withLock apply(key, draftId, lookup)
                if (lookup.code() != 404) throw responseError(lookup)
                val file = sourceFile(draft)
                val digest = MessageDigest.getInstance("SHA-256")
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) }.equals(draft.sha256,true)) {
                    "TRANSCRIPTION_SOURCE_CHECKSUM_FAILED"
                }
                checkScope(key)
                val body = object : RequestBody() {
                    override fun contentType() = "audio/mp4".toMediaType()
                    override fun contentLength() = draft.sizeBytes
                    override fun writeTo(sink: BufferedSink) {
                        try {
                        var bytes = 0L
                        val sentDigest = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (job?.isActive == false || key != currentScopeKey())
                                    throw IOException("TRANSCRIPTION_UPLOAD_INTERRUPTED")
                                val count = input.read(buffer); if (count < 0) break
                                bytes += count
                                if (bytes > draft.sizeBytes) throw IOException("TRANSCRIPTION_SOURCE_CHANGED")
                                sentDigest.update(buffer,0,count); sink.write(buffer,0,count)
                            }
                        }
                        if (bytes != draft.sizeBytes || !sentDigest.digest().joinToString("") {
                            "%02x".format(it) }.equals(draft.sha256,true)) throw IOException("TRANSCRIPTION_SOURCE_CHANGED")
                        } catch (error: IOException) { throw error }
                        catch (error: Exception) { throw IOException("TRANSCRIPTION_UPLOAD_INTERRUPTED", error) }
                    }
                }
                val response = api.createChatTranscription(draft.conversationId,
                    MultipartBody.Part.createFormData("file", draft.filename, body),
                    draftId.toRequestBody("text/plain".toMediaType()),
                    "ru".toRequestBody("text/plain".toMediaType()),
                    draft.durationMs.toString().toRequestBody("text/plain".toMediaType()))
                checkScope(key)
                apply(key, draftId, response)
            } catch (error: Exception) {
                withContext(NonCancellable) { failure(key, draftId, error) }
                throw error
            } finally { if (job != null) active.remove(activeKey, job) }
        }
    }
    /** Read-only recovery; polling never records, uploads, or sends a message. */
    suspend fun refresh(draftId: String): ChatTranscriptionV2Entity? {
        val key = currentScopeKey() ?: return null
        return lock(key, draftId).withLock {
            val row = jobs.get(key,draftId) ?: return@withLock null
            try {
                val response = api.getChatTranscription(row.conversationId,draftId)
                checkScope(key)
                if (response.code() == 404 && row.jobId == null) return@withLock row
                apply(key,draftId,response)
            } catch (error: Exception) {
                withContext(NonCancellable) { failure(key,draftId,error) }
                throw error
            }
        }
    }
    suspend fun cancel(draftId: String): ChatTranscriptionV2Entity? {
        val key = currentScopeKey() ?: return null
        active[operationKey(key,draftId)]?.cancel(CancellationException("TRANSCRIPTION_UPLOAD_INTERRUPTED"))
        return lock(key,draftId).withLock {
            val row = jobs.get(key,draftId) ?: return@withLock null
            checkScope(key)
            if (ChatTranscriptionPolicy.terminal(row.state)) return@withLock row
            jobs.setState(key,draftId,"CANCEL_REQUESTED")
            try {
                val response = api.cancelChatTranscription(row.conversationId,draftId)
                checkScope(key)
                if (response.code() == 404 && row.jobId == null) {
                    // A fully uploaded POST may still commit after the connection is lost.
                    // Keep cancellation pending rather than claiming the server stopped it.
                    return@withLock jobs.get(key,draftId)
                }
                apply(key,draftId,response)
            } catch (error: Exception) {
                withContext(NonCancellable) { failure(key,draftId,error) }
                throw error
            }
        }
    }
    suspend fun saveEditedText(draftId: String, text: String) {
        ChatTranscriptionPolicy.validateEditedText(text)
        val key = currentScopeKey() ?: error("CONTEXT_UNAVAILABLE")
        checkScope(key)
        check(jobs.edit(key,draftId,text) == 1) { "TRANSCRIPTION_EDIT_UNAVAILABLE" }
    }
    suspend fun markTextEnqueued(draftId: String, clientMessageId: String) {
        val key = currentScopeKey() ?: error("CONTEXT_UNAVAILABLE")
        require(clientMessageId == draftId + ":text") { "TRANSCRIPTION_TEXT_ID_INVALID" }
        checkScope(key)
        check(jobs.markTextEnqueued(key,draftId,clientMessageId) == 1) { "TRANSCRIPTION_SEND_UNAVAILABLE" }
    }
    private suspend fun apply(key: String, id: String, response: Response<ChatV2TranscriptionResponse>): ChatTranscriptionV2Entity? {
        checkScope(key)
        val job = response.body()?.job?.takeIf { response.isSuccessful && response.body()?.success == true }
            ?: throw responseError(response)
        check(ChatTranscriptionPolicy.validJob(job,id)) { "TRANSCRIPTION_RESPONSE_INVALID" }
        val row = jobs.get(key,id) ?: return null
        // Older concurrent/recovered responses cannot reopen a finished job.
        if (row.jobId != null && (row.jobId != job.jobId || job.updatedAt < row.updatedAt ||
            ChatTranscriptionPolicy.terminal(row.state) && !ChatTranscriptionPolicy.terminal(job.state))) return row
        checkScope(key)
        // Only recognition columns change; a user's edit is updated by a separate atomic DAO query.
        jobs.updateJob(key,id,job.jobId,job.state,job.text,job.errorCode,job.createdAt,job.updatedAt,job.completedAt)
        return jobs.get(key,id)?.takeIf { key == currentScopeKey() }
    }
    private suspend fun failure(key: String,id: String,error: Exception) {
        if (key != currentScopeKey()) return
        val row = jobs.get(key,id) ?: return
        if (error.message in setOf("TRANSCRIPTION_ACCESS_DENIED", "ATTACHMENTS_ACCESS_DENIED")) {
            // A confirmed access revocation invalidates cached results, including completed jobs.
            jobs.deny(key,id)
            return
        }
        if (ChatTranscriptionPolicy.terminal(row.state)) return
        val code = if (error is CancellationException) "TRANSCRIPTION_UPLOAD_INTERRUPTED" else
            error.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: "TRANSCRIPTION_NETWORK_FAILURE"
        jobs.setError(key,id,code)
    }
    private fun responseError(response: Response<*>): IOException {
        if (response.code() in setOf(401, 403)) return IOException("TRANSCRIPTION_ACCESS_DENIED")
        val code = runCatching {
            val json = JSONObject(response.errorBody()?.string().orEmpty())
            json.optString("errorCode").ifBlank { json.optString("code") }.ifBlank {
                json.optString("error").takeIf { it.matches(Regex("[A-Z0-9_]+")) }.orEmpty()
            }
        }.getOrDefault("").ifBlank { "TRANSCRIPTION_HTTP_${response.code()}" }
        return IOException(code)
    }
}
