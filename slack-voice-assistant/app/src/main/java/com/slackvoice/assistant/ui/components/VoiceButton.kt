package com.slackvoice.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.slackvoice.assistant.ui.theme.SlackBlue
import com.slackvoice.assistant.ui.theme.SlackGreen
import com.slackvoice.assistant.ui.theme.SlackPurple
import com.slackvoice.assistant.voice.VoiceAssistant

@Composable
fun VoiceButton(
    voiceState: VoiceAssistant.VoiceState,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Animation for pulsing effect
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val isAnimating = voiceState == VoiceAssistant.VoiceState.Listening ||
            voiceState == VoiceAssistant.VoiceState.Speaking

    val backgroundColor by animateColorAsState(
        targetValue = when (voiceState) {
            VoiceAssistant.VoiceState.Idle -> SlackPurple
            VoiceAssistant.VoiceState.Listening -> SlackGreen
            VoiceAssistant.VoiceState.Processing -> SlackBlue
            VoiceAssistant.VoiceState.Speaking -> SlackPurple.copy(alpha = 0.8f)
        },
        label = "backgroundColor"
    )

    val icon = when (voiceState) {
        VoiceAssistant.VoiceState.Idle -> Icons.Default.Mic
        VoiceAssistant.VoiceState.Listening -> Icons.Default.Mic
        VoiceAssistant.VoiceState.Processing -> Icons.Default.Mic
        VoiceAssistant.VoiceState.Speaking -> Icons.Default.VolumeUp
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Outer pulse ring (visible when active)
        if (isAnimating) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(backgroundColor.copy(alpha = 0.3f))
            )
        }

        // Main button
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) backgroundColor else Color.Gray
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (enabled) icon else Icons.Default.MicOff,
                contentDescription = when (voiceState) {
                    VoiceAssistant.VoiceState.Idle -> "Tap to speak"
                    VoiceAssistant.VoiceState.Listening -> "Listening"
                    VoiceAssistant.VoiceState.Processing -> "Processing"
                    VoiceAssistant.VoiceState.Speaking -> "Speaking"
                },
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
fun VoiceStateIndicator(
    voiceState: VoiceAssistant.VoiceState,
    modifier: Modifier = Modifier
) {
    val stateText = when (voiceState) {
        VoiceAssistant.VoiceState.Idle -> "Tap to speak"
        VoiceAssistant.VoiceState.Listening -> "Listening..."
        VoiceAssistant.VoiceState.Processing -> "Processing..."
        VoiceAssistant.VoiceState.Speaking -> "Speaking..."
    }

    val stateColor = when (voiceState) {
        VoiceAssistant.VoiceState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        VoiceAssistant.VoiceState.Listening -> SlackGreen
        VoiceAssistant.VoiceState.Processing -> SlackBlue
        VoiceAssistant.VoiceState.Speaking -> SlackPurple
    }

    Text(
        text = stateText,
        style = MaterialTheme.typography.bodyLarge,
        color = stateColor,
        modifier = modifier
    )
}
