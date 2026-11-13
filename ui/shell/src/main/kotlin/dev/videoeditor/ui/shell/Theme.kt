package dev.videoeditor.ui.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8AB4FF),
        onPrimary = Color(0xFF00285E),
        primaryContainer = Color(0xFF1F3F73),
        onPrimaryContainer = Color(0xFFD8E2FF),
        secondary = Color(0xFFBFC6DC),
        secondaryContainer = Color(0xFF3F4759),
        tertiary = Color(0xFFFFB86B),
        tertiaryContainer = Color(0xFF5C3B12),
        background = Color(0xFF111318),
        surface = Color(0xFF111318),
        surfaceContainerLow = Color(0xFF191C21),
        surfaceContainer = Color(0xFF1D2025),
        surfaceContainerHigh = Color(0xFF282A2F),
    )

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2D5BA8),
        primaryContainer = Color(0xFFD8E2FF),
        tertiary = Color(0xFF8A5100),
        tertiaryContainer = Color(0xFFFFDCBE),
    )

/** Material 3 theme for the editor; dark by default when the system is dark. */
@Composable
fun EditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
