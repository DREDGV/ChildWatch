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
    version = 3,
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
                // 1) chat_messages: add created_at (NOT NULL), set from timestamp for existing rows
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE chat_messages SET created_at = timestamp WHERE created_at = 0")

                // Ensure indices exist (idempotent)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_message_id ON chat_messages(message_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_child_id ON chat_messages(child_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sender ON chat_messages(sender)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_is_read ON chat_messages(is_read)")

                // 2) audio_recordings: add created_at (NOT NULL) if missing, set from timestamp
                database.execSQL("ALTER TABLE audio_recordings ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE audio_recordings SET created_at = timestamp WHERE created_at = 0")
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
                    database.execSQL("ALTER TABLE chat_messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("UPDATE chat_messages SET created_at = timestamp WHERE created_at = 0")
                } catch (e: Exception) {
                    // Column may already exist from MIGRATION_1_2 — ignore
                }
                
                // Ensure chat_messages indices exist
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_message_id ON chat_messages(message_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_child_id ON chat_messages(child_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sender ON chat_messages(sender)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_is_read ON chat_messages(is_read)")

                // Ensure audio_recordings has created_at column
                try {
                    database.execSQL("ALTER TABLE audio_recordings ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("UPDATE audio_recordings SET created_at = timestamp WHERE created_at = 0")
                } catch (e: Exception) {
                    // Column may already exist — ignore
                }
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // Fallback only in debug builds or for future migrations
                    .fallbackToDestructiveMigration()
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
