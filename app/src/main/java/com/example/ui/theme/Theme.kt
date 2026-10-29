package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KamakhyaDarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color(0xFF00262C),
    primaryContainer = ElectricBlueAccent,
    onPrimaryContainer = TextPrimaryDark,
    secondary = VioletAccent,
    onSecondary = Color.White,
    secondaryContainer = DeepNavyElevated,
    onSecondaryContainer = SoftViolet,
    tertiary = CyanGlow,
    background = DeepNavyBackground,
    onBackground = TextPrimaryDark,
    surface = DeepNavySurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DeepNavyCard,
    onSurfaceVariant = TextSecondaryDark,
    error = StatusErrorRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KamakhyaDarkColorScheme,
        typography = Typography,
        content = content
    )
}
