package ru.example.childwatch.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecurePreferencesRemovalTest {
    @Test fun nullAndRemoveClearEncryptedCopyEvenWithoutEncryption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A dedicated fixture never touches the family's real preferences or tokens.
        val name = "removal_test_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val prefs = SecurePreferences(context, name, encryptSensitive = false)
            for (useNull in listOf(true, false)) {
                assertTrue(raw.edit().putBoolean("encryption_enabled", false)
                    .putString("auth_token", "old plain fixture")
                    .putString("enc_auth_token", "old encrypted fixture")
                    .putString("keep", "unrelated").commit())
                if (useNull) prefs.putString("auth_token", null) else prefs.remove("auth_token")
                assertFalse(raw.contains("auth_token"))
                assertFalse(raw.contains("enc_auth_token"))
                assertEquals("unrelated", raw.getString("keep", null))
                assertNull(SecurePreferences(context, name, encryptSensitive = false).getString("auth_token"))
            }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
