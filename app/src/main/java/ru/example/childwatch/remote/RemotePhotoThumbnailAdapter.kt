package ru.example.childwatch.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import ru.example.childwatch.R

class RemotePhotoThumbnailAdapter(
    private val onPhotoClick: ((RemotePhotoItem) -> Unit)? = null
) : ListAdapter<RemotePhotoItem, RemotePhotoThumbnailAdapter.ThumbnailViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo_thumbnail_horizontal, parent, false)
        return ThumbnailViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ThumbnailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgThumbnail: ImageView = itemView.findViewById(R.id.imgThumbnail)

        fun bind(item: RemotePhotoItem) {
            Glide.with(imgThumbnail)
                .load(item.previewUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder)
                .centerCrop()
                .into(imgThumbnail)

            itemView.setOnClickListener { onPhotoClick?.invoke(item) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<RemotePhotoItem>() {
        override fun areItemsTheSame(oldItem: RemotePhotoItem, newItem: RemotePhotoItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RemotePhotoItem, newItem: RemotePhotoItem) =
            oldItem == newItem
    }
}
