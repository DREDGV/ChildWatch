package ru.childwatch.shared.chat

import org.junit.Assert.*
import org.junit.Test

class ChatMediaCatalogProtocolTest {
    private val sticker = ChatV2MediaCatalogItemDto("happy", "Happy", "STICKER", "image/png", 100,
        "a".repeat(64), "happy.png", 256, 256)
    private val catalog = ChatV2MediaCatalogResponse(true, 1, listOf(sticker))
    @Test fun catalogBindsTheExactVersionedResource() {
        ChatAttachmentPolicy.validate("STICKER", 10L*1024*1024, "image/png")
        assertTrue(runCatching { ChatAttachmentPolicy.validate("STICKER", 100, "image/jpeg") }.isFailure)
        ChatMediaCatalogPolicy.validate(catalog)
        assertTrue(ChatMediaCatalogPolicy.contains(catalog, sticker))
        assertFalse(ChatMediaCatalogPolicy.contains(catalog, sticker.copy(sha256="b".repeat(64))))
        ChatMediaCatalogPolicy.validate(catalog.copy(items=listOf(sticker.copy(type="GIF",mimeType="image/gif",filename="happy.gif"))))
    }
    @Test fun unsafePathsDuplicateIdsAndDecodeDimensionsAreRejected() {
        val invalid = listOf(catalog.copy(version=0), catalog.copy(items=null), catalog.copy(items=listOf(sticker,sticker)),
            catalog.copy(items=(1..65).map { sticker.copy(id="item-$it") }),
            catalog.copy(items=listOf(sticker.copy(id="../other"))), catalog.copy(items=listOf(sticker.copy(filename="../happy.png"))),
            catalog.copy(items=listOf(sticker.copy(label="hidden\ncontrol"))),
            catalog.copy(items=listOf(sticker.copy(width=100000))), catalog.copy(items=listOf(sticker.copy(sha256="invalid"))),
            catalog.copy(items=listOf(sticker.copy(type="STICKER",mimeType="image/jpeg"))),
            catalog.copy(items=listOf(sticker.copy(sizeBytes=10L*1024*1024+1))))
        invalid.forEach { assertTrue(runCatching { ChatMediaCatalogPolicy.validate(it) }.isFailure) }
    }
    @Test fun cacheNeverSurvivesClockReversalOrTheOneDayBoundary() {
        assertTrue(ChatMediaCatalogPolicy.canUseCache(1, 1+ChatMediaCatalogPolicy.MAX_CACHE_AGE_MS))
        assertFalse(ChatMediaCatalogPolicy.canUseCache(1, 2+ChatMediaCatalogPolicy.MAX_CACHE_AGE_MS))
        assertFalse(ChatMediaCatalogPolicy.canUseCache(20,19))
    }
}
