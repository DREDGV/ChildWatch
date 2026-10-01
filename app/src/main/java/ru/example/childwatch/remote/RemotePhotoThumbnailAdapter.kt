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
    /**
     * Supplies the token the server demands for a picture.
     *
     * Without it every thumbnail came back 401 and the grid showed placeholders
     * although the server held the photographs.
     */
    private val tokenProvider: () -> String? = { null },
    private val onPhotoClick: ((RemotePhotoItem) -> Unit)? = null,
    private val onPhotoActions: ((RemotePhotoItem) -> Unit)? = null,
    private val heightDp: Int = 72
) : ListAdapter<RemotePhotoItem, RemotePhotoThumbnailAdapter.ThumbnailViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo_thumbnail_horizontal, parent, false)
        view.layoutParams.height = (heightDp * parent.resources.displayMetrics.density).toInt()
        return ThumbnailViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ThumbnailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgThumbnail: ImageView = itemView.findViewById(R.id.imgThumbnail)

        fun bind(item: RemotePhotoItem) {
            val date = java.text.SimpleDateFormat("dd MMM · HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))
            itemView.findViewById<android.widget.TextView>(R.id.tvPhotoDate).text = date
            itemView.contentDescription = itemView.context.getString(R.string.remote_photo_gallery_item_description, date)
            itemView.findViewById<View>(R.id.btnPhotoActions).apply {
                visibility = if (heightDp >= 88 && onPhotoActions != null) View.VISIBLE else View.GONE
                contentDescription = context.getString(R.string.remote_photo_gallery_actions_description, date)
                setOnClickListener { onPhotoActions?.invoke(item) }
            }
            itemView.setOnLongClickListener { onPhotoActions?.invoke(item); onPhotoActions != null }
            Glide.with(imgThumbnail)
                .load(AuthenticatedMedia.url(item.previewUrl, tokenProvider))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder)
                .centerCrop()
                .into(imgThumbnail)

            itemView.setOnClickListener { onPhotoClick?.invoke(item) }
        }
    }

    override fun onViewRecycled(holder: ThumbnailViewHolder) {
        Glide.with(holder.itemView).clear(holder.itemView.findViewById<ImageView>(R.id.imgThumbnail))
        super.onViewRecycled(holder)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<RemotePhotoItem>() {
        override fun areItemsTheSame(oldItem: RemotePhotoItem, newItem: RemotePhotoItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RemotePhotoItem, newItem: RemotePhotoItem) =
            oldItem == newItem
    }
}
