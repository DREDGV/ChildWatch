package ru.childwatch.shared.family

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyDeviceHeartbeatPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun `missing local or implausibly future heartbeat remains unknown`() {
        assertEquals(FamilyPresenceState.UNKNOWN, FamilyDeviceHeartbeatPolicy.presence(null, true, now))
        assertEquals(FamilyPresenceState.UNKNOWN, FamilyDeviceHeartbeatPolicy.presence(now, false, now))
        assertEquals(FamilyPresenceState.UNKNOWN, FamilyDeviceHeartbeatPolicy.presence(now + 31_000, true, now))
        assertEquals(FamilyPresenceState.UNKNOWN, FamilyDeviceHeartbeatPolicy.presence(0, true, now))
    }

    @Test fun `unchanged snapshot ages through each recency boundary`() {
        val seen = now
        assertEquals(FamilyPresenceState.ONLINE, FamilyDeviceHeartbeatPolicy.presence(seen, true, now + 120_000))
        assertEquals(FamilyPresenceState.RECENTLY_ACTIVE, FamilyDeviceHeartbeatPolicy.presence(seen, true, now + 120_001))
        assertEquals(FamilyPresenceState.RECENTLY_ACTIVE, FamilyDeviceHeartbeatPolicy.presence(seen, true, now + 86_400_000))
        assertEquals(FamilyPresenceState.OFFLINE, FamilyDeviceHeartbeatPolicy.presence(seen, true, now + 86_400_001))
    }

    @Test fun `small server clock skew does not imply lost connection`() {
        assertEquals(FamilyPresenceState.ONLINE, FamilyDeviceHeartbeatPolicy.presence(now + 30_000, true, now))
    }
}
