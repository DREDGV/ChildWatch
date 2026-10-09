package ru.example.childwatch.designsystem;

import java.util.Locale;

/** Picker metadata is advisory; the same byte limit must also be enforced while copying. */
public final class ChatAttachmentInputPolicy {
    public enum Mode { IMAGE, FILE }
    public static final long IMAGE_LIMIT_BYTES = 10L * 1024 * 1024;
    public static final long FILE_LIMIT_BYTES = 25L * 1024 * 1024;
    private ChatAttachmentInputPolicy() { }

    public static long limitBytes(Mode mode) {
        if (mode == null) throw new IllegalArgumentException("Attachment mode missing");
        return mode == Mode.IMAGE ? IMAGE_LIMIT_BYTES : FILE_LIMIT_BYTES;
    }

    public static String mimeType(String mime) {
        String normalized = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        int parameters = normalized.indexOf(';');
        if (parameters >= 0) normalized = normalized.substring(0, parameters).trim();
        return normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
            ? normalized : "application/octet-stream";
    }

    public static void validate(Mode mode, String mime, long declaredBytes) {
        long limit = limitBytes(mode);
        if (declaredBytes == 0) throw new IllegalArgumentException("EMPTY");
        if (declaredBytes > limit) throw new IllegalArgumentException("TOO_LARGE");
        String type = mimeType(mime);
        if (mode == Mode.IMAGE && !(type.equals("image/jpeg") || type.equals("image/png")
            || type.equals("image/webp") || type.equals("image/gif"))) {
            throw new IllegalArgumentException("UNSUPPORTED_IMAGE");
        }
    }

    /** Display name only. Never use provider-supplied names as a local path. */
    public static String displayName(String name) {
        if (name == null) return "attachment";
        String result = name.replaceAll("[\\\\/\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", "_").trim();
        if (result.isEmpty() || result.equals(".") || result.equals("..")) return "attachment";
        return result.length() > 160 ? result.substring(0, 160) : result;
    }
}
