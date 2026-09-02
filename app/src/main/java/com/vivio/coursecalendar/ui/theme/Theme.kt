package com.vivio.coursecalendar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6FE0),
    onPrimary = Color.White,
    secondary = Color(0xFFE8871E),
    onSecondary = Color.White,
    tertiary = Color(0xFF0FA47F),
    background = Color(0xFFF7F8FA),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB1F5),
    onPrimary = Color(0xFF0B2A55),
    secondary = Color(0xFFF0A75B),
    onSecondary = Color(0xFF3A2205),
    background = Color(0xFF121318),
    surface = Color(0xFF1C1D24)
)

@Composable
fun VivioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
