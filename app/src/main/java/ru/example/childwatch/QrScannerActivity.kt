package ru.example.childwatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ru.example.childwatch.databinding.ActivityQrScannerBinding
import ru.childwatch.shared.onboarding.FamilyInvitationTokenParser
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScannerBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private var finished = false
    @Volatile private var processing = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.scanHint.setText(R.string.family_scan_permission)
    }
    private val picture = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image -> read(image) {} }
            .onFailure { binding.scanHint.setText(R.string.family_scan_image_error) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        binding.scanCloseButton.setOnClickListener { finish() }
        binding.scanImageButton.setOnClickListener { picture.launch("image/*") }
        binding.scanCameraButton.setOnClickListener { requestCamera() }
        requestCamera()
    }
    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                    @ExperimentalGetImage
                    override fun analyze(proxy: ImageProxy) {
                        val media = proxy.image
                        if (isFinishing || isDestroyed || finished || processing || media == null) { proxy.close(); return }
                        processing = true
                        read(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)) {
                            processing = false
                            proxy.close()
                        }
                    }
                })
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                binding.scanHint.setText(R.string.family_scan_hint)
            } catch (failure: Exception) {
                Log.w("FamilyQr", "Camera unavailable", failure)
                binding.scanHint.setText(R.string.family_scan_camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun read(image: InputImage, complete: () -> Unit) {
        if (isFinishing || isDestroyed || finished) { complete(); return }
        scanner.process(image).addOnSuccessListener { codes ->
            if (isFinishing || isDestroyed || finished) return@addOnSuccessListener
            val value = codes.firstOrNull { it.rawValue != null }?.rawValue
            if (value != null) {
                if (intent.getBooleanExtra("invitation_only", false) && FamilyInvitationTokenParser.parse(value) == null)
                    binding.scanHint.setText(R.string.family_scan_not_invitation)
                else {
                    finished = true
                    setResult(RESULT_OK, Intent().putExtra("SCANNED_QR_CODE", value))
                    finish()
                }
            } else binding.scanHint.setText(R.string.family_scan_no_code)
        }.addOnFailureListener {
            if (!isDestroyed) binding.scanHint.setText(R.string.family_scan_image_error)
        }.addOnCompleteListener { complete() }
    }
    override fun onDestroy() {
        scanner.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
