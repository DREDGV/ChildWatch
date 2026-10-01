package ru.example.childwatch.profile

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser

object FamilyInvitationQr {
    fun encode(value: String, size: Int): BitMatrix {
        require(FamilyInvitationTokenParser.parse(value) != null)
        return QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
}
