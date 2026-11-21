package ru.example.childwatch.designsystem;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import org.json.JSONArray;

/** A local composer draft for one identity and conversation. */
public final class ChatTextDraft {
    private final SharedPreferences storage;
    private final String key;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable save = this::flush;
    private String pending;

    public ChatTextDraft(Context context, String scope, String conversationId) {
        storage = context.getSharedPreferences("chat_text_drafts", Context.MODE_PRIVATE);
        key = storageKey(scope, conversationId);
    }

    private static String storageKey(String scope, String conversationId) {
        return new JSONArray().put(scope).put(conversationId).toString();
    }

    public static String peek(Context context, String scope, String conversationId) {
        return context.getSharedPreferences("chat_text_drafts", Context.MODE_PRIVATE)
                .getString(storageKey(scope, conversationId), "");
    }

    public void restore(EditText input) {
        String text = storage.getString(key, "");
        if (input.length() == 0 && !text.isEmpty()) {
            input.setText(text);
            input.setSelection(input.length());
        }
    }

    public void changed(String text) {
        handler.removeCallbacks(save);
        pending = text;
        if (text.isEmpty()) flush();
        else handler.postDelayed(save, 350);
    }

    public void flush() {
        handler.removeCallbacks(save);
        if (pending == null) return;
        SharedPreferences.Editor edit = storage.edit();
        if (pending.isEmpty()) edit.remove(key);
        else edit.putString(key, pending);
        edit.apply();
        pending = null;
    }

    public void discard() {
        changed("");
    }
}
