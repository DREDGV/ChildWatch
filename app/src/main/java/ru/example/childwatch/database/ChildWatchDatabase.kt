package ru.example.childwatch.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
 */
@Database(
    entities = [
        Child::class,
        Parent::class,
        ChatMessageEntity::class,
        AudioRecording::class,
        LocationPoint::class
    ],
    version = 1,
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
                    // Migration strategy will be added in future versions
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
