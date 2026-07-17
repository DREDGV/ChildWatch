package ru.childwatch.shared.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActiveContextResolverTest {
    private val resolver = ActiveContextResolver(clock = { 1234L })

    @Test
    fun `canonical values have priority over legacy values`() {
        val result = resolver.resolve(
            listOf(
                candidate(
                    source = ContextSource.CANONICAL,
                    self = "parent-new",
                    target = "child-new",
                    server = "https://new.example",
                    updatedAt = 900L
                ),
                candidate(
                    source = ContextSource.LEGACY_MIGRATION,
                    self = "parent-old",
                    target = "child-old",
                    server = "https://old.example"
                )
            )
        )

        requireNotNull(result)
        assertEquals("parent-new", result.selfDeviceId)
        assertEquals("child-new", result.targetDeviceId)
        assertEquals("https://new.example", result.serverUrl)
        assertEquals(ContextSource.CANONICAL, result.source)
        assertEquals(900L, result.updatedAt)
    }

    @Test
    fun `blank canonical fields do not erase lower priority values`() {
        val result = resolver.resolve(
            listOf(
                candidate(
                    source = ContextSource.CANONICAL,
                    self = "parent",
                    target = "   ",
                    server = ""
                ),
                candidate(
                    source = ContextSource.ACTIVE_SESSION,
                    target = "child",
                    server = "https://server.example"
                )
            )
        )

        requireNotNull(result)
        assertEquals("parent", result.selfDeviceId)
        assertEquals("child", result.targetDeviceId)
        assertEquals("https://server.example", result.serverUrl)
    }

    @Test
    fun `self device is excluded and next valid target is used`() {
        val result = resolver.resolve(
            listOf(
                candidate(
                    source = ContextSource.CANONICAL,
                    self = "same-device",
                    target = "same-device",
                    server = "https://server.example"
                ),
                candidate(
                    source = ContextSource.ACTIVE_SESSION,
                    target = "other-device"
                )
            )
        )

        requireNotNull(result)
        assertEquals("other-device", result.targetDeviceId)
        assertNotEquals(result.selfDeviceId, result.targetDeviceId)
    }

    @Test
    fun `selection changes focused member and target atomically`() {
        val original = configuredContext(target = "child-a")

        val changed = original.withSelection(
            focusedMemberId = "member-b",
            targetDeviceId = "child-b",
            updatedAt = 2000L
        )

        assertEquals("member-b", changed.focusedMemberId)
        assertEquals("child-b", changed.targetDeviceId)
        assertEquals(ContextSource.CANONICAL, changed.source)
        assertEquals(2000L, changed.updatedAt)

        val selfSelection = changed.withSelection("self-member", "parent", updatedAt = 2001L)
        assertNull(selfSelection.focusedMemberId)
        assertNull(selfSelection.targetDeviceId)
    }

    @Test
    fun `migration is idempotent`() {
        val store = InMemoryStore()
        val migration = ActiveContextMigration(store, legacyCandidates = {
            listOf(
                candidate(
                    source = ContextSource.ACTIVE_SESSION,
                    self = "parent",
                    target = "child",
                    server = "https://server.example"
                )
            )
        }, resolver = resolver)

        val first = migration.migrateIfNeeded()
        val second = migration.migrateIfNeeded()

        assertSame(first, second)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun `single pair derives the same family id on both devices`() {
        val parent = resolver.resolve(
            listOf(candidate(ContextSource.LEGACY_MIGRATION, "parent", "child", "https://server.example"))
        )
        val child = resolver.resolve(
            listOf(candidate(ContextSource.LEGACY_MIGRATION, "child", "parent", "https://server.example"))
        )

        requireNotNull(parent)
        requireNotNull(child)
        assertEquals(parent.familyId, child.familyId)
    }

    @Test
    fun `canonical codec round trips context without changing it`() {
        val original = configuredContext(target = "child/with space")
            .copy(familyId = "family", selfMemberId = "member", updatedAt = 777L)

        val decoded = ActiveContextCodec.decode(ActiveContextCodec.encode(original))

        assertEquals(original, decoded)
    }

    private fun configuredContext(target: String): ActiveContext {
        return ActiveContext(
            selfDeviceId = "parent",
            targetDeviceId = target,
            focusedMemberId = StableContextIds.memberId(target),
            serverUrl = "https://server.example",
            source = ContextSource.ACTIVE_SESSION
        )
    }

    private fun candidate(
        source: ContextSource,
        self: String? = null,
        target: String? = null,
        server: String? = null,
        updatedAt: Long = 0L
    ) = ActiveContextCandidate(
        selfDeviceId = self,
        targetDeviceId = target,
        serverUrl = server,
        source = source,
        updatedAt = updatedAt
    )

    private class InMemoryStore : ActiveContextStore {
        var value: ActiveContext? = null
        var writeCount: Int = 0

        override fun read(): ActiveContext? = value

        override fun write(context: ActiveContext) {
            value = context
            writeCount += 1
        }
    }
}
