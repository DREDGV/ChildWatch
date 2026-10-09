package ru.example.childwatch.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.example.childwatch.database.entity.ChatAttachmentDraftV2Entity
import ru.example.childwatch.database.entity.ChatTranscriptionV2Entity
import java.util.UUID

/** Opens shipped schemas through additive migrations; never touches the real app database. */
@RunWith(AndroidJUnit4::class)
class ChatMediaMigrationTest {
    @Test fun installedTextHistorySurvivesAndDraftsAreDurable() = runBlocking { verifyUpgrade(12) }
    @Test fun installedMediaAndCorrectionsSurvive() = runBlocking { verifyUpgrade(13) }
    private suspend fun verifyUpgrade(sourceVersion: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "chat_media_upgrade_${UUID.randomUUID()}"
        val schema = JSONObject(instrumentation.context.assets.open(
            "ru.example.childwatch.database.ChildWatchDatabase/${sourceVersion}.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        var room: ChildWatchDatabase? = null
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(sourceVersion) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val tables = schema.getJSONArray("entities")
                            for (i in 0 until tables.length()) {
                                val table = tables.getJSONObject(i)
                                val tableName = table.getString("tableName")
                                db.execSQL(table.getString("createSql").replace("${'$'}{TABLE_NAME}", tableName))
                                val indices = table.optJSONArray("indices")
                                for (index in 0 until (indices?.length() ?: 0)) db.execSQL(indices!!.getJSONObject(index)
                                    .getString("createSql").replace("${'$'}{TABLE_NAME}", tableName))
                            }
                            val setup = schema.getJSONArray("setupQueries")
                            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = error("source fixture")
                    }).build())
            helper.writableDatabase.execSQL("INSERT INTO chat_conversations_v2 (conversation_id,family_id,type,created_at,updated_at,last_sequence,last_read_sequence,unread_count,muted,is_archived,sync_state) VALUES ('conversation','family','FAMILY',1,1,1,0,1,0,0,'SYNCED')")
            helper.writableDatabase.execSQL("INSERT INTO chat_messages_v2 (message_id,client_message_id,conversation_id,legacy_sender,text,message_type,sent_at,client_sent_at,created_at,status,delivery_state,is_read,sync_state) VALUES ('old','client-old','conversation','parent','saved text','TEXT',1,1,1,'sent','ACCEPTED',0,'SYNCED')")
            if (sourceVersion == 13) {
                helper.writableDatabase.execSQL("INSERT INTO chat_attachment_drafts_v2 (client_message_id,conversation_id,server_url,family_id,actor_member_id,device_id,local_path,source_uri,filename,mime_type,size_bytes,sha256,attachment_type,duration_ms,state,progress_bytes,caption,sender_name,sender_role,attempt_count,next_attempt_at,created_at,updated_at) VALUES ('old-voice','conversation','https://server','family','actor','device','/fixture-voice','content://voice','voice.m4a','audio/mp4',100,'source-hash','VOICE',1000,'DRAFT',0,'','name','CHILD',0,0,2,2)")
                helper.writableDatabase.execSQL("INSERT INTO chat_media_capabilities_v2 VALUES ('old-scope','{\"attachments\":true}',2)")
                helper.writableDatabase.execSQL("UPDATE chat_messages_v2 SET attachments_json = '[{\"attachmentId\":\"old-media\"}]' WHERE message_id = 'old'")
            }
            helper.close()
            room = Room.databaseBuilder(context, ChildWatchDatabase::class.java, name)
                .addMigrations(ChildWatchDatabase.MIGRATION_12_13, ChildWatchDatabase.MIGRATION_13_14).build()
            assertEquals("saved text",room.chatMessageV2Dao().getByMessageId("old")!!.text)
            if (sourceVersion == 13) {
                assertEquals("VOICE", room.chatAttachmentDraftV2Dao().get("old-voice")!!.attachmentType)
                assertEquals("/fixture-voice", room.chatAttachmentDraftV2Dao().get("old-voice")!!.localPath)
            }
            assertEquals(if (sourceVersion == 12) "[]" else "[{\"attachmentId\":\"old-media\"}]",room.chatMessageV2Dao().getByMessageId("old")!!.attachmentsJson)
            val draft = ChatAttachmentDraftV2Entity("stable", "conversation", "https://server", "family", "actor",
                "device", "/fixture-private-file", "content://fixture/document", "picture.png", "image/png", 100,
                "a".repeat(64), "IMAGE", createdAt=2, updatedAt=2)
            assertTrue(room.chatAttachmentDraftV2Dao().insert(draft) != -1L)
            val transcription = ChatTranscriptionV2Entity("scope", "stable", "conversation", "https://server", "family", "actor",
                "device", "a".repeat(64), jobId="job", state="SUCCEEDED", text="recognized", createdAt=2, updatedAt=3)
            assertTrue(room.chatTranscriptionV2Dao().insert(transcription) != -1L)
            room.chatTranscriptionV2Dao().edit("scope", "stable", "corrected")
            assertEquals(1, room.chatTranscriptionV2Dao().markTextEnqueued("scope", "stable", "stable:text"))
            // A late poll updates job columns only and cannot erase the edit.
            room.chatTranscriptionV2Dao().updateJob("scope", "stable", "job", "SUCCEEDED", "raw poll", null, 2, 4, 4)
            assertNull(room.chatTranscriptionV2Dao().get("other-scope", "stable"))
            room.close()
            room = Room.databaseBuilder(context, ChildWatchDatabase::class.java, name).addMigrations(ChildWatchDatabase.MIGRATION_12_13, ChildWatchDatabase.MIGRATION_13_14).build()
            assertEquals(draft,room.chatAttachmentDraftV2Dao().get("stable"))
            assertEquals("corrected",room.chatTranscriptionV2Dao().get("scope", "stable")!!.editorText())
            assertTrue(room.chatTranscriptionV2Dao().get("scope", "stable")!!.isEdited)
            assertEquals("stable:text", room.chatTranscriptionV2Dao().get("scope", "stable")!!.textMessageId)
            val other = transcription.copy(scopeKey="other", draftId="other-recording", text="private other result")
            assertTrue(room.chatTranscriptionV2Dao().insert(other) != -1L)
            room.chatTranscriptionV2Dao().deny("scope", "stable")
            val denied = room.chatTranscriptionV2Dao().get("scope", "stable")!!
            assertEquals("FAILED", denied.state)
            assertEquals("TRANSCRIPTION_ACCESS_DENIED", denied.errorCode)
            assertNull(denied.text)
            assertNull(denied.editedText)
            assertFalse(denied.isEdited)
            assertEquals(0, room.chatTranscriptionV2Dao().edit("scope", "stable", "late editor callback"))
            assertEquals(0, room.chatTranscriptionV2Dao().markTextEnqueued("scope", "stable", "stable:text"))
            assertEquals("private other result", room.chatTranscriptionV2Dao().get("other", "other-recording")!!.text)
            assertEquals(draft, room.chatAttachmentDraftV2Dao().get("stable"))
            assertEquals("saved text",room.chatMessageV2Dao().getByMessageId("old")!!.text)
        } finally { room?.close(); context.deleteDatabase(name) }
    }
}
