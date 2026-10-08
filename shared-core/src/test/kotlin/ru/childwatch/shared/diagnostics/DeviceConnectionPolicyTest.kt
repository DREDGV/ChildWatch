package ru.childwatch.shared.diagnostics

import org.junit.Assert.*
import org.junit.Test
import ru.childwatch.shared.diagnostics.DeviceConnectionPolicy.Freshness
import ru.childwatch.shared.diagnostics.DeviceConnectionPolicy.Microphone
import ru.childwatch.shared.diagnostics.DeviceConnectionPolicy.LOCATION_FRESH_MS
import ru.childwatch.shared.diagnostics.DeviceConnectionPolicy.TELEMETRY_FRESH_MS

class DeviceConnectionPolicyTest {
    private val now = 1_791_000_000_000L

    @Test fun `telemetry and location expire independently`() {
        val at = now - 150_000L
        assertEquals(Freshness.FRESH, DeviceConnectionPolicy.freshness(at, now))
        assertEquals(Freshness.STALE, DeviceConnectionPolicy.freshness(at, now, LOCATION_FRESH_MS))
        assertEquals(Freshness.FRESH, DeviceConnectionPolicy.freshness(now - TELEMETRY_FRESH_MS, now))
        assertEquals(Freshness.STALE, DeviceConnectionPolicy.freshness(now - TELEMETRY_FRESH_MS - 1, now))
    }

    @Test fun `missing and future data never confirm readiness`() {
        assertEquals(Freshness.UNKNOWN, DeviceConnectionPolicy.freshness(null, now))
        assertEquals(Freshness.UNKNOWN, DeviceConnectionPolicy.freshness(0, now))
        assertEquals(Freshness.CLOCK_MISMATCH, DeviceConnectionPolicy.freshness(now + 30_001, now))
        assertEquals(Microphone.UNKNOWN, DeviceConnectionPolicy.microphone(now + 30_001, true, false, now))
    }

    @Test fun `stale denial is historical not current`() {
        assertEquals(Microphone.STALE, DeviceConnectionPolicy.microphone(now - 180_001, false, true, now))
        assertEquals(Microphone.PERMISSION_MISSING, DeviceConnectionPolicy.microphone(now, false, true, now))
        assertEquals(Microphone.MUTED, DeviceConnectionPolicy.microphone(now, true, true, now))
    }

    @Test fun `granted permission never proves recording works`() {
        assertEquals(Microphone.PREREQUISITES_ONLY, DeviceConnectionPolicy.microphone(now, true, false, now))
        assertEquals(Microphone.PREREQUISITES_ONLY, DeviceConnectionPolicy.microphone(now, true, null, now))
        assertEquals(Microphone.UNKNOWN, DeviceConnectionPolicy.microphone(now, null, false, now))
    }

    @Test fun `old seconds and invalid numeric values handled safely`() {
        assertEquals(now, DeviceConnectionPolicy.epoch(now / 1000))
        assertEquals(now, DeviceConnectionPolicy.epoch(now))
        assertNull(DeviceConnectionPolicy.epoch(Double.NaN))
        assertNull(DeviceConnectionPolicy.epoch(Double.POSITIVE_INFINITY))
        assertNull(DeviceConnectionPolicy.epoch(-1))
    }
}
