package ru.example.childwatch.designsystem;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatGifPolicyTest {
    private static byte[] gif(int width, int height, int frames) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] header = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        output.write(header, 0, header.length);
        word(output, width); word(output, height);
        output.write(0x80); output.write(0); output.write(0);
        output.write(new byte[] { 0, 0, 0, -1, -1, -1 }, 0, 6);
        for (int index = 0; index < frames; index++) {
            output.write(0x2c); word(output, 0); word(output, 0); word(output, 1); word(output, 1);
            output.write(0); output.write(2);
            // Valid two-bit LZW: clear, palette index zero, end of information.
            output.write(2); output.write(0x44); output.write(1); output.write(0);
        }
        output.write(0x3b);
        return output.toByteArray();
    }
    private static void word(ByteArrayOutputStream output, int value) {
        output.write(value & 255); output.write((value >>> 8) & 255);
    }

    @Test public void validStaticAndAnimatedFramesArePreviewable() {
        assertTrue(ChatGifPolicy.previewable(gif(1, 1, 1)));
        assertTrue(ChatGifPolicy.previewable(gif(256, 256, 12)));
        assertTrue(ChatGifPolicy.previewable(gif(1, 1, 120)));
    }

    @Test public void compressedSizeAndCanvasDimensionsAreIndependentLimits() {
        assertFalse(ChatGifPolicy.previewable(null));
        assertFalse(ChatGifPolicy.previewable(new byte[10 * 1024 * 1024 + 1]));
        assertFalse(ChatGifPolicy.previewable(gif(0, 1, 1)));
        assertFalse(ChatGifPolicy.previewable(gif(1025, 1, 1)));
        assertFalse(ChatGifPolicy.previewable(gif(1, 1025, 1)));
    }

    @Test public void frameCountAndDecodedCanvasBudgetRejectSmallCompressedBombs() {
        assertFalse(ChatGifPolicy.previewable(gif(1, 1, 121)));
        assertTrue(ChatGifPolicy.previewable(gif(1024, 1024, 32)));
        assertFalse(ChatGifPolicy.previewable(gif(1024, 1024, 33)));
    }

    @Test public void frameRectangleMustFitDeclaredCanvas() {
        byte[] outside = gif(1, 1, 1);
        outside[20] = 1; // left = one, width = one, canvas width = one.
        assertFalse(ChatGifPolicy.previewable(outside));
        byte[] zero = gif(1, 1, 1);
        zero[24] = 0;
        assertFalse(ChatGifPolicy.previewable(zero));
    }

    @Test public void truncatedPaletteFrameDataAndMissingTrailerAreRejected() {
        byte[] valid = gif(1, 1, 1);
        for (int length = 0; length < valid.length; length++) {
            assertFalse("Accepted truncated GIF at byte " + length,
                ChatGifPolicy.previewable(Arrays.copyOf(valid, length)));
        }
        byte[] shortBlock = valid.clone(); shortBlock[30] = 10;
        assertFalse(ChatGifPolicy.previewable(shortBlock));
        byte[] emptyBlock = valid.clone(); emptyBlock[30] = 0;
        assertFalse(ChatGifPolicy.previewable(emptyBlock));
        byte[] noPalette = valid.clone(); noPalette[10] = 0;
        assertFalse(ChatGifPolicy.previewable(noPalette));
        byte[] invalidCodeSize = valid.clone(); invalidCodeSize[29] = 9;
        assertFalse(ChatGifPolicy.previewable(invalidCodeSize));
    }

    @Test public void malformedFixedGraphicControlExtensionIsRejected() {
        byte[] valid = gif(1, 1, 1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(valid, 0, 19);
        byte[] control = { 0x21, (byte) 0xf9, 4, 0, 1, 0, 0, 0 };
        output.write(control, 0, control.length); output.write(valid, 19, valid.length - 19);
        byte[] withControl = output.toByteArray();
        assertTrue(ChatGifPolicy.previewable(withControl));
        withControl[21] = 3;
        assertFalse(ChatGifPolicy.previewable(withControl));
    }

    @Test public void bundledCatalogAnimationsMeetTheSameUserFileBounds() throws Exception {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("server/assets/chat-catalog/v1"))) {
            directory = directory.getParent();
        }
        assertNotNull("Repository catalog must be available to this source test", directory);
        for (String name : new String[] { "hello-wave.gif", "heart-pulse.gif" }) {
            assertTrue(name, ChatGifPolicy.previewable(Files.readAllBytes(directory.resolve("server/assets/chat-catalog/v1/" + name))));
        }
    }
}
