package ru.example.parentwatch

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import ru.example.parentwatch.databinding.ActivityQrCodeBinding
import ru.example.parentwatch.session.ChildActiveSessionStore

class QrCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrCodeBinding
    private val sessionStore by lazy { ChildActiveSessionStore(this) }

    companion object {
        private const val QR_CODE_SIZE = 512
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.child_qr_title_primary)

        setupUI()
        generateAndDisplayQrCode()
    }

    private fun setupUI() {
        binding.closeButton.setOnClickListener { finish() }
        binding.shareButton.setOnClickListener { shareQrCode() }
    }

    private fun generateAndDisplayQrCode() {
        try {
            val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            val deviceId = sessionStore.resolveCurrentChildId().ifBlank {
                prefs.getString("device_id", null).orEmpty()
            }

            if (deviceId.isBlank()) {
                Toast.makeText(this, getString(R.string.child_qr_missing_device), Toast.LENGTH_SHORT).show()
                binding.deviceIdText.text = getString(R.string.child_qr_missing_device)
                return
            }

            binding.qrTitleText.text = getString(R.string.child_qr_title_primary)
            binding.instructionsText.text = getString(R.string.child_qr_subtitle_primary)
            binding.qrHelpText.text = getString(R.string.child_qr_help_primary)
            binding.deviceIdText.text = getString(R.string.child_qr_device_label, deviceId)
            binding.qrCodeImageView.setImageBitmap(generateQrCode(deviceId))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка генерации QR-кода: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateQrCode(deviceId: String): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(deviceId, BarcodeFormat.QR_CODE, QR_CODE_SIZE, QR_CODE_SIZE)

        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }

    private fun shareQrCode() {
        Toast.makeText(this, "Функция поделиться пока в разработке", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
