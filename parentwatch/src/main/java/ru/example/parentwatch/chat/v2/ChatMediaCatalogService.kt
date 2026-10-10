package ru.example.parentwatch.chat.v2

import android.content.Context
import android.graphics.BitmapFactory
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import ru.childwatch.shared.chat.*
import ru.example.parentwatch.network.ChatMediaApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Private, versioned media cache. Cached bytes are never authority to upload or to join a conversation. */
class ChatMediaCatalogService internal constructor(
    private val context: Context,
    private val repository: ChatV2Repository,
    private val attachments: ChatAttachmentService,
    private val api: ChatMediaApi,
    private val clock: () -> Long
) {
    private val gson = Gson()
    private data class CacheEntry(val scopeKey: String, val conversationId: String,
        val capturedAt: Long, val catalog: ChatV2MediaCatalogResponse)
    private data class Scope(val key: String, val conversation: String, val directory: File)
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private const val MAX_METADATA_BYTES = 128 * 1024L
    }
    fun currentScopeKey(): String? = attachments.currentScopeKey()
    private suspend fun scope(conversationId: String): Scope {
        val key = currentScopeKey() ?: error("CONTEXT_UNAVAILABLE")
        val actor = repository.mediaScope() ?: error("CONTEXT_UNAVAILABLE")
        check(repository.getCachedConversation(conversationId)?.familyId == actor.family) { "CONTEXT_CHANGED" }
        check(key == currentScopeKey()) { "CONTEXT_CHANGED" }
        val hash = hash(gson.toJson(listOf(key,conversationId)).toByteArray(Charsets.UTF_8))
        val directory = File(context.cacheDir, "chat-attachments/media-catalog/$hash").canonicalFile
        val root = File(context.cacheDir, "chat-attachments/media-catalog").canonicalFile
        check(directory.parentFile == root) { "MEDIA_CATALOG_PATH_INVALID" }
        return Scope(key,conversationId,directory)
    }
    private fun checkScope(scope: Scope) { check(scope.key == currentScopeKey()) { "CONTEXT_CHANGED" } }
    private fun lock(scope: Scope) = locks.getOrPut(scope.directory.absolutePath) { Mutex() }
    private fun cached(scope: Scope): CacheEntry? = runCatching {
        val file = File(scope.directory,"catalog.json")
        if (!file.isFile || file.length() !in 1..MAX_METADATA_BYTES) return@runCatching null
        val entry = gson.fromJson(file.readText(Charsets.UTF_8),CacheEntry::class.java)
        if (entry.scopeKey != scope.key || entry.conversationId != scope.conversation ||
            !ChatMediaCatalogPolicy.canUseCache(entry.capturedAt,clock())) return@runCatching null
        ChatMediaCatalogPolicy.validate(entry.catalog)
        entry
    }.getOrNull()
    private fun purge(scope: Scope) {
        // Only this generated private directory is removed, never another scope or the voice drafts.
        scope.directory.listFiles()?.forEach { if (it.isFile) it.delete() }
    }
    private fun save(scope: Scope,catalog: ChatV2MediaCatalogResponse) {
        checkScope(scope)
        check(scope.directory.isDirectory || scope.directory.mkdirs()) { "MEDIA_CATALOG_CACHE_UNAVAILABLE" }
        val bytes = gson.toJson(CacheEntry(scope.key,scope.conversation,clock(),catalog)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_METADATA_BYTES) { "MEDIA_CATALOG_INVALID" }
        val temp = File(scope.directory,UUID.randomUUID().toString()+".part")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            checkScope(scope)
            check(temp.renameTo(File(scope.directory,"catalog.json"))) { "MEDIA_CATALOG_CACHE_UNAVAILABLE" }
            // At most the current catalog's immutable resources are retained.
            val names = catalog.items.orEmpty().map { filename(catalog.version,it) }.toSet() + "catalog.json"
            scope.directory.listFiles()?.forEach { if (it.isFile && it.name !in names) it.delete() }
        } finally { temp.delete() }
    }
    suspend fun load(conversationId: String): ChatV2MediaCatalogResponse = withContext(Dispatchers.IO) {
        val scope = scope(conversationId)
        lock(scope).withLock {
            try {
                val caps = attachments.capabilities()
                checkScope(scope)
                if (!caps.mediaCatalog) {
                    purge(scope)
                    error("MEDIA_CATALOG_SERVER_UNAVAILABLE")
                }
                val response = api.getChatMediaCatalog(conversationId)
                checkScope(scope)
                if (response.code() in setOf(401,403)) { purge(scope); throw IOException("MEDIA_CATALOG_ACCESS_DENIED") }
                val catalog = response.body()?.takeIf { response.isSuccessful } ?: throw responseError(response)
                ChatMediaCatalogPolicy.validate(catalog)
                check(caps.mediaCatalogVersion == null || caps.mediaCatalogVersion == catalog.version) { "MEDIA_CATALOG_VERSION_CHANGED" }
                save(scope,catalog)
                catalog
            } catch (error: Exception) {
                checkScope(scope)
                if (error.message in setOf("ATTACHMENTS_ACCESS_DENIED","MEDIA_CATALOG_ACCESS_DENIED")) {
                    purge(scope); throw IOException("MEDIA_CATALOG_ACCESS_DENIED",error)
                }
                // Only connection failures can use the already verified, exact-scope one-day cache.
                if (error is IOException && !error.message.orEmpty().startsWith("MEDIA_CATALOG_HTTP_") &&
                    !error.message.orEmpty().startsWith("CAPABILITIES_HTTP_")) {
                    cached(scope)?.let { return@withLock it.catalog }
                }
                throw error
            }
        }
    }
    suspend fun fetch(conversationId: String,catalog: ChatV2MediaCatalogResponse,
        item: ChatV2MediaCatalogItemDto): File = withContext(Dispatchers.IO) {
        val scope = scope(conversationId)
        lock(scope).withLock {
            checkScope(scope)
            ChatMediaCatalogPolicy.validate(catalog)
            require(ChatMediaCatalogPolicy.contains(catalog,item)) { "MEDIA_CATALOG_ITEM_INVALID" }
            val verified = cached(scope)?.catalog ?: error("MEDIA_CATALOG_RELOAD_REQUIRED")
            check(verified == catalog) { "MEDIA_CATALOG_VERSION_CHANGED" }
            val file = File(scope.directory,filename(catalog.version,item))
            if (validFile(file,item)) { checkScope(scope); return@withLock file }
            file.delete()
            val response = api.getChatMediaCatalogContent(conversationId,catalog.version,item.id)
            try { checkScope(scope) } catch (error: Exception) {
                response.body()?.close(); response.errorBody()?.close(); throw error
            }
            if (response.code() in setOf(401,403)) {
                response.body()?.close(); response.errorBody()?.close()
                purge(scope); throw IOException("MEDIA_CATALOG_ACCESS_DENIED")
            }
            val body = response.body()?.takeIf { response.isSuccessful } ?: throw responseError(response)
            val temp = File(scope.directory,UUID.randomUUID().toString()+".part")
            try {
                body.use {
                    val declared = it.contentLength()
                    require(declared < 0 || declared == item.sizeBytes) { "MEDIA_CATALOG_SIZE_INVALID" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    it.byteStream().use { input -> FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive(); checkScope(scope)
                            val count = input.read(buffer); if (count < 0) break
                            size += count
                            require(size <= item.sizeBytes && size <= ChatAttachmentPolicy.MEDIA_LIMIT) { "MEDIA_CATALOG_SIZE_INVALID" }
                            digest.update(buffer,0,count); output.write(buffer,0,count)
                        }
                        output.fd.sync()
                    } }
                    check(size == item.sizeBytes && hex(digest.digest()).equals(item.sha256,true)) { "MEDIA_CATALOG_CHECKSUM_FAILED" }
                }
                check(validDimensions(temp,item)) { "MEDIA_CATALOG_DIMENSIONS_INVALID" }
                checkScope(scope)
                check(temp.renameTo(file)) { "MEDIA_CATALOG_CACHE_UNAVAILABLE" }
                file
            } finally { temp.delete() }
        }
    }
    private suspend fun validFile(file: File,item: ChatV2MediaCatalogItemDto): Boolean {
        if (!file.isFile || file.length() != item.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer,0,count)
            }
        }
        return hex(digest.digest()).equals(item.sha256,true) && validDimensions(file,item)
    }
    private fun validDimensions(file: File,item: ChatV2MediaCatalogItemDto): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.absolutePath,options)
        return options.outWidth == item.width && options.outHeight == item.height && options.outMimeType == item.mimeType
    }
    private fun filename(version: Int,item: ChatV2MediaCatalogItemDto) =
        "$version-${item.sha256.lowercase()}." + if (item.type=="GIF") "gif" else "png"
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun responseError(response: Response<*>): IOException {
        response.errorBody()?.close()
        return IOException("MEDIA_CATALOG_HTTP_${response.code()}")
    }
}
