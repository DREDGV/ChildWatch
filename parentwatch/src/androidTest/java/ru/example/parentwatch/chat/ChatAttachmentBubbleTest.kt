package ru.example.parentwatch.chat

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.childwatch.shared.chat.ChatV2AttachmentDto
import ru.example.parentwatch.R

/** Isolated bubble fixtures: no activity login, recording, command or network. */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentBubbleTest {
    @Test fun recycledAttachmentBubbleRestoresTextAndHidesWithdrawnFile() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ParentWatch)
            val parent = FrameLayout(context)
            var opened: String? = null
            val adapter = ChatAdapter("parent", "fixture-parent", onAttachmentOpen = { opened = it.id })
            val holder = adapter.onCreateViewHolder(parent, 2)
            val media = ChatMessage("fixture", "", "parent", "fixture-parent", timestamp = 1,
                attachments = listOf(ChatV2AttachmentDto("private-fixture", "family.pdf", "application/octet-stream", 12, "a".repeat(64), "FILE")))
            holder.bind(media)
            assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.attachmentCard).visibility)
            assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.messageText).visibility)
            holder.itemView.findViewById<View>(R.id.attachmentOpen).performClick()
            assertEquals("fixture", opened)
            holder.bind(media.copy(deletedAt = 2))
            assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.attachmentCard).visibility)
            assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.messageText).visibility)
            holder.bind(media.copy(text = "Обычное сообщение", attachments = emptyList()))
            assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.attachmentCard).visibility)
            assertEquals("Обычное сообщение", holder.itemView.findViewById<TextView>(R.id.messageText).text.toString())
        }
    }
}
