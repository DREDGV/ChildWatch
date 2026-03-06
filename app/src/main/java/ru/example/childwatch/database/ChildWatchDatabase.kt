package ru.example.childwatch.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.example.childwatch.database.dao.*
import ru.example.childwatch.database.entity.*

/**
 * ChildWatch Room Database
 *
 * Main database class for the ChildWatch application.
 * Implements Singleton pattern to ensure only one instance exists.
 *
 * Version 1: Initial database schema
 * - Children table
 * - Parents table
 * - Chat messages table
 * - Audio recordings table
 * - Location points table
 *
 * Version 2: Added ParentLocation for "Where are parents?" feature
 * - Parent locations table with battery level, speed, bearing
 *
 * Version 3: Added Geofences for zone monitoring
 * - Geofences table with radius, notifications
 *
 * Version 4: Added contact metadata fields to children
 * - role, icon_id, allowed_features, alias
 *
 * Version 5: Normalize children defaults for contact fields
 * Version 6: Rebuild children table to align schema hash on upgraded installs
 */
@Database(
    entities = [
        Child::class,
        Parent::class,
        ChatMessageEntity::class,
        AudioRecording::class,
        LocationPoint::class,
        ParentLocation::class,
        Geofence::class
    ],
    version = 6,
    exportSchema = true
)
abstract class ChildWatchDatabase : RoomDatabase() {

    /**
     * Get ChildDao instance
     */
    abstract fun childDao(): ChildDao

    /**
     * Get ParentDao instance
     */
    abstract fun parentDao(): ParentDao

    /**
     * Get ChatMessageDao instance
     */
    abstract fun chatMessageDao(): ChatMessageDao

    /**
     * Get AudioRecordingDao instance
     */
    abstract fun audioRecordingDao(): AudioRecordingDao

    /**
     * Get LocationDao instance
     */
    abstract fun locationDao(): LocationDao

    /**
     * Get ParentLocationDao instance
     */
    abstract fun parentLocationDao(): ParentLocationDao

    /**
     * Get GeofenceDao instance
     */
    abstract fun geofenceDao(): GeofenceDao

    companion object {
        /**
         * Singleton instance
         */
        @Volatile
        private var INSTANCE: ChildWatchDatabase? = null

        /**
         * Database name
         */
        private const val DATABASE_NAME = "childwatch.db"

        /**
         * Migration from version 1 to 2
         * Adds parent_locations table for "Where are parents?" feature
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create parent_locations table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS parent_locations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        parent_id TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        accuracy REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        battery_level INTEGER,
                        speed REAL,
                        bearing REAL
                    )
                """.trimIndent())
                
                // Create indices for performance
                database.execSQL("CREATE INDEX IF NOT EXISTS index_parent_locations_parent_id ON parent_locations(parent_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_parent_locations_timestamp ON parent_locations(timestamp)")
                
                // Backfill changes in existing tables introduced in v2/v3 schema
                // 1) chat_messages: add created_at column (if missing), initialize from timestamp
                try {
                    // Добавляем nullable колонку
                    database.execSQL("ALTER TABLE chat_messages ADD COLUMN created_at INTEGER")
                } catch (e: Exception) {
                    // Колонка уже существует — пропускаем
                }
                // Заполняем NULL значения
                database.execSQL("UPDATE chat_messages SET created_at = timestamp WHERE created_at IS NULL")

                // Ensure indices exist (idempotent)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_message_id ON chat_messages(message_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_child_id ON chat_messages(child_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sender ON chat_messages(sender)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_is_read ON chat_messages(is_read)")

                // 2) audio_recordings: add created_at column (if missing), initialize from timestamp
                try {
                    database.execSQL("ALTER TABLE audio_recordings ADD COLUMN created_at INTEGER")
                } catch (e: Exception) {
                    // Колонка уже существует — пропускаем
                }
                database.execSQL("UPDATE audio_recordings SET created_at = timestamp WHERE created_at IS NULL")
            }
        }

        /**
         * Migration from version 2 to 3
         * Adds geofences table for zone monitoring
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create geofences table
                // ВАЖНО: не задаем DEFAULT для булевых значений, чтобы схема соответствовала Entity (Room ожидает defaultValue='undefined')
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS geofences (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        radius REAL NOT NULL,
                        device_id TEXT NOT NULL,
                        is_active INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        notification_on_enter INTEGER NOT NULL,
                        notification_on_exit INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Create indices for performance
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofences_device_id ON geofences(device_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofences_is_active ON geofences(is_active)")
                
                // Ensure chat_messages has created_at column (if upgrading from v1 directly to v3)
                try {
                    database.execSQL("ALTER TABLE chat_messages ADD COLUMN created_at INTEGER")
                } catch (e: Exception) {
                    // Column may already exist from MIGRATION_1_2 — ignore
                }
                database.execSQL("UPDATE chat_messages SET created_at = timestamp WHERE created_at IS NULL")
                
                // Ensure chat_messages indices exist
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_message_id ON chat_messages(message_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_child_id ON chat_messages(child_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sender ON chat_messages(sender)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_is_read ON chat_messages(is_read)")

                // Ensure audio_recordings has created_at column
                try {
                    database.execSQL("ALTER TABLE audio_recordings ADD COLUMN created_at INTEGER")
                } catch (e: Exception) {
                    // Column may already exist — ignore
                }
                database.execSQL("UPDATE audio_recordings SET created_at = timestamp WHERE created_at IS NULL")
            }
        }

        /**
         * Migration from version 3 to 4
         * Adds contact metadata fields to children table
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE children ADD COLUMN role TEXT NOT NULL DEFAULT 'child'")
                } catch (e: Exception) {
                    // Column may already exist
                }
                try {
                    database.execSQL("ALTER TABLE children ADD COLUMN icon_id INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                }
                try {
                    database.execSQL("ALTER TABLE children ADD COLUMN allowed_features INTEGER NOT NULL DEFAULT 15")
                } catch (e: Exception) {
                }
                try {
                    database.execSQL("ALTER TABLE children ADD COLUMN alias TEXT")
                } catch (e: Exception) {
                }
            }
        }

        /**
         * Migration from version 4 to 5
         * Rebuild children table to normalize defaults across installs.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS children_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        device_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'child',
                        icon_id INTEGER NOT NULL DEFAULT 0,
                        allowed_features INTEGER NOT NULL DEFAULT 15,
                        alias TEXT,
                        age INTEGER,
                        avatar_url TEXT,
                        phone_number TEXT,
                        last_seen_at INTEGER,
                        is_active INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO children_new (
                        id, device_id, name, role, icon_id, allowed_features, alias,
                        age, avatar_url, phone_number, last_seen_at, is_active, created_at, updated_at
                    )
                    SELECT
                        id, device_id, name, role, icon_id, allowed_features, alias,
                        age, avatar_url, phone_number, last_seen_at, is_active, created_at, updated_at
                    FROM children
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE children")
                database.execSQL("ALTER TABLE children_new RENAME TO children")

                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_children_device_id ON children(device_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_children_created_at ON children(created_at)")
            }
        }

        /**
         * Migration from version 5 to 6
         * Rebuild children table to align schema hash safely on upgraded installs.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS children_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        device_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'child',
                        icon_id INTEGER NOT NULL DEFAULT 0,
                        allowed_features INTEGER NOT NULL DEFAULT 15,
                        alias TEXT,
                        age INTEGER,
                        avatar_url TEXT,
                        phone_number TEXT,
                        last_seen_at INTEGER,
                        is_active INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO children_new (
                        id, device_id, name, role, icon_id, allowed_features, alias,
                        age, avatar_url, phone_number, last_seen_at, is_active, created_at, updated_at
                    )
                    SELECT
                        id,
                        device_id,
                        name,
                        COALESCE(role, 'child'),
                        COALESCE(icon_id, 0),
                        COALESCE(allowed_features, 15),
                        alias,
                        age,
                        avatar_url,
                        phone_number,
                        last_seen_at,
                        is_active,
                        created_at,
                        updated_at
                    FROM children
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE children")
                database.execSQL("ALTER TABLE children_new RENAME TO children")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_children_device_id ON children(device_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_children_created_at ON children(created_at)")
            }
        }

        /**
         * Get database instance (Singleton pattern)
         *
         * @param context Application context
         * @return Database instance
         */
        fun getInstance(context: Context): ChildWatchDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChildWatchDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6
                    )
                    // Only allow destructive migration on DOWNGRADE (not upgrade)
                    // This preserves data on upgrades while allowing clean reinstalls
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()

                INSTANCE = instance
                instance
            }
        }

        /**
         * Close database (for testing purposes)
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
