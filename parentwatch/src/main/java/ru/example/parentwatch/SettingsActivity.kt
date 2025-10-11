package ru.example.parentwatch

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.example.parentwatch.databinding.ActivitySettingsBinding

/**
 * Settings Activity for ParentWatch
 * 
 * Features:
 * - Server URL configuration
 * - Device ID display and management
 * - Monitoring intervals
 * - About information
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"
        
        setupUI()
        loadSettings()
    }
    
    private fun setupUI() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        
        // Load current settings
        val serverUrl = prefs.getString("server_url", getString(R.string.server_url_hint)) ?: getString(R.string.server_url_hint)
        val deviceId = prefs.getString("device_id", "Не настроен") ?: "Не настроен"
        
        binding.serverUrlInput.setText(serverUrl)
        binding.deviceIdText.setText(deviceId)
        
        // Save button
        binding.saveButton.setOnClickListener {
            saveSettings()
        }
        
        // Localhost button
        binding.useLocalhostBtn.setOnClickListener {
            binding.serverUrlInput.setText("http://10.0.2.2:3000")
        }
        
        // Railway button
        binding.useRailwayBtn.setOnClickListener {
            binding.serverUrlInput.setText("https://childwatch-production.up.railway.app")
        }
        
        // Copy Device ID button
        binding.copyIdButton.setOnClickListener {
            copyDeviceId()
        }
        
        // Show QR Code button
        binding.showQrButton.setOnClickListener {
            showQRCode()
        }
    }
    
    private fun loadSettings() {
        // Settings are loaded in setupUI
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val serverUrl = binding.serverUrlInput.text.toString().trim()
        
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Введите URL сервера", Toast.LENGTH_SHORT).show()
            return
        }
        
        prefs.edit()
            .putString("server_url", serverUrl)
            .apply()
        
        Toast.makeText(this, "✅ Настройки сохранены", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun copyDeviceId() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        
        if (deviceId == null) {
            Toast.makeText(this, "Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "✅ Device ID скопирован", Toast.LENGTH_SHORT).show()
    }
    
    private fun showQRCode() {
        // TODO: Implement QR code display
        Toast.makeText(this, "QR-код (в разработке)", Toast.LENGTH_SHORT).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

