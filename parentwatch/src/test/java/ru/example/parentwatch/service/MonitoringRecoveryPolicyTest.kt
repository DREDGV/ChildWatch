package ru.example.parentwatch.service

import org.junit.Assert.*
import org.junit.Test

class MonitoringRecoveryPolicyTest {
    @Test fun `temporary loss of running flag preserves enabled monitoring`() {
        assertTrue(MonitoringRecoveryPolicy.shouldRecover(true, false, false))
    }
    @Test fun `explicit stop wins over boot autostart and stale running flag`() {
        assertFalse(MonitoringRecoveryPolicy.shouldRecover(false, true, true))
    }
    @Test fun `existing installs retain boot recovery until explicit choice`() {
        assertTrue(MonitoringRecoveryPolicy.shouldRecover(null, false, true))
        assertTrue(MonitoringRecoveryPolicy.shouldRecover(null, true, false))
        assertFalse(MonitoringRecoveryPolicy.shouldRecover(null, false, false))
    }
}
