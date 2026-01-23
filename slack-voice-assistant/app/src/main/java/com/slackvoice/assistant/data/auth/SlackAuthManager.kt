package com.slackvoice.assistant.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.slackvoice.assistant.BuildConfig
import com.slackvoice.assistant.data.model.SlackAuthInfo
import com.slackvoice.assistant.data.remote.SlackApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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

    fun getOAuthUrl(): String {
        val scopes = listOf(
            "channels:history",
            "channels:read",
            "groups:history",
            "groups:read",
            "im:history",
            "im:read",
            "mpim:history",
            "mpim:read",
            "users:read",
            "users:read.email",
            "chat:write"
        ).joinToString(",")

        return Uri.parse(SlackApi.OAUTH_URL)
            .buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SLACK_CLIENT_ID)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("user_scope", scopes)
            .appendQueryParameter("redirect_uri", BuildConfig.SLACK_REDIRECT_URI)
            .build()
            .toString()
    }

    suspend fun handleOAuthCallback(code: String): Result<SlackAuthInfo> {
        return try {
            _authState.value = AuthState.Authenticating

            val response = slackApi.oauthAccess(
                clientId = BuildConfig.SLACK_CLIENT_ID,
                clientSecret = BuildConfig.SLACK_CLIENT_SECRET,
                code = code,
                redirectUri = BuildConfig.SLACK_REDIRECT_URI
            )

            if (!response.isOk || response.authedUser?.accessToken == null) {
                _authState.value = AuthState.NotAuthenticated
                return Result.failure(Exception(response.error ?: "OAuth failed"))
            }

            val userToken = response.authedUser.accessToken

            // Get user info
            val authTest = slackApi.authTest("Bearer $userToken")

            if (!authTest.isOk) {
                _authState.value = AuthState.NotAuthenticated
                return Result.failure(Exception(authTest.error ?: "Auth test failed"))
            }

            val authInfo = SlackAuthInfo(
                accessToken = userToken,
                userId = authTest.userId ?: "",
                teamId = authTest.teamId ?: response.team?.id ?: "",
                teamName = authTest.team ?: response.team?.name ?: "",
                userName = authTest.user ?: "",
                scope = response.authedUser.scope ?: ""
            )

            saveAuthInfo(authInfo)
            cachedAuthInfo = authInfo
            _authState.value = AuthState.Authenticated(authInfo)

            Result.success(authInfo)
        } catch (e: Exception) {
            _authState.value = AuthState.NotAuthenticated
            Result.failure(e)
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
