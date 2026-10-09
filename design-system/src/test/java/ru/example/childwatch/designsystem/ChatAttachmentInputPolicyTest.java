package ru.example.childwatch.designsystem;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatAttachmentInputPolicyTest {
    @Test public void limitsDistinguishPhotoAndOriginalFile() {
        ChatAttachmentInputPolicy.validate(ChatAttachmentInputPolicy.Mode.IMAGE, "image/jpeg", 10L * 1024 * 1024);
        try {
            ChatAttachmentInputPolicy.validate(ChatAttachmentInputPolicy.Mode.IMAGE, "image/jpeg", 10L * 1024 * 1024 + 1);
            fail("Oversize image accepted");
        } catch (IllegalArgumentException expected) { assertEquals("TOO_LARGE", expected.getMessage()); }
        ChatAttachmentInputPolicy.validate(ChatAttachmentInputPolicy.Mode.FILE, "image/jpeg", 25L * 1024 * 1024);
    }
    @Test public void unknownProviderSizeRequiresStreamValidationButEmptyIsRejected() {
        ChatAttachmentInputPolicy.validate(ChatAttachmentInputPolicy.Mode.FILE, null, -1);
        try {
            ChatAttachmentInputPolicy.validate(ChatAttachmentInputPolicy.Mode.FILE, null, 0);
            fail("Empty document accepted");
        } catch (IllegalArgumentException expected) { assertEquals("EMPTY", expected.getMessage()); }
    }
    @Test public void arbitraryActiveContentCannotBeSentAsPreviewImage() {
        for (String type : new String[] { "image/svg+xml", "text/html", null }) {
            try {
                ChatAttachmentInputPolicy.validate(ChatAttachmentInputPolicy.Mode.IMAGE, type, 100);
                fail("Unsupported image accepted");
            } catch (IllegalArgumentException expected) { assertEquals("UNSUPPORTED_IMAGE", expected.getMessage()); }
        }
        ChatAttachmentInputPolicy.validate(ChatAttachmentInputPolicy.Mode.FILE, "image/svg+xml", 100);
    }
    @Test public void mimeNormalizationAndNamesDoNotTrustProviderMetadata() {
        assertEquals("image/jpeg", ChatAttachmentInputPolicy.mimeType(" IMAGE/JPEG; charset=binary "));
        assertEquals("application/octet-stream", ChatAttachmentInputPolicy.mimeType("image/jpeg\r\nX-Injected: yes"));
        assertEquals(".._private_secret.jpg", ChatAttachmentInputPolicy.displayName("../private/secret.jpg"));
        assertEquals("attachment", ChatAttachmentInputPolicy.displayName(".."));
        assertFalse(ChatAttachmentInputPolicy.displayName("a\u202Etxt.exe").contains("\u202E"));
    }
}
