package ru.example.parentwatch.chat.v2

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.childwatch.shared.chat.ConversationMember
import ru.example.parentwatch.R

/**
 * Choosing people out of the family, one or several.
 *
 * Adding to a group may name several people at once, so the answer is a list of ticks
 * rather than a single tap.
 *
 * A confirmation with nobody ticked is answered here, under the list, instead of closing
 * the dialog: the same form is one tick away from being right, and taking it away would
 * make the person open it again to do what they had already started. That is the whole
 * reason this is a dialog built by hand rather than `setMultiChoiceItems` with a builder
 * listener, which dismisses on the press and leaves nothing to say.
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
        val errorText = TextView(activity).apply {
            TextViewCompat.setTextAppearance(
                this,
                R.style.TextAppearance_ChildWatch_TextInput_Error
            )
            visibility = View.GONE
        }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            boxes.forEach(::addView)
            addView(errorText)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scrollable(activity, column))
            // Answered below instead of by the builder, which would close the dialog
            // on the press and leave the refusal nowhere to appear.
            .setPositiveButton(confirmLabel, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun submit() {
            errorText.visibility = View.GONE
            val picked = members.filterIndexed { index, _ -> boxes[index].isChecked }
            // An empty answer is a mistake rather than a request to change nothing, and
            // the server refuses it with the same words.
            if (picked.isEmpty()) {
                errorText.setText(R.string.group_members_required)
                errorText.visibility = View.VISIBLE
                return
            }
            dialog.dismiss()
            onPicked(picked)
        }

        boxes.forEach { box ->
            box.setOnCheckedChangeListener { _, _ -> errorText.visibility = View.GONE }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
        }
        dialog.show()
    }

    /**
     * One scrolling column.
     *
     * The height is bounded because a family may be long: without it the list would run
     * off the screen and the buttons under it would be unreachable.
     */
    private fun scrollable(activity: Activity, column: View): ScrollView {
        val density = activity.resources.displayMetrics.density
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
