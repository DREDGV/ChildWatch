package ru.childwatch.shared.chat
import org.junit.Assert.*
import org.junit.Test

class ChatAttachmentProtocolTest {
    @Test fun limitsAndFormatsAreEnforcedBeforeNetwork() {
        ChatAttachmentPolicy.validate("FILE",25L*1024*1024,"application/pdf")
        ChatAttachmentPolicy.validate("IMAGE",10L*1024*1024,"image/jpeg")
        listOf<Pair<String,()->Unit>>(
            "file overflow" to {ChatAttachmentPolicy.validate("FILE",25L*1024*1024+1,"application/pdf")},
            "image overflow" to {ChatAttachmentPolicy.validate("IMAGE",10L*1024*1024+1,"image/jpeg")},
            "empty" to {ChatAttachmentPolicy.validate("FILE",0,"application/pdf")},
            "mislabeled gif" to {ChatAttachmentPolicy.validate("GIF",1,"image/jpeg")},
            "html image" to {ChatAttachmentPolicy.validate("IMAGE",1,"text/html")},
            "voice duration" to {ChatAttachmentPolicy.validate("VOICE",1,"audio/mp4",180001)},
            "unknown type" to {ChatAttachmentPolicy.validate("UNKNOWN",1,"application/pdf")}
        ).forEach { (name,block)-> assertThrows(name,IllegalArgumentException::class.java) {block()} }
    }
    @Test fun serverMetadataMustBindExactBytesAndType() {
        val hash="a".repeat(64)
        val dto=ChatV2AttachmentDto("id","doc.pdf","application/octet-stream",100,hash,"FILE")
        assertTrue(ChatAttachmentPolicy.verifyMetadata("FILE",100,hash,dto))
        assertFalse(ChatAttachmentPolicy.verifyMetadata("IMAGE",100,hash,dto))
        assertFalse(ChatAttachmentPolicy.verifyMetadata("FILE",101,hash,dto))
        assertFalse(ChatAttachmentPolicy.verifyMetadata("FILE",100,"b".repeat(64),dto))
        assertFalse(ChatAttachmentPolicy.verifyMetadata("FILE",100,hash,dto.copy(attachmentId="")))
    }
    @Test fun nullMediaFieldsFromOldGsonServerBecomeText() {
        val dto=ChatV2MessageDto("server","client","conversation",1,text="old text",clientSentAt=1,
            serverCreatedAt=2,messageType=null,attachments=null)
        val model=dto.toDomain()
        assertEquals("TEXT",model.messageType); assertTrue(model.attachments.isEmpty())
    }
    @Test fun withdrawnMessageNeverCarriesAttachmentForDisplay() {
        val attachment=ChatV2AttachmentDto("id","doc.pdf","application/pdf",100,"a".repeat(64),"FILE")
        val dto=ChatV2MessageDto("server","client","conversation",1,text="",clientSentAt=1,
            serverCreatedAt=2,deletedAt=3,messageType="FILE",attachments=listOf(attachment))
        assertTrue(dto.toDomain().attachments.isEmpty())
    }
    @Test fun messageRetryRetainsSingleIdAndReference() {
        val first=ChatV2SendMessageRequest("stable","",123,"IMAGE",listOf("attachment"))
        val retry=first.copy()
        assertEquals(first,retry); assertEquals("stable",retry.clientMessageId)
        assertEquals(listOf("attachment"),retry.attachmentIds)
        assertEquals("TEXT",ChatV2SendMessageRequest("text","hello",1).messageType)
    }
    @Test fun filenamesNeverBecomePaths() {
        assertEquals("doc.pdf",ChatAttachmentPolicy.filename("C:\\folder\\doc.pdf"))
        assertEquals("doc.pdf",ChatAttachmentPolicy.filename("../../doc.pdf"))
        assertEquals("badname",ChatAttachmentPolicy.filename("bad\nname"))
    }
    @Test fun offlineCapabilityCacheExpiresAndDoesNotSurviveClockReversal() {
        assertTrue(ChatMediaCapabilityPolicy.canUseCached(1000,1001))
        assertFalse(ChatMediaCapabilityPolicy.canUseCached(1000,0))
        assertFalse(ChatMediaCapabilityPolicy.canUseCached(1000,1000+24L*60*60*1000+1))
        assertFalse(ChatMediaCapabilityPolicy.canUseCached(0,1))
    }
    @Test fun automaticOldClientAttachmentCaptionIsHiddenForModernMediaOnly() {
        val attachment=ChatV2AttachmentDto("id","doc.pdf","application/pdf",100,"a".repeat(64),"FILE")
        val dto=ChatV2MessageDto("server","client","conversation",1,text="[Вложение: doc.pdf]",clientSentAt=1,
            serverCreatedAt=2,messageType="FILE",attachments=listOf(attachment),mediaFallback=true)
        assertEquals("",dto.toDomain().text)
        assertEquals("real caption",dto.copy(text="real caption",mediaFallback=false).toDomain().text)
        assertEquals("[Вложение: doc.pdf]",dto.copy(attachments=emptyList()).toDomain().text)
    }
}
