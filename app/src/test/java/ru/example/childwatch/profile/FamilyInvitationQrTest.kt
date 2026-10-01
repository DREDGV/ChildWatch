package ru.example.childwatch.profile

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser

class FamilyInvitationQrTest {
    @Test fun generatedInvitationCanBeReadAtPhoneAndImageSizes() {
        for (size in listOf(240, 640)) {
            val token = "0123456789abcdef".repeat(4)
            val uri = "childwatch://family/join?token=$token"
            val matrix = FamilyInvitationQr.encode(uri, size)
            val source = object : LuminanceSource(size, size) {
                override fun getRow(y: Int, row: ByteArray?): ByteArray = ByteArray(size) { x ->
                    (if (matrix[x, y]) 0 else 255).toByte()
                }
                override fun getMatrix(): ByteArray = ByteArray(size * size) { i ->
                    (if (matrix[i % size, i / size]) 0 else 255).toByte()
                }
            }
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
            assertEquals(uri, decoded)
            assertEquals(token, FamilyInvitationTokenParser.parse(decoded))
        }
    }
}
