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
import java.util.UUID

/** Builds the shipped v12 schema, then opens v13 with its registered additive migration. */
@RunWith(AndroidJUnit4::class)
class ChatMediaMigrationTest {
    @Test fun installedTextHistorySurvivesAndDraftsAreDurable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "chat_media_upgrade_${UUID.randomUUID()}"
        val schema = JSONObject(instrumentation.context.assets.open(
            "ru.example.childwatch.database.ChildWatchDatabase/12.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        var room: ChildWatchDatabase? = null
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(12) {
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
                        override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = error("v12 fixture")
                    }).build())
            helper.writableDatabase.execSQL("INSERT INTO chat_conversations_v2 (conversation_id,family_id,type,created_at,updated_at,last_sequence,last_read_sequence,unread_count,muted,is_archived,sync_state) VALUES ('conversation','family','FAMILY',1,1,1,0,1,0,0,'SYNCED')")
            helper.writableDatabase.execSQL("INSERT INTO chat_messages_v2 (message_id,client_message_id,conversation_id,legacy_sender,text,message_type,sent_at,client_sent_at,created_at,status,delivery_state,is_read,sync_state) VALUES ('old','client-old','conversation','parent','saved text','TEXT',1,1,1,'sent','ACCEPTED',0,'SYNCED')")
            helper.close()
            room = Room.databaseBuilder(context, ChildWatchDatabase::class.java, name)
                .addMigrations(ChildWatchDatabase.MIGRATION_12_13).build()
            assertEquals("saved text",room.chatMessageV2Dao().getByMessageId("old")!!.text)
            assertEquals("[]",room.chatMessageV2Dao().getByMessageId("old")!!.attachmentsJson)
            val draft = ChatAttachmentDraftV2Entity("stable", "conversation", "https://server", "family", "actor",
                "device", "/fixture-private-file", "content://fixture/document", "picture.png", "image/png", 100,
                "a".repeat(64), "IMAGE", createdAt=2, updatedAt=2)
            assertTrue(room.chatAttachmentDraftV2Dao().insert(draft) != -1L)
            room.close()
            room = Room.databaseBuilder(context, ChildWatchDatabase::class.java, name).addMigrations(ChildWatchDatabase.MIGRATION_12_13).build()
            assertEquals(draft,room.chatAttachmentDraftV2Dao().get("stable"))
            assertEquals("saved text",room.chatMessageV2Dao().getByMessageId("old")!!.text)
        } finally { room?.close(); context.deleteDatabase(name) }
    }
}
