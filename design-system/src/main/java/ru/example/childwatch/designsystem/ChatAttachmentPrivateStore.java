package ru.example.childwatch.designsystem;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Durable private draft copy. No network, permissions request, public URL or family fallback. */
public final class ChatAttachmentPrivateStore {
    public static final class Staged {
        public final File file;
        public final String displayName, mimeType, sha256;
        public final long sizeBytes;
        Staged(File file, String name, String mime, String sha256, long size) {
            this.file = file; this.displayName = name; this.mimeType = mime;
            this.sha256 = sha256; this.sizeBytes = size;
        }
    }
    private ChatAttachmentPrivateStore() { }

    /** Run on IO thread. Scope must include configured server, family, actor and conversation. */
    public static Staged copyDocument(Context context, Uri uri, String scope,
                                     ChatAttachmentInputPolicy.Mode mode, BooleanSupplier cancelled) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Copy requires IO thread");
        if (scope == null || scope.trim().isEmpty()) throw new IllegalArgumentException("Attachment scope missing");
        if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException("Content URI required");
        String name = "attachment";
        long declaredSize = -1;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex);
            }
        }
        String mime = ChatAttachmentInputPolicy.mimeType(context.getContentResolver().getType(uri));
        ChatAttachmentInputPolicy.validate(mode, mime, declaredSize);
        String scopeHash = hex(digest().digest(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        File directory = new File(context.getFilesDir(), "chat-attachments/" + scopeHash + "/drafts");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create draft directory");
        File target = new File(directory, UUID.randomUUID() + ".bin");
        boolean complete = false;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot open document");
            MessageDigest digest = digest();
            long size = 0;
            try (FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean()))
                        throw new IOException("CANCELLED");
                    size += count;
                    if (size > ChatAttachmentInputPolicy.limitBytes(mode)) throw new IOException("TOO_LARGE");
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
                if (size == 0) throw new IOException("EMPTY");
                if (cancelled != null && cancelled.getAsBoolean()) throw new IOException("CANCELLED");
                output.getFD().sync();
            }
            complete = true;
            return new Staged(target, ChatAttachmentInputPolicy.displayName(name), mime, hex(digest.digest()), size);
        } finally {
            if (!complete && target.exists() && !target.delete()) target.deleteOnExit();
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
