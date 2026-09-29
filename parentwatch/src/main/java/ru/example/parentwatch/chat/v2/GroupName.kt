package ru.example.parentwatch.chat.v2

import androidx.annotation.StringRes
import ru.example.parentwatch.R

/**
 * The rules a group's name has to satisfy, checked before the server is asked.
 *
 * The server states the same rules and keeps them: this only spares a person the
 * round trip that would end in a refusal, and lets the reason appear under the
 * field they are still looking at. The limit is the server's own
 * `MAX_GROUP_TITLE_LENGTH`, counted the way the server counts it — in UTF-16
 * units, which counts an emoji as two.
 */
object GroupName {

    const val MAX_LENGTH = 64

    /** The reason [title] cannot be used, or null when it can. */
    @StringRes
    fun problem(title: String): Int? = when {
        title.isBlank() -> R.string.group_name_required
        title.length > MAX_LENGTH -> R.string.group_name_too_long
        else -> null
    }
}
