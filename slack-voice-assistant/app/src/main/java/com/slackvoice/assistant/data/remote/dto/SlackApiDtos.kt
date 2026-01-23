package com.slackvoice.assistant.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Base response for all Slack API calls
 */
open class SlackResponse(
    val ok: Boolean,
    val error: String? = null,
    val warning: String? = null
)

/**
 * OAuth access response
 */
data class OAuthAccessResponse(
    @SerializedName("ok") val isOk: Boolean,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("scope") val scope: String?,
    @SerializedName("authed_user") val authedUser: AuthedUser?,
    @SerializedName("team") val team: TeamInfo?,
    val error: String? = null
)

data class AuthedUser(
    val id: String,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("token_type") val tokenType: String?,
    val scope: String?
)

data class TeamInfo(
    val id: String,
    val name: String
)

/**
 * User identity response
 */
data class UserIdentityResponse(
    @SerializedName("ok") val isOk: Boolean,
    val user: UserIdentity?,
    val team: TeamIdentity?,
    val error: String? = null
)

data class UserIdentity(
    val id: String,
    val name: String,
    val email: String?
)

data class TeamIdentity(
    val id: String,
    val name: String
)

/**
 * Conversations list response
 */
data class ConversationsListResponse(
    @SerializedName("ok") val isOk: Boolean,
    val channels: List<ChannelDto>?,
    @SerializedName("response_metadata") val responseMetadata: ResponseMetadata?,
    val error: String? = null
)

data class ChannelDto(
    val id: String,
    val name: String,
    @SerializedName("is_channel") val isChannel: Boolean?,
    @SerializedName("is_group") val isGroup: Boolean?,
    @SerializedName("is_im") val isIm: Boolean?,
    @SerializedName("is_mpim") val isMpim: Boolean?,
    @SerializedName("is_private") val isPrivate: Boolean?,
    @SerializedName("is_member") val isMember: Boolean?,
    @SerializedName("num_members") val numMembers: Int?,
    val topic: ChannelTopic?,
    val purpose: ChannelPurpose?
)

data class ChannelTopic(
    val value: String,
    val creator: String?,
    @SerializedName("last_set") val lastSet: Long?
)

data class ChannelPurpose(
    val value: String,
    val creator: String?,
    @SerializedName("last_set") val lastSet: Long?
)

data class ResponseMetadata(
    @SerializedName("next_cursor") val nextCursor: String?
)

/**
 * Conversations history response
 */
data class ConversationsHistoryResponse(
    @SerializedName("ok") val isOk: Boolean,
    val messages: List<MessageDto>?,
    @SerializedName("has_more") val hasMore: Boolean?,
    @SerializedName("response_metadata") val responseMetadata: ResponseMetadata?,
    val error: String? = null
)

data class MessageDto(
    val type: String?,
    val subtype: String?,
    val user: String?,
    val text: String?,
    val ts: String?,
    @SerializedName("thread_ts") val threadTs: String?,
    @SerializedName("reply_count") val replyCount: Int?,
    @SerializedName("reply_users_count") val replyUsersCount: Int?,
    val reactions: List<ReactionDto>?,
    val files: List<FileDto>?,
    val edited: EditedInfo?,
    @SerializedName("bot_id") val botId: String?,
    val username: String?
)

data class ReactionDto(
    val name: String,
    val count: Int,
    val users: List<String>?
)

data class FileDto(
    val id: String,
    val name: String?,
    val mimetype: String?,
    val size: Long?
)

data class EditedInfo(
    val user: String,
    val ts: String
)

/**
 * Users info response
 */
data class UsersInfoResponse(
    @SerializedName("ok") val isOk: Boolean,
    val user: UserDto?,
    val error: String? = null
)

data class UserDto(
    val id: String,
    val name: String,
    @SerializedName("real_name") val realName: String?,
    val profile: UserProfile?
)

data class UserProfile(
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("real_name") val realName: String?,
    @SerializedName("image_72") val image72: String?,
    @SerializedName("image_192") val image192: String?
)

/**
 * Users list response
 */
data class UsersListResponse(
    @SerializedName("ok") val isOk: Boolean,
    val members: List<UserDto>?,
    @SerializedName("response_metadata") val responseMetadata: ResponseMetadata?,
    val error: String? = null
)

/**
 * Conversations mark response (for tracking read status)
 */
data class ConversationsMarkResponse(
    @SerializedName("ok") val isOk: Boolean,
    val error: String? = null
)

/**
 * Auth test response
 */
data class AuthTestResponse(
    @SerializedName("ok") val isOk: Boolean,
    @SerializedName("user_id") val userId: String?,
    @SerializedName("team_id") val teamId: String?,
    val team: String?,
    val user: String?,
    val error: String? = null
)
