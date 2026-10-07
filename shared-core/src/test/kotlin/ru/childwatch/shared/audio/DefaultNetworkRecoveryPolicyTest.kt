package ru.childwatch.shared.audio

import org.junit.Assert.*
import org.junit.Test

class DefaultNetworkRecoveryPolicyTest {
    @Test fun healthySocketFlagsDoNotHideWifiToCellularSwitch() {
        val route = DefaultNetworkRecoveryPolicy("wifi")
        assertTrue(route.validated("cellular"))
        assertFalse(route.validated("cellular"))
    }
    @Test fun returnOfSameRouteAfterLossNeedsOneRecovery() {
        val route = DefaultNetworkRecoveryPolicy("wifi")
        route.lost("wifi")
        assertTrue(route.validated("wifi"))
        assertFalse(route.validated("wifi"))
    }
    @Test fun lateLossOfOldNetworkCannotTearDownNewOne() {
        val route = DefaultNetworkRecoveryPolicy("wifi")
        assertTrue(route.validated("cellular"))
        route.lost("wifi")
        assertFalse(route.validated("cellular"))
    }
    @Test fun startupOfflineGetsConnectionOnFirstUsableRoute() {
        assertTrue(DefaultNetworkRecoveryPolicy().validated("cellular"))
    }
    @Test fun initialReadyRouteAndDuplicateCapabilitiesDoNotChurnSocket() {
        val route = DefaultNetworkRecoveryPolicy("wifi")
        assertFalse(route.validated("wifi"))
        assertFalse(route.validated(""))
        route.lost("unrelated")
        assertFalse(route.validated("wifi"))
    }
}
