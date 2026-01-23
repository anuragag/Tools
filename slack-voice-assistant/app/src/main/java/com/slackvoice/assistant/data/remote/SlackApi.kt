package com.slackvoice.assistant.data.remote

import com.slackvoice.assistant.data.remote.dto.*
import retrofit2.http.*

/**
 * Slack Web API interface
 * https://api.slack.com/methods
 *
 * Uses personal user token for authentication (xoxc- or xoxp- tokens)
 */
interface SlackApi {

    companion object {
        const val BASE_URL = "https://slack.com/api/"
    }

    /**
     * Test authentication and get user info
     */
    @GET("auth.test")
    suspend fun authTest(
        @Header("Authorization") auth: String
    ): AuthTestResponse

    /**
     * List conversations the user is a member of
     */
    @GET("conversations.list")
    suspend fun conversationsList(
        @Header("Authorization") auth: String,
        @Query("types") types: String = "public_channel,private_channel,mpim,im",
        @Query("exclude_archived") excludeArchived: Boolean = true,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null
    ): ConversationsListResponse

    /**
     * Get conversation history (messages)
     */
    @GET("conversations.history")
    suspend fun conversationsHistory(
        @Header("Authorization") auth: String,
        @Query("channel") channel: String,
        @Query("limit") limit: Int = 50,
        @Query("oldest") oldest: String? = null,
        @Query("latest") latest: String? = null,
        @Query("inclusive") inclusive: Boolean = true,
        @Query("cursor") cursor: String? = null
    ): ConversationsHistoryResponse

    /**
     * Get thread replies
     */
    @GET("conversations.replies")
    suspend fun conversationsReplies(
        @Header("Authorization") auth: String,
        @Query("channel") channel: String,
        @Query("ts") ts: String,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null
    ): ConversationsHistoryResponse

    /**
     * Get info about a specific user
     */
    @GET("users.info")
    suspend fun usersInfo(
        @Header("Authorization") auth: String,
        @Query("user") user: String
    ): UsersInfoResponse

    /**
     * List users in workspace
     */
    @GET("users.list")
    suspend fun usersList(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 200,
        @Query("cursor") cursor: String? = null
    ): UsersListResponse

    /**
     * Mark a conversation as read
     */
    @FormUrlEncoded
    @POST("conversations.mark")
    suspend fun conversationsMark(
        @Header("Authorization") auth: String,
        @Field("channel") channel: String,
        @Field("ts") ts: String
    ): ConversationsMarkResponse

    /**
     * Post a message (for future use if user wants to send drafts)
     */
    @FormUrlEncoded
    @POST("chat.postMessage")
    suspend fun chatPostMessage(
        @Header("Authorization") auth: String,
        @Field("channel") channel: String,
        @Field("text") text: String,
        @Field("thread_ts") threadTs: String? = null
    ): SlackResponse
}
