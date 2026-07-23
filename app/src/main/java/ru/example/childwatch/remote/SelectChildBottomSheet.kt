package ru.example.childwatch.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import ru.example.childwatch.R
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.profile.ParentLinkedChildOption

class SelectChildBottomSheet : BottomSheetDialogFragment() {

    var onChildSelected: ((ParentLinkedChildOption) -> Unit)? = null
    var children: List<ParentLinkedChildOption> = emptyList()
    var currentDeviceId: String? = null

    private lateinit var adapter: ChildSelectorAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_select_child, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dismiss() }

        adapter = ChildSelectorAdapter(currentDeviceId) { option ->
            onChildSelected?.invoke(option)
            dismiss()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvChildren)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        adapter.submitList(children)
    }

    private class ChildSelectorAdapter(
        private val currentDeviceId: String?,
        private val onSelect: (ParentLinkedChildOption) -> Unit
    ) : ListAdapter<ParentLinkedChildOption, ChildSelectorAdapter.ChildViewHolder>(ChildDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChildViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_child_selector, parent, false)
            return ChildViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChildViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ChildViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card: MaterialCardView = itemView.findViewById(R.id.cardChild)
            private val avatar: ImageView = itemView.findViewById(R.id.imgAvatar)
            private val name: TextView = itemView.findViewById(R.id.tvChildName)
            private val status: TextView = itemView.findViewById(R.id.tvChildStatus)
            private val check: ImageView = itemView.findViewById(R.id.imgSelected)

            fun bind(option: ParentLinkedChildOption) {
                name.text = option.displayName.trim().ifBlank { "Ребёнок" }
                status.text = option.deviceDisplayName?.trim().orEmpty()
                FamilyAvatarRenderer.bind(avatar, option.avatarKey)

                val isSelected = option.deviceId == currentDeviceId
                check.visibility = if (isSelected) View.VISIBLE else View.GONE
                card.isChecked = isSelected

                card.strokeColor = if (isSelected) {
                    itemView.context.getColor(R.color.cw_color_primary)
                } else {
                    itemView.context.getColor(R.color.cw_color_outline_variant)
                }

                itemView.setOnClickListener { onSelect(option) }
            }
        }

        companion object ChildDiff : DiffUtil.ItemCallback<ParentLinkedChildOption>() {
            override fun areItemsTheSame(
                oldItem: ParentLinkedChildOption,
                newItem: ParentLinkedChildOption
            ) = oldItem.deviceId == newItem.deviceId

            override fun areContentsTheSame(
                oldItem: ParentLinkedChildOption,
                newItem: ParentLinkedChildOption
            ) = oldItem == newItem
        }
    }
}
