package ru.example.childwatch.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.example.childwatch.utils.ParentMonitorProfileNameRules.FALLBACK
import ru.example.childwatch.utils.ParentMonitorProfileNameRules.chooseDisplayName
import ru.example.childwatch.utils.ParentMonitorProfileNameRules.isUsablePersonName

/**
 * The person card on the parent home screen showed the family name where the
 * owner's own name belongs, because the stored profile name was created during
 * onboarding from the family name. The rules below are what stops that value
 * from being presented as somebody's name.
 */
class ParentMonitorProfileManagerNameTest {

    private companion object {
        const val FAMILY = "Семья"
    }

    @Test
    fun `the family name is never shown as the person's name`() {
        assertEquals(FALLBACK, chooseDisplayName(null, FAMILY, FAMILY))
        assertEquals(FALLBACK, chooseDisplayName("", FAMILY, FAMILY))
        assertEquals(FALLBACK, chooseDisplayName(FAMILY, FAMILY, FAMILY))
    }

    @Test
    fun `the name published to the family wins over the local copy`() {
        assertEquals("Папа", chooseDisplayName("Папа", FAMILY, FAMILY))
        assertEquals("Папа", chooseDisplayName("Папа", null, FAMILY))
        assertEquals("Папа", chooseDisplayName("  Папа  ", FAMILY, FAMILY))
    }

    @Test
    fun `a stored name is used when the family has no name for the person`() {
        assertEquals("Мама", chooseDisplayName(null, "Мама", FAMILY))
        assertEquals("Мама", chooseDisplayName("   ", "Мама", FAMILY))
    }

    @Test
    fun `a person really called like the family is still shown`() {
        // The rule removes the family name only when it is not the person's own.
        assertEquals("Семья", chooseDisplayName("Семья", null, "Дом"))
    }

    @Test
    fun `device identifiers are not names`() {
        assertFalse(isUsablePersonName("child-63d15754"))
        assertFalse(isUsablePersonName("device_1...906d"))
        assertFalse(isUsablePersonName("12345"))
        assertFalse(isUsablePersonName("   "))
        assertEquals(
            FALLBACK,
            chooseDisplayName("child-63d15754", "device_1...906d", FAMILY)
        )
    }

    @Test
    fun `real names in any script are accepted`() {
        assertTrue(isUsablePersonName("Папа"))
        assertTrue(isUsablePersonName("Григорий"))
        assertTrue(isUsablePersonName("Dad"))
        assertTrue(isUsablePersonName("Мама 2"))
    }
}
