package ru.example.childwatch.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.example.childwatch.R
import ru.example.childwatch.database.entity.Child
import java.text.SimpleDateFormat
import java.util.*

/**
 * Адаптер для отображения списка детских устройств
 */
class ChildrenAdapter(
    private val onChildClick: (Child) -> Unit,
    private val onChildEdit: (Child) -> Unit
) : ListAdapter<Child, ChildrenAdapter.ChildViewHolder>(ChildDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChildViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_child, parent, false)
        return ChildViewHolder(view, onChildClick, onChildEdit)
    }

    override fun onBindViewHolder(holder: ChildViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChildViewHolder(
        itemView: View,
        private val onChildClick: (Child) -> Unit,
        private val onChildEdit: (Child) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val childAvatar: ImageView = itemView.findViewById(R.id.childAvatar)
        private val childName: TextView = itemView.findViewById(R.id.childName)
        private val childDeviceId: TextView = itemView.findViewById(R.id.childDeviceId)
        private val lastSeenText: TextView = itemView.findViewById(R.id.lastSeenText)
        private val activeIndicator: View = itemView.findViewById(R.id.activeIndicator)
        private val editButton: ImageView = itemView.findViewById(R.id.editButton)

        private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("ru"))

        fun bind(child: Child) {
            // Установить имя
            childName.text = child.name

            // Установить Device ID (первые 8 символов)
            childDeviceId.text = if (child.deviceId.length > 8) {
                child.deviceId.substring(0, 8) + "..."
            } else {
                child.deviceId
            }

            // Установить последнюю активность
            if (child.lastSeenAt != null) {
                val lastSeenDate = Date(child.lastSeenAt)
                lastSeenText.text = "Активность: ${formatLastSeen(lastSeenDate)}"
            } else {
                lastSeenText.text = "Активность: неизвестно"
            }

            // Установить индикатор активности
            if (child.isActive && child.lastSeenAt != null) {
                val timeDiff = System.currentTimeMillis() - child.lastSeenAt
                // Если был онлайн в последние 5 минут - показываем зеленый индикатор
                if (timeDiff < 5 * 60 * 1000) {
                    activeIndicator.visibility = View.VISIBLE
                    activeIndicator.setBackgroundResource(android.R.drawable.presence_online)
                } else {
                    activeIndicator.visibility = View.INVISIBLE
                }
            } else {
                activeIndicator.visibility = View.INVISIBLE
            }

            // Установить аватар
            if (child.avatarUrl != null) {
                try {
                    childAvatar.setImageURI(Uri.parse(child.avatarUrl))
                } catch (e: Exception) {
                    childAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
                }
            } else {
                childAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
            }

            // Обработчик клика на карточку
            itemView.setOnClickListener {
                onChildClick(child)
            }

            // Обработчик долгого клика на карточку (альтернативный способ редактирования)
            itemView.setOnLongClickListener {
                onChildEdit(child)
                true
            }

            // Обработчик клика на кнопку редактирования
            editButton.setOnClickListener {
                onChildEdit(child)
            }
        }

        private fun formatLastSeen(date: Date): String {
            val now = System.currentTimeMillis()
            val diff = now - date.time

            return when {
                diff < 60 * 1000 -> "только что"
                diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} мин назад"
                diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)} ч назад"
                diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)} дн назад"
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
