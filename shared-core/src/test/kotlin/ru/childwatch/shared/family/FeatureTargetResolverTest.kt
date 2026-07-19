package ru.childwatch.shared.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTargetResolverTest {
    private val resolver = FeatureTargetResolver()

    @Test
    fun `selected target A produces a complete immutable feature snapshot`() {
        val active = activeContext(targetDeviceId = "child-a", focusedMemberId = "member-a")

        val resolved = resolved(
            resolver.resolve(
                activeContext = active,
                ownerScope = "parent",
                feature = "remote-photo"
            )
        )

        assertEquals(FeatureTargetOrigin.ACTIVE_SELECTION, resolved.origin)
        assertEquals("family-1", resolved.context.familyId)
        assertEquals("parent-member", resolved.context.selfMemberId)
        assertEquals("member-a", resolved.context.focusedMemberId)
        assertEquals("parent-device", resolved.context.selfDeviceId)
        assertEquals("child-a", resolved.context.targetDeviceId)
        assertEquals("child-a", resolved.targetDeviceId)
        assertEquals("https://server.example", resolved.context.serverUrl)
        assertEquals(active.storageNamespace("parent", "remote-photo"), resolved.context.storageNamespace)
        assertEquals(ContextSource.CANONICAL, resolved.context.source)
        assertEquals(100L, resolved.context.updatedAt)
    }

    @Test
    fun `explicit target B creates a separate snapshot without changing active target A`() {
        val active = activeContext(targetDeviceId = "child-a", focusedMemberId = "member-a")
        val before = active.copy()

        val resolved = resolved(
            resolver.resolve(
                activeContext = active,
                ownerScope = "parent",
                feature = "attention-signal",
                explicitTargetDeviceId = " child-b ",
                explicitFocusedMemberId = " member-b "
            )
        )

        assertEquals(before, active)
        assertEquals("child-a", active.targetDeviceId)
        assertEquals("member-a", active.focusedMemberId)
        assertEquals(FeatureTargetOrigin.EXPLICIT_REQUEST, resolved.origin)
        assertEquals("child-b", resolved.context.targetDeviceId)
        assertEquals("member-b", resolved.context.focusedMemberId)
        assertEquals(active.source, resolved.context.source)
        assertEquals(active.updatedAt, resolved.context.updatedAt)
        assertNotEquals(
            active.storageNamespace("parent", "attention-signal"),
            resolved.context.storageNamespace
        )
    }

    @Test
    fun `explicit target without member does not carry focused member from another target`() {
        val active = activeContext(targetDeviceId = "child-a", focusedMemberId = "member-a")

        val resolved = resolved(
            resolver.resolve(
                activeContext = active,
                ownerScope = "parent",
                feature = "attention-signal",
                explicitTargetDeviceId = "child-b"
            )
        )

        assertEquals("child-b", resolved.context.targetDeviceId)
        assertNull(resolved.context.focusedMemberId)
    }

    @Test
    fun `missing active context is rejected`() {
        val result = resolver.resolve(
            activeContext = null,
            ownerScope = "parent",
            feature = "remote-photo"
        )

        assertRejected(result, FeatureTargetFailure.ACTIVE_CONTEXT_MISSING)
    }

    @Test
    fun `missing selected target is rejected`() {
        val result = resolver.resolve(
            activeContext = activeContext(targetDeviceId = null, focusedMemberId = null),
            ownerScope = "parent",
            feature = "remote-photo"
        )

        assertRejected(result, FeatureTargetFailure.TARGET_DEVICE_MISSING)
    }

    @Test
    fun `blank explicit target fails closed instead of using selected target`() {
        val result = resolver.resolve(
            activeContext = activeContext(targetDeviceId = "child-a", focusedMemberId = "member-a"),
            ownerScope = "parent",
            feature = "remote-photo",
            explicitTargetDeviceId = "   "
        )

        assertRejected(result, FeatureTargetFailure.TARGET_DEVICE_MISSING)
    }

    @Test
    fun `explicit self target fails closed instead of using selected target`() {
        val active = activeContext(targetDeviceId = "child-a", focusedMemberId = "member-a")

        val result = resolver.resolve(
            activeContext = active,
            ownerScope = "parent",
            feature = "remote-photo",
            explicitTargetDeviceId = " parent-device "
        )

        assertRejected(result, FeatureTargetFailure.TARGET_IS_SELF)
        assertEquals("child-a", active.targetDeviceId)
    }

    @Test
    fun `legacy positional FeatureContext constructor remains available`() {
        val context = FeatureContext(
            "parent-device",
            "child-a",
            "https://server.example",
            "legacy-namespace",
            ContextSource.LEGACY_MIGRATION,
            50L
        )

        assertEquals("child-a", context.targetDeviceId)
        assertNull(context.familyId)
        assertNull(context.selfMemberId)
        assertNull(context.focusedMemberId)
    }

    private fun activeContext(
        targetDeviceId: String?,
        focusedMemberId: String?
    ): ActiveContext {
        return ActiveContext(
            familyId = "family-1",
            selfMemberId = "parent-member",
            selfDeviceId = "parent-device",
            focusedMemberId = focusedMemberId,
            targetDeviceId = targetDeviceId,
            serverUrl = "https://server.example",
            source = ContextSource.CANONICAL,
            updatedAt = 100L
        )
    }

    private fun resolved(result: FeatureTargetResult): FeatureTargetResult.Resolved {
        assertTrue("Expected a resolved feature target, got $result", result is FeatureTargetResult.Resolved)
        return result as FeatureTargetResult.Resolved
    }

    private fun assertRejected(result: FeatureTargetResult, reason: FeatureTargetFailure) {
        assertTrue("Expected a rejected feature target, got $result", result is FeatureTargetResult.Rejected)
        assertEquals(reason, (result as FeatureTargetResult.Rejected).reason)
    }
}
