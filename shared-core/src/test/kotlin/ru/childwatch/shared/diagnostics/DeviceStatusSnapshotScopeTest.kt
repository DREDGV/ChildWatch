package ru.childwatch.shared.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DeviceStatusSnapshotScopeTest {
    private val actor = listOf("https://server", "family", "adult", "own-phone")
    private fun scope(fields: List<String> = actor, target: String = "child-a") =
        requireNotNull(DeviceStatusSnapshotScope.from(fields, target))

    @Test fun `every identity field isolates cached snapshots and late responses`() {
        val old = scope()
        for (index in actor.indices) {
            val changed = scope(actor.toMutableList().apply { this[index] += "-other" })
            assertNotEquals(old.cacheKey, changed.cacheKey)
            assertFalse(old.accepts(changed, "child-a", true))
        }
        assertNotEquals(old.cacheKey, scope(target = "child-b").cacheKey)
        assertFalse(old.accepts(scope(target = "child-b"), "child-a", true))
    }

    @Test fun `matching read still needs success and exact recipient`() {
        val expected = scope()
        assertTrue(expected.accepts(scope(), "child-a", true))
        assertFalse(expected.accepts(scope(), "child-b", true))
        assertFalse(expected.accepts(scope(), "child-a", false))
        assertFalse(expected.accepts(null, "child-a", true))
    }

    @Test fun `missing context never falls back and whitespace is stable`() {
        assertNull(DeviceStatusSnapshotScope.from(actor.take(3), "child-a"))
        assertNull(DeviceStatusSnapshotScope.from(actor.map { "" }, "child-a"))
        assertNull(DeviceStatusSnapshotScope.from(actor, " "))
        assertEquals(scope().cacheKey, scope(actor.map { " $it " }, " child-a ").cacheKey)
    }

    @Test fun `cache encoding cannot collide at field boundaries`() {
        assertNotEquals(scope(listOf("ab", "c", "adult", "own-phone")).cacheKey,
            scope(listOf("a", "bc", "adult", "own-phone")).cacheKey)
    }
}
