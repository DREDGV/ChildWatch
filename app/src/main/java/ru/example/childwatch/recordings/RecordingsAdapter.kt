package ru.example.childwatch.recordings

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import ru.example.childwatch.R
import ru.example.childwatch.audio.RecordingMetadata
import ru.example.childwatch.databinding.ItemRecordingBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    private val onPlayClicked: (RecordingMetadata) -> Unit,
    private val onDeleteClicked: (RecordingMetadata) -> Unit
) : ListAdapter<RecordingMetadata, RecordingsAdapter.ViewHolder>(DiffCallback) {

    private var playingId: String? = null

    fun setPlayingId(id: String?) {
        playingId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemRecordingBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordingMetadata) {
            val context = binding.root.context
            val isPlaying = item.id == playingId
            val fileExists = File(item.filePath).exists()

            binding.titleText.text = item.fileName
            binding.infoText.text = buildInfoLine(context, item, fileExists)

            val playIcon = ContextCompat.getDrawable(
                context,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            binding.playPauseButton.apply {
                text = if (isPlaying) context.getString(R.string.recording_stop) else context.getString(R.string.recording_play)
                icon = playIcon
                isEnabled = fileExists
                setOnClickListener { onPlayClicked(item) }
            }

            binding.deleteButton.apply {
                icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_delete)
                setOnClickListener { onDeleteClicked(item) }
            }

            val strokeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
            binding.root.strokeWidth = if (isPlaying) binding.root.resources.getDimensionPixelSize(R.dimen.recording_playing_stroke) else 0
            binding.root.strokeColor = strokeColor
        }

        private fun buildInfoLine(context: Context, metadata: RecordingMetadata, fileExists: Boolean): String {
            val dateString = DATE_FORMAT.format(Date(metadata.createdAt))
            val durationString = formatDuration(metadata.durationMs)
            val sizeString = Formatter.formatFileSize(context, metadata.sizeBytes)
            val base = "$dateString • $durationString • $sizeString"
            return if (fileExists) base else "$base • файл отсутствует"
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        private val DiffCallback = object : DiffUtil.ItemCallback<RecordingMetadata>() {
            override fun areItemsTheSame(oldItem: RecordingMetadata, newItem: RecordingMetadata): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: RecordingMetadata, newItem: RecordingMetadata): Boolean {
                return oldItem == newItem
            }
        }
    }
}
