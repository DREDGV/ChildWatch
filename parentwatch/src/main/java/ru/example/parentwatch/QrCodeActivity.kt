package ru.example.parentwatch

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import ru.example.parentwatch.databinding.ActivityQrCodeBinding

/**
 * QR Code Activity for ParentWatch
 * Displays QR code with Device ID for easy pairing
 */
class QrCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrCodeBinding

    companion object {
        private const val TAG = "QrCodeActivity"
        private const val QR_CODE_SIZE = 512 // Size in pixels
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "QR-код устройства"

        setupUI()
        generateAndDisplayQrCode()
    }

    private fun setupUI() {
        // Close button
        binding.closeButton.setOnClickListener {
            finish()
        }

        // Share button
        binding.shareButton.setOnClickListener {
            shareQrCode()
        }
    }

    private fun generateAndDisplayQrCode() {
        try {
            val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", null)

            if (deviceId.isNullOrEmpty()) {
                Toast.makeText(this, "Device ID не найден", Toast.LENGTH_SHORT).show()
                binding.deviceIdText.text = "Device ID не настроен"
                return
            }

            // Display Device ID
            binding.deviceIdText.text = deviceId

            // Generate QR code
            val qrCodeBitmap = generateQrCode(deviceId)

            // Display QR code
            binding.qrCodeImageView.setImageBitmap(qrCodeBitmap)

            // Show instructions
            binding.instructionsText.text = """
                Отсканируйте этот QR-код с помощью
                родительского приложения (ChildWatch)
                для быстрого сопряжения устройств
            """.trimIndent()

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка генерации QR-кода: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Generate QR code bitmap from Device ID
     */
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

    /**
     * Share QR code (future implementation)
     */
    private fun shareQrCode() {
        // TODO: Implement sharing functionality
        Toast.makeText(this, "Функция поделиться в разработке", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
