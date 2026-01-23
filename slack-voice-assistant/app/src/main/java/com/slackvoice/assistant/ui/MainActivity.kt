package com.slackvoice.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.slackvoice.assistant.data.auth.SlackAuthManager
import com.slackvoice.assistant.ui.main.MainViewModel
import com.slackvoice.assistant.ui.screens.CatchUpScreen
import com.slackvoice.assistant.ui.screens.DraftsScreen
import com.slackvoice.assistant.ui.screens.LoginScreen
import com.slackvoice.assistant.ui.theme.SlackVoiceAssistantTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.updateAudioPermission(isGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle OAuth callback from deep link
        handleIntent(intent)

        setContent {
            SlackVoiceAssistantTheme {
                val navController = rememberNavController()
                val authState by viewModel.authState.collectAsStateWithLifecycle()
                val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
                val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
                val currentSummary by viewModel.currentChannelSummary.collectAsStateWithLifecycle()
                val channelProgress by viewModel.channelProgress.collectAsStateWithLifecycle()
                val drafts by viewModel.drafts.collectAsStateWithLifecycle()
                val draftCount by viewModel.draftCount.collectAsStateWithLifecycle()
                val hasAudioPermission by viewModel.hasAudioPermission.collectAsStateWithLifecycle()
                val error by viewModel.error.collectAsStateWithLifecycle()

                // Snackbar for errors
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(error) {
                    error?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearError()
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    modifier = Modifier.fillMaxSize()
                ) { paddingValues ->
                    when (authState) {
                        is SlackAuthManager.AuthState.Loading,
                        is SlackAuthManager.AuthState.Authenticating -> {
                            // Show loading
                            LoginScreen(
                                onSignInClick = {},
                                isLoading = true,
                                modifier = Modifier.padding(paddingValues)
                            )
                        }

                        is SlackAuthManager.AuthState.NotAuthenticated -> {
                            LoginScreen(
                                onSignInClick = { openSlackOAuth() },
                                modifier = Modifier.padding(paddingValues)
                            )
                        }

                        is SlackAuthManager.AuthState.Authenticated -> {
                            NavHost(
                                navController = navController,
                                startDestination = "catchup",
                                modifier = Modifier.padding(paddingValues)
                            ) {
                                composable("catchup") {
                                    CatchUpScreen(
                                        sessionState = sessionState,
                                        voiceState = voiceState,
                                        currentSummary = currentSummary,
                                        channelProgress = channelProgress,
                                        draftCount = draftCount,
                                        hasAudioPermission = hasAudioPermission,
                                        onStartCatchUp = {
                                            requestAudioPermissionIfNeeded()
                                            viewModel.startCatchUp()
                                        },
                                        onVoiceButtonClick = {
                                            when (voiceState) {
                                                com.slackvoice.assistant.voice.VoiceAssistant.VoiceState.Idle -> {
                                                    viewModel.startListening()
                                                }
                                                com.slackvoice.assistant.voice.VoiceAssistant.VoiceState.Listening -> {
                                                    viewModel.stopListening()
                                                }
                                                com.slackvoice.assistant.voice.VoiceAssistant.VoiceState.Speaking -> {
                                                    viewModel.stopSpeaking()
                                                }
                                                else -> {}
                                            }
                                        },
                                        onStopSession = { viewModel.stopSession() },
                                        onViewDrafts = { navController.navigate("drafts") },
                                        onSettingsClick = { /* TODO: Settings screen */ },
                                        onSignOutClick = { viewModel.signOut() }
                                    )
                                }

                                composable("drafts") {
                                    DraftsScreen(
                                        drafts = drafts,
                                        onDeleteDraft = { viewModel.deleteDraft(it) },
                                        onDeleteAllDrafts = {
                                            drafts.forEach { viewModel.deleteDraft(it) }
                                        },
                                        onBackClick = { navController.popBackStack() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "slackvoice" && uri.host == "oauth") {
                val code = uri.getQueryParameter("code")
                if (code != null) {
                    viewModel.handleOAuthCallback(code)
                }
            }
        }
    }

    private fun openSlackOAuth() {
        val url = viewModel.getOAuthUrl()
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        try {
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            // Fallback to regular browser
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        }
    }

    private fun requestAudioPermissionIfNeeded() {
        if (!viewModel.hasAudioPermission.value) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
