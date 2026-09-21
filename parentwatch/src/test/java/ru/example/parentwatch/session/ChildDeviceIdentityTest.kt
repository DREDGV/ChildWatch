package ru.example.parentwatch.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which device identifier the application uses.
 *
 * This exists because one phone once registered twice under two identities: the
 * token manager generated `device_<androidId>` while the rest of the application
 * stored a `child-xxxxxxxx` value. The server then held two member records for a
 * single device, and later requests addressed the wrong one.
 */
class ChildDeviceIdentityTest {

    @Test
    fun `the identifier the device is bound by wins over the generated form`() {
        // This is the situation that caused the duplicate: a bound `child-` value
        // in the application's store and a generated `device_` value in the token
        // store. Choosing the token store would orphan the registered device.
        val chosen = ChildDeviceIdentity.selectStored(
            appChildDeviceId = "child-63d15754",
            appDeviceId = "child-63d15754",
            tokenDeviceId = "device_e3db3037711fc78e"
        )
        assertEquals("child-63d15754", chosen)
    }

    @Test
    fun `the application store is used when only the generic key is present`() {
        val chosen = ChildDeviceIdentity.selectStored(
            appChildDeviceId = null,
            appDeviceId = "child-abcdef12",
            tokenDeviceId = "device_e3db3037711fc78e"
        )
        assertEquals("child-abcdef12", chosen)
    }

    @Test
    fun `the token store is used when the application store is empty`() {
        val chosen = ChildDeviceIdentity.selectStored(
            appChildDeviceId = null,
            appDeviceId = null,
            tokenDeviceId = "device_e3db3037711fc78e"
        )
        assertEquals("device_e3db3037711fc78e", chosen)
    }

    @Test
    fun `an older store is read so a registered device keeps its identity`() {
        val chosen = ChildDeviceIdentity.selectStored(
            appChildDeviceId = null,
            appDeviceId = null,
            tokenDeviceId = null,
            legacyChildDeviceId = "child-6bc359d8"
        )
        assertEquals("child-6bc359d8", chosen)
    }

    @Test
    fun `a blank or padded value is not accepted as an identifier`() {
        // A blank identifier must never be returned, otherwise the application
        // would register with an empty device id; padding is trimmed so the same
        // device is not seen as a different one.
        assertNull(
            ChildDeviceIdentity.selectStored(
                appChildDeviceId = "   ",
                appDeviceId = "",
                tokenDeviceId = null
            )
        )
        assertEquals(
            "child-63d15754",
            ChildDeviceIdentity.selectStored(
                appChildDeviceId = "  child-63d15754  ",
                appDeviceId = null,
                tokenDeviceId = null
            )
        )
    }

    @Test
    fun `nothing stored means no identifier is chosen`() {
        // resolve() derives a stable value in this case; selectStored() must not
        // invent one, so the derivation stays in a single place.
        assertNull(
            ChildDeviceIdentity.selectStored(
                appChildDeviceId = null,
                appDeviceId = null,
                tokenDeviceId = null
            )
        )
    }

    @Test
    fun `the choice is stable across repeated calls`() {
        val first = ChildDeviceIdentity.selectStored("child-63d15754", null, "device_x")
        val second = ChildDeviceIdentity.selectStored("child-63d15754", null, "device_x")
        assertEquals(first, second)
        assertTrue(first == "child-63d15754")
    }
}
