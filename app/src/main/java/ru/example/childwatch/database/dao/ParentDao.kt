package ru.example.childwatch.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.Parent

/**
 * DAO for Parent entity
 *
 * Provides database access methods for managing parent records.
 */
@Dao
interface ParentDao {

    /**
     * Insert a new parent
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(parent: Parent): Long

    /**
     * Insert multiple parents
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(parents: List<Parent>): List<Long>

    /**
     * Update an existing parent
     */
    @Update
    suspend fun update(parent: Parent)

    /**
     * Delete a parent
     */
    @Delete
    suspend fun delete(parent: Parent)

    /**
     * Get parent by ID
     */
    @Query("SELECT * FROM parents WHERE id = :id")
    suspend fun getById(id: Long): Parent?

    /**
     * Get parent by account ID
     */
    @Query("SELECT * FROM parents WHERE account_id = :accountId")
    suspend fun getByAccountId(accountId: String): Parent?

    /**
     * Get parent by email
     */
    @Query("SELECT * FROM parents WHERE email = :email")
    suspend fun getByEmail(email: String): Parent?

    /**
     * Get parent by account ID as Flow (reactive)
     */
    @Query("SELECT * FROM parents WHERE account_id = :accountId")
    fun getByAccountIdFlow(accountId: String): Flow<Parent?>

    /**
     * Get all parents
     */
    @Query("SELECT * FROM parents ORDER BY created_at DESC")
    suspend fun getAll(): List<Parent>

    /**
     * Get all parents as Flow (reactive)
     */
    @Query("SELECT * FROM parents ORDER BY created_at DESC")
    fun getAllFlow(): Flow<List<Parent>>

    /**
     * Get verified parents
     */
    @Query("SELECT * FROM parents WHERE is_verified = 1 ORDER BY created_at DESC")
    suspend fun getVerifiedParents(): List<Parent>

    /**
     * Update verification status
     */
    @Query("UPDATE parents SET is_verified = :isVerified, updated_at = :timestamp WHERE id = :parentId")
    suspend fun updateVerificationStatus(parentId: Long, isVerified: Boolean, timestamp: Long = System.currentTimeMillis())

    /**
     * Update password hash
     */
    @Query("UPDATE parents SET password_hash = :passwordHash, updated_at = :timestamp WHERE id = :parentId")
    suspend fun updatePasswordHash(parentId: Long, passwordHash: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Delete all parents
     */
    @Query("DELETE FROM parents")
    suspend fun deleteAll()

    /**
     * Get count of parents
     */
    @Query("SELECT COUNT(*) FROM parents")
    suspend fun getCount(): Int

    /**
     * Check if email exists
     */
    @Query("SELECT COUNT(*) > 0 FROM parents WHERE email = :email")
    suspend fun emailExists(email: String): Boolean
}
