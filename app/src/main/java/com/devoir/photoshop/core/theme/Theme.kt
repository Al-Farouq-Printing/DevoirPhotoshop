package com.devoir.photoshop.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7AB7FF), onPrimary = Color(0xFF00315A),
    primaryContainer = Color(0xFF1F4A78), onPrimaryContainer = Color(0xFFD3E6FF),
    background = Color(0xFF16171A), onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1E1F23), onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2A2C31), onSurfaceVariant = Color(0xFFC4C6CC),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B6AC9), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6FF), onPrimaryContainer = Color(0xFF001C3A),
    background = Color(0xFFF3F4F7), surface = Color.White,
    surfaceVariant = Color(0xFFE2E4EA),
)

@Composable
fun DevoirTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
