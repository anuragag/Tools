package com.slackvoice.assistant.data.model

import java.time.Instant

/**
 * Represents a Slack channel
 */
data class SlackChannel(
    val id: String,
    val name: String,
    val isPrivate: Boolean = false,
    val isMember: Boolean = true,
    val numMembers: Int = 0,
    val topic: String? = null,
    val purpose: String? = null
)

/**
 * Represents a Slack user
 */
data class SlackUser(
    val id: String,
    val name: String,
    val realName: String?,
    val displayName: String?,
    val avatarUrl: String? = null
) {
    fun displayableName(): String = displayName ?: realName ?: name
}

/**
 * Represents a single Slack message
 */
data class SlackMessage(
    val id: String,
    val channelId: String,
    val userId: String?,
    val userName: String?,
    val text: String,
    val timestamp: String,
    val threadTs: String? = null,
    val replyCount: Int = 0,
    val reactions: List<Reaction> = emptyList(),
    val files: List<FileAttachment> = emptyList(),
    val isEdited: Boolean = false
) {
    val parsedTimestamp: Instant
        get() = Instant.ofEpochSecond(timestamp.substringBefore(".").toLong())
}

/**
 * Message reaction
 */
data class Reaction(
    val name: String,
    val count: Int,
    val users: List<String> = emptyList()
)

/**
 * File attachment in a message
 */
data class FileAttachment(
    val id: String,
    val name: String,
    val mimeType: String?,
    val size: Long
)

/**
 * Summary of activity in a channel
 */
data class ChannelSummary(
    val channel: SlackChannel,
    val messageCount: Int,
    val participants: List<SlackUser>,
    val topMessages: List<SlackMessage>,
    val hasUnreadMentions: Boolean,
    val threadCount: Int,
    val summary: String,
    val lastActivityTime: Instant?
)

/**
 * Current user's authentication info
 */
data class SlackAuthInfo(
    val accessToken: String,
    val userId: String,
    val teamId: String,
    val teamName: String,
    val userName: String,
    val scope: String
)

/**
 * Conversation history response
 */
data class ConversationHistory(
    val messages: List<SlackMessage>,
    val hasMore: Boolean,
    val nextCursor: String?
)

/**
 * Unread counts for a channel
 */
data class ChannelUnreadInfo(
    val channelId: String,
    val lastRead: String,
    val unreadCount: Int,
    val mentionCount: Int
)
