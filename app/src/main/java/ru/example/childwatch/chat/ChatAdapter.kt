package ru.example.childwatch.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.example.childwatch.R
import ru.example.childwatch.designsystem.ChatAnimatedMedia

class ChatAdapter(
    private val currentUser: String,
    private val currentUserDeviceId: String? = null,
    private val onRetryMessage: ((ChatMessage) -> Unit)? = null,
    /** Long press opens the edit and delete actions for that message. */
    private val onMessageLongPress: ((ChatMessage) -> Unit)? = null,
    private val onAttachmentOpen: ((ChatMessage) -> Unit)? = null,
    private val onAttachmentPreview: ((ChatMessage, android.widget.ImageView) -> Unit)? = null,
    private val onQuoteOpen: ((String) -> Unit)? = null
) : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private fun isOutgoing(message: ChatMessage) =
        message.isOutgoing(currentUser, currentUserDeviceId)

    fun submitMessages(
        messages: List<ChatMessage>,
        onCommitted: (() -> Unit)? = null
    ) {
        submitList(messages.toList()) {
            onCommitted?.invoke()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (isOutgoing(getItem(position))) VIEW_TYPE_OUTGOING else VIEW_TYPE_INCOMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_OUTGOING) {
            R.layout.item_message_outgoing
        } else {
            R.layout.item_message_incoming
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view, viewType == VIEW_TYPE_OUTGOING)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewDetachedFromWindow(holder: MessageViewHolder) {
        holder.stopMediaAnimation()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: MessageViewHolder) {
        holder.releaseMediaPreview()
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: MessageViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.resumeVisibleMediaAnimation()
    }

    inner class MessageViewHolder(itemView: View, private val isOutgoing: Boolean) :
        RecyclerView.ViewHolder(itemView) {
        private val dayDivider: TextView = itemView.findViewById(R.id.chatDayDivider)
        private val unreadDivider: View = itemView.findViewById(R.id.chatUnreadDivider)
        private val bubble: View = itemView.findViewById(R.id.chatMessageBubble)
        private val quoteBlock: View = itemView.findViewById(R.id.chatQuoteBlock)
        private val quoteText: TextView? = itemView.findViewById(R.id.chatQuoteText)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val senderText: TextView? = itemView.findViewById(R.id.senderText)
        private val retryButton: TextView? = itemView.findViewById(R.id.retryButton)

        private val attachmentCard: View? = itemView.findViewById(R.id.attachmentCard)
        private val attachmentImage: android.widget.ImageView? = itemView.findViewById(R.id.attachmentImage)
        private val attachmentName: TextView? = itemView.findViewById(R.id.attachmentName)
        private val attachmentMeta: TextView? = itemView.findViewById(R.id.attachmentMeta)
        private val attachmentOpen: TextView? = itemView.findViewById(R.id.attachmentOpen)

        fun stopMediaAnimation() {
            attachmentImage?.let { ChatAnimatedMedia.update(it, false) }
        }

        fun releaseMediaPreview() {
            attachmentImage?.let { ChatAnimatedMedia.clear(it); it.tag = null }
        }

        fun resumeVisibleMediaAnimation() {
            attachmentImage?.post {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION && itemView.isAttachedToWindow) {
                    attachmentImage?.let { ChatAnimatedMedia.update(it, true) }
                }
            }
        }

        fun bind(message: ChatMessage) {
            val withdrawn = message.deletedAt != null
            dayDivider.text = message.dayLabel
            dayDivider.visibility = if (message.dayLabel == null) View.GONE else View.VISIBLE
            unreadDivider.visibility = if (message.unreadBoundary) View.VISIBLE else View.GONE
            val tone = if (isOutgoing) R.drawable.cw_chat_bubble_outgoing else R.drawable.cw_chat_bubble_incoming
            val attachment = message.attachments.firstOrNull().takeUnless { withdrawn }
            val emotionOnly = attachment?.type in setOf("GIF", "STICKER") && message.text.isBlank() && message.quoteLabel == null
            bubble.setBackgroundResource(if (emotionOnly) 0 else tone)
            val space = itemView.resources.displayMetrics.density
            bubble.setPaddingRelative((12 * space).toInt(), (8 * space).toInt(), (12 * space).toInt(), (8 * space).toInt())
            itemView.setBackgroundColor(if (message.historyTarget && !message.unreadBoundary)
                itemView.context.getColor(R.color.cw_color_surface_variant) else android.graphics.Color.TRANSPARENT)
            val quote = message.quoteLabel.takeUnless { withdrawn }
            val target = message.replyToMessageId.takeUnless { withdrawn }
            quoteBlock.visibility = if (quote == null) View.GONE else View.VISIBLE
            quoteText?.text = quote?.let {
                android.text.SpannableString(it).apply {
                    val authorEnd = it.indexOf('\n')
                    if (authorEnd > 0) {
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, authorEnd, 0)
                        setSpan(android.text.style.ForegroundColorSpan(itemView.context.getColor(R.color.cw_color_primary)),
                            0, authorEnd, 0)
                    }
                }
            }
            val canOpen = quote != null && target != null && onQuoteOpen != null
            quoteBlock.setOnClickListener(if (canOpen) View.OnClickListener {
                target?.let { onQuoteOpen?.invoke(it) }
            } else null)
            quoteBlock.isClickable = canOpen
            quoteBlock.isFocusable = canOpen
            attachmentCard?.visibility = if (attachment == null) View.GONE else View.VISIBLE
            attachmentImage?.apply {
                // Cancel the old target while its attachment tag still identifies its ownership.
                ChatAnimatedMedia.clear(this)
                tag = attachment?.attachmentId
                visibility = if (attachment?.type in listOf("IMAGE", "GIF", "STICKER")) View.VISIBLE else View.GONE
                if (visibility == View.VISIBLE) onAttachmentPreview?.invoke(message, this)
            }
            val emotion = attachment?.type in listOf("GIF", "STICKER")
            val density = itemView.resources.displayMetrics.density
            attachmentCard?.apply {
                setBackgroundResource(if (emotion) 0 else R.drawable.cw_chat_quote_surface)
                val pad = if (emotion) 0 else (8 * density).toInt()
                setPadding(pad, pad, pad, pad)
                minimumWidth = if (emotion) 0 else (160 * density).toInt()
            }
            attachmentImage?.apply {
                scaleType = if (emotion) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = layoutParams.apply { height = ((if (emotion) 200 else 150) * density).toInt() }
                contentDescription = attachment?.filename ?: context.getString(R.string.chat_media_preview)
                setOnClickListener(if (attachment == null) null else View.OnClickListener { onAttachmentOpen?.invoke(message) })
            }
            attachmentName?.visibility = if (emotion) View.GONE else View.VISIBLE
            attachmentMeta?.visibility = if (emotion) View.GONE else View.VISIBLE
            attachmentOpen?.visibility = if (emotion) View.GONE else View.VISIBLE
            timestampText.setTextColor(itemView.context.getColor(R.color.cw_color_on_surface_variant))
            attachmentName?.text = attachment?.filename.orEmpty()
            attachmentMeta?.text = attachment?.let {
                val size = android.text.format.Formatter.formatShortFileSize(itemView.context, it.sizeBytes)
                if (it.type == "VOICE") {
                    val seconds = (it.durationMs ?: 0L) / 1000
                    java.lang.String.format(java.util.Locale.getDefault(), "%d:%02d · %s", seconds / 60, seconds % 60, size)
                } else size
            }.orEmpty()
            attachmentOpen?.apply {
                setText(if (attachment?.type == "VOICE") R.string.chat_voice_listen else if (attachment?.type in listOf("IMAGE", "GIF", "STICKER")) R.string.chat_media_open else R.string.chat_media_download)
                setOnClickListener(if (attachment == null) null else View.OnClickListener { onAttachmentOpen?.invoke(message) })
            }
            messageText.visibility = if (!withdrawn && message.text.isBlank() && attachment != null) View.GONE else View.VISIBLE
            // A withdrawn message keeps its place but shows no text, matching
            // what the other participants see.
            messageText.text = if (withdrawn) {
                itemView.context.getString(R.string.chat_message_deleted)
            } else {
                message.text
            }
            messageText.alpha = if (withdrawn) 0.6f else 1f
            // An edited message says so, the way messengers mark a rewrite.
            val timestamp = if (!withdrawn && message.editedAt != null) {
                itemView.context.getString(
                    R.string.chat_message_edited_time,
                    message.getFormattedTime()
                )
            } else {
                message.getFormattedTime()
            }

            // Long press is how a message is edited or deleted.
            itemView.setOnLongClickListener {
                onMessageLongPress?.invoke(message)
                onMessageLongPress != null
            }

            val senderName = message.getSenderName().trim()
            if (!isOutgoing && senderName.isNotEmpty()) {
                senderText?.apply {
                    visibility = View.VISIBLE
                    text = senderName
                }
            } else {
                senderText?.visibility = View.GONE
            }

            if (isOutgoing) {
                val context = itemView.context
                val statusName = context.getString(when (message.status) {
                    ChatMessage.MessageStatus.QUEUED -> R.string.chat_status_queued
                    ChatMessage.MessageStatus.SENDING -> R.string.chat_status_sending
                    ChatMessage.MessageStatus.SENT -> R.string.chat_status_sent
                    ChatMessage.MessageStatus.DELIVERED -> R.string.chat_status_delivered
                    ChatMessage.MessageStatus.READ -> R.string.chat_status_read
                    ChatMessage.MessageStatus.FAILED -> R.string.chat_status_failed
                })
                val statusLabel = when (message.status) {
                    ChatMessage.MessageStatus.SENT -> "✓"
                    ChatMessage.MessageStatus.DELIVERED, ChatMessage.MessageStatus.READ -> "✓✓"
                    else -> statusName
                }
                val label = context.getString(R.string.chat_message_meta, timestamp, statusLabel)
                val color = when (message.status) {
                    ChatMessage.MessageStatus.READ -> R.color.cw_color_primary
                    ChatMessage.MessageStatus.FAILED -> R.color.cw_color_error
                    else -> R.color.cw_color_on_surface_variant
                }
                timestampText.text = android.text.SpannableString(label).apply {
                    val statusStart = label.lastIndexOf(statusLabel).coerceAtLeast(0)
                    setSpan(android.text.style.ForegroundColorSpan(context.getColor(color)), statusStart, length, 0)
                }
                timestampText.contentDescription = context.getString(R.string.chat_message_meta, timestamp, statusName)
            } else {
                timestampText.text = timestamp
                timestampText.contentDescription = null
            }

            retryButton?.let { button ->
                if (isOutgoing && message.status == ChatMessage.MessageStatus.FAILED) {
                    button.visibility = View.VISIBLE
                    button.setOnClickListener {
                        onRetryMessage?.invoke(message)
                    }
                } else {
                    button.visibility = View.GONE
                    button.setOnClickListener(null)
                }
            }
        }
    }

    private class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val VIEW_TYPE_INCOMING = 1
        private const val VIEW_TYPE_OUTGOING = 2
    }
}
