package ru.example.childwatch.remote

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.util.Log
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import ru.example.childwatch.R

/**
 * Adapter for displaying remote camera photos in a grid.
 */
class RemotePhotoAdapter : ListAdapter<RemotePhotoItem, RemotePhotoAdapter.PhotoViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val photoPreview: ImageView = itemView.findViewById(R.id.photoPreview)
        private val photoName: TextView = itemView.findViewById(R.id.photoName)
        private val photoMeta: TextView = itemView.findViewById(R.id.photoMeta)

        fun bind(item: RemotePhotoItem) {
            photoName.text = item.displayName
            photoMeta.text = item.metaInfo

            Glide.with(photoPreview)
                .load(item.previewUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder)
                .into(photoPreview)

            itemView.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.fullImageUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    itemView.context.startActivity(intent)
                } catch (e: Exception) {
                    // Surface the error via logcat; user stays on screen
                    Log.e("RemotePhotoAdapter", "Unable to open photo", e)
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<RemotePhotoItem>() {
        override fun areItemsTheSame(oldItem: RemotePhotoItem, newItem: RemotePhotoItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RemotePhotoItem, newItem: RemotePhotoItem): Boolean =
            oldItem == newItem
    }
}

/**
 * Model representing a single remote photo entry for the gallery.
 */
data class RemotePhotoItem(
    val id: Long,
    val displayName: String,
    val metaInfo: String,
    val previewUrl: String,
    val fullImageUrl: String
)
