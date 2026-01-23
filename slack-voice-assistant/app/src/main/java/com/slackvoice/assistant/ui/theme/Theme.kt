package com.slackvoice.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Slack-inspired colors
val SlackPurple = Color(0xFF4A154B)
val SlackPurpleLight = Color(0xFF611F69)
val SlackGreen = Color(0xFF2EB67D)
val SlackBlue = Color(0xFF36C5F0)
val SlackYellow = Color(0xFFECB22E)
val SlackRed = Color(0xFFE01E5A)

// Light theme colors
private val LightColorScheme = lightColorScheme(
    primary = SlackPurple,
    onPrimary = Color.White,
    primaryContainer = SlackPurpleLight,
    onPrimaryContainer = Color.White,
    secondary = SlackGreen,
    onSecondary = Color.White,
    secondaryContainer = SlackGreen.copy(alpha = 0.2f),
    onSecondaryContainer = SlackGreen,
    tertiary = SlackBlue,
    onTertiary = Color.White,
    error = SlackRed,
    onError = Color.White,
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1D1C1D),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1D1C1D),
    surfaceVariant = Color(0xFFF8F8F8),
    onSurfaceVariant = Color(0xFF49454F)
)

// Dark theme colors
private val DarkColorScheme = darkColorScheme(
    primary = SlackPurpleLight,
    onPrimary = Color.White,
    primaryContainer = SlackPurple,
    onPrimaryContainer = Color.White,
    secondary = SlackGreen,
    onSecondary = Color.Black,
    secondaryContainer = SlackGreen.copy(alpha = 0.3f),
    onSecondaryContainer = SlackGreen,
    tertiary = SlackBlue,
    onTertiary = Color.Black,
    error = SlackRed,
    onError = Color.White,
    background = Color(0xFF1D1C1D),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1D1C1D),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFCAC4D0)
)

@Composable
fun SlackVoiceAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
