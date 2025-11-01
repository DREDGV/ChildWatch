package ru.example.childwatch

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONObject
import ru.example.childwatch.network.WebSocketManager
import android.view.View
import android.widget.LinearLayout

/**
 * RemoteCameraActivity - Remote photo capture for ParentMonitor
 * 
 * Features:
 * - Send take_photo commands to child device via WebSocket
 * - Display gallery of captured photos
 * - Support front and back camera
 */
class RemoteCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteCameraActivity"
        const val EXTRA_CHILD_ID = "childId"
        const val EXTRA_CHILD_NAME = "childName"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var childNameText: TextView
    private lateinit var frontCameraButton: MaterialButton
    private lateinit var backCameraButton: MaterialButton
    private lateinit var photosRecyclerView: RecyclerView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var emptyStateLayout: LinearLayout

    private var childId: String? = null
    private var childName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_camera)

        // Get child device info from intent
        childId = intent.getStringExtra(EXTRA_CHILD_ID)
        childName = intent.getStringExtra(EXTRA_CHILD_NAME)

        if (childId == null) {
            Toast.makeText(this, "Ошибка: не указан ID устройства ребенка", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupToolbar()
        setupButtons()
        loadPhotos()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        statusText = findViewById(R.id.statusText)
        childNameText = findViewById(R.id.childNameText)
        frontCameraButton = findViewById(R.id.frontCameraButton)
        backCameraButton = findViewById(R.id.backCameraButton)
        photosRecyclerView = findViewById(R.id.photosRecyclerView)
        progressIndicator = findViewById(R.id.progressIndicator)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)

        // Display child name if available
        childNameText.text = if (childName != null) {
            "Устройство: $childName"
        } else {
            "ID: $childId"
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupButtons() {
        frontCameraButton.setOnClickListener {
            takePhoto("front")
        }

        backCameraButton.setOnClickListener {
            takePhoto("back")
        }
    }

    /**
     * Send take_photo command to child device
     */
    private fun takePhoto(camera: String) {
        Log.d(TAG, "Taking photo with $camera camera for child: $childId")
        
        // Check WebSocket connection
        if (!WebSocketManager.isConnected()) {
            Toast.makeText(
                this,
                "Нет связи с устройством ребенка. Подключите устройство.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Update UI
        updateStatus("Отправка команды...")
        disableButtons()

        // Prepare command data
        val commandData = JSONObject().apply {
            put("camera", camera)
        }

        // Send command via WebSocket
        sendTakePhotoCommand(childId!!, camera)
    }

    /**
     * Send take_photo command via WebSocket
     */
    private fun sendTakePhotoCommand(deviceId: String, camera: String) {
        try {
            val commandData = JSONObject().apply {
                put("camera", camera)
            }

            WebSocketManager.sendCommand(
                "take_photo",
                commandData,
                onSuccess = {
                    Log.d(TAG, "✅ take_photo command sent successfully")
                    runOnUiThread {
                        updateStatus("Команда отправлена")
                        Toast.makeText(this, "Команда отправлена устройству", Toast.LENGTH_SHORT).show()
                        enableButtons()
                        
                        // Reload photos after a delay to show the new photo
                        statusText.postDelayed({
                            loadPhotos()
                        }, 3000)
                    }
                },
                onError = { error ->
                    Log.e(TAG, "❌ Failed to send command: $error")
                    runOnUiThread {
                        updateStatus("Ошибка отправки")
                        Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_SHORT).show()
                        enableButtons()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending command", e)
            runOnUiThread {
                updateStatus("Ошибка")
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                enableButtons()
            }
        }
    }

    /**
     * Load photos from server
     */
    private fun loadPhotos() {
        Log.d(TAG, "Loading photos for device: $childId")
        
        // Show loading state
        progressIndicator.visibility = View.VISIBLE
        photosRecyclerView.visibility = View.GONE
        emptyStateLayout.visibility = View.GONE
        
        // TODO: Implement API call to fetch photos
        // For now, show empty state
        progressIndicator.postDelayed({
            progressIndicator.visibility = View.GONE
            emptyStateLayout.visibility = View.VISIBLE
            updateStatus("Готов к съемке")
        }, 1000)
    }

    private fun updateStatus(status: String) {
        statusText.text = status
    }

    private fun disableButtons() {
        frontCameraButton.isEnabled = false
        backCameraButton.isEnabled = false
    }

    private fun enableButtons() {
        frontCameraButton.isEnabled = true
        backCameraButton.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RemoteCameraActivity destroyed")
    }
}
