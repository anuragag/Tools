package com.slackvoice.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slackvoice.assistant.catchup.CatchUpSession
import com.slackvoice.assistant.data.model.ChannelSummary
import com.slackvoice.assistant.ui.components.ChannelSummaryCard
import com.slackvoice.assistant.ui.components.VoiceButton
import com.slackvoice.assistant.ui.components.VoiceStateIndicator
import com.slackvoice.assistant.voice.VoiceAssistant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatchUpScreen(
    sessionState: CatchUpSession.SessionState,
    voiceState: VoiceAssistant.VoiceState,
    currentSummary: ChannelSummary?,
    channelProgress: Pair<Int, Int>,
    draftCount: Int,
    hasAudioPermission: Boolean,
    onStartCatchUp: () -> Unit,
    onVoiceButtonClick: () -> Unit,
    onStopSession: () -> Unit,
    onViewDrafts: () -> Unit,
    onSettingsClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catch Up") },
                actions = {
                    // Draft count badge
                    if (draftCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge { Text(draftCount.toString()) }
                            }
                        ) {
                            IconButton(onClick = onViewDrafts) {
                                Icon(
                                    imageVector = Icons.Default.Drafts,
                                    contentDescription = "View drafts"
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = onViewDrafts) {
                            Icon(
                                imageVector = Icons.Default.Drafts,
                                contentDescription = "View drafts"
                            )
                        }
                    }

                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }

                    IconButton(onClick = onSignOutClick) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = "Sign out"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (sessionState) {
                is CatchUpSession.SessionState.NotStarted -> {
                    NotStartedContent(
                        onStartClick = onStartCatchUp
                    )
                }

                is CatchUpSession.SessionState.Loading -> {
                    LoadingContent()
                }

                is CatchUpSession.SessionState.InProgress,
                is CatchUpSession.SessionState.Replying,
                is CatchUpSession.SessionState.Paused -> {
                    ActiveSessionContent(
                        sessionState = sessionState,
                        voiceState = voiceState,
                        currentSummary = currentSummary,
                        channelProgress = channelProgress,
                        hasAudioPermission = hasAudioPermission,
                        onVoiceButtonClick = onVoiceButtonClick,
                        onStopSession = onStopSession
                    )
                }

                is CatchUpSession.SessionState.NoMessages -> {
                    NoMessagesContent(
                        onStartOver = onStartCatchUp
                    )
                }

                is CatchUpSession.SessionState.Completed -> {
                    CompletedContent(
                        draftCount = draftCount,
                        onStartOver = onStartCatchUp,
                        onViewDrafts = onViewDrafts
                    )
                }

                is CatchUpSession.SessionState.Error -> {
                    ErrorContent(
                        message = sessionState.message,
                        onRetry = onStartCatchUp
                    )
                }
            }
        }
    }
}

@Composable
private fun NotStartedContent(
    onStartClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Headset,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Ready to catch up?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "I'll summarize your Slack activity\nand read it aloud to you",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStartClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Catching Up")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Voice commands hint
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Voice Commands",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                CommandHint("\"Next\"", "Go to next channel")
                CommandHint("\"Skip\"", "Skip current channel")
                CommandHint("\"Reply\"", "Compose a response")
                CommandHint("\"Done\"", "Finish catching up")
            }
        }
    }
}

@Composable
private fun CommandHint(command: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = command,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingContent(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Loading your Slack activity...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActiveSessionContent(
    sessionState: CatchUpSession.SessionState,
    voiceState: VoiceAssistant.VoiceState,
    currentSummary: ChannelSummary?,
    channelProgress: Pair<Int, Int>,
    hasAudioPermission: Boolean,
    onVoiceButtonClick: () -> Unit,
    onStopSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Progress indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Channel ${channelProgress.first} of ${channelProgress.second}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = onStopSession) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("End Session")
            }
        }

        LinearProgressIndicator(
            progress = {
                if (channelProgress.second > 0) {
                    channelProgress.first.toFloat() / channelProgress.second
                } else 0f
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Current channel summary
        currentSummary?.let { summary ->
            ChannelSummaryCard(
                summary = summary,
                isCurrentChannel = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Session state indicator
        AnimatedVisibility(
            visible = sessionState is CatchUpSession.SessionState.Replying
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Composing Reply",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Say your message, then \"done\" to save",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Voice button
        VoiceButton(
            voiceState = voiceState,
            enabled = hasAudioPermission,
            onClick = onVoiceButtonClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Voice state text
        VoiceStateIndicator(voiceState = voiceState)

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun NoMessagesContent(
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "All caught up!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "No new messages in the last 24 hours",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(onClick = onStartOver) {
            Text("Check Again")
        }
    }
}

@Composable
private fun CompletedContent(
    draftCount: Int,
    onStartOver: () -> Unit,
    onViewDrafts: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.TaskAlt,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Catch-up Complete!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (draftCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You have $draftCount draft ${if (draftCount == 1) "reply" else "replies"}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onViewDrafts) {
                Icon(
                    imageVector = Icons.Default.Drafts,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View Drafts")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onStartOver) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Over")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Try Again")
        }
    }
}
