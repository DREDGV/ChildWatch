package ru.childwatch.shared.chat

import org.junit.Assert.*
import org.junit.Test

class ChatTranscriptionProtocolTest {
    @Test fun correctionsAreBoundedByUtf8BytesIncludingMultibyteText() {
        ChatTranscriptionPolicy.validateEditedText("")
        ChatTranscriptionPolicy.validateEditedText("a".repeat(16 * 1024))
        ChatTranscriptionPolicy.validateEditedText("я".repeat(8 * 1024))
        assertTrue(runCatching { ChatTranscriptionPolicy.validateEditedText("a".repeat(16 * 1024 + 1)) }.isFailure)
        assertTrue(runCatching { ChatTranscriptionPolicy.validateEditedText("я".repeat(8 * 1024 + 1)) }.isFailure)
    }
    @Test fun sourceRequiresTheSameBoundedVoiceRecording() {
        ChatTranscriptionPolicy.validateSource("VOICE", 100, "audio/mp4", 180_000, "a".repeat(64))
        for (invalid in listOf({ ChatTranscriptionPolicy.validateSource("FILE", 100, "audio/mp4", 100, "a".repeat(64)) },
            { ChatTranscriptionPolicy.validateSource("VOICE", 10L*1024*1024+1, "audio/mp4", 100, "a".repeat(64)) },
            { ChatTranscriptionPolicy.validateSource("VOICE", 100, "audio/mp4", 180_001, "a".repeat(64)) },
            { ChatTranscriptionPolicy.validateSource("VOICE", 100, "audio/mp4", 100, "wrong") })) {
            assertTrue(runCatching { invalid() }.isFailure)
        }
    }
    @Test fun jobsMustBelongToTheRequestAndHaveAnActualResult() {
        val job = ChatV2TranscriptionJobDto("job", "request", "SUCCEEDED", "hello", createdAt=10, updatedAt=20)
        assertTrue(ChatTranscriptionPolicy.validJob(job, "request"))
        assertFalse(ChatTranscriptionPolicy.validJob(job, "other"))
        assertFalse(ChatTranscriptionPolicy.validJob(job.copy(text=null), "request"))
        assertFalse(ChatTranscriptionPolicy.validJob(job.copy(state="MADE_UP"), "request"))
        assertFalse(ChatTranscriptionPolicy.validJob(job.copy(updatedAt=9), "request"))
    }
    @Test fun pollsKeepEditsIncludingAnIntentionallyEmptyCorrection() {
        assertEquals("corrected", ChatTranscriptionPolicy.editorText(true,"corrected","raw"))
        assertEquals("", ChatTranscriptionPolicy.editorText(true,"","raw"))
        assertEquals("raw", ChatTranscriptionPolicy.editorText(false,null,"raw"))
        assertFalse(ChatTranscriptionPolicy.terminal("LOCAL_PENDING"))
        assertTrue(ChatTranscriptionPolicy.terminal("CANCELLED"))
    }
}
