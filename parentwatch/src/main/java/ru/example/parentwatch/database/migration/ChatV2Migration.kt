package ru.example.parentwatch.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

/** Stable IDs shared by the SQL migration and future repository mapping. */
object ChatV2LegacyIdentity {
    const val FAMILY_CONVERSATION_PREFIX = "local-family-v2:"
    const val DEVICE_MEMBER_PREFIX = "device:"
    const val LEGACY_ROLE_MEMBER_PREFIX = "legacy-role:"

    fun conversationId(childDeviceId: String?, legacyChildId: Long): String {
        return FAMILY_CONVERSATION_PREFIX + deviceKey(childDeviceId, legacyChildId)
    }

    fun memberId(
        authorDeviceId: String?,
        legacySender: String,
        childDeviceId: String?,
        legacyChildId: Long
    ): String {
        val authorKey = authorDeviceId?.trim().orEmpty()
        if (authorKey.isNotEmpty()) return DEVICE_MEMBER_PREFIX + authorKey

        return if (legacySender.equals("child", ignoreCase = true)) {
            DEVICE_MEMBER_PREFIX + deviceKey(childDeviceId, legacyChildId)
        } else {
            LEGACY_ROLE_MEMBER_PREFIX + legacySender.trim().lowercase(Locale.ROOT)
        }
    }

    private fun deviceKey(childDeviceId: String?, legacyChildId: Long): String {
        return childDeviceId?.trim()?.takeIf { it.isNotEmpty() } ?: "child-$legacyChildId"
    }
}

/**
 * Additive Room migration. Legacy chat_messages is deliberately neither
 * changed nor removed; every legacy row is copied into the v2 projection.
 */
object ChatV2Migration {
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            createTables(database)
            createIndices(database)
            migrateLegacyConversations(database)
            migrateLegacyMembers(database)
            migrateLegacyMessages(database)
        }
    }

    private fun createTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_conversations_v2 (
                conversation_id TEXT NOT NULL,
                server_conversation_id TEXT,
                family_id TEXT,
                type TEXT NOT NULL,
                title TEXT,
                legacy_child_id INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_message_at INTEGER,
                last_message_preview TEXT,
                last_sequence INTEGER NOT NULL,
                last_read_sequence INTEGER NOT NULL,
                unread_count INTEGER NOT NULL,
                last_read_at INTEGER,
                muted_until INTEGER,
                muted INTEGER NOT NULL,
                is_archived INTEGER NOT NULL,
                sync_state TEXT NOT NULL,
                PRIMARY KEY(conversation_id)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_conversation_members_v2 (
                conversation_id TEXT NOT NULL,
                member_id TEXT NOT NULL,
                server_member_id TEXT,
                device_id TEXT,
                display_name TEXT,
                role TEXT NOT NULL,
                is_local_user INTEGER NOT NULL,
                joined_at INTEGER NOT NULL,
                last_active_at INTEGER,
                last_delivered_at INTEGER,
                last_read_at INTEGER,
                is_muted INTEGER NOT NULL,
                PRIMARY KEY(conversation_id, member_id),
                FOREIGN KEY(conversation_id) REFERENCES chat_conversations_v2(conversation_id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages_v2 (
                local_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                message_id TEXT NOT NULL,
                server_message_id TEXT,
                client_message_id TEXT,
                server_sequence INTEGER,
                conversation_id TEXT NOT NULL,
                legacy_row_id INTEGER,
                sender_member_id TEXT,
                sender_device_id TEXT,
                sender_display_name TEXT,
                sender_role TEXT,
                legacy_sender TEXT NOT NULL,
                text TEXT NOT NULL,
                message_type TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                client_sent_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                server_created_at INTEGER,
                status TEXT NOT NULL,
                delivery_state TEXT NOT NULL,
                failure_code TEXT,
                legacy_message_id TEXT,
                is_read INTEGER NOT NULL,
                delivered_at INTEGER,
                read_at INTEGER,
                edited_at INTEGER,
                reply_to_message_id TEXT,
                sync_state TEXT NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES chat_conversations_v2(conversation_id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_outbox_v2 (
                outbox_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                client_message_id TEXT NOT NULL,
                message_id TEXT,
                conversation_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                text TEXT NOT NULL,
                client_sent_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                attempt_count INTEGER NOT NULL,
                next_attempt_at INTEGER NOT NULL,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                lease_token TEXT,
                lease_expires_at INTEGER,
                priority INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES chat_conversations_v2(conversation_id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun createIndices(database: SupportSQLiteDatabase) {
        val statements = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_conversations_v2_server_conversation_id ON chat_conversations_v2(server_conversation_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_conversations_v2_family_id ON chat_conversations_v2(family_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_conversations_v2_type ON chat_conversations_v2(type)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_conversations_v2_legacy_child_id ON chat_conversations_v2(legacy_child_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_conversations_v2_last_message_at ON chat_conversations_v2(last_message_at)",
            "CREATE INDEX IF NOT EXISTS index_chat_conversation_members_v2_member_id ON chat_conversation_members_v2(member_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_conversation_members_v2_device_id ON chat_conversation_members_v2(device_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_v2_message_id ON chat_messages_v2(message_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_v2_server_message_id ON chat_messages_v2(server_message_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_v2_client_message_id ON chat_messages_v2(client_message_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_messages_v2_conversation_id ON chat_messages_v2(conversation_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_messages_v2_conversation_id_sent_at ON chat_messages_v2(conversation_id, sent_at)",
            "CREATE INDEX IF NOT EXISTS index_chat_messages_v2_conversation_id_server_sequence ON chat_messages_v2(conversation_id, server_sequence)",
            "CREATE INDEX IF NOT EXISTS index_chat_messages_v2_sender_member_id ON chat_messages_v2(sender_member_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_messages_v2_status ON chat_messages_v2(status)",
            "CREATE INDEX IF NOT EXISTS index_chat_messages_v2_is_read ON chat_messages_v2(is_read)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_outbox_v2_client_message_id ON chat_outbox_v2(client_message_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_outbox_v2_message_id ON chat_outbox_v2(message_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_outbox_v2_conversation_id ON chat_outbox_v2(conversation_id)",
            "CREATE INDEX IF NOT EXISTS index_chat_outbox_v2_state_next_attempt_at ON chat_outbox_v2(state, next_attempt_at)"
        )
        statements.forEach(database::execSQL)
    }

    private fun migrateLegacyConversations(database: SupportSQLiteDatabase) {
        val deviceKey = legacyDeviceKeySql("c", "m")
        val conversationId = "'${ChatV2LegacyIdentity.FAMILY_CONVERSATION_PREFIX}' || $deviceKey"
        database.execSQL(
            """
            INSERT OR IGNORE INTO chat_conversations_v2 (
                conversation_id, server_conversation_id, family_id, type, title, legacy_child_id,
                created_at, updated_at, last_message_at, last_message_preview, unread_count,
                last_sequence, last_read_sequence, last_read_at, muted_until, muted,
                is_archived, sync_state
            )
            SELECT
                $conversationId,
                NULL,
                $conversationId,
                'FAMILY',
                COALESCE(NULLIF(c.alias, ''), NULLIF(c.name, ''), 'Семейный чат'),
                m.child_id,
                MIN(COALESCE(m.created_at, m.timestamp)),
                MAX(COALESCE(m.created_at, m.timestamp)),
                MAX(m.timestamp),
                (
                    SELECT latest.text
                    FROM chat_messages AS latest
                    WHERE latest.child_id = m.child_id
                    ORDER BY latest.timestamp DESC, latest.id DESC
                    LIMIT 1
                ),
                SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END),
                0,
                0,
                MAX(CASE WHEN m.is_read = 1 THEN m.timestamp ELSE NULL END),
                NULL,
                0,
                0,
                'MIGRATED'
            FROM chat_messages AS m
            LEFT JOIN children AS c ON c.id = m.child_id
            GROUP BY m.child_id, c.device_id, c.alias, c.name
            """.trimIndent()
        )
    }

    private fun migrateLegacyMembers(database: SupportSQLiteDatabase) {
        val deviceKey = legacyDeviceKeySql("c", "m")
        val conversationId = "'${ChatV2LegacyIdentity.FAMILY_CONVERSATION_PREFIX}' || $deviceKey"
        val childMemberId = "'${ChatV2LegacyIdentity.DEVICE_MEMBER_PREFIX}' || $deviceKey"

        // Ensure every migrated family thread has a child member, even if its
        // local history currently contains only guardian-authored messages.
        database.execSQL(
            """
            INSERT OR IGNORE INTO chat_conversation_members_v2 (
                conversation_id, member_id, server_member_id, device_id, display_name, role,
                is_local_user, joined_at, last_active_at, last_delivered_at, last_read_at, is_muted
            )
            SELECT
                $conversationId,
                $childMemberId,
                NULL,
                NULLIF(TRIM(c.device_id), ''),
                COALESCE(NULLIF(c.alias, ''), NULLIF(c.name, ''), 'Ребёнок'),
                'CHILD',
                0,
                MIN(COALESCE(m.created_at, m.timestamp)),
                MAX(CASE WHEN LOWER(m.sender) = 'child' THEN m.timestamp ELSE NULL END),
                MAX(CASE WHEN LOWER(m.sender) = 'child' AND LOWER(m.status) IN ('delivered', 'read') THEN m.timestamp ELSE NULL END),
                MAX(CASE WHEN LOWER(m.sender) = 'child' AND (m.is_read = 1 OR LOWER(m.status) = 'read') THEN m.timestamp ELSE NULL END),
                0
            FROM chat_messages AS m
            LEFT JOIN children AS c ON c.id = m.child_id
            GROUP BY m.child_id, c.device_id, c.alias, c.name
            """.trimIndent()
        )

        val authorMemberId = """
            CASE
                WHEN NULLIF(TRIM(m.author_device_id), '') IS NOT NULL
                    THEN '${ChatV2LegacyIdentity.DEVICE_MEMBER_PREFIX}' || TRIM(m.author_device_id)
                WHEN LOWER(m.sender) = 'child'
                    THEN $childMemberId
                ELSE '${ChatV2LegacyIdentity.LEGACY_ROLE_MEMBER_PREFIX}' || LOWER(TRIM(m.sender))
            END
        """.trimIndent()
        database.execSQL(
            """
            INSERT OR IGNORE INTO chat_conversation_members_v2 (
                conversation_id, member_id, server_member_id, device_id, display_name, role,
                is_local_user, joined_at, last_active_at, last_delivered_at, last_read_at, is_muted
            )
            SELECT
                author_rows.conversation_id,
                author_rows.member_id,
                NULL,
                MAX(author_rows.device_id),
                COALESCE(MAX(NULLIF(author_rows.display_name, '')), MAX(author_rows.fallback_name)),
                MAX(author_rows.role),
                0,
                MIN(author_rows.event_at),
                MAX(author_rows.event_at),
                MAX(author_rows.delivered_at),
                MAX(author_rows.read_at),
                0
            FROM (
                SELECT
                    $conversationId AS conversation_id,
                    $authorMemberId AS member_id,
                    NULLIF(TRIM(m.author_device_id), '') AS device_id,
                    m.author_display_name AS display_name,
                    CASE
                        WHEN LOWER(m.sender) = 'child'
                            THEN COALESCE(NULLIF(c.alias, ''), NULLIF(c.name, ''), m.sender)
                        ELSE m.sender
                    END AS fallback_name,
                    CASE WHEN LOWER(m.sender) = 'child' THEN 'CHILD' ELSE 'GUARDIAN' END AS role,
                    COALESCE(m.created_at, m.timestamp) AS event_at,
                    CASE WHEN LOWER(m.status) IN ('delivered', 'read') THEN m.timestamp ELSE NULL END AS delivered_at,
                    CASE WHEN m.is_read = 1 OR LOWER(m.status) = 'read' THEN m.timestamp ELSE NULL END AS read_at
                FROM chat_messages AS m
                LEFT JOIN children AS c ON c.id = m.child_id
            ) AS author_rows
            GROUP BY author_rows.conversation_id, author_rows.member_id
            """.trimIndent()
        )
    }

    private fun migrateLegacyMessages(database: SupportSQLiteDatabase) {
        val deviceKey = legacyDeviceKeySql("c", "m")
        val conversationId = "'${ChatV2LegacyIdentity.FAMILY_CONVERSATION_PREFIX}' || $deviceKey"
        val senderMemberId = """
            CASE
                WHEN NULLIF(TRIM(m.author_device_id), '') IS NOT NULL
                    THEN '${ChatV2LegacyIdentity.DEVICE_MEMBER_PREFIX}' || TRIM(m.author_device_id)
                WHEN LOWER(m.sender) = 'child'
                    THEN '${ChatV2LegacyIdentity.DEVICE_MEMBER_PREFIX}' || $deviceKey
                ELSE '${ChatV2LegacyIdentity.LEGACY_ROLE_MEMBER_PREFIX}' || LOWER(TRIM(m.sender))
            END
        """.trimIndent()
        database.execSQL(
            """
            INSERT OR IGNORE INTO chat_messages_v2 (
                message_id, server_message_id, client_message_id, server_sequence,
                conversation_id, legacy_row_id, sender_member_id, sender_device_id,
                sender_display_name, sender_role, legacy_sender, text, message_type,
                sent_at, client_sent_at, created_at, server_created_at, status,
                delivery_state, failure_code, legacy_message_id, is_read, delivered_at,
                read_at, edited_at, reply_to_message_id, sync_state
            )
            SELECT
                m.message_id,
                NULL,
                NULL,
                NULL,
                $conversationId,
                m.id,
                $senderMemberId,
                m.author_device_id,
                m.author_display_name,
                CASE WHEN LOWER(m.sender) = 'child' THEN 'CHILD' ELSE 'GUARDIAN' END,
                m.sender,
                m.text,
                'TEXT',
                m.timestamp,
                m.timestamp,
                COALESCE(m.created_at, m.timestamp),
                NULL,
                m.status,
                CASE LOWER(m.status)
                    WHEN 'sending' THEN 'SENDING'
                    WHEN 'sent' THEN 'ACCEPTED'
                    WHEN 'delivered' THEN 'DELIVERED'
                    WHEN 'read' THEN 'READ'
                    WHEN 'failed' THEN 'FAILED'
                    ELSE 'ACCEPTED'
                END,
                NULL,
                m.message_id,
                m.is_read,
                NULL,
                NULL,
                NULL,
                NULL,
                'MIGRATED'
            FROM chat_messages AS m
            LEFT JOIN children AS c ON c.id = m.child_id
            """.trimIndent()
        )
    }

    private fun legacyDeviceKeySql(childAlias: String, messageAlias: String): String {
        return "COALESCE(NULLIF(TRIM($childAlias.device_id), ''), " +
            "'child-' || CAST($messageAlias.child_id AS TEXT))"
    }
}
