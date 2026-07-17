package ru.example.childwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import ru.example.childwatch.utils.SecureSettingsManager

class QrCodeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_code)

        supportActionBar?.apply {
            title = getString(R.string.parent_qr_title_backup)
            setDisplayHomeAsUpEnabled(true)
        }

        val deviceId = getOwnDeviceId()
        if (deviceId.isBlank()) {
            Toast.makeText(this, getString(R.string.parent_qr_missing_device), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val qrImageView = findViewById<ImageView>(R.id.qrImageView)
        val titleText = findViewById<TextView>(R.id.qrTitleText)
        val subtitleText = findViewById<TextView>(R.id.qrSubtitleText)
        val deviceIdText = findViewById<TextView>(R.id.deviceIdText)
        val helpText = findViewById<TextView>(R.id.qrHelpText)

        try {
            qrImageView.setImageBitmap(generateQRCode(deviceId, 512))
            titleText.text = getString(R.string.parent_qr_title_backup)
            subtitleText.text = getString(R.string.parent_qr_subtitle_backup)
            deviceIdText.text = getString(R.string.parent_qr_device_label, deviceId)
            helpText.text = getString(R.string.parent_qr_help_backup)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка генерации QR: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun getOwnDeviceId(): String {
        val secure = SecureSettingsManager(this)
        secure.getDeviceId()?.let { if (it.isNotBlank()) return it }

        val tokens = getSharedPreferences("childwatch_tokens", Context.MODE_PRIVATE)
        tokens.getString("device_id", null)?.let { idFromTokens ->
            if (idFromTokens.isNotBlank()) {
                secure.setDeviceId(idFromTokens)
                return idFromTokens
            }
        }

        val legacy = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            .getString("device_id", null)
        if (!legacy.isNullOrBlank()) {
            secure.setDeviceId(legacy)
            return legacy
        }

        val androidId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        val generated = "device_$androidId"
        secure.setDeviceId(generated)
        getSharedPreferences("childwatch_tokens", Context.MODE_PRIVATE)
            .edit().putString("device_id", generated).apply()
        return generated
    }

    private fun generateQRCode(text: String, size: Int): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }
}
