package com.slackvoice.assistant.data.repository

import com.slackvoice.assistant.data.local.DraftReply
import com.slackvoice.assistant.data.local.DraftReplyDao
import com.slackvoice.assistant.data.local.DraftReplyEntity
import com.slackvoice.assistant.data.local.toDomain
import com.slackvoice.assistant.data.model.ChannelSummary
import com.slackvoice.assistant.data.model.SlackMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftReplyRepository @Inject constructor(
    private val draftReplyDao: DraftReplyDao
) {

    /**
     * Get all draft replies as a Flow
     */
    fun getAllDrafts(): Flow<List<DraftReply>> {
        return draftReplyDao.getAllDrafts().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * Get all drafts synchronously
     */
    suspend fun getAllDraftsList(): List<DraftReply> {
        return draftReplyDao.getAllDraftsList().map { it.toDomain() }
    }

    /**
     * Get draft count as Flow
     */
    fun getDraftCount(): Flow<Int> {
        return draftReplyDao.getDraftCount()
    }

    /**
     * Get draft count synchronously
     */
    suspend fun getDraftCountSync(): Int {
        return draftReplyDao.getDraftCountSync()
    }

    /**
     * Get a specific draft by ID
     */
    suspend fun getDraftById(id: Long): DraftReply? {
        return draftReplyDao.getDraftById(id)?.toDomain()
    }

    /**
     * Get drafts for a specific channel
     */
    suspend fun getDraftsForChannel(channelId: String): List<DraftReply> {
        return draftReplyDao.getDraftsForChannel(channelId).map { it.toDomain() }
    }

    /**
     * Create a new draft reply
     */
    suspend fun createDraft(
        channelId: String,
        channelName: String,
        draftText: String,
        threadTs: String? = null,
        originalMessage: SlackMessage? = null,
        originalMessageAuthor: String? = null
    ): Long {
        val entity = DraftReplyEntity(
            channelId = channelId,
            channelName = channelName,
            threadTs = threadTs,
            originalMessageId = originalMessage?.id,
            originalMessageText = originalMessage?.text,
            originalMessageAuthor = originalMessageAuthor,
            draftText = draftText,
            createdAt = Instant.now().toEpochMilli(),
            updatedAt = Instant.now().toEpochMilli()
        )
        return draftReplyDao.insertDraft(entity)
    }

    /**
     * Create draft from channel summary context
     */
    suspend fun createDraftForChannel(
        summary: ChannelSummary,
        draftText: String,
        replyToMessage: SlackMessage? = null,
        authorName: String? = null
    ): Long {
        return createDraft(
            channelId = summary.channel.id,
            channelName = summary.channel.name,
            draftText = draftText,
            threadTs = replyToMessage?.threadTs ?: replyToMessage?.id,
            originalMessage = replyToMessage,
            originalMessageAuthor = authorName
        )
    }

    /**
     * Update an existing draft
     */
    suspend fun updateDraft(draft: DraftReply) {
        val entity = draft.copy(updatedAt = Instant.now()).toEntity()
        draftReplyDao.updateDraft(entity)
    }

    /**
     * Update draft text
     */
    suspend fun updateDraftText(id: Long, newText: String) {
        val existing = draftReplyDao.getDraftById(id) ?: return
        val updated = existing.copy(
            draftText = newText,
            updatedAt = Instant.now().toEpochMilli()
        )
        draftReplyDao.updateDraft(updated)
    }

    /**
     * Append text to an existing draft
     */
    suspend fun appendToDraft(id: Long, additionalText: String) {
        val existing = draftReplyDao.getDraftById(id) ?: return
        val newText = if (existing.draftText.isEmpty()) {
            additionalText
        } else {
            "${existing.draftText} $additionalText"
        }
        val updated = existing.copy(
            draftText = newText,
            updatedAt = Instant.now().toEpochMilli()
        )
        draftReplyDao.updateDraft(updated)
    }

    /**
     * Delete a draft
     */
    suspend fun deleteDraft(id: Long) {
        draftReplyDao.deleteDraftById(id)
    }

    /**
     * Mark draft as sent
     */
    suspend fun markAsSent(id: Long) {
        draftReplyDao.markAsSent(id)
    }

    /**
     * Clear all sent drafts
     */
    suspend fun clearSentDrafts() {
        draftReplyDao.clearSentDrafts()
    }

    /**
     * Delete all drafts
     */
    suspend fun deleteAllDrafts() {
        draftReplyDao.deleteAllDrafts()
    }
}
