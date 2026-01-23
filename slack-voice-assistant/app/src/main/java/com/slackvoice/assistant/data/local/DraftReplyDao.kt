package com.slackvoice.assistant.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftReplyDao {

    @Query("SELECT * FROM draft_replies WHERE isSent = 0 ORDER BY updatedAt DESC")
    fun getAllDrafts(): Flow<List<DraftReplyEntity>>

    @Query("SELECT * FROM draft_replies WHERE isSent = 0 ORDER BY updatedAt DESC")
    suspend fun getAllDraftsList(): List<DraftReplyEntity>

    @Query("SELECT * FROM draft_replies WHERE id = :id")
    suspend fun getDraftById(id: Long): DraftReplyEntity?

    @Query("SELECT * FROM draft_replies WHERE channelId = :channelId AND isSent = 0 ORDER BY updatedAt DESC")
    suspend fun getDraftsForChannel(channelId: String): List<DraftReplyEntity>

    @Query("SELECT COUNT(*) FROM draft_replies WHERE isSent = 0")
    fun getDraftCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM draft_replies WHERE isSent = 0")
    suspend fun getDraftCountSync(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: DraftReplyEntity): Long

    @Update
    suspend fun updateDraft(draft: DraftReplyEntity)

    @Delete
    suspend fun deleteDraft(draft: DraftReplyEntity)

    @Query("DELETE FROM draft_replies WHERE id = :id")
    suspend fun deleteDraftById(id: Long)

    @Query("UPDATE draft_replies SET isSent = 1 WHERE id = :id")
    suspend fun markAsSent(id: Long)

    @Query("DELETE FROM draft_replies WHERE isSent = 1")
    suspend fun clearSentDrafts()

    @Query("DELETE FROM draft_replies")
    suspend fun deleteAllDrafts()
}
