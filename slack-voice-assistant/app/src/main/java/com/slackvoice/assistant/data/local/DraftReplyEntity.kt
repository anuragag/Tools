package com.slackvoice.assistant.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Entity representing a draft reply stored locally
 */
@Entity(tableName = "draft_replies")
data class DraftReplyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Channel info
    val channelId: String,
    val channelName: String,

    // Thread info (optional, for thread replies)
    val threadTs: String? = null,

    // Original message being replied to (optional)
    val originalMessageId: String? = null,
    val originalMessageText: String? = null,
    val originalMessageAuthor: String? = null,

    // Draft content
    val draftText: String,

    // Timestamps
    val createdAt: Long = Instant.now().toEpochMilli(),
    val updatedAt: Long = Instant.now().toEpochMilli(),

    // Status
    val isSent: Boolean = false
)

/**
 * Domain model for draft replies
 */
data class DraftReply(
    val id: Long = 0,
    val channelId: String,
    val channelName: String,
    val threadTs: String? = null,
    val originalMessageId: String? = null,
    val originalMessageText: String? = null,
    val originalMessageAuthor: String? = null,
    val draftText: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isSent: Boolean = false
) {
    fun toEntity() = DraftReplyEntity(
        id = id,
        channelId = channelId,
        channelName = channelName,
        threadTs = threadTs,
        originalMessageId = originalMessageId,
        originalMessageText = originalMessageText,
        originalMessageAuthor = originalMessageAuthor,
        draftText = draftText,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        isSent = isSent
    )
}

fun DraftReplyEntity.toDomain() = DraftReply(
    id = id,
    channelId = channelId,
    channelName = channelName,
    threadTs = threadTs,
    originalMessageId = originalMessageId,
    originalMessageText = originalMessageText,
    originalMessageAuthor = originalMessageAuthor,
    draftText = draftText,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    isSent = isSent
)
