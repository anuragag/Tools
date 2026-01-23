package com.slackvoice.assistant.data.repository

import com.slackvoice.assistant.data.auth.SlackAuthManager
import com.slackvoice.assistant.data.model.*
import com.slackvoice.assistant.data.remote.SlackApi
import com.slackvoice.assistant.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlackRepository @Inject constructor(
    private val slackApi: SlackApi,
    private val authManager: SlackAuthManager
) {
    private val userCache = mutableMapOf<String, SlackUser>()

    private fun getAuthHeader(): String {
        return authManager.getAuthHeader()
            ?: throw IllegalStateException("Not authenticated")
    }

    /**
     * Get all channels the user is a member of
     */
    suspend fun getChannels(): Result<List<SlackChannel>> = withContext(Dispatchers.IO) {
        try {
            val channels = mutableListOf<SlackChannel>()
            var cursor: String? = null

            do {
                val response = slackApi.conversationsList(
                    auth = getAuthHeader(),
                    cursor = cursor
                )

                if (!response.isOk) {
                    return@withContext Result.failure(Exception(response.error ?: "Failed to fetch channels"))
                }

                response.channels?.forEach { dto ->
                    channels.add(dto.toModel())
                }

                cursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotEmpty() }
            } while (cursor != null)

            Result.success(channels.filter { it.isMember })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get messages from a channel since a specific time
     */
    suspend fun getChannelMessages(
        channelId: String,
        since: Instant? = null,
        limit: Int = 100
    ): Result<ConversationHistory> = withContext(Dispatchers.IO) {
        try {
            val oldest = since?.epochSecond?.toString()

            val response = slackApi.conversationsHistory(
                auth = getAuthHeader(),
                channel = channelId,
                limit = limit,
                oldest = oldest
            )

            if (!response.isOk) {
                return@withContext Result.failure(Exception(response.error ?: "Failed to fetch messages"))
            }

            val messages = response.messages?.mapNotNull { dto ->
                dto.toModel(channelId)
            } ?: emptyList()

            Result.success(
                ConversationHistory(
                    messages = messages,
                    hasMore = response.hasMore ?: false,
                    nextCursor = response.responseMetadata?.nextCursor
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get user info by ID
     */
    suspend fun getUser(userId: String): Result<SlackUser> = withContext(Dispatchers.IO) {
        // Check cache first
        userCache[userId]?.let { return@withContext Result.success(it) }

        try {
            val response = slackApi.usersInfo(
                auth = getAuthHeader(),
                user = userId
            )

            if (!response.isOk || response.user == null) {
                return@withContext Result.failure(Exception(response.error ?: "User not found"))
            }

            val user = response.user.toModel()
            userCache[userId] = user
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get multiple users and cache them
     */
    suspend fun preloadUsers(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var cursor: String? = null

            do {
                val response = slackApi.usersList(
                    auth = getAuthHeader(),
                    cursor = cursor
                )

                if (!response.isOk) {
                    return@withContext Result.failure(Exception(response.error ?: "Failed to fetch users"))
                }

                response.members?.forEach { dto ->
                    val user = dto.toModel()
                    userCache[user.id] = user
                }

                cursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotEmpty() }
            } while (cursor != null)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get channel summaries for all channels with recent activity
     */
    suspend fun getChannelSummaries(
        lookbackHours: Int = 24
    ): Result<List<ChannelSummary>> = withContext(Dispatchers.IO) {
        try {
            // Preload users first
            preloadUsers()

            val channels = getChannels().getOrThrow()
            val since = Instant.now().minus(lookbackHours.toLong(), ChronoUnit.HOURS)

            val summaries = channels.map { channel ->
                async {
                    try {
                        val history = getChannelMessages(channel.id, since).getOrNull()
                        if (history != null && history.messages.isNotEmpty()) {
                            createChannelSummary(channel, history.messages)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            // Sort by message count (most active first)
            Result.success(summaries.sortedByDescending { it.messageCount })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createChannelSummary(
        channel: SlackChannel,
        messages: List<SlackMessage>
    ): ChannelSummary {
        val currentUserId = authManager.getAuthInfo()?.userId

        // Get unique participants
        val participantIds = messages.mapNotNull { it.userId }.distinct()
        val participants = participantIds.mapNotNull { userId ->
            userCache[userId] ?: getUser(userId).getOrNull()
        }

        // Check for mentions
        val hasUnreadMentions = currentUserId?.let { userId ->
            messages.any { msg ->
                msg.text.contains("<@$userId>") ||
                        msg.text.contains("<!channel>") ||
                        msg.text.contains("<!here>")
            }
        } ?: false

        // Count threads
        val threadCount = messages.count { it.replyCount > 0 }

        // Get top messages (most reactions or replies)
        val topMessages = messages
            .sortedByDescending { it.replyCount + it.reactions.sumOf { r -> r.count } }
            .take(5)

        // Generate summary text
        val summary = generateSummaryText(channel, messages, participants, hasUnreadMentions)

        val lastActivity = messages.maxOfOrNull { it.parsedTimestamp }

        return ChannelSummary(
            channel = channel,
            messageCount = messages.size,
            participants = participants,
            topMessages = topMessages,
            hasUnreadMentions = hasUnreadMentions,
            threadCount = threadCount,
            summary = summary,
            lastActivityTime = lastActivity
        )
    }

    private fun generateSummaryText(
        channel: SlackChannel,
        messages: List<SlackMessage>,
        participants: List<SlackUser>,
        hasUnreadMentions: Boolean
    ): String {
        val sb = StringBuilder()

        // Channel intro
        sb.append("In ${channel.name}, ")

        // Participant summary
        when {
            participants.size == 1 -> {
                sb.append("${participants.first().displayableName()} posted ${messages.size} messages. ")
            }
            participants.size == 2 -> {
                sb.append("${participants[0].displayableName()} and ${participants[1].displayableName()} ")
                sb.append("exchanged ${messages.size} messages. ")
            }
            participants.size <= 5 -> {
                sb.append("${participants.size} people discussed with ${messages.size} messages. ")
            }
            else -> {
                sb.append("${participants.size} people were active with ${messages.size} messages. ")
            }
        }

        // Mention alert
        if (hasUnreadMentions) {
            sb.append("You were mentioned! ")
        }

        // Thread info
        val threadCount = messages.count { it.replyCount > 0 }
        if (threadCount > 0) {
            sb.append("$threadCount conversation threads. ")
        }

        // Sample recent topics (extract from messages)
        val recentKeywords = extractKeyTopics(messages)
        if (recentKeywords.isNotEmpty()) {
            sb.append("Topics include: ${recentKeywords.joinToString(", ")}. ")
        }

        return sb.toString()
    }

    private fun extractKeyTopics(messages: List<SlackMessage>): List<String> {
        // Simple keyword extraction - find frequently mentioned words
        val wordCounts = mutableMapOf<String, Int>()
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "must", "shall",
            "can", "need", "dare", "ought", "used", "to", "of", "in",
            "for", "on", "with", "at", "by", "from", "as", "into",
            "through", "during", "before", "after", "above", "below",
            "between", "under", "again", "further", "then", "once",
            "here", "there", "when", "where", "why", "how", "all",
            "each", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than",
            "too", "very", "just", "and", "but", "if", "or", "because",
            "until", "while", "this", "that", "these", "those", "i",
            "you", "he", "she", "it", "we", "they", "what", "which",
            "who", "whom", "its", "his", "her", "their", "our", "your"
        )

        messages.forEach { msg ->
            // Remove Slack formatting and links
            val cleanText = msg.text
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("[^a-zA-Z\\s]"), " ")
                .lowercase()

            cleanText.split(Regex("\\s+")).forEach { word ->
                if (word.length > 3 && word !in stopWords) {
                    wordCounts[word] = (wordCounts[word] ?: 0) + 1
                }
            }
        }

        return wordCounts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }

    fun getCachedUser(userId: String): SlackUser? = userCache[userId]

    // Extension functions to convert DTOs to models
    private fun ChannelDto.toModel() = SlackChannel(
        id = id,
        name = name,
        isPrivate = isPrivate ?: isGroup ?: false,
        isMember = isMember ?: true,
        numMembers = numMembers ?: 0,
        topic = topic?.value,
        purpose = purpose?.value
    )

    private fun MessageDto.toModel(channelId: String): SlackMessage? {
        // Skip subtypes like channel_join, channel_leave, etc.
        if (subtype != null && subtype !in listOf("file_share", "thread_broadcast")) {
            return null
        }

        return SlackMessage(
            id = ts ?: return null,
            channelId = channelId,
            userId = user,
            userName = username,
            text = text ?: "",
            timestamp = ts,
            threadTs = threadTs,
            replyCount = replyCount ?: 0,
            reactions = reactions?.map { it.toModel() } ?: emptyList(),
            files = files?.map { it.toModel() } ?: emptyList(),
            isEdited = edited != null
        )
    }

    private fun ReactionDto.toModel() = Reaction(
        name = name,
        count = count,
        users = users ?: emptyList()
    )

    private fun FileDto.toModel() = FileAttachment(
        id = id,
        name = name ?: "Unnamed file",
        mimeType = mimetype,
        size = size ?: 0
    )

    private fun UserDto.toModel() = SlackUser(
        id = id,
        name = name,
        realName = realName ?: profile?.realName,
        displayName = profile?.displayName?.takeIf { it.isNotEmpty() },
        avatarUrl = profile?.image192 ?: profile?.image72
    )
}
