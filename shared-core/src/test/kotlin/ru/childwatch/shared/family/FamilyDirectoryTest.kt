package ru.childwatch.shared.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FamilyDirectoryTest {
    private val family = Family("family-1", "Наша семья", 1L, 2L)
    private val parent = FamilyMember("parent-member", family.id, "Марина", FamilyRole.PARENT)
    private val child = FamilyMember("child-member", family.id, "Лёва", FamilyRole.CHILD)

    @Test
    fun `directory keeps people separate from their devices`() {
        val directory = directory(
            devices = listOf(
                device("child-phone", child.id, lastSeenAt = 1_000L),
                device("child-tablet", child.id, lastSeenAt = 2_000L)
            )
        )

        val person = directory.person(child.id)

        assertEquals("Лёва", person?.member?.displayName)
        assertEquals(listOf("child-phone", "child-tablet"), person?.devices?.map { it.deviceId })
        assertEquals("child-tablet", person?.primaryDevice()?.deviceId)
    }

    @Test
    fun `preferred active device wins over most recent device`() {
        val directory = directory(
            devices = listOf(
                device("child-phone", child.id, lastSeenAt = 1_000L),
                device("child-tablet", child.id, lastSeenAt = 2_000L)
            )
        )

        assertEquals(
            "child-phone",
            directory.resolveTargetDevice(child.id, preferredDeviceId = "child-phone")?.deviceId
        )
    }

    @Test
    fun `self member is never returned as remote target`() {
        val directory = directory(
            devices = listOf(
                device("parent-phone", parent.id, lastSeenAt = 2_000L),
                device("child-phone", child.id, lastSeenAt = 2_000L)
            )
        )

        assertNull(directory.resolveTargetDevice(parent.id, "parent-phone"))
        assertEquals(listOf(child.id), directory.targetPeople().map { it.member.id })
    }

    @Test
    fun `inactive and foreign records are excluded`() {
        val inactiveMember = child.copy(id = "inactive-member", isActive = false)
        val directory = FamilyDirectoryAssembler.assemble(
            family = family,
            members = listOf(parent, child, inactiveMember),
            devices = listOf(
                device("child-active", child.id, lastSeenAt = 1_000L),
                device("child-disabled", child.id, lastSeenAt = 2_000L, active = false),
                device("inactive-phone", inactiveMember.id, lastSeenAt = 3_000L),
                device("foreign-phone", child.id, lastSeenAt = 4_000L).copy(familyId = "other-family")
            ),
            selfMemberId = parent.id
        )

        assertEquals(listOf("child-active"), directory.person(child.id)?.devices?.map { it.deviceId })
        assertNull(directory.person(inactiveMember.id))
    }

    @Test
    fun `seconds timestamps are normalized to milliseconds`() {
        assertEquals(1_784_000_000_000L, FamilyDirectoryAssembler.epochMillis(1_784_000_000L))
        assertEquals(1_784_000_000_123L, FamilyDirectoryAssembler.epochMillis(1_784_000_000_123L))
        assertNull(FamilyDirectoryAssembler.epochMillis(0L))
    }

    private fun directory(devices: List<FamilyDevice>): FamilyDirectorySnapshot {
        return FamilyDirectoryAssembler.assemble(
            family = family,
            members = listOf(parent, child),
            devices = devices,
            selfMemberId = parent.id
        )
    }

    private fun device(
        id: String,
        memberId: String,
        lastSeenAt: Long?,
        active: Boolean = true
    ) = FamilyDevice(
        id = "family-device-$id",
        familyId = family.id,
        memberId = memberId,
        deviceId = id,
        displayName = id,
        lastSeenAt = lastSeenAt,
        isActive = active
    )
}
