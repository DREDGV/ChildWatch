package ru.example.childwatch.chat.v2

import android.app.Activity
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.childwatch.shared.chat.ConversationMember
import ru.example.childwatch.R

/**
 * Choosing people out of the family, one or several.
 *
 * Adding to a group may name several people at once, so the answer is a list of ticks
 * rather than a single tap.
 */
internal object MemberPicker {

    /** A multi-select list; [onPicked] is called with the ticked members. */
    fun pick(
        activity: Activity,
        title: String,
        members: List<ConversationMember>,
        confirmLabel: String,
        onPicked: (List<ConversationMember>) -> Unit
    ) {
        val boxes = members.map { member ->
            CheckBox(activity).apply {
                text = member.displayName
                buttonTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.cw_color_primary)
                )
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scrollable(activity, boxes))
            .setPositiveButton(confirmLabel) { _, _ ->
                val picked = members.filterIndexed { index, _ -> boxes[index].isChecked }
                // An empty answer is a mistake rather than a request to change nothing,
                // and the server refuses it with the same words.
                if (picked.isEmpty()) {
                    Toast.makeText(
                        activity,
                        R.string.group_create_members_empty,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    onPicked(picked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * One scrolling column of check boxes.
     *
     * The height is bounded because a family may be long: without it the list would run
     * off the screen and the buttons under it would be unreachable.
     */
    private fun scrollable(activity: Activity, boxes: List<CheckBox>): ScrollView {
        val density = activity.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            boxes.forEach(::addView)
        }
        return ScrollView(activity).apply {
            addView(
                column,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (320 * density).toInt()
            )
        }
    }
}
