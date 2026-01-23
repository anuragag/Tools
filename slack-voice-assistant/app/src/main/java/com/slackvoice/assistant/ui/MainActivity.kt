package com.slackvoice.assistant.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
                val isValidatingToken by viewModel.isValidatingToken.collectAsStateWithLifecycle()

                // Snackbar for errors (only for non-auth errors)
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(error) {
                    // Only show snackbar for errors when authenticated
                    if (error != null && authState is SlackAuthManager.AuthState.Authenticated) {
                        snackbarHostState.showSnackbar(error!!)
                        viewModel.clearError()
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    modifier = Modifier.fillMaxSize()
                ) { paddingValues ->
                    when (authState) {
                        is SlackAuthManager.AuthState.Loading -> {
                            // Show loading state
                            LoginScreen(
                                onTokenSubmit = {},
                                isLoading = true,
                                modifier = Modifier.padding(paddingValues)
                            )
                        }

                        is SlackAuthManager.AuthState.NotAuthenticated,
                        is SlackAuthManager.AuthState.Authenticating -> {
                            LoginScreen(
                                onTokenSubmit = { token ->
                                    viewModel.submitToken(token)
                                },
                                isLoading = isValidatingToken || authState is SlackAuthManager.AuthState.Authenticating,
                                error = error,
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

    private fun requestAudioPermissionIfNeeded() {
        if (!viewModel.hasAudioPermission.value) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
