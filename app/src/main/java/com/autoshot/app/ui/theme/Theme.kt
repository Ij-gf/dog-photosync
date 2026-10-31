package com.autoshot.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF4A5C7A),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF10151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10151C),
    surfaceVariant = Color(0xFFEDF0F5),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFC9D0DA),
    error = Color(0xFFD92D20),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB0FF),
    onPrimary = Color(0xFF06214A),
    primaryContainer = Color(0xFF12365F),
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = Color(0xFFA8B8CE),
    background = Color(0xFF0E1218),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF161C24),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF222A35),
    onSurfaceVariant = Color(0xFFB4BFCB),
    outline = Color(0xFF3A4552),
    error = Color(0xFFFF8A80),
)

@Composable
fun AutoShotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
