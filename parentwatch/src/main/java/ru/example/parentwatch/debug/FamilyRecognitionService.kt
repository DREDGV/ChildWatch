package ru.example.parentwatch.debug

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Uses an installed speech engine only on an explicit recognition request. */
class FamilyRecognitionService : RecognitionService() {
    private var engine: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (!ru.example.parentwatch.service.AssistantRecoveryAccess.isSelected(this)) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return
        }
        if (engine != null) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        val provider = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }.firstOrNull {
                it.packageName != packageName && it.permission == "android.permission.BIND_SPEECH_RECOGNITION"
            }
        if (provider == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); return }
        try {
            engine = SpeechRecognizer.createSpeechRecognizer(this, ComponentName(provider.packageName, provider.name)).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle) = listener.readyForSpeech(params)
                    override fun onBeginningOfSpeech() = listener.beginningOfSpeech()
                    override fun onRmsChanged(rmsdB: Float) = listener.rmsChanged(rmsdB)
                    override fun onBufferReceived(buffer: ByteArray) = listener.bufferReceived(buffer)
                    override fun onEndOfSpeech() = listener.endOfSpeech()
                    override fun onError(error: Int) { release(); listener.error(error) }
                    override fun onResults(results: Bundle) { release(); listener.results(results) }
                    override fun onPartialResults(partialResults: Bundle) = listener.partialResults(partialResults)
                    override fun onEvent(eventType: Int, params: Bundle) = Unit
                })
                startListening(Intent(recognizerIntent))
            }
        } catch (_: Exception) { release(); listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onStopListening(listener: Callback) { engine?.stopListening() }
    override fun onCancel(listener: Callback) { release() }
    private fun release() { engine?.destroy(); engine = null }
    override fun onDestroy() { release(); super.onDestroy() }
}
