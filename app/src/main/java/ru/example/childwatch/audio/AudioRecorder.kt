package ru.example.childwatch.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import ru.example.childwatch.utils.PermissionHelper
import java.io.File
import java.io.IOException

/**
 * AudioRecorder for capturing audio in chunks
 * 
 * Features:
 * - Records audio in configurable durations
 * - Saves to temporary files
 * - Handles Android version differences
 * - Respects Android 14+ background microphone restrictions
 */
class AudioRecorder(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioRecorder"
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128000
    }
    
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var isRecording = false
    
    /**
     * Start recording audio for specified duration
     * Returns the file where audio will be saved
     * 
     * NOTE: On Android 14+, this only works in foreground service
     * with proper notification showing microphone usage
     */
    fun startRecording(durationSeconds: Int): File? {
        if (!PermissionHelper.hasAudioPermission(context)) {
            Log.e(TAG, "Audio recording permission not granted")
            return null
        }
        
        if (isRecording) {
            Log.w(TAG, "Already recording audio")
            return currentAudioFile
        }
        
        try {
            // Create temporary file for audio
            currentAudioFile = createTempAudioFile()
            
            // Initialize MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            mediaRecorder?.apply {
                // Set audio source - use MIC for general recording
                // NOTE: Android 14+ may restrict background microphone access
                setAudioSource(MediaRecorder.AudioSource.MIC)
                
                // Set output format and encoder
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                
                // Set quality parameters
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setAudioEncodingBitRate(AUDIO_BITRATE)
                
                // Set output file
                setOutputFile(currentAudioFile?.absolutePath)
                
                // Set maximum duration (as backup to manual stop)
                setMaxDuration(durationSeconds * 1000)
                
                // Set listener for when recording stops
                setOnInfoListener { mr, what, extra ->
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                            Log.d(TAG, "Max duration reached, stopping recording")
                            stopRecording()
                        }
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                            Log.d(TAG, "Max file size reached, stopping recording")
                            stopRecording()
                        }
                    }
                }
                
                // Prepare and start recording
                prepare()
                start()
                
                isRecording = true
                Log.d(TAG, "Started audio recording for ${durationSeconds}s to ${currentAudioFile?.name}")
            }
            
            return currentAudioFile
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start audio recording", e)
            cleanup()
            return null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting audio recording (Android 14+ background restriction?)", e)
            cleanup()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting audio recording", e)
            cleanup()
            return null
        }
    }
    
    /**
     * Stop recording audio
     */
    fun stopRecording(): File? {
        if (!isRecording) {
            Log.w(TAG, "Not currently recording")
            return currentAudioFile
        }
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            
            isRecording = false
            Log.d(TAG, "Stopped audio recording")
            
            val recordedFile = currentAudioFile
            
            // Check if file was created and has content
            if (recordedFile?.exists() == true && recordedFile.length() > 0) {
                Log.d(TAG, "Audio file recorded: ${recordedFile.name}, size: ${recordedFile.length()} bytes")
                return recordedFile
            } else {
                Log.w(TAG, "Audio file is empty or doesn't exist")
                recordedFile?.delete()
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
            return null
        } finally {
            cleanup()
        }
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Create temporary file for audio recording
     */
    private fun createTempAudioFile(): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "audio_${timestamp}.m4a"
        
        // Use internal cache directory for temporary files
        val cacheDir = context.cacheDir
        return File(cacheDir, fileName)
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        mediaRecorder?.release()
        mediaRecorder = null
        currentAudioFile = null
        isRecording = false
    }
    
    /**
     * Get supported audio formats for this device
     */
    fun getSupportedFormats(): List<String> {
        return listOf("AAC", "MPEG_4") // Basic formats that should work on most devices
    }
    
    /**
     * Check if audio recording is available on this device
     * On Android 14+, background recording may be restricted
     */
    fun isAudioRecordingAvailable(): Boolean {
        return try {
            // Check permission
            if (!PermissionHelper.hasAudioPermission(context)) {
                Log.d(TAG, "Audio permission not granted")
                return false
            }
            
            // On Android 14+, background microphone access is restricted
            // This should only be called from foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.d(TAG, "Android 14+ detected - audio recording only available in foreground service")
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking audio recording availability", e)
            false
        }
    }
}
