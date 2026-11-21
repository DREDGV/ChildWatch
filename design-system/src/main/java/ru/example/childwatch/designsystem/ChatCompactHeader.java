package ru.example.childwatch.designsystem;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ChatCompactHeader extends LinearLayout {
    private final TextView title;
    private final TextView subtitle;
    private final TextView notice;
    private final View identity;

    public ChatCompactHeader(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.cw_chat_compact_header, this, true);
        title = findViewById(R.id.chatHeaderTitle);
        subtitle = findViewById(R.id.chatHeaderSubtitle);
        notice = findViewById(R.id.chatConnectionNotice);
        identity = findViewById(R.id.chatHeaderIdentity);
    }

    public ImageView avatar() { return findViewById(R.id.chatHeaderAvatar); }

    public void showConversation(CharSequence name, CharSequence details) {
        title.setText(name);
        subtitle.setText(details);
        subtitle.setVisibility(details.length() == 0 ? GONE : VISIBLE);
        identity.setContentDescription(getContext().getString(R.string.chat_header_details, name)
                + (details.length() == 0 ? "" : ". " + details));
    }

    public void showConnectionNotice(CharSequence message) {
        if (!android.text.TextUtils.equals(notice.getText(), message)) notice.setText(message);
        notice.setVisibility(message.length() == 0 ? GONE : VISIBLE);
    }

    public void bindActions(Runnable back, Runnable openDetails, Runnable search) {
        findViewById(R.id.chatBack).setOnClickListener(view -> back.run());
        identity.setFocusable(true);
        identity.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        subtitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.setOnClickListener(view -> openDetails.run());
        findViewById(R.id.chatHeaderSearch).setOnClickListener(view -> search.run());
    }
}
