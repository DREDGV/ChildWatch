package ru.example.childwatch

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.core.view.doOnNextLayout
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
import androidx.core.widget.doAfterTextChanged
import ru.example.childwatch.chat.v2.ChatV2Repository
import ru.example.childwatch.chat.v2.ChatV2RepositoryException
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

    private var textDraft: ru.example.childwatch.designsystem.ChatTextDraft? = null
    private var composerScope: String? = null
    private var restoringDraft = false
    private val media by lazy { repository.attachments(this) }
    private val transcriptions by lazy { repository.transcriptions(this) }
    private var transcriptionEnabled = false
    private val mediaCatalog by lazy { repository.mediaCatalog(this) }
    private var attachmentPicker: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var catalogEnabled = false
    private var gifEnabled = false
    private var catalogDialog: ru.example.childwatch.designsystem.ChatMediaCatalogDialog? = null
    private var catalogLoadJob: Job? = null
    private var catalogActionJob: Job? = null
    private var loadedCatalog: ru.childwatch.shared.chat.ChatV2MediaCatalogResponse? = null
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

    private var replySource: ConversationMessage? = null
    private var restoredReplyId: String? = null
    private var historyAnchor: ru.childwatch.shared.chat.ConversationAnchor? = null
    private var pendingAnchorScroll: Long? = null
    private var unreadBoundary: Long? = null
    private var readingUnreadWindow = false
    private var historyJob: Job? = null
    private var historySearchJob: Job? = null
    private var historySearch: ru.example.childwatch.designsystem.ChatHistorySearchDialog? = null

    private fun showReply(source: ConversationMessage?) {
        replySource = source
        binding.chatReplyPanel.visibility = if (source == null) View.GONE else View.VISIBLE
        binding.chatReplyAuthor.text = source?.let { getString(R.string.chat_reply_to, it.senderDisplayName) }.orEmpty()
        binding.chatReplyText.text = source?.let(::quoteBody).orEmpty()
        binding.chatReplySource.setOnClickListener(source?.let { original ->
            View.OnClickListener { openMessageContext(original.messageId) }
        })
        updateComposerState()
    }

    private fun quoteBody(source: ConversationMessage): String =
        source.text.trim().take(160).ifBlank {
            source.attachments.firstOrNull()?.filename ?: getString(R.string.chat_quote_attachment)
        }

    private fun quotePreview(source: ConversationMessage): String =
        if (source.deletedAt != null) getString(R.string.chat_message_deleted)
        else getString(R.string.chat_reply_preview, source.senderDisplayName, quoteBody(source))

    private fun updateComposerState() {
        val draft = mediaDraft
        val busy = textSendInProgress || mediaPreparing || mediaSendJob?.isActive == true || draft?.state in setOf("QUEUED", "UPLOADING", "UPLOADED")
        binding.sendButton.isEnabled = !busy && (!binding.messageInput.text.isNullOrBlank() || draft != null)
        binding.attachmentButton.isEnabled = mediaEnabled && !busy && draft == null && replySource == null
        binding.attachmentDraftCancel.isEnabled = !mediaPreparing
    }

    private fun startMedia() {
        lifecycleScope.launch {
            val capability = runCatching { media.capabilities() }.getOrNull()
            mediaEnabled = capability?.attachments == true
            voiceEnabled = mediaEnabled && "VOICE" in capability!!.attachmentTypes
            transcriptionEnabled = voiceEnabled && capability?.transcription == true
            catalogEnabled = mediaEnabled && capability?.mediaCatalog == true
            gifEnabled = mediaEnabled && "GIF" in capability!!.attachmentTypes
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
        if (!mediaEnabled || mediaDraft != null || mediaPreparing || replySource != null) return
        val guard = media.currentScopeKey() ?: return
        attachmentPicker?.dismiss()
        attachmentPicker = ru.example.childwatch.designsystem.ChatAttachmentPickerDialog.show(this, voiceEnabled) { type ->
            if (guard != media.currentScopeKey()) return@show
            if (mediaDraft != null || mediaPreparing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@show
            if (type == "VOICE") {
                voicePermissionScope = media.currentScopeKey()
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) showVoiceRecorder()
                else voicePermission.launch(android.Manifest.permission.RECORD_AUDIO)
            } else pickMediaFile(type)
        }
    }

    private fun pickMediaFile(type: String) {
        if (!mediaEnabled || mediaDraft != null || mediaPreparing || (type == "GIF" && !gifEnabled)) return
        pickerType = type; pickerScope = media.currentScopeKey()
        documentPicker.launch(when (type) {
            "GIF" -> arrayOf("image/gif")
            "IMAGE" -> arrayOf("image/jpeg", "image/png", "image/webp")
            else -> arrayOf("*/*")
        })
    }

    private fun showEmotions() {
        if ((catalogEnabled || gifEnabled) && mediaDraft == null && !mediaPreparing) {
            emojiPopup?.dismiss()
            showMediaCatalog()
        } else emojiPopup?.toggle()
    }

    private fun showMediaCatalog() {
        if ((!catalogEnabled && !gifEnabled) || mediaPreparing || mediaDraft != null || replySource != null) return
        val guard = media.currentScopeKey() ?: return
        catalogDialog?.close()
        lateinit var panel: ru.example.childwatch.designsystem.ChatMediaCatalogDialog
        panel = ru.example.childwatch.designsystem.ChatMediaCatalogDialog(this, { guard == media.currentScopeKey() },
            object : ru.example.childwatch.designsystem.ChatMediaCatalogDialog.Actions {
                override fun pick(id: String) = attachCatalogItem(panel, guard, id)
                override fun retry() = loadMediaCatalog(panel, guard)
                override fun motion(enabled: Boolean) = updateVisibleMedia(enabled)
                override fun emoji() { if (guard == media.currentScopeKey()) emojiPopup?.toggle() }
                override fun phoneGif() { if (guard == media.currentScopeKey()) pickMediaFile("GIF") }
                override fun closed() {
                    catalogLoadJob?.cancel(); catalogLoadJob = null
                    catalogActionJob?.cancel(); catalogActionJob = null
                    loadedCatalog = null
                }
            })
        catalogDialog = panel; panel.phoneGifEnabled(gifEnabled); panel.show(); if (catalogEnabled) loadMediaCatalog(panel, guard) else panel.items(emptyList())
    }

    private fun loadMediaCatalog(panel: ru.example.childwatch.designsystem.ChatMediaCatalogDialog, guard: String) {
        catalogLoadJob?.cancel()
        catalogLoadJob = lifecycleScope.launch {
            try {
                val catalog = mediaCatalog.load(conversationId)
                if (guard != media.currentScopeKey() || catalogDialog !== panel || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                loadedCatalog = catalog
                panel.items(catalog.items.orEmpty().map { ru.example.childwatch.designsystem.ChatMediaCatalogDialog.Entry(it.id, it.label, it.type) })
                for (item in catalog.items.orEmpty()) {
                    val file = mediaCatalog.fetch(conversationId, catalog, item)
                    if (guard != media.currentScopeKey() || catalogDialog !== panel || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                    panel.preview(item.id, file)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (guard == media.currentScopeKey() && catalogDialog === panel) panel.failed() }
        }
    }

    private fun attachCatalogItem(panel: ru.example.childwatch.designsystem.ChatMediaCatalogDialog, guard: String, id: String) {
        if (guard != media.currentScopeKey() || mediaPreparing || mediaDraft != null) return
        val catalog = loadedCatalog ?: return
        val item = catalog.items.orEmpty().firstOrNull { it.id == id } ?: return
        mediaPreparing = true; panel.busy(true); updateComposerState()
        catalogActionJob = lifecycleScope.launch {
            try {
                val file = mediaCatalog.fetch(conversationId, catalog, item)
                check(guard == media.currentScopeKey()) { "CONTEXT_CHANGED" }
                val displayName = item.label + if (item.type == "GIF") ".gif" else ".png"
                val uri = androidx.core.content.FileProvider.getUriForFile(this@ChatConversationV2Activity, "$packageName.fileprovider", file, displayName)
                mediaDraft = media.createDraft(conversationId, uri, displayName, item.mimeType, item.type)
                catalogActionJob = null
                panel.close(); catalogDialog = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (guard == media.currentScopeKey() && catalogDialog === panel) panel.failed()
            } finally {
                mediaPreparing = false; catalogActionJob = null
                if (catalogDialog === panel) panel.busy(false)
                renderMediaDraft()
            }
        }
    }

    private fun updateVisibleMedia(animate: Boolean) {
        ru.example.childwatch.designsystem.ChatAnimatedMedia.update(binding.attachmentDraftImage,
            animate && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val list = binding.messagesRecyclerView
        for (index in 0 until list.childCount) {
            val image = list.getChildAt(index).findViewById<android.widget.ImageView>(R.id.attachmentImage)
            if (image != null) ru.example.childwatch.designsystem.ChatAnimatedMedia.update(image,
                animate && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
    }

    private fun showVoiceRecorder() {
        if (!voiceEnabled || mediaDraft != null || mediaPreparing || replySource != null) return
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
        textDraft?.flush()
        presenceWatcher.stop()
        participantsDialog?.close(); participantsDialog = null
        attachmentPicker?.dismiss(); attachmentPicker = null
        updateVisibleMedia(false)
        catalogDialog?.close(); catalogDialog = null
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
                        if (type in setOf("IMAGE", "GIF")) ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.Mode.IMAGE else ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.Mode.FILE, mime, size)
                    Pair(ru.example.childwatch.designsystem.ChatAttachmentInputPolicy.displayName(name), mime)
                }
                check(guard != null && guard == media.currentScopeKey()) { "CONTEXT_CHANGED" }
                val actualType = if (type == "IMAGE" && metadata.second == "image/gif") "GIF" else type
                mediaDraft = media.createDraft(conversationId, uri, metadata.first, metadata.second, actualType)
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
            ru.example.childwatch.designsystem.ChatAnimatedMedia.clear(binding.attachmentDraftImage)
            binding.attachmentDraftImage.visibility = View.GONE
            binding.attachmentDraftImage.tag = draft?.clientMessageId
        }
        if (draft != null) {
            if (previewChanged && draft.caption.isNotBlank() && binding.messageInput.text.isNullOrBlank()) binding.messageInput.setText(draft.caption)
            val size = android.text.format.Formatter.formatShortFileSize(this, draft.sizeBytes)
            val progress = ((draft.progressBytes.toDouble() / draft.sizeBytes.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
            val state = when (draft.state) {
                "QUEUED" -> getString(R.string.chat_status_queued)
                "UPLOADING" -> getString(R.string.chat_media_uploading, progress)
                "UPLOADED" -> getString(R.string.chat_media_finishing)
                "RETRY" -> getString(R.string.chat_media_retry_scheduled)
                "FAILED" -> getString(R.string.chat_media_failed)
                else -> size
            }
            binding.attachmentDraftText.text = if (draft.attachmentType in setOf("GIF", "STICKER")) {
                val label = getString(if (draft.attachmentType == "GIF") R.string.chat_catalog_gifs else R.string.chat_catalog_stickers)
                if (draft.state in setOf("QUEUED", "UPLOADING", "UPLOADED", "FAILED", "RETRY")) "$label · $state" else label
            } else getString(R.string.chat_media_size, draft.filename, state)
            binding.attachmentDraftCancel.setText(if (draft.state == "DRAFT") R.string.chat_media_remove else R.string.chat_media_cancel)
            binding.attachmentDraftProgress.isIndeterminate = false
            binding.attachmentDraftProgress.progress = progress
            binding.attachmentDraftProgress.visibility = if (draft.state == "UPLOADING") View.VISIBLE else View.GONE
            if (previewChanged && draft.attachmentType in setOf("IMAGE", "GIF", "STICKER")) {
                val guard = media.currentScopeKey()
                lifecycleScope.launch {
                    val file = java.io.File(draft.localPath)
                    val previewable = draft.attachmentType != "GIF" || kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { file.length() <= 10L * 1024 * 1024 && ru.example.childwatch.designsystem.ChatGifPolicy.previewable(file.readBytes()) }.getOrDefault(false)
                    }
                    if (guard == media.currentScopeKey() && binding.attachmentDraftImage.tag == draft.clientMessageId) {
                        if (!previewable) {
                            binding.attachmentDraftImage.contentDescription = getString(R.string.chat_gif_preview_limited)
                            return@launch
                        }
                        binding.attachmentDraftImage.visibility = View.VISIBLE
                        binding.attachmentDraftImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        ru.example.childwatch.designsystem.ChatAnimatedMedia.load(binding.attachmentDraftImage, file,
                            { guard == media.currentScopeKey() && binding.attachmentDraftImage.tag == draft.clientMessageId },
                            { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }, { })
                    }
                }
            }
        }
        updateComposerState()
    }

    private fun previewAttachment(message: ChatMessage, image: android.widget.ImageView) {
        val attachment = message.attachments.firstOrNull() ?: return
        val guard = media.currentScopeKey()
        lifecycleScope.launch {
            var downloaded: java.io.File? = null
            try {
                val file = media.download(conversationId, attachment); downloaded = file
                if (attachment.type == "GIF") {
                    val safe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        file.length() <= 10L * 1024 * 1024 && ru.example.childwatch.designsystem.ChatGifPolicy.previewable(file.readBytes())
                    }
                    if (!safe) {
                        if (guard == media.currentScopeKey() && image.tag == attachment.attachmentId) image.contentDescription = getString(R.string.chat_gif_preview_limited)
                        return@launch
                    }
                }
                if (guard != media.currentScopeKey() || image.tag != attachment.attachmentId) return@launch
                ru.example.childwatch.designsystem.ChatAnimatedMedia.load(image, file,
                    { guard == media.currentScopeKey() && image.tag == attachment.attachmentId },
                    { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ru.example.childwatch.designsystem.ChatAnimatedMedia.animationsEnabled(this@ChatConversationV2Activity) },
                    { file.delete() })
                downloaded = null // Decoder owns the temporary file until ready/failed/cleared.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (guard == media.currentScopeKey() && image.tag == attachment.attachmentId) image.contentDescription = getString(R.string.chat_media_unavailable)
            } finally { downloaded?.delete() }
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
            catch (_: Exception) {
                val saved = media.getDraft(draft.clientMessageId)
                val reason = when (saved?.state) {
                    "RETRY" -> R.string.chat_media_retry_scheduled
                    "FAILED" -> R.string.chat_media_failed
                    else -> R.string.chat_media_unavailable
                }
                Toast.makeText(this@ChatConversationV2Activity, reason, Toast.LENGTH_LONG).show()
            } finally { mediaSendJob = null; updateComposerState() }
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
    private var pendingNewMessages = 0
    private var displayedMessageLimit = INITIAL_MESSAGE_LIMIT
    private var nextBeforeSequence: Long? = null
    private var loadingOlderMessages = false

    /** Pending "is writing" report and the timer that clears a stale indicator. */
    private var typingReportJob: Job? = null
    private var typingTimeoutJob: Job? = null
    private var hasReportedTyping = false
    private var presenceSnapshot: ru.childwatch.shared.chat.ChatPresenceSnapshot? = null
    private var participantsDialog: ru.example.childwatch.designsystem.ChatParticipantsDialog? = null
    private val presenceWatcher by lazy {
        ru.childwatch.shared.chat.ChatConversationPresenceWatcher(lifecycleScope, changed = { renderConversationPresence(it) })
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
            runOnUiThread { showChatStatus(R.string.chat_v2_offline) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        binding.messageInput.isSaveEnabled = false
        setContentView(binding.root)

        pickerType = savedInstanceState?.getString("media_picker_type") ?: "FILE"
        pickerScope = savedInstanceState?.getString("media_picker_scope")
        restoredReplyId = savedInstanceState?.getString("chat_reply_source")
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
        outState.putString("chat_reply_source", replySource?.messageId)
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
        historySearch?.dismiss()
        if (::binding.isInitialized) ru.example.childwatch.designsystem.ChatAnimatedMedia.clear(binding.attachmentDraftImage)
        emojiPopup?.dismiss()
        emojiPopup = null
        super.onDestroy()
    }

    private fun configureStaticUi() = with(binding) {
        val initialTitle = intent.getStringExtra(EXTRA_CONVERSATION_TITLE)
            ?.takeIf { it.isNotBlank() } ?: getString(R.string.chat_title_family)
        connectionStatusCard.visibility = View.GONE
        compactChatHeader.visibility = View.VISIBLE
        compactChatHeader.showConversation(initialTitle, "")
        FamilyAvatarRenderer.bind(compactChatHeader.avatar(), null, initialTitle)
        compactChatHeader.bindActions({ onBackPressedDispatcher.onBackPressed() }, ::showParticipants, ::openHistorySearch)
        showChatStatus(R.string.chat_v2_syncing)
        typingIndicator.visibility = View.GONE
        composerMetaText.visibility = View.GONE
        sendButton.setIconResource(R.drawable.cw_chat_send_arrow)
        emptyStateTitle.setText(R.string.chat_v2_no_messages)
        emptyStateText.setText(R.string.chat_empty_hint)

        emojiPopup = EmojiPopup(root, messageInput)
        emojiButton.setOnClickListener { showEmotions() }
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
            if (restoringDraft) {
                updateComposerState()
                return@doAfterTextChanged
            }
            if (textDraft != null && composerScope == media.currentScopeKey()) {
                textDraft?.changed(editable?.toString().orEmpty())
            }
            updateComposerState()
            reportTyping(!editable.isNullOrBlank())
        }
        newMessagesButton.setOnClickListener { scrollToBottom() }
        chatReplyCancel.setOnClickListener { showReply(null) }
        chatHistoryLatest.setOnClickListener { scrollToBottom() }
        chatHistoryContinue.setOnClickListener {
            renderedMessages.lastOrNull { it.serverSequence != null }?.let {
                val readThrough = historyAnchor?.let { anchor -> viewedWatermark >= anchor.lastSequence } == true
                openMessageContext(it.messageId, keepReadingUnread = readingUnreadWindow && readThrough)
            }
        }
        onBackPressedDispatcher.addCallback(this@ChatConversationV2Activity,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (historyAnchor != null || historyJob?.isActive == true) {
                        historyJob?.cancel()
                        scrollToBottom()
                    }
                    else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            })
        messagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateVisibleMedia(true)
                if (historyAnchor != null) {
                    recordVisibleMessages()
                } else if (isNearBottom()) {
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

    /** One foreground lease for this conversation, independent of family monitoring. */
    private fun startConversationPresence() {
        if (!::repository.isInitialized || conversation == null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val transport = repository.presenceTransport(conversationId) ?: return
        presenceWatcher.start(transport)
    }

    private fun participantStatus(open: Boolean, connected: Boolean?): String = getString(when {
        open -> ru.example.childwatch.designsystem.R.string.chat_participants_open
        connected == true -> ru.example.childwatch.designsystem.R.string.chat_participants_connected
        connected == false -> ru.example.childwatch.designsystem.R.string.chat_participants_not_connected
        else -> ru.example.childwatch.designsystem.R.string.chat_participants_status_unknown
    })

    private fun participantEntries(): List<ru.example.childwatch.designsystem.ChatParticipantsDialog.Entry> {
        val fresh = presenceSnapshot
        return if (fresh != null) fresh.participants.distinctBy { it.memberId }.map { member ->
            ru.example.childwatch.designsystem.ChatParticipantsDialog.Entry(
                if (member.memberId == fresh.actorMemberId) getString(R.string.chat_sender_you)
                else member.displayName?.takeIf { it.isNotBlank() } ?: getString(ru.example.childwatch.designsystem.R.string.chat_participants_title),
                participantStatus(member.chatOpen, member.deviceConnected), member.chatOpen)
        } else conversation?.members.orEmpty().distinctBy { it.memberId }.map { member ->
            ru.example.childwatch.designsystem.ChatParticipantsDialog.Entry(
                if (member.isLocalUser) getString(R.string.chat_sender_you) else member.displayName,
                participantStatus(false, null), false)
        }
    }

    private fun renderConversationPresence(snapshot: ru.childwatch.shared.chat.ChatPresenceSnapshot?) {
        if (!::binding.isInitialized) return
        presenceSnapshot = snapshot
        participantsDialog?.render(participantEntries(), snapshot != null)
    }

    private fun showParticipants() {
        val current = conversation ?: return
        participantsDialog?.close()
        val openSettings = if (current.type == ConversationType.GROUP) Runnable { openGroupSettings() } else null
        participantsDialog = ru.example.childwatch.designsystem.ChatParticipantsDialog(this, openSettings).also {
            it.render(participantEntries(), presenceSnapshot != null)
            it.show()
        }
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
        if (message.serverMessageId != null && mediaDraft == null && !mediaPreparing && composerScope != null) {
            labels += getString(R.string.chat_reply)
        }
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
                    chosen == getString(R.string.chat_reply) -> {
                        val source = renderedMessages.firstOrNull { it.messageId == message.serverMessageId && it.deletedAt == null }
                        if (source != null) {
                            showReply(source)
                            binding.messageInput.requestFocus()
                        }
                    }
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
        val openingScope = media.currentScopeKey()
        binding.loadingIndicator.visibility = View.VISIBLE
        conversation = repository.getCachedConversation(conversationId)
        if (conversation == null) {
            runCatching { repository.refreshConversations(resolveTargetChildDeviceId()) }
            conversation = repository.getCachedConversation(conversationId)
        }
        val current = conversation
        if (current == null) {
            if (finishIfGroupAccessLost()) return
            Toast.makeText(this, R.string.chat_v2_offline, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (finishIfGroupAccessLost()) return
        if (media.currentScopeKey() != openingScope) {
            finish()
            return
        }
        composerScope = openingScope
        composerScope?.let { scope ->
            textDraft = ru.example.childwatch.designsystem.ChatTextDraft(this, scope, conversationId).also { draft ->
                restoringDraft = true
                try {
                    if (binding.messageInput.length() == 0) draft.restore(binding.messageInput)
                    else draft.changed(binding.messageInput.text.toString())
                } finally {
                    restoringDraft = false
                }
            }
        }
        startConversationPresence()
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
            onAttachmentPreview = { message, image -> previewAttachment(message, image) },
            onQuoteOpen = { openMessageContext(it) }
        ).also { chatAdapter ->
            binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this)
            binding.messagesRecyclerView.adapter = chatAdapter
        }
        binding.loadingIndicator.visibility = View.GONE

        restoredReplyId?.let { id ->
            if (composerScope != null) {
                val source = repository.quoteSources(conversationId, listOf(id))[id]
                if (source?.deletedAt == null) showReply(source)
            }
            restoredReplyId = null
        }
        if (composerScope != null && current.lastSequence > current.lastReadSequence) {
            try {
                repository.loadMessageAnchor(conversationId, unread = true)?.let { anchor ->
                    unreadBoundary = anchor.sequence
                    setHistoryAnchor(anchor, readWindow = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this, R.string.chat_history_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        observeMessages(localMember?.memberId)
        startMedia()
        lifecycleScope.launch { reloadAfterGroupChange() }
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
    private fun renderHeader(current: Conversation) {
        val peer = current.members.firstOrNull { !it.isLocalUser }.takeIf { current.type == ConversationType.DIRECT }
        val title = peer?.displayName ?: current.title
        val subtitle = if (peer != null) roleLabel(peer.role) else memberNames(current)
        binding.compactChatHeader.showConversation(title, subtitle)
        FamilyAvatarRenderer.bind(binding.compactChatHeader.avatar(), current.avatarKey ?: peer?.avatarKey, title)
    }

    private fun showChatStatus(resId: Int) {
        binding.compactChatHeader.showConnectionNotice(if (resId == R.string.chat_v2_ready) "" else getString(resId))
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

    /** Refreshes the header after membership or group settings change. */
    private suspend fun reloadAfterGroupChange() {
        try {
            repository.refreshConversations(resolveTargetChildDeviceId())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (finishIfGroupAccessLost()) return
        val current = repository.getCachedConversation(conversationId) ?: return
        conversation = current
        renderHeader(current)
    }

    private suspend fun finishIfGroupAccessLost(): Boolean {
        if (!repository.isGroupAccessLost(conversationId)) return false
        finishUnavailableGroup()
        return true
    }

    private fun finishUnavailableGroup() {
        if (isFinishing || isDestroyed) return
        textDraft?.discard()
        textDraft = null
        conversation = null
        Toast.makeText(this, R.string.group_gone, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun roleLabel(role: ConversationMemberRole): String = when (role) {
        ConversationMemberRole.PARENT -> getString(R.string.family_role_parent)
        ConversationMemberRole.CHILD -> getString(R.string.family_role_child)
        ConversationMemberRole.GUARDIAN -> getString(R.string.family_role_relative)
    }

    private fun openHistorySearch() {
        if (!initialScrollDone && historyAnchor == null) return
        if (conversation == null || composerScope == null || composerScope != media.currentScopeKey()) return
        historySearch?.dismiss()
        val search = ru.example.childwatch.designsystem.ChatHistorySearchDialog(this) { query ->
            historySearchJob?.cancel()
            historySearchJob = lifecycleScope.launch {
                val dialog = historySearch ?: return@launch
                try {
                    val result = repository.searchMessages(conversationId, query)
                    if (!dialog.isShowing || dialog !== historySearch || composerScope != media.currentScopeKey()) return@launch
                    val message = when {
                        result.fromCache && result.messages.isEmpty() -> getString(R.string.chat_search_cache_empty)
                        result.messages.isEmpty() -> getString(R.string.chat_search_empty)
                        result.fromCache -> getString(R.string.chat_search_cache_label)
                        result.hasMore -> getString(R.string.chat_search_limited)
                        else -> ""
                    }
                    val labels = result.messages.map {
                        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(it.serverCreatedAt ?: it.clientSentAt))
                        getString(R.string.chat_search_result, it.senderDisplayName, date, it.text.trim().take(180))
                    }
                    dialog.showResults(labels, message) { index ->
                        result.messages.getOrNull(index)?.let {
                            dialog.dismiss()
                            openMessageContext(it.messageId)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (dialog.isShowing && dialog === historySearch) dialog.setStatus(getString(R.string.chat_search_failed))
                }
            }
        }
        historySearch = search
        search.onDismiss {
            if (historySearch === search) {
                historySearch = null
                historySearchJob?.cancel()
            }
        }
        search.show()
    }

    private fun openMessageContext(messageId: String, keepReadingUnread: Boolean = false) {
        if (conversation == null || composerScope == null || composerScope != media.currentScopeKey()) return
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            try {
                val anchor = repository.loadMessageAnchor(conversationId, messageId)
                if (anchor == null) Toast.makeText(this@ChatConversationV2Activity, R.string.chat_original_unavailable, Toast.LENGTH_SHORT).show()
                else {
                    setHistoryAnchor(anchor, readWindow = keepReadingUnread)
                    observeMessages(conversation?.localMemberId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@ChatConversationV2Activity, R.string.chat_history_open_failed, Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun setHistoryAnchor(anchor: ru.childwatch.shared.chat.ConversationAnchor, readWindow: Boolean) {
        historyAnchor = anchor
        pendingAnchorScroll = anchor.sequence
        readingUnreadWindow = readWindow && !anchor.fromCache
        binding.chatHistoryNavigation.visibility = View.VISIBLE
        binding.chatHistoryContinue.visibility = if (anchor.canContinue) View.VISIBLE else View.GONE
        binding.newMessagesButton.visibility = View.GONE
    }

    private fun observeMessages(localMemberId: String?) {
        messageObserverJob?.cancel()
        messageObserverJob = lifecycleScope.launch {
            val anchor = historyAnchor
            val stream = if (anchor == null) repository.observeMessages(conversationId, displayedMessageLimit)
                else repository.observeMessageAnchor(conversationId, anchor)
            stream.collectLatest { messages ->
                val ordered = if (anchor == null) messages.asReversed() else messages
                val composerReply = replySource?.messageId
                val quotes = repository.quoteSources(conversationId, ordered.mapNotNull { it.replyToMessageId } + listOfNotNull(composerReply))
                if (anchor != historyAnchor) return@collectLatest
                if (composerReply != null && composerReply == replySource?.messageId) {
                    val source = quotes[composerReply]?.takeIf { it.deletedAt == null }
                    if (source != replySource) showReply(source)
                }
                renderMessages(ordered, localMemberId, quotes)
            }
        }
    }

    private var renderedMessages: List<ConversationMessage> = emptyList()
    private var viewedWatermark = 0L

    override fun onResume() {
        super.onResume()
        if (composerScope != null && composerScope != media.currentScopeKey()) {
            finish()
            return
        }
        binding.root.post { startConversationPresence() }
        binding.messagesRecyclerView.post { recordVisibleMessages(); updateVisibleMedia(true) }
    }

    private fun recordVisibleMessages() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || pendingAnchorScroll != null) return
        if (historyAnchor == null && !isNearBottom()) return
        if (historyAnchor != null && !readingUnreadWindow) return
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

    private fun renderMessages(
        messages: List<ConversationMessage>,
        localMemberId: String?,
        quotes: Map<String, ConversationMessage>
    ) {
        val anchor = historyAnchor
        val wasNearBottom = isNearBottom()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        var previousDay: java.time.LocalDate? = null
        val rows = messages.map { message ->
            val timestamp = message.serverCreatedAt ?: message.clientSentAt
            val day = java.time.Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
            val dayLabel = if (day == previousDay) null else if (day == today) {
                getString(R.string.chat_day_today)
            } else android.text.format.DateUtils.formatDateTime(this, timestamp,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
            previousDay = day
            message.toLegacy(localMemberId).copy(
                dayLabel = dayLabel,
                quoteLabel = message.replyToMessageId?.let { id -> quotes[id]?.let(::quotePreview) ?: getString(R.string.chat_reply_original) },
                unreadBoundary = message.serverSequence != null && message.serverSequence == unreadBoundary,
                historyTarget = anchor != null && message.serverSequence == anchor.sequence
            )
        }
        adapter?.submitMessages(rows) {
            if (anchor != historyAnchor) return@submitMessages
            val added = ru.childwatch.shared.chat.countNewIncomingMessages(renderedMessages, messages, localMemberId)
            renderedMessages = messages
            binding.messagesRecyclerView.post { recordVisibleMessages() }
            binding.emptyStateCard.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (anchor != null) {
                pendingAnchorScroll?.let { sequence ->
                    val position = messages.indexOfFirst { it.serverSequence == sequence }
                    val recycler = binding.messagesRecyclerView
                    val manager = recycler.layoutManager as? LinearLayoutManager
                    if (position < 0 || manager == null) {
                        pendingAnchorScroll = null
                    } else {
                        recycler.doOnNextLayout {
                            if (anchor == historyAnchor && pendingAnchorScroll == sequence) {
                                pendingAnchorScroll = null
                                recordVisibleMessages()
                            }
                        }
                        manager.scrollToPositionWithOffset(position, 0)
                    }
                }
            } else if (!initialScrollDone || wasNearBottom) {
                initialScrollDone = true
                scrollToBottom()
            } else if (added > 0) {
                pendingNewMessages += added
                binding.newMessagesButton.text = getString(
                    R.string.chat_v2_new_messages,
                    pendingNewMessages
                )
                binding.newMessagesButton.visibility = View.VISIBLE
            }
        }
    }

    private fun sendMessage() {
        if (composerScope != null && composerScope != media.currentScopeKey()) {
            finish()
            return
        }
        if (textSendInProgress || mediaPreparing) return
        if (mediaDraft != null) { sendMediaDraft(); return }
        val text = binding.messageInput.text?.toString().orEmpty()
        if (text.isBlank()) return
        val reply = replySource
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
                    senderMemberId = localMember?.memberId,
                    replyToMessageId = reply?.messageId
                )
                if (replySource == reply) showReply(null)
                if (binding.messageInput.text?.toString() == text) binding.messageInput.text?.clear()
                scrollToBottom()
                val sentRealtime = WebSocketManager.sendChatV2Message(
                    conversationId = conversationId,
                    clientMessageId = queued.clientMessageId,
                    text = queued.text,
                    clientSentAt = queued.clientSentAt,
                    replyToMessageId = queued.replyToMessageId
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
                    showChatStatus(R.string.chat_v2_ready)
                    updateReadReceiptStatus()
                }
                .onFailure { syncOnce() }
        }
    }

    private fun loadOlderMessages() {
        if (historyAnchor != null || historyJob?.isActive == true) return
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
                    displayedMessageLimit += page.messages.size
                    observeMessages(conversation?.localMemberId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ChatV2RepositoryException) {
                if (error.code == "GROUP_ACCESS_REVOKED") finishUnavailableGroup()
                else showChatStatus(R.string.chat_v2_offline)
            } catch (_: Exception) {
                showChatStatus(R.string.chat_v2_offline)
            } finally {
                loadingOlderMessages = false
            }
        }
    }

    private suspend fun syncOnce() {
        if (!::repository.isInitialized || conversation == null) return
        try {
            if (finishIfGroupAccessLost()) return
            repository.flushOutbox()
            val page = repository.syncMessagesPage(conversationId, limit = 200)
            if (displayedMessageLimit == INITIAL_MESSAGE_LIMIT) {
                nextBeforeSequence = page.nextBeforeSequence
            }
            updateReadReceiptStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ChatV2RepositoryException) {
            if (error.code == "GROUP_ACCESS_REVOKED") finishUnavailableGroup()
            else showChatStatus(R.string.chat_v2_offline)
        } catch (_: Exception) {
            showChatStatus(R.string.chat_v2_offline)
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
            showChatStatus(R.string.chat_v2_offline)
            return false
        }
    }

    private suspend fun updateReadReceiptStatus() {
        showChatStatus(if (repository.hasPendingReadReceipt(conversationId))
            R.string.chat_v2_read_receipt_pending else R.string.chat_v2_ready)
    }

    private fun isNearBottom(): Boolean {
        val manager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager ?: return true
        val count = adapter?.itemCount ?: 0
        return count == 0 || manager.findLastVisibleItemPosition() >= count - 3
    }

    private fun scrollToBottom() {
        if (historyAnchor != null) {
            historyJob?.cancel()
            historyAnchor = null
            pendingAnchorScroll = null
            readingUnreadWindow = false
            initialScrollDone = false
            binding.chatHistoryNavigation.visibility = View.GONE
            observeMessages(conversation?.localMemberId)
            return
        }
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
            serverMessageId = messageId.takeIf { serverSequence != null },
            replyToMessageId = replyToMessageId,
            status = when (deliveryState) {
                ChatDeliveryState.QUEUED -> ChatMessage.MessageStatus.QUEUED
                ChatDeliveryState.SENDING -> ChatMessage.MessageStatus.SENDING
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
