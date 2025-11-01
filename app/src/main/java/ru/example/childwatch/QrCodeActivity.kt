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

/**
 * Activity для отображения QR-кода родительского устройства
 * Используется в ParentWatch для сканирования и сохранения parent_device_id
 */
class QrCodeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_code)

        supportActionBar?.apply {
            title = "QR-код устройства"
            setDisplayHomeAsUpEnabled(true)
        }

        // Получаем device_id
        val deviceId = getMyDeviceId()
        
        if (deviceId.isEmpty()) {
            Toast.makeText(this, "Device ID не найден", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Генерация QR-кода
        val qrImageView = findViewById<ImageView>(R.id.qrImageView)
        val deviceIdText = findViewById<TextView>(R.id.deviceIdText)
        
        try {
            val qrBitmap = generateQRCode(deviceId, 512)
            qrImageView.setImageBitmap(qrBitmap)
            deviceIdText.text = "Device ID: $deviceId"
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка генерации QR: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /**
     * Получение device_id из SharedPreferences
     */
    private fun getMyDeviceId(): String {
        val prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", "") ?: ""
    }

    /**
     * Генерация QR-кода из строки
     */
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
