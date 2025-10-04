package ru.example.childwatch

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ru.example.childwatch.databinding.ActivityLocationMapBinding
import ru.example.childwatch.location.LocationManager
import ru.example.childwatch.utils.PermissionHelper
import kotlinx.coroutines.*

/**
 * Location Map Activity for displaying current location
 * 
 * Features:
 * - Current location display
 * - Location coordinates
 * - Location accuracy info
 * - Refresh location
 */
class LocationMapActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "LocationMapActivity"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
    
    private lateinit var binding: ActivityLocationMapBinding
    private lateinit var locationManager: LocationManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize location manager
        locationManager = LocationManager(this)
        
        // Setup UI
        setupUI()
        
        // Get initial location
        refreshLocation()
    }
    
    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Местоположение ребенка"
        
        // Refresh location button
        binding.refreshLocationButton.setOnClickListener {
            refreshLocation()
        }
        
        // Show initial message
        binding.locationInfoText.text = "Получение местоположения..."
    }
    
    private fun refreshLocation() {
        if (!PermissionHelper.hasLocationPermissions(this)) {
            requestLocationPermission()
            return
        }
        
        binding.locationInfoText.text = "Получение местоположения..."
        
        // Use coroutine to get location
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val location = locationManager.getCurrentLocation()
                if (location != null) {
                    updateLocationInfo(location)
                } else {
                    binding.locationInfoText.text = "Не удалось получить геолокацию"
                    Toast.makeText(this@LocationMapActivity, "Не удалось получить геолокацию", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission denied", e)
                binding.locationInfoText.text = "Нет разрешения на геолокацию"
                Toast.makeText(this@LocationMapActivity, "Нет разрешения на геолокацию", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Location error", e)
                binding.locationInfoText.text = "Ошибка получения геолокации: ${e.message}"
                Toast.makeText(this@LocationMapActivity, "Ошибка получения геолокации", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateLocationInfo(location: Location) {
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        
        binding.locationInfoText.text = buildString {
            appendLine("📍 Текущее местоположение:")
            appendLine()
            appendLine("Широта: ${String.format("%.6f", location.latitude)}")
            appendLine("Долгота: ${String.format("%.6f", location.longitude)}")
            appendLine("Точность: ${location.accuracy.toInt()} метров")
            appendLine("Время: ${timeFormat.format(java.util.Date(location.time))}")
            appendLine()
            appendLine("Статус: ✅ Местоположение получено")
        }
        
        Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude}")
        Toast.makeText(this, "Местоположение обновлено", Toast.LENGTH_SHORT).show()
    }
    
    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshLocation()
            } else {
                binding.locationInfoText.text = "Разрешение на геолокацию необходимо для работы"
                Toast.makeText(this, "Разрешение на геолокацию необходимо для работы", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
