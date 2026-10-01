package ru.childwatch.shared.onboarding

/**
 * What the first screen of an application must do, decided from facts rather
 * than from a local "setup finished" flag.
 *
 * The flag alone is not enough: it is lost when the application is reinstalled,
 * while the family, the person and the linked other phone still exist on the
 * server. A screen that trusts only the flag asks a person who is already in a
 * family to create or join one, which is work that was already done and cannot
 * be seen to be done.
 */
enum class FamilyFirstRunAction {
    /** Setup is finished on this phone; go to the main screen. */
    CONTINUE_TO_APP,

    /** The phone is already in a family. Say so and continue. */
    SAY_ALREADY_IN_FAMILY,

    /** The phone is not in a family, but a phone of this family is linked. */
    OFFER_CONNECT_TO_LINKED,

    /** Nothing is known: the person decides between starting and joining. */
    ASK_START_OR_JOIN,

    /** The server could not be asked, so no claim about the family is made. */
    OFFLINE_RETRY
}

/**
 * Which question each step of the first run asks, so a screen can show
 * "Шаг N из M" without inventing its own numbering.
 */
enum class ParentFirstRunStep(val number: Int, val total: Int) {
    /** Who this person is, and what the phone is connected to. */
    WHO(1, 2),

    /** What to do next: connect to the phone of the child, or set up a family. */
    CONNECTION(2, 2)
}

object ChildFirstRunStep {
    const val CODE_NUMBER = 1
    const val CODE_TOTAL = 2
    const val DONE_NUMBER = 2
    const val DONE_TOTAL = 2
}

/**
 * Decides the first screen of ParentMonitor from server truth.
 *
 * Precedence matters and is deliberate:
 * 1. A finished setup is never re-opened.
 * 2. A membership is the strongest fact, and it wins over everything else.
 * 3. A linked phone of the family means the family already exists, so the
 *    person must not be asked to create one.
 * 4. Only a phone with nothing behind it is asked to choose.
 */
object ParentFirstRunPolicy {
    fun decide(
        localCompleted: Boolean,
        serverReachable: Boolean,
        hasFamilyMembership: Boolean,
        hasKnownChildPhone: Boolean
    ): FamilyFirstRunAction = when {
        localCompleted -> FamilyFirstRunAction.CONTINUE_TO_APP
        !serverReachable -> FamilyFirstRunAction.OFFLINE_RETRY
        hasFamilyMembership -> FamilyFirstRunAction.SAY_ALREADY_IN_FAMILY
        hasKnownChildPhone -> FamilyFirstRunAction.OFFER_CONNECT_TO_LINKED
        else -> FamilyFirstRunAction.ASK_START_OR_JOIN
    }
}

/**
 * The same decision for ChildDevice, added next to the older entry policy.
 *
 * A phone that the server already holds as a member of a family is completed
 * silently: the child must not be sent through a wizard for a relationship that
 * already works.
 */
object ChildFirstRunPolicy {
    fun decide(
        localCompleted: Boolean,
        serverReachable: Boolean,
        hasServerMembership: Boolean,
        hasLegacyParentLink: Boolean
    ): FamilyFirstRunAction = when {
        localCompleted -> FamilyFirstRunAction.CONTINUE_TO_APP
        !serverReachable -> FamilyFirstRunAction.OFFLINE_RETRY
        hasServerMembership -> FamilyFirstRunAction.SAY_ALREADY_IN_FAMILY
        hasLegacyParentLink -> FamilyFirstRunAction.CONTINUE_TO_APP
        else -> FamilyFirstRunAction.ASK_START_OR_JOIN
    }
}
