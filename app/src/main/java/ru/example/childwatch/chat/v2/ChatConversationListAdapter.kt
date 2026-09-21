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
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatConversationListAdapter(
    private val onClick: (Conversation) -> Unit,
    /** Long press opens rename and remove; null keeps the row read-only. */
    private val onEdit: ((Conversation) -> Unit)? = null
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
            // A group is not a person: use the picture the conversation itself
            // carries. A direct chat has no shared picture, so it borrows the
            // peer's. Only a genuinely pictureless conversation falls back to the
            // letter, which FamilyAvatarRenderer draws from the last value.
            val peer = item.members.firstOrNull { !it.isLocalUser }
            val avatarKey = if (item.type == ConversationType.FAMILY) {
                item.avatarKey
            } else {
                item.avatarKey ?: peer?.avatarKey
            }
            FamilyAvatarRenderer.bind(
                avatarImage,
                avatarKey,
                peer?.displayName ?: item.title
            )
            timeText.text = item.updatedAt.takeIf { it > 0 }?.let {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
            }.orEmpty()
            val unread = item.unreadCount
            unreadText.visibility = if (unread > 0) View.VISIBLE else View.GONE
            unreadText.text = if (unread > 99) "99+" else unread.toString()
            root.setOnClickListener { onClick(item) }
            // Long press is the standard way to reach rename and delete.
            root.setOnLongClickListener {
                onEdit?.invoke(item)
                onEdit != null
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem.conversationId == newItem.conversationId

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem == newItem
    }
}
