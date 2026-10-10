package ru.example.childwatch.designsystem;

/** Bounds a GIF before a native decoder sees it; oversized media can still be downloaded as a file. */
public final class ChatGifPolicy {
    private ChatGifPolicy() { }
    public static boolean previewable(byte[] data) {
        if (data == null || data.length < 14 || data.length > 10 * 1024 * 1024) return false;
        if (data[0] != 'G' || data[1] != 'I' || data[2] != 'F' || data[3] != '8'
                || (data[4] != '7' && data[4] != '9') || data[5] != 'a') return false;
        int width = word(data, 6), height = word(data, 8);
        if (width < 1 || height < 1 || width > 1024 || height > 1024) return false;
        int position = 13, packed = data[10] & 255, frames = 0;
        boolean globalPalette = (packed & 128) != 0;
        if (globalPalette) position += 3 * (1 << ((packed & 7) + 1));
        if (position >= data.length) return false;
        while (position < data.length) {
            int marker = data[position++] & 255;
            if (marker == 0x3b) return frames > 0;
            if (marker == 0x21) {
                if (position >= data.length) return false;
                int label = data[position++] & 255;
                // The graphic control extension has a fixed four-byte body, unlike comment data.
                if (label == 0xf9) {
                    if (position + 6 > data.length || (data[position] & 255) != 4 || data[position + 5] != 0) return false;
                    position += 6;
                } else {
                    if ((label == 0xff && (position >= data.length || (data[position] & 255) != 11))
                            || (label == 0x01 && (position >= data.length || (data[position] & 255) != 12))) return false;
                    position = skipBlocks(data, position);
                }
            } else if (marker == 0x2c) {
                if (position + 9 > data.length) return false;
                int left = word(data, position), top = word(data, position + 2);
                int fw = word(data, position + 4), fh = word(data, position + 6);
                if (fw < 1 || fh < 1 || left + fw > width || top + fh > height) return false;
                if (++frames > 120 || (long) width * height * frames > 32L * 1024 * 1024) return false;
                packed = data[position + 8] & 255; position += 9;
                if ((packed & 128) != 0) position += 3 * (1 << ((packed & 7) + 1));
                else if (!globalPalette) return false;
                if (position >= data.length || (data[position] & 255) < 2 || (data[position] & 255) > 8) return false;
                position++;
                if (position >= data.length || data[position] == 0) return false;
                position = skipBlocks(data, position);
            } else return false;
            if (position < 0) return false;
        }
        return false;
    }
    private static int word(byte[] data, int position) { return (data[position] & 255) | (data[position + 1] & 255) << 8; }
    private static int skipBlocks(byte[] data, int position) {
        while (position < data.length) {
            int size = data[position++] & 255;
            if (size == 0) return position;
            if (position + size > data.length) return -1;
            position += size;
        }
        return -1;
    }
}
