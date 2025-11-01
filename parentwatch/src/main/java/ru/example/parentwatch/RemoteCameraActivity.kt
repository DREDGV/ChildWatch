package ru.example.parentwatch

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.example.parentwatch.databinding.ActivityRemoteCameraBinding
import ru.example.parentwatch.network.WebSocketManager

/**
 * Remote Camera Activity
 * 
 * Features:
 * - Take remote photo (front/back camera)
 * - View captured photos in gallery
 * - Delete photos
 */
class RemoteCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteCameraActivity"
    }

    private lateinit var binding: ActivityRemoteCameraBinding
    private var selectedChildId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedChildId = intent.getStringExtra("childId")

        setupToolbar()
        setupButtons()
        loadPhotos()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Удаленная камера"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupButtons() {
        // Take photo with front camera
        binding.takeFrontPhotoButton.setOnClickListener {
            takePhoto("front")
        }

        // Take photo with back camera
        binding.takeBackPhotoButton.setOnClickListener {
            takePhoto("back")
        }

        // Refresh gallery
        binding.refreshButton.setOnClickListener {
            loadPhotos()
        }
    }

    /**
     * Send take photo command to child device
     */
    private fun takePhoto(camera: String) {
        val childId = selectedChildId
        if (childId.isNullOrEmpty()) {
            Toast.makeText(this, "Устройство не выбрано", Toast.LENGTH_SHORT).show()
            return
        }

        val cameraText = if (camera == "front") "передней" else "задней"
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Сделать фото?")
            .setMessage("Будет сделано фото с $cameraText камеры устройства ребенка")
            .setPositiveButton("Да") { _, _ ->
                sendTakePhotoCommand(childId, camera)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Send take_photo command via WebSocket
     */
    private fun sendTakePhotoCommand(childId: String, camera: String) {
        try {
            if (!WebSocketManager.isConnected()) {
                Toast.makeText(this, "Нет подключения к серверу", Toast.LENGTH_SHORT).show()
                binding.statusText.text = "Нет подключения к серверу"
                return
            }

            binding.statusText.text = "Отправка команды..."
            
            val commandData = JSONObject().apply {
                put("camera", camera)
            }

            WebSocketManager.getClient()?.sendCommand(
                "take_photo",
                commandData,
                onSuccess = {
                    runOnUiThread {
                        binding.statusText.text = "Команда отправлена. Ожидание фото..."
                        Toast.makeText(
                            this@RemoteCameraActivity,
                            "Команда отправлена на устройство",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        // Refresh after 5 seconds
                        binding.statusText.postDelayed({
                            loadPhotos()
                        }, 5000)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        binding.statusText.text = "Ошибка: $error"
                        Toast.makeText(
                            this@RemoteCameraActivity,
                            "Ошибка: $error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending photo command", e)
            binding.statusText.text = "Ошибка: ${e.message}"
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Load captured photos from server
     */
    private fun loadPhotos() {
        val childId = selectedChildId
        if (childId.isNullOrEmpty()) {
            binding.statusText.text = "Устройство не выбрано"
            return
        }

        lifecycleScope.launch {
            try {
                binding.statusText.text = "Загрузка фото..."
                
                // TODO: Implement API call to get photos list
                // For now, just show placeholder
                binding.statusText.text = "Нет фотографий"
                binding.emptyText.text = "Фотографии будут отображаться здесь после съемки"
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                binding.statusText.text = "Ошибка загрузки"
            }
        }
    }
}
