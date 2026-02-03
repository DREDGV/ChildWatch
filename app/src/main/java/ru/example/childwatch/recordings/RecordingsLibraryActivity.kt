package ru.example.childwatch.recordings

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ru.example.childwatch.R
import ru.example.childwatch.audio.RecordingMetadata
import ru.example.childwatch.audio.RecordingRepository
import ru.example.childwatch.databinding.ActivityRecordingsLibraryBinding
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsLibraryBinding
    private lateinit var repository: RecordingRepository
    private val recordingsAdapter by lazy {
        RecordingsAdapter(
            onPlayClicked = ::handlePlayClicked,
            onDeleteClicked = ::handleDeleteClicked,
            onSaveClicked = ::handleSaveClicked
        )
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: String? = null
    private var currentMetadata: RecordingMetadata? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var pendingExport: RecordingMetadata? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/*")
    ) { uri: Uri? ->
        val metadata = pendingExport
        pendingExport = null
        if (uri != null && metadata != null) {
            exportRecording(metadata, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Don't set support action bar - use toolbar directly
        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        repository = RecordingRepository(this)

        binding.recordingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@RecordingsLibraryActivity)
            adapter = recordingsAdapter
        }

        setupPlayerControls()
        loadRecordings()
    }

    override fun onStop() {
        super.onStop()
        // Do not stop playback on screen off or background.
        progressHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        mediaPlayer?.let {
            if (it.isPlaying) {
                updatePlayerState(isPlaying = true)
                startProgressUpdates()
            }
        }
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun loadRecordings() {
        val items = repository.getRecordings()
        recordingsAdapter.submitList(items)
        recordingsAdapter.setPlayingId(currentlyPlayingId)
        binding.recordingsRecyclerView.isVisible = items.isNotEmpty()
        binding.emptyView.isVisible = items.isEmpty()
    }

    private fun setupPlayerControls() {
        binding.playerCard.isVisible = false
        binding.playPauseButton.setOnClickListener {
            togglePlayerPlayback()
        }
        binding.rewindButton.setOnClickListener {
            seekBy(-10_000)
        }
        binding.forwardButton.setOnClickListener {
            seekBy(10_000)
        }
        binding.exportButton.setOnClickListener {
            currentMetadata?.let { handleSaveClicked(it) }
        }
        binding.playerSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updatePlayerTime(progress, seekBar?.max ?: 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val player = mediaPlayer ?: return
                val target = seekBar?.progress ?: return
                player.seekTo(target)
                isSeeking = false
            }
        })
    }

    private fun handlePlayClicked(metadata: RecordingMetadata) {
        if (!File(metadata.filePath).exists()) {
            Toast.makeText(this, R.string.recording_missing_file, Toast.LENGTH_LONG).show()
            repository.removeRecording(metadata.id)
            loadRecordings()
            return
        }

        if (metadata.id == currentlyPlayingId) {
            stopPlayback()
        } else {
            playRecording(metadata)
        }
    }

    private fun playRecording(metadata: RecordingMetadata) {
        stopPlayback()
        val player = MediaPlayer()
        try {
            player.setDataSource(metadata.filePath)
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setOnPreparedListener {
                it.start()
                updatePlayerState(isPlaying = true)
                startProgressUpdates()
            }
            player.setOnCompletionListener { stopPlayback() }
            player.prepareAsync()
            mediaPlayer = player
            currentlyPlayingId = metadata.id
            currentMetadata = metadata
            recordingsAdapter.setPlayingId(currentlyPlayingId)
            showPlayer(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play recording", e)
            player.release()
            mediaPlayer = null
            currentlyPlayingId = null
            currentMetadata = null
            recordingsAdapter.setPlayingId(null)
            Toast.makeText(this, R.string.recording_play_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayback() {
        progressHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (ignored: IllegalStateException) {
            } finally {
                player.release()
            }
        }
        mediaPlayer = null
        currentlyPlayingId = null
        currentMetadata = null
        recordingsAdapter.setPlayingId(null)
        binding.playerCard.isVisible = false
    }

    private fun handleDeleteClicked(metadata: RecordingMetadata) {
        val wasPlaying = metadata.id == currentlyPlayingId
        if (repository.removeRecording(metadata.id)) {
            if (wasPlaying) {
                stopPlayback()
            }
            Toast.makeText(this, R.string.recording_deleted, Toast.LENGTH_SHORT).show()
            loadRecordings()
        } else {
            Toast.makeText(this, R.string.recording_delete_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSaveClicked(metadata: RecordingMetadata) {
        if (!File(metadata.filePath).exists()) {
            Toast.makeText(this, R.string.recording_missing_file, Toast.LENGTH_LONG).show()
            repository.removeRecording(metadata.id)
            loadRecordings()
            return
        }
        pendingExport = metadata
        exportLauncher.launch(metadata.fileName)
    }

    private fun exportRecording(metadata: RecordingMetadata, uri: Uri) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val input = File(metadata.filePath).inputStream()
                    val output = contentResolver.openOutputStream(uri)
                    if (output == null) {
                        input.close()
                        return@withContext false
                    }
                    copyStreams(input, output)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to export recording", e)
                    false
                }
            }
            Toast.makeText(
                this@RecordingsLibraryActivity,
                if (success) R.string.recording_saved else R.string.recording_save_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copyStreams(input: InputStream, output: OutputStream) {
        input.use { source ->
            output.use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = source.read(buffer)
                while (read >= 0) {
                    if (read > 0) target.write(buffer, 0, read)
                    read = source.read(buffer)
                }
                target.flush()
            }
        }
    }

    private fun showPlayer(metadata: RecordingMetadata) {
        binding.playerCard.isVisible = true
        binding.playerTitleText.text = metadata.fileName
        binding.playerSeekBar.progress = 0
        binding.playerSeekBar.max = 0
        binding.playerTimeText.text = "00:00 / 00:00"
        updatePlayerState(isPlaying = true)
    }

    private fun togglePlayerPlayback() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            updatePlayerState(isPlaying = false)
        } else {
            player.start()
            updatePlayerState(isPlaying = true)
            startProgressUpdates()
        }
    }

    private fun updatePlayerState(isPlaying: Boolean) {
        val icon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        binding.playPauseButton.icon = AppCompatResources.getDrawable(this, icon)
        binding.playPauseButton.text = if (isPlaying) "⏸" else "▶"
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacksAndMessages(null)
        progressHandler.post(object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (player != null && !isSeeking) {
                    val pos = player.currentPosition
                    val dur = player.duration.coerceAtLeast(0)
                    binding.playerSeekBar.max = dur
                    binding.playerSeekBar.progress = pos
                    updatePlayerTime(pos, dur)
                }
                if (player != null && (player.isPlaying || !isSeeking)) {
                    progressHandler.postDelayed(this, 500)
                }
            }
        })
    }

    private fun updatePlayerTime(positionMs: Int, durationMs: Int) {
        val posText = formatTime(positionMs.toLong())
        val durText = formatTime(durationMs.toLong())
        binding.playerTimeText.text = "$posText / $durText"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun seekBy(deltaMs: Int) {
        val player = mediaPlayer ?: return
        val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration)
        player.seekTo(target)
        updatePlayerTime(target, player.duration)
    }

    companion object {
        private const val TAG = "RecordingsLibrary"
    }
}
