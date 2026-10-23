package com.keihix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryEmeraldLight,
    onPrimary = DarkBackground,
    primaryContainer = PrimaryEmeraldContainer,
    onPrimaryContainer = TextPrimary,
    secondary = SecondaryTeal,
    onSecondary = DarkBackground,
    secondaryContainer = SecondaryTealContainer,
    onSecondaryContainer = TextPrimary,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = ErrorRed,
    onError = DarkBackground
)

@Composable
fun KeihiXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // KeihiXはフィンテック・ダークテーマを基調として統一
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
