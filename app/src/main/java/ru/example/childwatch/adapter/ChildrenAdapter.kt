package ru.example.childwatch.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import ru.childwatch.shared.family.FamilyPresenceState
import ru.childwatch.shared.family.FamilyRole
import ru.example.childwatch.R
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.contacts.ContactRoles
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.profile.ParentLinkedChildOption
import ru.example.childwatch.profile.FamilyAvatarRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows a person first and keeps the technical device endpoint secondary.
 * The Room [Child] remains the compatibility item while canonical family
 * identity is supplied through [ParentLinkedChildOption].
 */
class ChildrenAdapter(
    private val onChildClick: (Child) -> Unit,
    private val onChildEdit: (Child) -> Unit,
    private val onChildAttention: (Child) -> Unit
) : ListAdapter<Child, ChildrenAdapter.ChildViewHolder>(ChildDiffCallback()) {

    private var presentationByDevice: Map<String, ParentLinkedChildOption> = emptyMap()
    private var selectedDeviceId: String? = null

    fun updatePresentation(
        options: List<ParentLinkedChildOption>,
        selectedDeviceId: String?
    ) {
        presentationByDevice = options.associateBy { it.deviceId }
        this.selectedDeviceId = selectedDeviceId?.trim()?.takeIf(String::isNotBlank)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChildViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_child, parent, false)
        return ChildViewHolder(view, onChildClick, onChildEdit, onChildAttention)
    }

    override fun onBindViewHolder(holder: ChildViewHolder, position: Int) {
        val child = getItem(position)
        holder.bind(
            child = child,
            option = presentationByDevice[child.deviceId],
            isSelected = child.deviceId == selectedDeviceId
        )
    }

    class ChildViewHolder(
        itemView: View,
        private val onChildClick: (Child) -> Unit,
        private val onChildEdit: (Child) -> Unit,
        private val onChildAttention: (Child) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val personCard: MaterialCardView = itemView.findViewById(R.id.personCard)
        private val childAvatar: ImageView = itemView.findViewById(R.id.childAvatar)
        private val childName: TextView = itemView.findViewById(R.id.childName)
        private val childDetails: TextView = itemView.findViewById(R.id.childDeviceId)
        private val lastSeenText: TextView = itemView.findViewById(R.id.lastSeenText)
        private val selectedIndicator: ImageView = itemView.findViewById(R.id.selectedIndicator)
        private val attentionButton: MaterialButton = itemView.findViewById(R.id.attentionButton)
        private val editButton: MaterialButton = itemView.findViewById(R.id.editButton)

        private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("ru"))

        fun bind(
            child: Child,
            option: ParentLinkedChildOption?,
            isSelected: Boolean
        ) {
            val context = itemView.context
            val displayName = option?.displayName?.trim()?.takeIf(String::isNotBlank)
                ?: child.name.trim().ifBlank { context.getString(R.string.chat_partner_child) }
            childName.text = displayName

            val roleLabel = roleLabel(option?.role, child.role)
            val deviceLabel = option?.deviceDisplayName?.trim()?.takeIf(String::isNotBlank)
                ?: child.alias?.trim()?.takeIf(String::isNotBlank)
            childDetails.text = if (deviceLabel == null) {
                context.getString(R.string.family_profile_role_only, roleLabel)
            } else {
                context.getString(R.string.family_profile_role_and_device, roleLabel, deviceLabel)
            }

            val lastSeenAt = option?.lastSeenAt ?: child.lastSeenAt
            val presence = resolvePresence(option?.presence, child, lastSeenAt)
            bindPresence(presence, lastSeenAt)
            bindAvatar(child, option)
            bindSelectedState(isSelected)

            personCard.contentDescription = buildString {
                append(displayName)
                append(". ")
                append(childDetails.text)
                append(". ")
                append(lastSeenText.text)
                if (isSelected) {
                    append(". ")
                    append(context.getString(R.string.family_profile_selected))
                }
            }

            personCard.setOnClickListener { onChildClick(child) }
            personCard.setOnLongClickListener {
                onChildEdit(child)
                true
            }
            editButton.contentDescription = context.getString(
                R.string.family_profile_edit_named,
                displayName
            )
            editButton.setOnClickListener { onChildEdit(child) }
            attentionButton.contentDescription = context.getString(
                R.string.family_profile_attention_named,
                displayName
            )
            attentionButton.setOnClickListener { onChildAttention(child) }
        }

        private fun bindSelectedState(isSelected: Boolean) {
            val context = itemView.context
            selectedIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            personCard.strokeWidth = context.resources.getDimensionPixelSize(
                if (isSelected) R.dimen.cw_stroke_selected else R.dimen.cw_stroke_thin
            )
            personCard.strokeColor = ContextCompat.getColor(
                context,
                if (isSelected) R.color.cw_color_primary else R.color.cw_color_outline_variant
            )
            personCard.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.cw_color_selected_surface else R.color.cw_color_surface
                )
            )
        }

        private fun bindPresence(presence: FamilyPresenceState, lastSeenAt: Long?) {
            val context = itemView.context
            when (presence) {
                FamilyPresenceState.ONLINE -> {
                    lastSeenText.setText(R.string.family_profile_presence_online)
                    lastSeenText.setTextAppearance(R.style.TextAppearance_ChildWatch_Status_Success)
                }
                FamilyPresenceState.RECENTLY_ACTIVE -> {
                    lastSeenText.text = lastSeenAt?.let {
                        context.getString(
                            R.string.family_profile_presence_recent
                        ) + ": " + formatLastSeen(Date(it))
                    } ?: context.getString(R.string.family_profile_presence_recent)
                    lastSeenText.setTextAppearance(R.style.TextAppearance_ChildWatch_Status_Info)
                }
                FamilyPresenceState.OFFLINE -> {
                    lastSeenText.text = lastSeenAt?.let {
                        context.getString(R.string.family_profile_presence_offline) + ": " +
                            formatLastSeen(Date(it))
                    } ?: context.getString(R.string.family_profile_presence_offline)
                    lastSeenText.setTextAppearance(R.style.TextAppearance_ChildWatch_Status)
                }
                FamilyPresenceState.UNKNOWN -> {
                    lastSeenText.setText(R.string.family_profile_presence_unknown)
                    lastSeenText.setTextAppearance(R.style.TextAppearance_ChildWatch_Status)
                }
            }
        }

        private fun bindAvatar(child: Child, option: ParentLinkedChildOption?) {
            val avatar = option?.avatarKey?.trim()?.takeIf(String::isNotBlank)
                ?: child.avatarUrl?.trim()?.takeIf(String::isNotBlank)
            FamilyAvatarRenderer.bind(
                view = childAvatar,
                avatarValue = avatar,
                fallbackRes = ContactIcons.resolve(option?.markerIconId ?: child.iconId, child.role)
            )
        }

        private fun resolvePresence(
            canonical: FamilyPresenceState?,
            child: Child,
            lastSeenAt: Long?
        ): FamilyPresenceState {
            if (canonical != null && canonical != FamilyPresenceState.UNKNOWN) return canonical
            if (lastSeenAt == null) return FamilyPresenceState.UNKNOWN
            val age = (System.currentTimeMillis() - lastSeenAt).coerceAtLeast(0L)
            return when {
                child.isActive && age <= 5 * 60 * 1000L -> FamilyPresenceState.ONLINE
                age <= 24 * 60 * 60 * 1000L -> FamilyPresenceState.RECENTLY_ACTIVE
                else -> FamilyPresenceState.OFFLINE
            }
        }

        private fun roleLabel(role: FamilyRole?, legacyRole: String): String {
            return when (role) {
                FamilyRole.PARENT -> "Родитель"
                FamilyRole.GUARDIAN -> "Родственник"
                FamilyRole.CHILD -> "Ребёнок"
                null -> ContactRoles.label(legacyRole).replace("Ребенок", "Ребёнок")
            }
        }

        private fun formatLastSeen(date: Date): String {
            val context = itemView.context
            val diff = (System.currentTimeMillis() - date.time).coerceAtLeast(0L)
            return when {
                diff < 60 * 1000L -> context.getString(R.string.family_profile_last_seen_now)
                diff < 60 * 60 * 1000L -> context.getString(
                    R.string.family_profile_last_seen_minutes,
                    diff / (60 * 1000L)
                )
                diff < 24 * 60 * 60 * 1000L -> context.getString(
                    R.string.family_profile_last_seen_hours,
                    diff / (60 * 60 * 1000L)
                )
                diff < 7 * 24 * 60 * 60 * 1000L -> context.getString(
                    R.string.family_profile_last_seen_days,
                    diff / (24 * 60 * 60 * 1000L)
                )
                else -> dateFormat.format(date)
            }
        }
    }

    class ChildDiffCallback : DiffUtil.ItemCallback<Child>() {
        override fun areItemsTheSame(oldItem: Child, newItem: Child): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Child, newItem: Child): Boolean {
            return oldItem == newItem
        }
    }
}
