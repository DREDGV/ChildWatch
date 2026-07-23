package ru.childwatch.shared.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyInvitationTokenParserTest {
    private val token = "a".repeat(64)

    @Test
    fun acceptsVersionedInvitationUriAndRawRecoveryCode() {
        assertEquals(token, FamilyInvitationTokenParser.parse("childwatch://family/join?token=$token"))
        assertEquals(token, FamilyInvitationTokenParser.parse(token.uppercase()))
    }

    @Test
    fun rejectsLegacyDeviceIdsAndMalformedLinks() {
        assertNull(FamilyInvitationTokenParser.parse("child-c7d50e08"))
        assertNull(FamilyInvitationTokenParser.parse("childwatch://family/join?token=short"))
        assertNull(FamilyInvitationTokenParser.parse("https://example.test/?token=$token"))
    }
}

class FamilyOnboardingRolePolicyTest {
    @Test
    fun `parent monitor accepts only adult roles`() {
        assertTrue(FamilyOnboardingRolePolicy.accepts(FamilyAppKind.PARENT_MONITOR, "PARENT"))
        assertTrue(FamilyOnboardingRolePolicy.accepts(FamilyAppKind.PARENT_MONITOR, "guardian"))
        assertFalse(FamilyOnboardingRolePolicy.accepts(FamilyAppKind.PARENT_MONITOR, "CHILD"))
    }

    @Test
    fun `child device accepts only child role`() {
        assertTrue(FamilyOnboardingRolePolicy.accepts(FamilyAppKind.CHILD_DEVICE, "CHILD"))
        assertFalse(FamilyOnboardingRolePolicy.accepts(FamilyAppKind.CHILD_DEVICE, "PARENT"))
    }
}

class FamilyOnboardingEntryPolicyTest {
    @Test
    fun `server membership completes even an upgraded legacy install`() {
        assertEquals(
            FamilyOnboardingEntryDecision.COMPLETE_FROM_SERVER,
            FamilyOnboardingEntryPolicy.decide(
                localCompleted = false,
                hasServerMembership = true,
                hasLegacyParentLink = true
            )
        )
    }

    @Test
    fun `legacy link is preserved when server has not migrated it yet`() {
        assertEquals(
            FamilyOnboardingEntryDecision.PRESERVE_LEGACY_LINK,
            FamilyOnboardingEntryPolicy.decide(false, false, true)
        )
    }

    @Test
    fun `only a genuinely new unbound phone opens the wizard`() {
        assertEquals(
            FamilyOnboardingEntryDecision.OPEN_JOIN_WIZARD,
            FamilyOnboardingEntryPolicy.decide(false, false, false)
        )
    }
}
