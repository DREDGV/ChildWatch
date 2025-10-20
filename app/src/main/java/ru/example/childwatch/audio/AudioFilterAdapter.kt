package ru.example.childwatch.audio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import ru.example.childwatch.R

class AudioFilterAdapter(
    private val items: List<AudioFilterItem>,
    private var selectedMode: AudioQualityMode,
    private val onFilterSelected: (AudioQualityMode) -> Unit
) : RecyclerView.Adapter<AudioFilterAdapter.FilterViewHolder>() {

    inner class FilterViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.filterCard)
        val emoji: TextView = view.findViewById(R.id.filterEmoji)
        val title: TextView = view.findViewById(R.id.filterTitle)
        val description: TextView = view.findViewById(R.id.filterDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_filter, parent, false)
        // Добавляем id для MaterialCardView
        view.findViewById<MaterialCardView>(R.id.filterCard)?.let {}
        return FilterViewHolder(view)
    }

    override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
        val item = items[position]
        holder.emoji.text = item.emoji
        holder.title.text = item.title
        holder.description.text = item.description
        // Выделение выбранного фильтра
        holder.card.strokeColor = if (item.mode == selectedMode)
            holder.card.context.getColor(R.color.md_theme_primary)
        else
            holder.card.context.getColor(R.color.md_theme_outline)
        holder.card.setOnClickListener {
            if (selectedMode != item.mode) {
                selectedMode = item.mode
                onFilterSelected(item.mode)
                notifyDataSetChanged()
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun setSelectedMode(mode: AudioQualityMode) {
        selectedMode = mode
        notifyDataSetChanged()
    }
}
