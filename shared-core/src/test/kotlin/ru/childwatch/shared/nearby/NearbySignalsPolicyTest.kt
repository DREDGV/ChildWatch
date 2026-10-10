package ru.childwatch.shared.nearby

import org.junit.Assert.*
import org.junit.Test

class NearbySignalsPolicyTest {
    @Test fun `cached WiFi observations retain their real age instead of upload age`() {
        val collector = NearbySignalCollector(NearbySignalSource.WIFI)
        collector.add("private-radio-key", "Kitchen", -61, 900_000)
        val signal = collector.freeze(1_030_000, 1_015_000).single()
        assertEquals(130_000L, signal.observedAgeMs)
        assertFalse(signal.observedDuringScan)
        assertEquals(NearbySourceState.READY, NearbySignalsPolicy.state(listOf(signal)))
    }

    @Test fun `unknown future and zero timestamps never prove current detection`() {
        val collector = NearbySignalCollector(NearbySignalSource.WIFI)
        collector.add("a", null, -72, 0)
        collector.add("b", "Future", -74, 11_000)
        val signals = collector.freeze(10_000, 5_000)
        assertTrue(signals.all { it.observedAgeMs == null && !it.observedDuringScan })
        assertEquals(NearbySourceState.STALE, NearbySignalsPolicy.state(signals))
    }

    @Test fun `empty radio results differ from a five minute old cache`() {
        assertEquals(NearbySourceState.EMPTY, NearbySignalsPolicy.state(emptyList()))
        val collector = NearbySignalCollector(NearbySignalSource.WIFI)
        collector.add("a", "Old", -52, 1_000)
        assertEquals(NearbySourceState.STALE, NearbySignalsPolicy.state(collector.freeze(301_001, 290_000)))
    }

    @Test fun `late duplicate callbacks cannot replace a newer measured observation`() {
        val collector = NearbySignalCollector(NearbySignalSource.BLE)
        collector.add("mac-inside-only", "Watch", -60, 22_000)
        collector.add("mac-inside-only", "Old watch", -100, 21_000)
        collector.add("mac-inside-only", null, -58, 23_000)
        val signals = collector.freeze(25_000, 10_000)
        assertEquals(1, signals.size)
        assertEquals("Watch", signals.single().name)
        assertEquals(-58, signals.single().rssiDbm)
        assertEquals(2_000L, signals.single().observedAgeMs)
        assertTrue(signals.single().observedDuringScan)
        assertFalse(signals.single().toString().contains("mac-inside-only"))
    }

    @Test fun `ids cannot correlate a hardware address across scans`() {
        fun scan(): NearbySignal = NearbySignalCollector(NearbySignalSource.BLE).let {
            it.add("11:22:33:44:55:66", "Beacon", -41, 4_000)
            it.freeze(5_000, 1_000).single()
        }
        assertNotEquals(scan().id, scan().id)
    }

    @Test fun `late callback after completion cannot mutate the published snapshot`() {
        val collector = NearbySignalCollector(NearbySignalSource.BLUETOOTH_CLASSIC)
        collector.add("a", "Headphones", -51, 5_000)
        val published = collector.freeze(7_000, 1_000)
        collector.add("b", "Late radio", -31, 7_500)
        assertEquals(1, published.size)
        assertEquals("Headphones", published.single().name)
        try {
            @Suppress("UNCHECKED_CAST")
            (published as MutableList<NearbySignal>).clear()
            fail("The published list must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun `dense environment is bounded and invalid RSSI is discarded`() {
        val collector = NearbySignalCollector(NearbySignalSource.BLE)
        collector.add("invalid", "Unknown strength", 127, 5_000)
        repeat(400) { collector.add("radio-$it", "Beacon $it", -50, 5_000) }
        val signals = collector.freeze(6_000, 1_000)
        assertEquals(100, signals.size)
        assertTrue(signals.none { it.name == "Unknown strength" })
    }

    @Test fun `cancelled collection cannot publish partial or late observations`() {
        val collector = NearbySignalCollector(NearbySignalSource.BLE)
        collector.add("private-beacon", "Beacon before cancellation", -60, 5_000)
        collector.discard()
        collector.add("private-late-beacon", "Late callback", -50, 6_000)
        assertTrue(collector.freeze(7_000, 1_000).isEmpty())
    }

    @Test fun `cooldown uses measurement clock and never bridges reboot`() {
        assertEquals(45_000L, NearbySignalsPolicy.cooldownRemaining(115_000, 100_000, true))
        assertEquals(0L, NearbySignalsPolicy.cooldownRemaining(160_000, 100_000, true))
        assertEquals(0L, NearbySignalsPolicy.cooldownRemaining(115_000, 100_000, false))
        assertEquals(0L, NearbySignalsPolicy.cooldownRemaining(5_000, 100_000, true))
    }

    @Test fun `radio names cannot inject controls or leak bare hardware addresses`() {
        assertEquals("Bedroom", NearbySignalsPolicy.cleanName("\n Bedroom\u202e\t"))
        assertNull(NearbySignalsPolicy.cleanName("<unknown ssid>"))
        assertNull(NearbySignalsPolicy.cleanName("11:22:33:44:55:66"))
        assertNull(NearbySignalsPolicy.cleanName("11-22-33-44-55-66"))
        assertEquals(128, NearbySignalsPolicy.cleanName("x".repeat(200))!!.length)
    }
}
