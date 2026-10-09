package ru.example.childwatch

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vanniktech.emoji.EmojiPopup
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.chat.ChatDeliveryState
import ru.childwatch.shared.chat.ChatV2UiRegistry
import ru.childwatch.shared.chat.ChatV2MessageDto
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationMember
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.ConversationType
import ru.childwatch.shared.chat.ConversationMessage
import ru.example.childwatch.chat.ChatAdapter
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.chat.presence.PeerPresenceWatcher
import androidx.core.widget.doAfterTextChanged
import ru.example.childwatch.chat.v2.ChatV2Repository
import ru.example.childwatch.chat.v2.GroupManagementDialog
import ru.example.childwatch.databinding.ActivityChatBinding
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.utils.SecureSettingsManager

class ChatConversationV2Activity : AppCompatActivity() {
    companion object {
        const val EXTRA_CONVERSATION_ID = "CHAT_V2_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_TITLE = "CHAT_V2_CONVERSATION_TITLE"

        /**
         * Fallback poll interval.
         *
         * Socket events refresh the screen immediately; this only bounds how
         * late a change can arrive when the socket is down. At 30 seconds a read
         * receipt could sit unnoticed for half a minute, which is what made the
         * "read" mark feel broken.
         */
        private const val SYNC_INTERVAL_MS = 8_000L
        private const val INITIAL_MESSAGE_LIMIT = 200
        private const val OLDER_MESSAGE_PAGE_SIZE = 100

        /**
         * How long the author may still edit a message.
         *
         * Mirrors the server rule: editing is for fixing a mistake, not for
         * rewriting what the others have already read.
         */
        private const val MESSAGE_EDIT_WINDOW_MS = 30 * 60 * 1000L

        /**
         * How long after the last keystroke the other side is told that writing
         * stopped, and how long an unanswered indicator stays on screen.
         */
        private const val TYPING_REPORT_INTERVAL_MS = 1_500L
        private const val TYPING_INDICATOR_TIMEOUT_MS = 6_000L
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var repository: ChatV2Repository

    private val media by lazy { repository.attachments(this) }
    private val transcriptions by lazy { repository.transcriptions(this) }
    private var transcriptionEnabled = false
    private var transcriptionDialog: ru.example.childwatch.designsystem.ChatTranscriptionDialog? = null
    private var transcriptionObserverJob: Job? = null
    private var transcriptionActionJob: Job? = null

    private var voiceEnabled = false
    private var voiceDialog: ru.example.childwatch.designsystem.ChatVoiceRecordingDialog? = null
    private var voicePlayback: ru.example.childwatch.designsystem.ChatVoicePlaybackDialog? = null
    private var voicePermissionScope: String? = null
    private val voicePermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if (::repository.isInitialized && voicePermissionScope == media.currentScopeKey()) showVoiceRecorder()
        voicePermissionScope = null
    }
    private var mediaEnabled = false
    private var mediaDraft: ru.example.childwatch.database.entity.ChatAttachmentDraftV2Entity? = null
    private var mediaPreparing = false
    private var textSendInProgress = false
    private var mediaSendJob: Job? = null
    private var mediaDownloadJob: Job? = null
    private var pickerType = "FILE"
    private var pickerScope: String? = null
    private val documentPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::repository.isInitialized && pickerScope != null && pickerScope == media.currentScopeKey()) prepareAttachment(uri)
    }
    private var pendingSave: Pair<java.io.File, ru.childwatch.shared.chat.ChatV2AttachmentDto>? = null
    private var pendingSaveScope: String? = null
    private val saveAttachmentPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selected = pendingSave
        pendingSave = null
        if (uri != null && selected != null && pendingSaveScope == media.currentScopeKey()) lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output -> selected.first.inputStream().use { it.copyTo(output) } }
                        ?: throw java.io.IOException("Cannot open destination")
                }
                Toast.makeText(this@ChatConversationV2Activity, R.string.chat_media_saved, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@ChatConversationV2Activity, R.string.chat_media_unavailable, Toast.LENGTH_LONG).show() }
            finally { selected.first.delete() }
        } else selected?.first?.delete()
        pendingSaveScope = null
    }

    private fun updateComposerState() {
        val draft = mediaDraft
        val busy = textSendInProgress || mediaPreparing || mediaSendJob?.isActive == true || draft?.state in setOf("QUEUED", "UPLOADING", "UPLOADED")
        binding.sendButton.isEnabled = !busy && (!binding.messageInput.text.isNullOrBlank() || draft != null)
        binding.attachmentButton.isEnabled = mediaEnabled && !busy && draft == null
        binding.attachmentDraftCancel.isEnabled = !mediaPreparing
    }

    private fun startMedia() {
        lifecycleScope.launch {
            val capability = runCatching { media.capabilities() }.getOrNull()
            mediaEnabled = capability?.attachments == true
            voiceEnabled = mediaEnabled && "VOICE" in capability!!.attachmentTypes
            transcriptionEnabled = voiceEnabled && capability?.transcription == true
            binding.attachmentButton.visibility = if (mediaEnabled) View.VISIBLE else View.GONE
            updateComposerState()
        }
        lifecycleScope.launch {
            media.observe(conversationId).collectLatest { drafts ->
                val previous = mediaDraft
                val pending = drafts.lastOrNull { it.state !in setOf("ENQUEUED", "SENT", "CANCELLED") }
                if (previous != null && drafts.any { it.clientMessageId == previous.clientMessageId && it.state in setOf("ENQUEUED", "SENT") } &&
                    binding.messageInput.text?.toString() == previous.caption) binding.messageInput.text?.clear()
                mediaDraft = pending
                renderMediaDraft()
            }
        }
    }

    private fun showAttachmentPicker() {
        if (!mediaEnabled || mediaDraft != null || mediaPreparing) return
        val choices = mutableListOf(getString(R.string.chat_media_photo), getString(R.string.chat_media_file))
        if (voiceEnabled) choices.add(getString(R.string.chat_voice_title))
        AlertDialog.Builder(this).setTitle(R.string.chat_media_attach)
            .setItems(choices.toTypedArray()) { _, index ->
                if (index == 2) {
                    voicePermissionScope = media.currentScopeKey()
                    if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) showVoiceRecorder()
                    else voicePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    pickerType = if (index == 0) "IMAGE" else "FILE"
                    pickerScope = media.currentScopeKey()
                    documentPicker.launch(if (index == 0) arrayOf("image/jpeg", "image/png", "image/webp", "image/gif") else arrayOf("*/*"))
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showVoiceRecorder() {
        if (!voiceEnabled || mediaDraft != null || mediaPreparing) return
        val guard = media.currentScopeKey() ?: return
        voiceDialog?.close()
        val recorder = ru.example.childwatch.designsystem.ChatVoiceRecordingDialog(this,
            com.google.gson.Gson().toJson(listOf(guard, conversationId)), { guard == media.currentScopeKey() }) { file, duration, onCopied ->
            if (guard != media.currentScopeKey()) return@ChatVoiceRecordingDialog
            mediaPreparing = true
            updateComposerState()
            lifecycleScope.launch {
                try {
                    check(ru.childwatch.shared.chat.VoiceRecordingPolicy.usable(duration, file.length()))
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@ChatConversationV2Activity, "$packageName.fileprovider", file, getString(R.string.chat_voice_filename))
                    mediaDraft = media.createDraft(conversationId, uri, getString(R.string.chat_voice_filename), "audio/mp4", "VOICE", duration)
                    onCopied.run()
                    renderMediaDraft()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    voiceDialog?.failedCopy()
                    Toast.makeText(this@ChatConversationV2Activity, R.string.chat_media_failed, Toast.LENGTH_LONG).show()
                } finally { mediaPreparing = false; updateComposerState() }
            }
        }
        voiceDialog = recorder
        recorder.show()
    }

    override fun onPause() {
        transcriptionDialog?.close(); transcriptionDialog = null
        mediaDownloadJob?.cancel()
        voiceDialog?.interrupt()
        voiceDialog = null
        voicePlayback?.close()
        voicePlayback = null
        super.onPause()
    }

    private fun showVoiceDraftActions(id: String) {
        val guard = media.currentScopeKey() ?: return
        lifecycleScope.launch {
            val draft = media.getDraft(id) ?: return@launch
            val stored = transcriptions.get(id)
            if (guard != media.currentScopeKey() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val choices = mutableListOf(getString(R.string.chat_voice_listen))
            if (transcriptionEnabled || stored != null) choices.add(getString(R.string.chat_transcription_title))
            AlertDialog.Builder(this@ChatConversationV2Activity).setTitle(R.string.chat_voice_title)
                .setItems(choices.toTypedArray()) { _, index ->
                    if (guard != media.currentScopeKey()) return@setItems
                    if (index == 0) {
                        voicePlayback?.close()
                        voicePlayback = ru.example.childwatch.designsystem.ChatVoicePlaybackDialog(this@ChatConversationV2Activity, java.io.File(draft.localPath)) { }
                        voicePlayback?.show()
                    } else showTranscription(id)
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun showTranscription(id: String) {
        val guard = transcriptions.currentScopeKey() ?: return
        transcriptionDialog?.close()
        val panel = ru.example.childwatch.designsystem.ChatTranscriptionDialog(this, { guard == transcriptions.currentScopeKey() },
            object : ru.example.childwatch.designsystem.ChatTranscriptionDialog.Actions {
                override fun request() = runTranscriptionAction(id, guard, false)
                override fun cancel() = runTranscriptionAction(id, guard, true)
                override fun edited(value: String) {
                    // Enter NonCancellable before yielding so a rotation cannot drop the last local edit.
                    lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            if (guard == transcriptions.currentScopeKey()) runCatching { transcriptions.saveEditedText(id, value) }
                        }
                    }
                }
                override fun send(value: String) = sendTranscriptionText(id, guard, value)
                override fun closed() {
                    transcriptionObserverJob?.cancel(); transcriptionObserverJob = null
                    transcriptionActionJob?.cancel(); transcriptionActionJob = null
                }
            })
        transcriptionDialog = panel
        panel.show()
        transcriptionObserverJob = lifecycleScope.launch {
            launch {
                transcriptions.observe(id).collectLatest { row ->
                    if (guard != transcriptions.currentScopeKey()) { panel.close(); return@collectLatest }
                    panel.render(row?.state ?: "EMPTY", row?.text, row?.editedText, row?.isEdited ?: false,
                        row?.errorCode, row?.textMessageId != null)
                }
            }
            var first = true
            while (isActive && guard == transcriptions.currentScopeKey()) {
                val row = transcriptions.get(id)
                if (row != null && (first || row.state in setOf("QUEUED", "RUNNING", "CANCEL_REQUESTED"))) {
                    try {
                        if (row.state == "CANCEL_REQUESTED") transcriptions.cancel(id) else transcriptions.refresh(id)
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
                first = false
                delay(3_000)
            }
            panel.close()
        }
    }

    private fun runTranscriptionAction(id: String, guard: String, cancel: Boolean) {
        if (guard != transcriptions.currentScopeKey()) return
        if (transcriptionActionJob?.isActive == true) {
            if (!cancel) return
            transcriptionActionJob?.cancel()
        }
        transcriptionActionJob = lifecycleScope.launch {
            try { if (cancel) transcriptions.cancel(id) else transcriptions.request(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@ChatConversationV2Activity, R.string.chat_transcription_network, Toast.LENGTH_LONG).show() }
            finally {
                if (transcriptionActionJob == kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
                    transcriptionActionJob = null
            }
        }
    }

    private fun sendTranscriptionText(id: String, guard: String, text: String) {
        if (guard != transcriptions.currentScopeKey() || textSendInProgress) return
        val member = conversation?.members?.firstOrNull { it.isLocalUser } ?: return
        if (ru.childwatch.shared.chat.ChatTextPolicy.validate(text) !is ru.childwatch.shared.chat.ChatTextValidation.Valid) {
            transcriptionDialog?.failedSending()
            Toast.makeText(this, R.string.chat_transcription_text_limit, Toast.LENGTH_LONG).show()
            return
        }
        textSendInProgress = true
        updateComposerState()
        transcriptionActionJob = lifecycleScope.launch {
            try {
                val row = transcriptions.get(id) ?: error("TRANSCRIPTION_UNAVAILABLE")
                check(row.state == "SUCCEEDED" && row.textMessageId == null && guard == transcriptions.currentScopeKey())
                val draft = media.getDraft(id) ?: error("TRANSCRIPTION_SOURCE_UNAVAILABLE")
                check(draft.actorMemberId == member.memberId && draft.state == "DRAFT")
                val messageId = id + ":text"
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    check(guard == transcriptions.currentScopeKey())
                    val existing = repository.getCachedMessageByClientId(messageId)
                    if (existing == null) repository.enqueueMessage(conversationId, text, member.displayName, member.role,
                        member.memberId, draft.deviceId, messageId)
                    transcriptions.markTextEnqueued(id, messageId)
                    // Text is already durable; this explicit choice consumes only the local voice draft.
                    media.cancel(id)
                }
                transcriptionActionJob = null
                transcriptionDialog?.close(); transcriptionDialog = null
                mediaDraft = null; renderMediaDraft(); scrollToBottom()
                repository.flushOutbox()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                transcriptionDialog?.failedSending()
                Toast.makeText(this@ChatConversationV2Activity, R.string.chat_transcription_send_failed, Toast.LENGTH_LONG).show()
            } finally { textSendInProgress = false; transcriptionActionJob = null; updateComposerState() }
        }
    }

    private fun prepareAttachment(uri: android.net.Uri) {
        if (mediaDraft != null || mediaPreparing) return
        val type = pickerType
        val guard = media.currentScopeKey()
        mediaPreparing = true
        binding.attachmentDraftPanel.visibility = View.VISIBLE
        binding.attachmentDraftText.setText(R.string.chat_media_preparing)
        binding.attachmentDraftProgress.visibility = View.VISIBLE
        binding.attachmentDraftProgress.isIndeterminate = true
        updateComposerState()
        lifecycleScope.launch {
            try {
                val metadata = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var name = "attachment"
                    var size = -1L
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val b = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                            if (b >= 0 && !cursor.isNull(b)) size = cursor.getLong(b)
                        }
                    }
                    val mime = ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.mimeType(contentResolver.getType(uri))
                    ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.validate(
                        if (type == "IMAGE") ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.Mode.IMAGE else ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.Mode.FILE, mime, size)
                    Pair(ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.displayName(name), mime)
                }
                check(guard != null && guard == media.currentScopeKey()) { "CONTEXT_CHANGED" }
                mediaDraft = media.createDraft(conversationId, uri, metadata.first, metadata.second, type)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val resource = when (error.message) {
                    "TOO_LARGE", "ATTACHMENT_SIZE_LIMIT" -> R.string.chat_media_too_large
                    "EMPTY", "ATTACHMENT_EMPTY" -> R.string.chat_media_empty
                    "UNSUPPORTED_IMAGE" -> R.string.chat_media_invalid_image
                    "ATTACHMENTS_SERVER_UNAVAILABLE" -> R.string.chat_media_unsupported
                    else -> R.string.chat_media_unavailable
                }
                Toast.makeText(this@ChatConversationV2Activity, resource, Toast.LENGTH_LONG).show()
            } finally { mediaPreparing = false; renderMediaDraft() }
        }
    }

    private fun renderMediaDraft() {
        val draft = mediaDraft
        if (mediaPreparing) return
        binding.attachmentDraftPanel.visibility = if (draft == null) View.GONE else View.VISIBLE
        val draftGuard = media.currentScopeKey()
        binding.attachmentDraftText.setOnClickListener(if (draft?.attachmentType == "VOICE") View.OnClickListener {
            if (mediaDraft?.clientMessageId == draft.clientMessageId && draftGuard != null && draftGuard == media.currentScopeKey()) showVoiceDraftActions(draft.clientMessageId)
        } else null)
        val previewChanged = binding.attachmentDraftImage.tag != draft?.clientMessageId
        if (previewChanged) {
            binding.attachmentDraftImage.setImageDrawable(null)
            binding.attachmentDraftImage.visibility = View.GONE
            binding.attachmentDraftImage.tag = draft?.clientMessageId
        }
        if (draft != null) {
            if (previewChanged && draft.caption.isNotBlank() && binding.messageInput.text.isNullOrBlank()) binding.messageInput.setText(draft.caption)
            val size = android.text.format.Formatter.formatShortFileSize(this, draft.sizeBytes)
            val progress = ((draft.progressBytes.toDouble() / draft.sizeBytes.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
            val state = when (draft.state) {
                "QUEUED", "UPLOADING", "UPLOADED" -> getString(R.string.chat_media_uploading, progress)
                "FAILED", "RETRY" -> getString(R.string.chat_media_failed)
                else -> size
            }
            binding.attachmentDraftText.text = getString(R.string.chat_media_size, draft.filename, state)
            binding.attachmentDraftCancel.setText(if (draft.state == "DRAFT") R.string.chat_media_remove else R.string.chat_media_cancel)
            binding.attachmentDraftProgress.isIndeterminate = false
            binding.attachmentDraftProgress.progress = progress
            binding.attachmentDraftProgress.visibility = if (draft.state in setOf("QUEUED", "UPLOADING", "UPLOADED")) View.VISIBLE else View.GONE
            if (previewChanged && draft.attachmentType in setOf("IMAGE", "GIF")) {
                val guard = media.currentScopeKey()
                lifecycleScope.launch {
                    val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeMediaImage(java.io.File(draft.localPath)) }
                    if (guard == media.currentScopeKey() && binding.attachmentDraftImage.tag == draft.clientMessageId) {
                        binding.attachmentDraftImage.setImageBitmap(bitmap)
                        binding.attachmentDraftImage.visibility = if (bitmap == null) View.GONE else View.VISIBLE
                    }
                }
            }
        }
        updateComposerState()
    }

    private fun decodeMediaImage(file: java.io.File): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun previewAttachment(message: ChatMessage, image: android.widget.ImageView) {
        val attachment = message.attachments.firstOrNull() ?: return
        val guard = media.currentScopeKey()
        lifecycleScope.launch {
            try {
                val file = media.download(conversationId, attachment)
                try {
                    val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeMediaImage(file) }
                    if (guard == media.currentScopeKey() && image.tag == attachment.attachmentId) image.setImageBitmap(bitmap)
                } finally { file.delete() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (image.tag == attachment.attachmentId) image.contentDescription = getString(R.string.chat_media_unavailable) }
        }
    }

    private fun openAttachment(message: ChatMessage) {
        val attachment = message.attachments.firstOrNull() ?: return
        val guard = media.currentScopeKey()
        mediaDownloadJob?.cancel()
        val progress = android.widget.ProgressBar(this).apply { isIndeterminate = true }
        val progressContainer = android.widget.FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(progress, android.widget.FrameLayout.LayoutParams(-1, (8 * resources.displayMetrics.density).toInt()))
        }
        val downloading = AlertDialog.Builder(this).setTitle(attachment.filename).setMessage(R.string.chat_media_preparing)
            .setView(progressContainer).setNegativeButton(android.R.string.cancel) { _, _ -> mediaDownloadJob?.cancel() }
            .setOnCancelListener { mediaDownloadJob?.cancel() }.create()
        downloading.show()
        mediaDownloadJob = lifecycleScope.launch {
            try {
                val file = media.download(conversationId, attachment) { received, total ->
                    runOnUiThread {
                        if (guard == media.currentScopeKey() && downloading.isShowing) {
                            progress.isIndeterminate = false
                            progress.max = 100
                            progress.progress = ((received.toDouble() / total.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
                            downloading.setMessage(getString(R.string.chat_media_downloading, progress.progress))
                        }
                    }
                }
                if (guard != media.currentScopeKey() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { file.delete(); return@launch }
                downloading.dismiss()
                if (attachment.type == "VOICE") {
                    voicePlayback?.close()
                    voicePlayback = ru.example.childwatch.designsystem.ChatVoicePlaybackDialog(this@ChatConversationV2Activity, file) { file.delete() }
                    voicePlayback?.show()
                    return@launch
                }
                val choices = arrayOf(getString(R.string.chat_media_open), getString(R.string.chat_media_save))
                AlertDialog.Builder(this@ChatConversationV2Activity).setTitle(attachment.filename)
                    .setItems(choices) { _, index ->
                        if (guard != media.currentScopeKey()) { file.delete(); return@setItems }
                        if (index == 1) { pendingSave = file to attachment; pendingSaveScope = guard; saveAttachmentPicker.launch(attachment.filename) }
                        else {
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(this@ChatConversationV2Activity, "$packageName.fileprovider", file)
                                val extension = attachment.filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                                val viewMime = if (attachment.mimeType == "application/octet-stream") android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: attachment.mimeType else attachment.mimeType
                                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, viewMime)
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this@ChatConversationV2Activity, R.string.chat_media_no_viewer, Toast.LENGTH_LONG).show() }
                        }
                    }.setNegativeButton(android.R.string.cancel) { _, _ -> file.delete() }.show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@ChatConversationV2Activity, R.string.chat_media_unavailable, Toast.LENGTH_LONG).show() }
            finally { downloading.dismiss(); mediaDownloadJob = null }
        }
    }

    private fun sendMediaDraft() {
        val draft = mediaDraft ?: return
        if (mediaSendJob?.isActive == true) return
        val member = conversation?.members?.firstOrNull { it.isLocalUser } ?: return
        val caption = binding.messageInput.text?.toString().orEmpty()
        mediaSendJob = lifecycleScope.launch {
            try {
                media.send(draft.clientMessageId, caption, member.displayName, member.role)
                if (binding.messageInput.text?.toString() == caption) binding.messageInput.text?.clear()
                repository.flushOutbox()
                scrollToBottom()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@ChatConversationV2Activity, R.string.chat_media_failed, Toast.LENGTH_LONG).show() }
            finally { mediaSendJob = null; updateComposerState() }
        }
        updateComposerState()
    }

    private lateinit var conversationId: String
    private var conversation: Conversation? = null
    private var adapter: ChatAdapter? = null
    private var emojiPopup: EmojiPopup? = null
    private var syncJob: Job? = null
    private var messageObserverJob: Job? = null
    private var initialScrollDone = false
    private var previousMessageCount = 0
    private var pendingNewMessages = 0
    private var displayedMessageLimit = INITIAL_MESSAGE_LIMIT
    private var nextBeforeSequence: Long? = null
    private var loadingOlderMessages = false
    private var olderMessagesPendingRender = false

    /** Pending "is writing" report and the timer that clears a stale indicator. */
    private var typingReportJob: Job? = null
    private var typingTimeoutJob: Job? = null
    private var hasReportedTyping = false
    private val peerNetworkClient by lazy { NetworkClient(this) }
    private val presenceWatcher by lazy {
        PeerPresenceWatcher(
            networkClient = peerNetworkClient,
            scope = lifecycleScope
        ) { snapshot -> runOnUiThread { renderPeerPresence(snapshot) } }
    }
    private val gson = Gson()
    private val chatV2MessageListener: (JSONObject) -> Unit = { payload ->
        handleRealtimeMessage(payload)
    }
    private val chatV2ReceiptListener: (JSONObject) -> Unit = { payload ->
        handleRealtimeEvent(payload)
    }
    private val chatV2ErrorListener: (JSONObject) -> Unit = { payload ->
        if (payload.optString("conversationId") == conversationId) {
            runOnUiThread { binding.connectionStatusText.setText(R.string.chat_v2_offline) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pickerType = savedInstanceState?.getString("media_picker_type") ?: "FILE"
        pickerScope = savedInstanceState?.getString("media_picker_scope")
        savedInstanceState?.getString("media_save_file")?.let { path ->
            val file = java.io.File(path)
            val expectedRoot = java.io.File(cacheDir, "chat-attachments").canonicalFile
            if (file.exists() && file.canonicalFile.parentFile == expectedRoot) {
                savedInstanceState.getString("media_save_metadata")?.let { json ->
                    runCatching { gson.fromJson(json, ru.childwatch.shared.chat.ChatV2AttachmentDto::class.java) }.getOrNull()?.let { pendingSave = file to it }
                }
            }
        }
        pendingSaveScope = savedInstanceState?.getString("media_save_scope")
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty().trim()
        if (conversationId.isBlank()) {
            finish()
            return
        }
        val serverUrl = resolveServerUrl()
        if (serverUrl.isBlank()) {
            Toast.makeText(this, R.string.chat_v2_offline, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        repository = ChatV2Repository.create(this, serverUrl)
        configureStaticUi()
        lifecycleScope.launch { loadConversation() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("media_picker_type", pickerType)
        outState.putString("media_picker_scope", pickerScope)
        pendingSave?.let { outState.putString("media_save_file", it.first.absolutePath); outState.putString("media_save_metadata", gson.toJson(it.second)) }
        outState.putString("media_save_scope", pendingSaveScope)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        WebSocketManager.addChatV2MessageListener(chatV2MessageListener)
        WebSocketManager.addChatV2ReceiptListener(chatV2ReceiptListener)
        WebSocketManager.addChatV2ErrorListener(chatV2ErrorListener)
        WebSocketManager.addChatV2TypingListener(chatV2TypingListener)
        WebSocketManager.subscribeChatV2(conversationId)
        ChatV2UiRegistry.enter(conversationId)
        startSyncLoop()
        startPeerPresence()
    }

    override fun onStop() {
        // Leaving the screen must not leave the other side looking at an indicator
        // that will never be cleared.
        reportTyping(false)
        presenceWatcher.stop()
        syncJob?.cancel()
        syncJob = null
        WebSocketManager.unsubscribeChatV2(conversationId)
        WebSocketManager.removeChatV2MessageListener(chatV2MessageListener)
        WebSocketManager.removeChatV2ReceiptListener(chatV2ReceiptListener)
        WebSocketManager.removeChatV2ErrorListener(chatV2ErrorListener)
        WebSocketManager.removeChatV2TypingListener(chatV2TypingListener)
        ChatV2UiRegistry.leave(conversationId)
        super.onStop()
    }

    override fun onDestroy() {
        emojiPopup?.dismiss()
        emojiPopup = null
        super.onDestroy()
    }

    private fun configureStaticUi() = with(binding) {
        chatPartnerName.text = intent.getStringExtra(EXTRA_CONVERSATION_TITLE)
            ?.takeIf { it.isNotBlank() } ?: getString(R.string.chat_title_family)
        // The two lines under the name describe the conversation, and both are written
        // once it has been read; a placeholder here would be a sentence about something
        // not yet known, and the empty state already carries its own text.
        chatPartnerMeta.visibility = View.GONE
        chatMembersText.visibility = View.GONE
        connectionStatusText.setText(R.string.chat_v2_syncing)
        typingIndicator.visibility = View.GONE
        chatInfoButton.visibility = View.GONE
        emptyStateTitle.setText(R.string.chat_v2_no_messages)

        emojiPopup = EmojiPopup(root, messageInput)
        emojiButton.setOnClickListener { emojiPopup?.toggle() }
        sendButton.setOnClickListener { sendMessage() }
        attachmentButton.setOnClickListener { showAttachmentPicker() }
        attachmentDraftCancel.setOnClickListener {
            val draft = mediaDraft ?: return@setOnClickListener
            lifecycleScope.launch {
                try { media.cancel(draft.clientMessageId); mediaDraft = null; renderMediaDraft() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(this@ChatConversationV2Activity, R.string.chat_media_unavailable, Toast.LENGTH_LONG).show() }
            }
        }
        // The send button now shows its own empty state, so it must follow the
        // input instead of looking ready when there is nothing to send.
        messageInput.doAfterTextChanged { editable ->
            updateComposerState()
            reportTyping(!editable.isNullOrBlank())
        }
        newMessagesButton.setOnClickListener { scrollToBottom() }
        messagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isNearBottom()) {
                    pendingNewMessages = 0
                    newMessagesButton.visibility = View.GONE
                    recordVisibleMessages()
                }
                val manager = recyclerView.layoutManager as? LinearLayoutManager
                if ((manager?.findFirstVisibleItemPosition() ?: Int.MAX_VALUE) <= 2) {
                    loadOlderMessages()
                }
            }
        })
    }

    /**
     * Shows "who is writing" in this conversation.
     *
     * The event carries the conversation it belongs to, so an indicator meant for a
     * different chat never appears here. It is cleared by a stop event, and also by
     * a timeout, because a phone that goes offline mid-sentence would otherwise
     * leave the indicator on forever.
     */
    private val chatV2TypingListener: (JSONObject) -> Unit = { payload ->
        val forThisConversation =
            payload.optString("conversationId").trim() == conversationId
        if (forThisConversation) {
            val isTyping = payload.optBoolean("isTyping", false)
            runOnUiThread { renderTypingIndicator(isTyping) }
        }
    }

    private fun renderTypingIndicator(isTyping: Boolean) {
        binding.typingIndicator.visibility = if (isTyping) View.VISIBLE else View.GONE
        typingTimeoutJob?.cancel()
        if (!isTyping) return
        // A stop event is not guaranteed: the other side may lose the connection
        // while writing, so the indicator is cleared on its own after a while.
        typingTimeoutJob = lifecycleScope.launch {
            delay(TYPING_INDICATOR_TIMEOUT_MS)
            binding.typingIndicator.visibility = View.GONE
        }
    }

    /**
     * Tells the other participants that this device is writing.
     *
     * Reports are throttled: a person produces a keystroke every moment, and sending
     * an event for each of them would flood the connection for no benefit. The stop
     * report is sent immediately so the indicator disappears as soon as writing ends.
     */
    private fun reportTyping(isTyping: Boolean) {
        typingReportJob?.cancel()
        if (!isTyping) {
            if (hasReportedTyping) {
                hasReportedTyping = false
                WebSocketManager.sendChatV2Typing(conversationId, false)
            }
            return
        }
        typingReportJob = lifecycleScope.launch {
            delay(TYPING_REPORT_INTERVAL_MS)
            hasReportedTyping = true
            WebSocketManager.sendChatV2Typing(conversationId, true)
        }
    }

    /**
     * Shows whether the other side is online, next to the header status.
     *
     * The server already tracks this per device link; nothing in the chat asked
     * for it, so a parent could not tell whether the child was reachable.
     */
    private fun startPeerPresence() {
        val target = resolveTargetChildDeviceId() ?: return
        val local = conversation?.members?.filter { it.isLocalUser }?.map { it.memberId }.orEmpty()
        presenceWatcher.start(
            target,
            (local + peerNetworkClient.ownDeviceId())
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        )
    }

    private fun renderPeerPresence(snapshot: PeerPresenceWatcher.PresenceSnapshot) {
        if (!snapshot.isKnown) return
        binding.peerPresenceRow.visibility = View.VISIBLE
        binding.peerPresenceText.setText(
            if (snapshot.isOnline) R.string.chat_presence_online else R.string.chat_presence_offline
        )
        val color = androidx.core.content.ContextCompat.getColor(
            this,
            if (snapshot.isOnline) R.color.presence_online else R.color.presence_offline
        )
        binding.peerPresenceText.setTextColor(color)
        binding.peerPresenceDot.background?.mutate()?.setTint(color)
    }

    /**
     * Offers the actions for one message.
     *
     * Only the author may rewrite or withdraw a message, and only while it is
     * recent, so the menu shows exactly what is possible for this message instead
     * of failing afterwards.
     */
    private fun showMessageActions(message: ChatMessage) {
        if (message.deletedAt != null) {
            Toast.makeText(this, R.string.chat_message_deleted, Toast.LENGTH_SHORT).show()
            return
        }

        val age = System.currentTimeMillis() - message.timestamp
        val isMine = message.isMine
        val labels = mutableListOf<String>()
        if (isMine && message.attachments.isEmpty() && age <= MESSAGE_EDIT_WINDOW_MS) {
            labels += getString(R.string.chat_action_edit_message)
        }
        if (isMine) {
            labels += getString(R.string.chat_action_delete_for_everyone)
        }
        labels += getString(R.string.chat_action_delete_for_me)
        val actions = labels.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(if (isMine) message.text else message.getSenderName())
            .setItems(actions) { _, which ->
                val chosen = actions[which]
                when {
                    chosen == getString(R.string.chat_action_edit_message) ->
                        promptEditMessage(message)
                    chosen == getString(R.string.chat_action_delete_for_everyone) ->
                        confirmDeleteMessage(message, forEveryone = true)
                    else -> confirmDeleteMessage(message, forEveryone = false)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptEditMessage(message: ChatMessage) {
        val input = android.widget.EditText(this).apply {
            setText(message.text)
            setSelection(text.length)
            maxLines = 4
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_message_edit_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newText = input.text?.toString().orEmpty().trim()
                if (newText.isEmpty() || newText == message.text) return@setPositiveButton
                lifecycleScope.launch {
                    val done = repository.editMessage(message.id, newText)
                    Toast.makeText(
                        this@ChatConversationV2Activity,
                        if (done) R.string.chat_message_updated else R.string.chat_message_action_failed,
                        if (done) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteMessage(message: ChatMessage, forEveryone: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(
                if (forEveryone) {
                    R.string.chat_action_delete_for_everyone
                } else {
                    R.string.chat_action_delete_for_me
                }
            )
            .setMessage(
                if (forEveryone) {
                    R.string.chat_delete_for_everyone_confirm
                } else {
                    R.string.chat_delete_for_me_confirm
                }
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val done = if (forEveryone) {
                        repository.deleteMessageForEveryone(message.id)
                    } else {
                        repository.deleteMessageForMe(message.id)
                    }
                    Toast.makeText(
                        this@ChatConversationV2Activity,
                        if (done) R.string.chat_message_deleted_done else R.string.chat_message_action_failed,
                        if (done) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun loadConversation() {
        binding.loadingIndicator.visibility = View.VISIBLE
        conversation = repository.getCachedConversations()
            .firstOrNull { it.conversationId == conversationId }
        if (conversation == null) {
            runCatching { repository.refreshConversations(resolveTargetChildDeviceId()) }
            conversation = repository.getCachedConversations()
                .firstOrNull { it.conversationId == conversationId }
        }
        val current = conversation
        if (current == null) {
            Toast.makeText(this, R.string.chat_v2_offline, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val localMember = current.members.firstOrNull { it.isLocalUser }
        renderHeader(current)
        val currentRole = if (localMember?.role == ConversationMemberRole.CHILD) "child" else "parent"
        adapter = ChatAdapter(
            currentUser = currentRole,
            currentUserDeviceId = localMember?.memberId,
            onRetryMessage = { failed ->
                lifecycleScope.launch {
                    repository.retryFailed(failed.id)
                    syncOnce()
                }
            },
            onMessageLongPress = { message -> showMessageActions(message) },
            onAttachmentOpen = { message -> openAttachment(message) },
            onAttachmentPreview = { message, image -> previewAttachment(message, image) }
        ).also { chatAdapter ->
            binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this)
            binding.messagesRecyclerView.adapter = chatAdapter
        }
        binding.loadingIndicator.visibility = View.GONE

        observeMessages(localMember?.memberId)
        startMedia()
        lifecycleScope.launch {
            // The list is read again so a group that was renamed, or that somebody else
            // has just been added to, reaches this header without waiting for the screen
            // to be reopened.
            runCatching { repository.refreshConversations(resolveTargetChildDeviceId()) }
            val refreshed = repository.getCachedConversations()
                .firstOrNull { it.conversationId == conversationId } ?: return@launch
            conversation = refreshed
            renderHeader(refreshed)
        }
        syncOnce()
    }

    /**
     * Writes who this conversation is into the header.
     *
     * A group also names the people in it: "a group" is not an answer to whom one is
     * writing to, and the membership the screen already holds is the honest source for
     * it — the same people the server listed when the list was refreshed.
     *
     * A group is opened for its settings from this header, because the header is what
     * already says which group this is. The family chat and a direct conversation are
     * left exactly as they were.
     */
    private fun renderHeader(current: Conversation) = with(binding) {
        val otherMember = current.members.firstOrNull { !it.isLocalUser }
        if (current.type == ConversationType.DIRECT && otherMember != null) {
            chatPartnerName.text = otherMember.displayName
            // The line says what this conversation is and who is on the other side.
            // It was hidden for a direct chat from the day it was written, so nobody
            // ever saw it; the owner asked for it to be shown everywhere.
            chatPartnerMeta.visibility = View.VISIBLE
            chatPartnerMeta.text = getString(
                R.string.chat_v2_direct_meta,
                roleLabel(otherMember.role)
            )
            chatMembersText.visibility = View.GONE
            FamilyAvatarRenderer.bind(
                chatAvatar,
                otherMember.avatarKey,
                otherMember.displayName
            )
        } else {
            chatPartnerName.text = current.title
            // Shown for every kind now, with the count that belongs to it: the owner
            // asked for the line that says how many people are in the conversation.
            chatPartnerMeta.visibility = View.VISIBLE
            chatPartnerMeta.text = if (current.type == ConversationType.GROUP) {
                resources.getQuantityString(
                    R.plurals.chat_v2_group_member_count,
                    current.members.size,
                    current.members.size
                )
            } else {
                resources.getQuantityString(
                    R.plurals.chat_v2_family_member_count,
                    current.members.size,
                    current.members.size
                )
            }
            // A family chat has no single peer, but the conversation itself owns a
            // shared picture; only one without a picture falls back to the letter
            // drawn from the title.
            FamilyAvatarRenderer.bind(chatAvatar, current.avatarKey, current.title)
            val names = memberNames(current)
            // A conversation whose membership is not on this phone says nothing about
            // it, rather than showing a line with no people in it.
            if (current.type == ConversationType.GROUP && names.isNotBlank()) {
                chatMembersText.text = getString(R.string.chat_v2_group_members, names)
                chatMembersText.visibility = View.VISIBLE
            } else {
                chatMembersText.visibility = View.GONE
            }
        }

        val isGroup = current.type == ConversationType.GROUP
        connectionStatusCard.isClickable = isGroup
        connectionStatusCard.isFocusable = isGroup
        connectionStatusCard.setOnClickListener(
            if (isGroup) View.OnClickListener { openGroupSettings() } else null
        )
        chatInfoButton.visibility = if (isGroup) View.VISIBLE else View.GONE
        chatInfoButton.contentDescription = getString(
            if (isGroup) R.string.chat_v2_group_info_desc else R.string.chat_info_button_desc
        )
        chatInfoButton.setOnClickListener(
            if (isGroup) View.OnClickListener { openGroupSettings() } else null
        )
    }

    /** Everybody in the conversation, with this device's own member named as such. */
    private fun memberNames(current: Conversation): String = current.members
        .distinctBy(ConversationMember::memberId)
        .sortedBy(ConversationMember::displayName)
        .joinToString(", ") { member ->
            if (member.isLocalUser || member.memberId == current.localMemberId) {
                getString(R.string.chat_sender_you)
            } else {
                member.displayName
            }
        }

    /**
     * Opens the group's settings from inside the conversation.
     *
     * Settings read the group's current membership directly. The family roster is
     * fetched on demand when adding people, rather than delaying every settings open.
     */
    private fun openGroupSettings() {
        if (conversation?.type != ConversationType.GROUP) return
        GroupManagementDialog.show(
            activity = this,
            scope = lifecycleScope,
            repository = repository,
            conversationId = conversationId,
            onRefresh = { reloadAfterGroupChange() }
        )
    }

    /**
     * Reads this conversation again after the group changed, and closes the screen when
     * the group is no longer one of this phone's conversations.
     *
     * The screen closes without a sentence of its own: every way a group can disappear
     * from here — a member leaving, the administrator closing it, or it being closed once
     * too few people were left — has already said so on the screen that caused it.
     */
    private suspend fun reloadAfterGroupChange() {
        runCatching { repository.refreshConversations(resolveTargetChildDeviceId()) }
        val current = repository.getCachedConversations()
            .firstOrNull { it.conversationId == conversationId }
        if (current == null) {
            runOnUiThread { finish() }
            return
        }
        conversation = current
        runOnUiThread { renderHeader(current) }
    }

    private fun roleLabel(role: ConversationMemberRole): String = when (role) {
        ConversationMemberRole.PARENT -> getString(R.string.family_role_parent)
        ConversationMemberRole.CHILD -> getString(R.string.family_role_child)
        ConversationMemberRole.GUARDIAN -> getString(R.string.family_role_relative)
    }

    private fun observeMessages(localMemberId: String?) {
        messageObserverJob?.cancel()
        messageObserverJob = lifecycleScope.launch {
            repository.observeMessages(conversationId, displayedMessageLimit).collectLatest { newestFirst ->
                renderMessages(newestFirst.asReversed(), localMemberId)
            }
        }
    }

    private var renderedMessages: List<ConversationMessage> = emptyList()
    private var viewedWatermark = 0L

    override fun onResume() {
        super.onResume()
        binding.messagesRecyclerView.post { recordVisibleMessages() }
    }

    private fun recordVisibleMessages() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !isNearBottom()) return
        val manager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = manager.findLastVisibleItemPosition()
        if (lastVisible < 0) return
        val sequence = renderedMessages.take(lastVisible + 1).maxOfOrNull { it.serverSequence ?: 0L } ?: 0L
        if (sequence <= viewedWatermark) return
        val previous = viewedWatermark
        viewedWatermark = sequence // Deduplicate Room emissions before the transaction completes.
        lifecycleScope.launch {
            try {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !recordViewedThrough(sequence)) {
                    if (viewedWatermark == sequence) viewedWatermark = previous
                }
            } catch (cancelled: CancellationException) {
                if (viewedWatermark == sequence) viewedWatermark = previous
                throw cancelled
            }
        }
    }

    private fun renderMessages(messages: List<ConversationMessage>, localMemberId: String?) {
        val wasNearBottom = isNearBottom()
        val added = (messages.size - previousMessageCount).coerceAtLeast(0)
        previousMessageCount = messages.size
        val rows = messages.map { it.toLegacy(localMemberId) }
        adapter?.submitMessages(rows) {
            renderedMessages = messages
            binding.messagesRecyclerView.post { recordVisibleMessages() }
            binding.emptyStateCard.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (!initialScrollDone || wasNearBottom) {
                initialScrollDone = true
                scrollToBottom()
            } else if (added > 0 && !olderMessagesPendingRender) {
                pendingNewMessages += added
                binding.newMessagesButton.text = getString(
                    R.string.chat_v2_new_messages,
                    pendingNewMessages
                )
                binding.newMessagesButton.visibility = View.VISIBLE
            }
            if (olderMessagesPendingRender && added > 0) olderMessagesPendingRender = false
        }
    }

    private fun sendMessage() {
        if (textSendInProgress || mediaPreparing) return
        if (mediaDraft != null) { sendMediaDraft(); return }
        val text = binding.messageInput.text?.toString().orEmpty()
        if (text.isBlank()) return
        val current = conversation ?: return
        val localMember = current.members.firstOrNull { it.isLocalUser }
        textSendInProgress = true
        updateComposerState()
        lifecycleScope.launch {
            try {
                val queued = repository.enqueueMessage(
                    conversationId = conversationId,
                    text = text,
                    senderDisplayName = localMember?.displayName
                        ?: getString(R.string.chat_sender_you),
                    senderRole = localMember?.role ?: ConversationMemberRole.GUARDIAN,
                    senderMemberId = localMember?.memberId
                )
                if (binding.messageInput.text?.toString() == text) binding.messageInput.text?.clear()
                scrollToBottom()
                val sentRealtime = WebSocketManager.sendChatV2Message(
                    conversationId = conversationId,
                    clientMessageId = queued.clientMessageId,
                    text = queued.text,
                    clientSentAt = queued.clientSentAt
                ) { acknowledgement ->
                    lifecycleScope.launch {
                        val dto = acknowledgement.optJSONObject("message")?.let { message ->
                            runCatching {
                                gson.fromJson(message.toString(), ChatV2MessageDto::class.java)
                            }.getOrNull()
                        }
                        if (acknowledgement.optBoolean("success") && dto != null) {
                            runCatching { repository.cacheRealtimeMessage(dto) }
                                .onFailure { repository.flushOutbox() }
                        } else {
                            repository.flushOutbox()
                        }
                    }
                }
                if (!sentRealtime) {
                    repository.flushOutbox()
                } else {
                    // Socket.IO acknowledgements can be lost on a network handover even when
                    // the emit itself succeeded. A delayed durable flush is idempotent and keeps
                    // the fallback latency bounded without duplicating acknowledged messages.
                    lifecycleScope.launch {
                        delay(3_000L)
                        repository.flushOutbox()
                    }
                }
            } catch (_: IllegalArgumentException) {
                Toast.makeText(
                    this@ChatConversationV2Activity,
                    R.string.chat_v2_message_rejected,
                    Toast.LENGTH_LONG
                ).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                Toast.makeText(this@ChatConversationV2Activity, R.string.chat_v2_offline, Toast.LENGTH_LONG).show()
            } finally { textSendInProgress = false; updateComposerState() }
        }
    }

    private fun startSyncLoop() {
        if (!::repository.isInitialized || syncJob?.isActive == true) return
        syncJob = lifecycleScope.launch {
            while (isActive) {
                syncOnce()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun handleRealtimeEvent(payload: JSONObject) {
        if (payload.optString("conversationId") != conversationId) return
        lifecycleScope.launch { syncOnce() }
    }

    private fun handleRealtimeMessage(payload: JSONObject) {
        if (payload.optString("conversationId") != conversationId) return
        val messageJson = payload.optJSONObject("message") ?: return
        lifecycleScope.launch {
            val dto = runCatching {
                gson.fromJson(messageJson.toString(), ChatV2MessageDto::class.java)
            }.getOrNull()
            if (dto == null) {
                syncOnce()
                return@launch
            }
            runCatching { repository.cacheRealtimeMessage(dto) }
                .onSuccess {
                    binding.connectionStatusText.setText(R.string.chat_v2_ready)
                    updateReadReceiptStatus()
                }
                .onFailure { syncOnce() }
        }
    }

    private fun loadOlderMessages() {
        val before = nextBeforeSequence ?: return
        if (loadingOlderMessages || !::repository.isInitialized) return
        loadingOlderMessages = true
        lifecycleScope.launch {
            try {
                val page = repository.syncMessagesPage(
                    conversationId = conversationId,
                    beforeSequence = before,
                    limit = OLDER_MESSAGE_PAGE_SIZE
                )
                nextBeforeSequence = page.nextBeforeSequence
                if (page.messages.isNotEmpty()) {
                    olderMessagesPendingRender = true
                    displayedMessageLimit += page.messages.size
                    observeMessages(conversation?.localMemberId)
                }
            } catch (_: Exception) {
                binding.connectionStatusText.setText(R.string.chat_v2_offline)
            } finally {
                loadingOlderMessages = false
            }
        }
    }

    private suspend fun syncOnce() {
        if (!::repository.isInitialized || conversation == null) return
        try {
            repository.flushOutbox()
            val page = repository.syncMessagesPage(conversationId, limit = 200)
            if (displayedMessageLimit == INITIAL_MESSAGE_LIMIT) {
                nextBeforeSequence = page.nextBeforeSequence
            }
            updateReadReceiptStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            binding.connectionStatusText.setText(R.string.chat_v2_offline)
        }
    }

    private suspend fun recordViewedThrough(sequence: Long): Boolean {
        try {
            repository.markReadThrough(conversationId, sequence)
            updateReadReceiptStatus()
            lifecycleScope.launch {
                repository.flushReadReceipts()
                updateReadReceiptStatus()
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            binding.connectionStatusText.setText(R.string.chat_v2_offline)
            return false
        }
    }

    private suspend fun updateReadReceiptStatus() {
        binding.connectionStatusText.setText(if (repository.hasPendingReadReceipt(conversationId))
            R.string.chat_v2_read_receipt_pending else R.string.chat_v2_ready)
    }

    private fun isNearBottom(): Boolean {
        val manager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager ?: return true
        val count = adapter?.itemCount ?: 0
        return count == 0 || manager.findLastVisibleItemPosition() >= count - 3
    }

    private fun scrollToBottom() {
        val last = (adapter?.itemCount ?: 0) - 1
        if (last >= 0) binding.messagesRecyclerView.scrollToPosition(last)
        binding.messagesRecyclerView.post { recordVisibleMessages() }
        pendingNewMessages = 0
        binding.newMessagesButton.visibility = View.GONE
    }

    private fun ConversationMessage.toLegacy(localMemberId: String?): ChatMessage {
        val role = if (senderRole == ConversationMemberRole.CHILD) "child" else "parent"
        return ChatMessage(
            id = clientMessageId,
            text = text,
            sender = role,
            authorDeviceId = senderMemberId,
            authorDisplayName = if (senderMemberId == localMemberId) {
                getString(R.string.chat_sender_you)
            } else {
                senderDisplayName
            },
            timestamp = serverCreatedAt ?: clientSentAt,
            isRead = deliveryState == ChatDeliveryState.READ,
            isMine = senderMemberId != null && senderMemberId == localMemberId,
            editedAt = editedAt,
            deletedAt = deletedAt,
            attachments = attachments,
            status = when (deliveryState) {
                ChatDeliveryState.QUEUED, ChatDeliveryState.SENDING -> ChatMessage.MessageStatus.SENDING
                ChatDeliveryState.ACCEPTED -> ChatMessage.MessageStatus.SENT
                ChatDeliveryState.DELIVERED -> ChatMessage.MessageStatus.DELIVERED
                ChatDeliveryState.READ -> ChatMessage.MessageStatus.READ
                ChatDeliveryState.FAILED -> ChatMessage.MessageStatus.FAILED
            }
        )
    }

    private fun resolveServerUrl(): String =
        ParentEffectiveContextProvider.get(this).featureContext("chat")?.serverUrl
            ?.trim().orEmpty()
            .ifBlank { SecureSettingsManager(this).getServerUrl().trim() }

    private fun resolveTargetChildDeviceId(): String? =
        ParentEffectiveContextProvider.get(this).featureContext("chat")?.targetDeviceId
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: SecureSettingsManager(this).getChildDeviceId()?.trim()?.takeIf { it.isNotEmpty() }
}
