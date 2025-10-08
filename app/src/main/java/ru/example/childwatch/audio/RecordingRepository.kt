package ru.example.childwatch.audio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists metadata about locally saved stream recordings.
 */
class RecordingRepository(private val context: Context) {

    private val lock = Any()
    private val indexFile: File by lazy {
        val dir = getRecordingsDir(context)
        File(dir, "recordings_index.json")
    }

    fun addRecording(metadata: RecordingMetadata) {
        synchronized(lock) {
            val list = loadInternal().toMutableList()
            list.removeAll { it.id == metadata.id }
            list.add(metadata)
            saveInternal(list)
        }
    }

    fun removeRecording(id: String): Boolean {
        synchronized(lock) {
            val list = loadInternal().toMutableList()
            val iterator = list.iterator()
            var removedMeta: RecordingMetadata? = null
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.id == id) {
                    removedMeta = item
                    iterator.remove()
                    break
                }
            }
            if (removedMeta != null) {
                saveInternal(list)
                val file = File(removedMeta.filePath)
                if (file.exists()) {
                    file.delete()
                }
                return true
            }
            return false
        }
    }

    fun getRecordings(): List<RecordingMetadata> {
        synchronized(lock) {
            return loadInternal().sortedByDescending { it.createdAt }
        }
    }

    private fun loadInternal(): List<RecordingMetadata> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val content = indexFile.readText()
            if (content.isBlank()) return emptyList()
            val json = JSONArray(content)
            val result = mutableListOf<RecordingMetadata>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                result.add(
                    RecordingMetadata(
                        id = obj.getString("id"),
                        fileName = obj.getString("fileName"),
                        filePath = obj.getString("filePath"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        durationMs = obj.optLong("durationMs", 0L),
                        sizeBytes = obj.optLong("sizeBytes", 0L)
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recordings index", e)
            emptyList()
        }
    }

    private fun saveInternal(list: List<RecordingMetadata>) {
        try {
            val array = JSONArray()
            list.forEach { metadata ->
                array.put(
                    JSONObject().apply {
                        put("id", metadata.id)
                        put("fileName", metadata.fileName)
                        put("filePath", metadata.filePath)
                        put("createdAt", metadata.createdAt)
                        put("durationMs", metadata.durationMs)
                        put("sizeBytes", metadata.sizeBytes)
                    }
                )
            }
            if (!indexFile.parentFile.exists()) {
                indexFile.parentFile?.mkdirs()
            }
            indexFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recordings index", e)
        }
    }

    companion object {
        private const val TAG = "RecordingRepository"

        fun getRecordingsDir(context: Context): File {
            val dir = File(context.filesDir, "recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
    }
}
