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

    /**
     * Whether the refusal was about the group's name.
     *
     * A refusal about the name belongs under the name field, where the person can
     * act on it; every other refusal belongs to the screen as a whole.
     */
    fun isAboutTitle(error: Throwable?): Boolean = forFailure(error) in setOf(
        R.string.group_error_title_required,
        R.string.group_error_title_too_long
    )

    @StringRes
    fun forCode(code: String?): Int = when (serverReason(code)) {
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
        "GROUP_AVATAR_UNSUPPORTED" -> R.string.group_error_avatar_unsupported
        "UNSUPPORTED_GROUP_AVATAR" -> R.string.group_error_avatar_unsupported
        "CONVERSATION_ACCESS_DENIED" -> R.string.group_error_access_denied
        else -> R.string.group_error_generic
    }

    /**
     * The server's own reason behind an exception, or null when the call never arrived.
     *
     * A screen needs to know what the refusal was about as well as how to word it, and
     * only the server's reason can say that.
     */
    fun of(error: Throwable?): String? = serverReason((error as? ChatV2RepositoryException)?.code)

    /**
     * The server's reason inside a code the repository raised.
     *
     * The repository wraps the server's answer for its log — `ADD_GROUP_MEMBERS_HTTP_403`
     * — so the cause the server named survives, but not as a code this table can match.
     * Matching the reason as a part of the label recovers it: every reason below was
     * refused by the server as it stands, whichever call carried it.
     */
    private fun serverReason(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return REASONS.firstOrNull(code::contains)
    }

    /** Every reason the server can name, longest first so a prefix cannot win. */
    private val REASONS = listOf(
        "GROUP_ADMIN_CANNOT_REMOVE_SELF",
        "GROUP_MEMBER_NOT_FOUND",
        "GROUP_TARGET_NOT_AVAILABLE",
        "GROUP_ADMIN_CANNOT_LEAVE",
        "GROUP_TITLE_REQUIRED",
        "GROUP_TITLE_TOO_LONG",
        "GROUP_MEMBERS_REQUIRED",
        "GROUP_AVATAR_UNSUPPORTED",
        "UNSUPPORTED_GROUP_AVATAR",
        "NOT_A_GROUP_CONVERSATION",
        "CONVERSATION_ACCESS_DENIED",
        "GROUP_ADMIN_REQUIRED",
        "GROUP_ADMIN_ALREADY"
    )
}
