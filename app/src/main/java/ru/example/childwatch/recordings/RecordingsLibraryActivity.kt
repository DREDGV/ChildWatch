package ru.example.childwatch.recordings

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ru.example.childwatch.R
import ru.example.childwatch.audio.RecordingMetadata
import ru.example.childwatch.audio.RecordingRepository
import ru.example.childwatch.databinding.ActivityRecordingsLibraryBinding
import java.io.File

class RecordingsLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsLibraryBinding
    private lateinit var repository: RecordingRepository
    private val recordingsAdapter by lazy {
        RecordingsAdapter(
            onPlayClicked = ::handlePlayClicked,
            onDeleteClicked = ::handleDeleteClicked
        )
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: String? = null

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

        loadRecordings()
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    private fun loadRecordings() {
        val items = repository.getRecordings()
        recordingsAdapter.submitList(items)
        recordingsAdapter.setPlayingId(currentlyPlayingId)
        binding.recordingsRecyclerView.isVisible = items.isNotEmpty()
        binding.emptyView.isVisible = items.isEmpty()
    }

    private fun handlePlayClicked(metadata: RecordingMetadata) {
        if (!File(metadata.filePath).exists()) {
            Toast.makeText(this, R.string.recording_play_failed, Toast.LENGTH_LONG).show()
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
            player.prepare()
            player.start()
            player.setOnCompletionListener { stopPlayback() }
            mediaPlayer = player
            currentlyPlayingId = metadata.id
            recordingsAdapter.setPlayingId(currentlyPlayingId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play recording", e)
            player.release()
            mediaPlayer = null
            currentlyPlayingId = null
            recordingsAdapter.setPlayingId(null)
            Toast.makeText(this, R.string.recording_play_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayback() {
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
        recordingsAdapter.setPlayingId(null)
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

    companion object {
        private const val TAG = "RecordingsLibrary"
    }
}
