package ru.example.childwatch.remote

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.example.childwatch.R

/**
 * Adapter for displaying remote camera photos in a grid.
 */
class RemotePhotoAdapter(
    private val onPhotoSave: ((RemotePhotoItem) -> Unit)? = null,
    private val onPhotoShare: ((RemotePhotoItem) -> Unit)? = null
) : ListAdapter<RemotePhotoItem, RemotePhotoAdapter.PhotoViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_photo, parent, false)
        return PhotoViewHolder(view, onPhotoSave, onPhotoShare)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PhotoViewHolder(
        itemView: View,
        private val onPhotoSave: ((RemotePhotoItem) -> Unit)?,
        private val onPhotoShare: ((RemotePhotoItem) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val photoPreview: ImageView = itemView.findViewById(R.id.photoPreview)
        private val photoName: TextView = itemView.findViewById(R.id.photoName)
        private val photoMeta: TextView = itemView.findViewById(R.id.photoMeta)

        fun bind(item: RemotePhotoItem) {
            photoName.text = item.displayName
            photoMeta.text = item.metaInfo

            Glide.with(photoPreview)
                .load(item.previewUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder)
                .into(photoPreview)

            itemView.setOnClickListener {
                showPhotoContextMenu(itemView.context, item, onPhotoSave, onPhotoShare)
            }

            itemView.setOnLongClickListener {
                openInBrowser(itemView.context, item.fullImageUrl)
                true
            }
        }

        private fun showPhotoContextMenu(
            context: android.content.Context,
            item: RemotePhotoItem,
            onSave: ((RemotePhotoItem) -> Unit)?,
            onShare: ((RemotePhotoItem) -> Unit)?
        ) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.remote_photo_menu_title)
                .setItems(
                    arrayOf(
                        context.getString(R.string.remote_photo_menu_open),
                        context.getString(R.string.remote_photo_menu_save),
                        context.getString(R.string.remote_photo_menu_share)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> openInBrowser(context, item.fullImageUrl)
                        1 -> onSave?.invoke(item)
                        2 -> onShare?.invoke(item)
                    }
                }
                .show()
        }

        private fun openInBrowser(context: android.content.Context, url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("RemotePhotoAdapter", "Unable to open photo", e)
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

data class RemotePhotoItem(
    val id: Long,
    val displayName: String,
    val metaInfo: String,
    val previewUrl: String,
    val fullImageUrl: String
)
