package ru.example.parentwatch.chat.v2

import androidx.annotation.StringRes
import ru.example.parentwatch.R

/**
 * What a refused group action says to the person.
 *
 * The server names every refusal with a code, and that code is the only thing that
 * can tell "you are not the group's administrator" from "that person is not in the
 * family". The code itself means nothing to a parent, so it is never shown; it is
 * turned into a sentence here.
 */
object GroupErrorMessages {

    /** The sentence for a failed group action. [error] may be anything that failed. */
    @StringRes
    fun forFailure(error: Throwable?): Int =
        forCode((error as? ChatV2RepositoryException)?.code)

    @StringRes
    fun forCode(code: String?): Int = when (code) {
        "GROUP_ADMIN_REQUIRED" -> R.string.group_error_admin_required
        "GROUP_ADMIN_CANNOT_LEAVE" -> R.string.group_error_admin_cannot_leave
        "GROUP_ADMIN_ALREADY" -> R.string.group_error_admin_already
        "GROUP_ADMIN_CANNOT_REMOVE_SELF" -> R.string.group_error_admin_cannot_remove_self
        "GROUP_MEMBER_NOT_FOUND" -> R.string.group_error_member_not_found
        "GROUP_TARGET_NOT_AVAILABLE" -> R.string.group_error_target_not_available
        "GROUP_MEMBERS_REQUIRED" -> R.string.group_error_members_required
        "GROUP_TITLE_REQUIRED" -> R.string.group_error_title_required
        "GROUP_TITLE_TOO_LONG" -> R.string.group_error_title_too_long
        "NOT_A_GROUP_CONVERSATION" -> R.string.group_error_not_a_group
        "CONVERSATION_ACCESS_DENIED" -> R.string.group_error_access_denied
        else -> R.string.group_error_generic
    }
}
