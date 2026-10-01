package ru.childwatch.shared.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class ParentFirstRunPolicyTest {

    @Test
    fun `a phone already in a family is told so instead of being asked again`() {
        assertEquals(
            FamilyFirstRunAction.SAY_ALREADY_IN_FAMILY,
            ParentFirstRunPolicy.decide(
                localCompleted = false,
                serverReachable = true,
                hasFamilyMembership = true,
                hasKnownChildPhone = true
            )
        )
    }

    @Test
    fun `a linked phone of the child is offered for connection without an invitation`() {
        assertEquals(
            FamilyFirstRunAction.OFFER_CONNECT_TO_LINKED,
            ParentFirstRunPolicy.decide(
                localCompleted = false,
                serverReachable = true,
                hasFamilyMembership = false,
                hasKnownChildPhone = true
            )
        )
    }

    @Test
    fun `only a phone with nothing behind it is asked to start or join`() {
        assertEquals(
            FamilyFirstRunAction.ASK_START_OR_JOIN,
            ParentFirstRunPolicy.decide(false, true, false, false)
        )
    }

    @Test
    fun `a finished setup is never re-opened`() {
        assertEquals(
            FamilyFirstRunAction.CONTINUE_TO_APP,
            ParentFirstRunPolicy.decide(
                localCompleted = true,
                serverReachable = true,
                hasFamilyMembership = false,
                hasKnownChildPhone = false
            )
        )
    }

    @Test
    fun `an unanswered server produces no claim about the family`() {
        assertEquals(
            FamilyFirstRunAction.OFFLINE_RETRY,
            ParentFirstRunPolicy.decide(
                localCompleted = false,
                serverReachable = false,
                hasFamilyMembership = false,
                hasKnownChildPhone = false
            )
        )
    }

    @Test
    fun `a finished setup still wins when the server is silent`() {
        assertEquals(
            FamilyFirstRunAction.CONTINUE_TO_APP,
            ParentFirstRunPolicy.decide(true, false, false, false)
        )
    }

    @Test
    fun `membership wins over everything except a finished setup`() {
        assertEquals(
            FamilyFirstRunAction.SAY_ALREADY_IN_FAMILY,
            ParentFirstRunPolicy.decide(false, true, true, false)
        )
    }
}

class ChildFirstRunPolicyTest {

    @Test
    fun `a phone the server holds as a member is completed silently`() {
        assertEquals(
            FamilyFirstRunAction.SAY_ALREADY_IN_FAMILY,
            ChildFirstRunPolicy.decide(false, true, true, false)
        )
    }

    @Test
    fun `a migrated legacy link keeps working without a wizard`() {
        assertEquals(
            FamilyFirstRunAction.CONTINUE_TO_APP,
            ChildFirstRunPolicy.decide(false, true, false, true)
        )
    }

    @Test
    fun `a genuinely new phone is asked for the code`() {
        assertEquals(
            FamilyFirstRunAction.ASK_START_OR_JOIN,
            ChildFirstRunPolicy.decide(false, true, false, false)
        )
    }

    @Test
    fun `an unanswered server does not send an existing phone through the wizard`() {
        assertEquals(
            FamilyFirstRunAction.OFFLINE_RETRY,
            ChildFirstRunPolicy.decide(false, false, false, false)
        )
    }
}

class ParentFirstRunStepTest {

    @Test
    fun `the parent flow is two steps and both are counted`() {
        assertEquals(1, ParentFirstRunStep.WHO.number)
        assertEquals(2, ParentFirstRunStep.WHO.total)
        assertEquals(2, ParentFirstRunStep.CONNECTION.number)
        assertEquals(2, ParentFirstRunStep.CONNECTION.total)
    }

    @Test
    fun `the child flow is two steps and the last one is the confirmation`() {
        assertEquals(1, ChildFirstRunStep.CODE_NUMBER)
        assertEquals(2, ChildFirstRunStep.CODE_TOTAL)
        assertEquals(2, ChildFirstRunStep.DONE_NUMBER)
    }
}
