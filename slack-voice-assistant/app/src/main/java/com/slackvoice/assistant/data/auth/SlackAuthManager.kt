package com.slackvoice.assistant.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.slackvoice.assistant.data.model.SlackAuthInfo
import com.slackvoice.assistant.data.remote.SlackApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Slack authentication using a personal user token.
 *
 * To get your token:
 * 1. Open Slack in your web browser
 * 2. Open Developer Tools (F12)
 * 3. Go to Application > Local Storage > https://app.slack.com
 * 4. Find the key that starts with "localConfig_v2"
 * 5. Look for the "token" field starting with "xoxc-"
 *
 * Alternatively, use the legacy token page (if available):
 * https://api.slack.com/legacy/custom-integrations/legacy-tokens
 */
@Singleton
class SlackAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val slackApi: SlackApi
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "slack_auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var cachedAuthInfo: SlackAuthInfo? = null

    init {
        loadStoredAuth()
    }

    private fun loadStoredAuth() {
        val token = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        if (token != null) {
            cachedAuthInfo = SlackAuthInfo(
                accessToken = token,
                userId = encryptedPrefs.getString(KEY_USER_ID, "") ?: "",
                teamId = encryptedPrefs.getString(KEY_TEAM_ID, "") ?: "",
                teamName = encryptedPrefs.getString(KEY_TEAM_NAME, "") ?: "",
                userName = encryptedPrefs.getString(KEY_USER_NAME, "") ?: "",
                scope = encryptedPrefs.getString(KEY_SCOPE, "") ?: ""
            )
            _authState.value = AuthState.Authenticated(cachedAuthInfo!!)
        } else {
            _authState.value = AuthState.NotAuthenticated
        }
    }

    /**
     * Validate and save a manually entered token.
     * Supports both xoxc- (cookie) tokens and xoxp- (user) tokens.
     */
    suspend fun validateAndSaveToken(token: String): Result<SlackAuthInfo> {
        val cleanToken = token.trim()

        // Basic validation
        if (cleanToken.isBlank()) {
            return Result.failure(Exception("Token cannot be empty"))
        }

        if (!cleanToken.startsWith("xoxc-") &&
            !cleanToken.startsWith("xoxp-") &&
            !cleanToken.startsWith("xoxb-")) {
            return Result.failure(Exception("Invalid token format. Token should start with xoxc-, xoxp-, or xoxb-"))
        }

        return try {
            _authState.value = AuthState.Authenticating

            // Test the token by calling auth.test
            val authTest = slackApi.authTest("Bearer $cleanToken")

            if (!authTest.isOk) {
                _authState.value = AuthState.NotAuthenticated
                val errorMsg = mapSlackError(authTest.error)
                return Result.failure(Exception(errorMsg))
            }

            val authInfo = SlackAuthInfo(
                accessToken = cleanToken,
                userId = authTest.userId ?: "",
                teamId = authTest.teamId ?: "",
                teamName = authTest.team ?: "",
                userName = authTest.user ?: "",
                scope = "user_token"
            )

            saveAuthInfo(authInfo)
            cachedAuthInfo = authInfo
            _authState.value = AuthState.Authenticated(authInfo)

            Result.success(authInfo)
        } catch (e: Exception) {
            _authState.value = AuthState.NotAuthenticated
            Result.failure(Exception("Failed to validate token: ${e.message}"))
        }
    }

    private fun saveAuthInfo(authInfo: SlackAuthInfo) {
        encryptedPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, authInfo.accessToken)
            .putString(KEY_USER_ID, authInfo.userId)
            .putString(KEY_TEAM_ID, authInfo.teamId)
            .putString(KEY_TEAM_NAME, authInfo.teamName)
            .putString(KEY_USER_NAME, authInfo.userName)
            .putString(KEY_SCOPE, authInfo.scope)
            .apply()
    }

    fun getAuthInfo(): SlackAuthInfo? = cachedAuthInfo

    fun getAuthHeader(): String? = cachedAuthInfo?.let { "Bearer ${it.accessToken}" }

    fun signOut() {
        encryptedPrefs.edit().clear().apply()
        cachedAuthInfo = null
        _authState.value = AuthState.NotAuthenticated
    }

    fun isAuthenticated(): Boolean = cachedAuthInfo != null

    /**
     * Maps Slack API error codes to user-friendly messages.
     * Includes enterprise-specific errors.
     */
    private fun mapSlackError(error: String?): String {
        return when (error) {
            // Basic auth errors
            "invalid_auth" -> "Invalid token. Please check and try again."
            "token_expired" -> "Token has expired. Please get a new one from your browser."
            "token_revoked" -> "Token has been revoked. Please get a new one."
            "not_authed" -> "Token is not valid for authentication."
            "account_inactive" -> "Your Slack account has been deactivated."

            // Enterprise Grid specific errors
            "enterprise_is_restricted" ->
                "Your Enterprise Grid org has restricted API access. Contact your IT admin."
            "team_access_not_granted" ->
                "API access not granted for this workspace. Your admin may need to approve API access."
            "org_login_required" ->
                "Your organization requires re-authentication. Please log into Slack web and get a fresh token."
            "ekm_access_denied" ->
                "Access denied by Enterprise Key Management. Contact your IT admin."
            "access_denied" ->
                "Access denied. Your organization may have restricted this type of access."

            // SSO/Session errors
            "invalid_token_type" ->
                "This token type is not supported. Try extracting a different token."
            "session_expired" ->
                "Your session has expired (SSO timeout). Please log into Slack web and get a fresh token."
            "session_invalidated" ->
                "Your session was invalidated. Please get a new token."
            "session_reset_required" ->
                "Session reset required by your admin. Log into Slack web and get a fresh token."
            "two_factor_setup_required" ->
                "Two-factor authentication setup required. Complete 2FA setup in Slack first."

            // Rate limiting / restrictions
            "ratelimited" ->
                "Rate limited by Slack. Please wait a few minutes and try again."
            "team_added_to_org" ->
                "Your workspace was recently added to an Enterprise Grid. Token may need refresh."
            "fatal_error" ->
                "Slack API error. Please try again later."

            // Network/connection errors
            "request_timeout" ->
                "Request timed out. Check your internet connection."
            "service_unavailable" ->
                "Slack is temporarily unavailable. Please try again later."

            // Compliance
            "compliance_exports_prevent_deletion" ->
                "Compliance policies are active. Your org has strict data controls."
            "org_user_not_in_team" ->
                "You're not a member of this workspace in your Enterprise Grid."

            // Unknown
            null -> "Authentication failed. Please try again."
            else -> "Error: $error. If this persists, try getting a fresh token."
        }
    }

    sealed class AuthState {
        data object Loading : AuthState()
        data object NotAuthenticated : AuthState()
        data object Authenticating : AuthState()
        data class Authenticated(val authInfo: SlackAuthInfo) : AuthState()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_TEAM_ID = "team_id"
        private const val KEY_TEAM_NAME = "team_name"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SCOPE = "scope"
    }
}
