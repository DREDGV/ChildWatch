package ru.childwatch.shared.chat

data class ChatV2MediaCatalogItemDto(
    val id: String,
    val label: String,
    val type: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val filename: String,
    val width: Int,
    val height: Int
)
data class ChatV2MediaCatalogResponse(
    val success: Boolean = false,
    val version: Int = 0,
    val items: List<ChatV2MediaCatalogItemDto>? = emptyList()
)

/** Strict metadata limits before using server-controlled IDs in paths or decoding images. */
object ChatMediaCatalogPolicy {
    const val MAX_ITEMS = 64
    const val MAX_DIMENSION = 1024
    const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000
    fun validate(catalog: ChatV2MediaCatalogResponse) {
        require(catalog.success && catalog.version > 0) { "MEDIA_CATALOG_INVALID" }
        val items = catalog.items ?: throw IllegalArgumentException("MEDIA_CATALOG_INVALID")
        require(items.size in 1..MAX_ITEMS && items.map { it.id }.distinct().size == items.size) { "MEDIA_CATALOG_INVALID" }
        items.forEach { item ->
            require(!item.id.isNullOrBlank() && item.id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))) { "MEDIA_CATALOG_ITEM_INVALID" }
            require(!item.label.isNullOrBlank() && item.label.length <= 80 && item.label.none { it < ' ' || it == '\u007f' }) { "MEDIA_CATALOG_LABEL_INVALID" }
            require(item.type in setOf("STICKER", "GIF")) { "MEDIA_CATALOG_TYPE_INVALID" }
            ChatAttachmentPolicy.validate(item.type, item.sizeBytes, item.mimeType)
            require(!item.sha256.isNullOrBlank() && item.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "MEDIA_CATALOG_HASH_INVALID" }
            val extension = if (item.type == "GIF") "gif" else "png"
            require(!item.filename.isNullOrBlank() && item.filename.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,95}\\.$extension"))) { "MEDIA_CATALOG_FILENAME_INVALID" }
            require(item.width in 1..MAX_DIMENSION && item.height in 1..MAX_DIMENSION) { "MEDIA_CATALOG_DIMENSIONS_INVALID" }
        }
    }
    fun canUseCache(capturedAt: Long, now: Long): Boolean =
        capturedAt > 0 && now >= capturedAt && now - capturedAt <= MAX_CACHE_AGE_MS
    fun contains(catalog: ChatV2MediaCatalogResponse, item: ChatV2MediaCatalogItemDto): Boolean =
        catalog.items?.any { it == item } == true
}
