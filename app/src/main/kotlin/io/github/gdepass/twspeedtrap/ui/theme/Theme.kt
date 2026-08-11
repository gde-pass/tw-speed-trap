package io.github.gdepass.twspeedtrap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// High-contrast dark palette: the app is read at arm's length on a motorcycle,
// often in sunlight, so contrast is prioritised over subtlety.
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF4FC3F7),
        onPrimary = Color(0xFF00131A),
        secondary = Color(0xFFFFB74D),
        onSecondary = Color(0xFF1A1000),
        error = Color(0xFFFF5252),
        onError = Color(0xFF1A0000),
        background = Color(0xFF0B0F12),
        onBackground = Color(0xFFF2F5F7),
        surface = Color(0xFF12181D),
        onSurface = Color(0xFFF2F5F7),
        surfaceVariant = Color(0xFF1C242B),
        onSurfaceVariant = Color(0xFFC4CDD4),
    )

@Composable
fun TwSpeedTrapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
