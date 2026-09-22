package ru.example.childwatch.update

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import ru.example.childwatch.R

/**
 * The quiet notice that says a new version exists.
 *
 * It is a card among the cards the screen already has, not a dialog: a dialog
 * would cover the monitoring controls and demand an answer, and somebody who
 * opened the application to look at the map would have to dismiss it first. The
 * notice can be closed, and closing it lasts until a newer release is published —
 * a version that cannot be offered again is a version nobody can install.
 *
 * The whole view is built here rather than in a layout file so that both
 * applications get exactly the same notice from exactly the same code, and so that
 * every word in it is a string resource.
 */
class UpdateNoticeView(private val context: Context) {

    val view: MaterialCardView = MaterialCardView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(16)
        }
        setCardBackgroundColor(color(R.color.cw_color_info_container))
        strokeWidth = 0
        radius = dp(16).toFloat()
        visibility = View.GONE
    }

    private val title: TextView = TextView(context).apply {
        text = context.getString(R.string.update_notice_title)
        setTextColor(color(R.color.cw_color_on_info_container))
        typeface = Typeface.DEFAULT_BOLD
        textSize = 16f
    }

    private val message: TextView = TextView(context).apply {
        setTextColor(color(R.color.cw_color_on_info_container))
        textSize = 14f
        setPadding(0, dp(4), 0, 0)
    }

    private val updateButton: MaterialButton = MaterialButton(context).apply {
        text = context.getString(R.string.update_notice_action)
        setOnClickListener(null)
    }

    private val dismissButton: MaterialButton = MaterialButton(context).apply {
        text = context.getString(R.string.update_notice_dismiss)
        minWidth = 0
        minimumWidth = 0
    }

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        column.addView(title)
        column.addView(message)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(
            updateButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        row.addView(
            dismissButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        )
        column.addView(row)

        view.addView(column)
    }

    /**
     * Shows the notice for [release].
     *
     * The message names the version being offered, so a person can compare it with
     * the line at the bottom of the screen instead of having to trust a number that
     * is not shown anywhere.
     */
    fun show(release: UpdateRelease, onUpdate: () -> Unit, onDismiss: () -> Unit) {
        message.text = if (release.versionName.isNotBlank()) {
            context.getString(R.string.update_notice_message, release.versionName)
        } else {
            context.getString(R.string.update_notice_message_plain)
        }
        updateButton.setOnClickListener { onUpdate() }
        dismissButton.setOnClickListener { onDismiss() }
        view.visibility = View.VISIBLE
    }

    /** Takes the notice off the screen, leaving the rest of the layout untouched. */
    fun hide() {
        view.visibility = View.GONE
    }

    /** True while the notice is on screen. */
    fun isShowing(): Boolean = view.visibility == View.VISIBLE

    /** Blocks the button while a download is already running. */
    fun setUpdating(updating: Boolean) {
        updateButton.isEnabled = !updating
        dismissButton.isEnabled = !updating
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun color(resourceId: Int): Int = ContextCompat.getColor(context, resourceId)
}
