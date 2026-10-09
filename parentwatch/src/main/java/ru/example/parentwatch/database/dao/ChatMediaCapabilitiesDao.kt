package ru.example.parentwatch.database.dao
import androidx.room.*
import ru.example.parentwatch.database.entity.ChatMediaCapabilitiesEntity
@Dao interface ChatMediaCapabilitiesDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row: ChatMediaCapabilitiesEntity)
    @Query("SELECT * FROM chat_media_capabilities_v2 WHERE scope_key = :key") suspend fun get(key: String): ChatMediaCapabilitiesEntity?
    @Query("DELETE FROM chat_media_capabilities_v2 WHERE scope_key = :key") suspend fun clear(key: String)
}
