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
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.example.childwatch.R
import ru.example.childwatch.audio.RecordingMetadata
import ru.example.childwatch.audio.RecordingRepository
import ru.example.childwatch.databinding.ActivityRecordingsLibraryBinding
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.utils.SecureSettingsManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsLibraryBinding
    private lateinit var repository: RecordingRepository
    private lateinit var networkClient: NetworkClient
    private lateinit var secureSettings: SecureSettingsManager
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

        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(
            this,
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        repository = RecordingRepository(this)
        networkClient = NetworkClient(this)
        secureSettings = SecureSettingsManager(this)

        binding.recordingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@RecordingsLibraryActivity)
            adapter = recordingsAdapter
        }

        setupPlayerControls()
        loadRecordings()
    }

    override fun onStop() {
        super.onStop()
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
        loadRecordings()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun loadRecordings() {
        lifecycleScope.launch {
            val mergedItems = withContext(Dispatchers.IO) {
                val localItems = repository.getRecordings()
                mergeWithRemoteArchive(localItems)
            }

            recordingsAdapter.submitList(mergedItems)
            recordingsAdapter.setPlayingId(currentlyPlayingId)
            binding.recordingsRecyclerView.isVisible = mergedItems.isNotEmpty()
            binding.emptyView.isVisible = mergedItems.isEmpty()
        }
    }

    private suspend fun mergeWithRemoteArchive(localItems: List<RecordingMetadata>): List<RecordingMetadata> {
        val remoteItems = fetchRemoteRecordings()
        if (remoteItems.isEmpty()) {
            return localItems.sortedByDescending { it.createdAt }
        }

        val merged = linkedMapOf<String, RecordingMetadata>()
        localItems.forEach { local ->
            merged[recordingKey(local)] = local
        }
        remoteItems.forEach { remote ->
            val key = recordingKey(remote)
            val existing = merged[key]
            merged[key] = if (existing == null) {
                remote
            } else {
                existing.copy(
                    createdAt = maxOf(existing.createdAt, remote.createdAt),
                    durationMs = existing.durationMs.takeIf { it > 0L } ?: remote.durationMs,
                    sizeBytes = maxOf(existing.sizeBytes, remote.sizeBytes),
                    downloadUrl = remote.downloadUrl ?: existing.downloadUrl,
                    remoteFileId = remote.remoteFileId ?: existing.remoteFileId
                )
            }
        }
        return merged.values.sortedByDescending { it.createdAt }
    }

    private suspend fun fetchRemoteRecordings(): List<RecordingMetadata> {
        val serverUrl = secureSettings.getServerUrl().trim()
        val deviceId = resolveOwnDeviceId()
        if (serverUrl.isBlank() || deviceId.isBlank()) {
            return emptyList()
        }

        return try {
            val response = networkClient.getRemoteAudio(deviceId, limit = 200)
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to load remote audio archive: ${response.code()}")
                emptyList()
            } else {
                val body = response.body()
                if (body == null || !body.success) {
                    emptyList()
                } else {
                    body.audioFiles.map { file ->
                        RecordingMetadata(
                            id = "remote_${file.id}",
                            fileName = file.filename,
                            filePath = "",
                            createdAt = file.timestamp,
                            durationMs = file.duration ?: 0L,
                            sizeBytes = file.fileSize,
                            downloadUrl = resolveAbsoluteUrl(serverUrl, file.downloadUrl),
                            remoteFileId = file.id
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch remote recordings", e)
            emptyList()
        }
    }

    private fun resolveOwnDeviceId(): String {
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val candidates = listOf(
            secureSettings.getDeviceId(),
            prefs.getString("device_id", null)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun resolveAbsoluteUrl(serverUrl: String, candidate: String): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            return candidate
        }
        return "${serverUrl.trimEnd('/')}/${candidate.trimStart('/')}"
    }

    private fun recordingKey(metadata: RecordingMetadata): String {
        return metadata.fileName.trim().lowercase().ifBlank { metadata.id }
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
        if (metadata.id == currentlyPlayingId) {
            stopPlayback()
            return
        }

        lifecycleScope.launch {
            val playable = prepareLocalRecording(metadata) ?: run {
                Toast.makeText(
                    this@RecordingsLibraryActivity,
                    R.string.recording_archive_unavailable,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            playRecording(playable)
        }
    }

    private suspend fun prepareLocalRecording(metadata: RecordingMetadata): RecordingMetadata? {
        if (metadata.filePath.isNotBlank()) {
            val file = File(metadata.filePath)
            if (file.exists() && file.length() > 0L) {
                return metadata
            }
        }

        val downloadUrl = metadata.downloadUrl ?: return null
        Toast.makeText(this, R.string.recording_archive_downloading, Toast.LENGTH_SHORT).show()
        return withContext(Dispatchers.IO) {
            downloadRemoteRecording(metadata, downloadUrl)
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
        if (!metadata.downloadUrl.isNullOrBlank()) {
            Toast.makeText(this, R.string.recording_archive_delete_disabled, Toast.LENGTH_SHORT).show()
            return
        }

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
        lifecycleScope.launch {
            val localCopy = prepareLocalRecording(metadata)
            if (localCopy == null) {
                Toast.makeText(
                    this@RecordingsLibraryActivity,
                    R.string.recording_archive_download_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            pendingExport = localCopy
            exportLauncher.launch(localCopy.fileName)
        }
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

    private fun downloadRemoteRecording(metadata: RecordingMetadata, downloadUrl: String): RecordingMetadata? {
        return try {
            val archiveDir = File(RecordingRepository.getRecordingsDir(this), "archive_cache")
            if (!archiveDir.exists()) {
                archiveDir.mkdirs()
            }

            val safeFileName = metadata.fileName
                .ifBlank { "${metadata.id}.wav" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val localFile = File(archiveDir, safeFileName)
            if (localFile.exists() && localFile.length() > 0L) {
                val cached = metadata.copy(filePath = localFile.absolutePath)
                repository.addRecording(cached)
                return cached
            }

            val requestBuilder = Request.Builder().url(downloadUrl)
            networkClient.getAuthToken()?.let { token ->
                requestBuilder.header("Authorization", "Bearer $token")
            }
            val client = OkHttpClient.Builder().build()
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Remote recording download failed: ${response.code}")
                    return null
                }

                val body = response.body ?: return null
                localFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }

            val cached = metadata.copy(filePath = localFile.absolutePath)
            repository.addRecording(cached)
            cached
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download remote recording", e)
            null
        }
    }

    companion object {
        private const val TAG = "RecordingsLibrary"
    }
}
