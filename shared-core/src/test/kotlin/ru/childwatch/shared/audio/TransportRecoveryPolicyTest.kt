package ru.childwatch.shared.audio

import org.junit.Assert.*
import org.junit.Test

class TransportRecoveryPolicyTest {
    @Test fun openSocketWithoutPongRecoversAfterGrace() {
        val p = TransportRecoveryPolicy(1_000)
        assertFalse(p.shouldRecover(60_999, true, true, true, 0))
        assertTrue(p.shouldRecover(61_000, true, true, true, 0))
    }
    @Test fun failedRegistrationHasShorterGraceAndBoundedBackoff() {
        val p = TransportRecoveryPolicy(1_000)
        assertTrue(p.shouldRecover(31_000, true, true, false, 0))
        assertTrue(p.shouldRecover(61_000, true, true, false, 0))
        assertTrue(p.shouldRecover(91_000, true, true, false, 0))
        assertFalse(p.shouldRecover(121_000, true, true, false, 0))
        assertTrue(p.shouldRecover(151_000, true, true, false, 0))
        assertFalse(p.shouldRecover(181_000, true, true, false, 0))
        assertTrue(p.shouldRecover(271_000, true, true, false, 0))
    }
    @Test fun stopAndOfflineNeverRecover() {
        val p = TransportRecoveryPolicy(1_000)
        assertFalse(p.shouldRecover(200_000, false, true, false, 0))
        assertFalse(p.shouldRecover(200_000, true, false, false, 0))
        assertTrue(p.shouldRecover(200_000, true, true, false, 0))
    }
    @Test fun lateFreshPongCancelsRecoveryButOldPongDoesNot() {
        val p = TransportRecoveryPolicy(1_000)
        assertTrue(p.shouldRecover(61_000, true, true, true, 0))
        assertFalse(p.shouldRecover(90_000, true, true, true, 89_000))
        assertFalse(p.shouldRecover(148_999, true, true, true, 89_000))
        assertTrue(p.shouldRecover(149_000, true, true, true, 89_000))
        assertTrue(p.shouldRecover(209_000, true, true, true, 89_000))
    }
    @Test fun healthyPongsNeverCausePeriodicRestart() {
        val p = TransportRecoveryPolicy(1_000)
        for (now in 25_000L..500_000L step 25_000L) {
            assertFalse(p.shouldRecover(now, true, true, true, now))
        }
    }
    @Test fun pongsDoNotHideFailedDeviceRegistration() {
        val p = TransportRecoveryPolicy(1_000)
        assertTrue(p.shouldRecover(31_000, true, true, false, 30_000))
    }
}
