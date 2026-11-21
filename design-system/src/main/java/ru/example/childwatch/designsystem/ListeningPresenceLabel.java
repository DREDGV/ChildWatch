package ru.example.childwatch.designsystem;

import android.content.Context;
import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ListeningPresenceLabel {
    private ListeningPresenceLabel() {}

    public static String text(Context context, JSONObject presence) {
        if (presence == null) return context.getString(R.string.cw_listening_unknown);
        switch (presence.optString("state")) {
            case "IDLE": return context.getString(R.string.cw_listening_idle);
            case "WAITING": return context.getString(R.string.cw_listening_waiting);
            case "ACTIVE":
                LinkedHashSet<String> names = new LinkedHashSet<>();
                JSONArray values = presence.optJSONArray("names");
                if (values != null) for (int index = 0; index < values.length(); index++) {
                    String name = values.optString(index, "").trim();
                    if (!name.isEmpty()) names.add(name);
                }
                if (names.isEmpty()) return context.getString(R.string.cw_listening_active_unknown);
                if (presence.optBoolean("unknownListener")) names.add(context.getString(R.string.cw_listening_unknown_person));
                return context.getString(R.string.cw_listening_active, String.join(", ", names));
            default: return context.getString(R.string.cw_listening_unknown);
        }
    }
}
