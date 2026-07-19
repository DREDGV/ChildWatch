package ru.example.childwatch.chat.v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationType
import ru.example.childwatch.databinding.ItemChatConversationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatConversationListAdapter(
    private val onClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ChatConversationListAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemChatConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(
        private val binding: ItemChatConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Conversation) = with(binding) {
            titleText.text = item.title
            previewText.text = item.lastMessagePreview?.takeIf { it.isNotBlank() }
                ?: if (item.type == ConversationType.FAMILY) {
                    "Общий чат семьи"
                } else {
                    "Личный диалог"
                }
            avatarText.text = if (item.type == ConversationType.FAMILY) {
                "С"
            } else {
                item.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "Л"
            }
            timeText.text = item.updatedAt.takeIf { it > 0 }?.let {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
            }.orEmpty()
            val unread = item.unreadCount
            unreadText.visibility = if (unread > 0) View.VISIBLE else View.GONE
            unreadText.text = if (unread > 99) "99+" else unread.toString()
            root.setOnClickListener { onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem.conversationId == newItem.conversationId

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem == newItem
    }
}
