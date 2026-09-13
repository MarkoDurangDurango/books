package com.bookshelf.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF25442E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EAD9),
    onPrimaryContainer = Color(0xFF102116),
    secondary = Color(0xFF756A52),
    background = Color(0xFFF8F6F0),
    surface = Color(0xFFFFFDF8),
    surfaceVariant = Color(0xFFEDEAE1),
    onSurface = Color(0xFF1D211E),
    onSurfaceVariant = Color(0xFF60665F),
    outline = Color(0xFFBBBDB5),
    error = Color(0xFFB3261E)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB9D6BC),
    onPrimary = Color(0xFF17301F),
    primaryContainer = Color(0xFF2F5138),
    background = Color(0xFF111411),
    surface = Color(0xFF181C18),
    surfaceVariant = Color(0xFF262B26),
    onSurface = Color(0xFFE5E8E3),
    onSurfaceVariant = Color(0xFFC3C8C1),
    outline = Color(0xFF8D928B),
    error = Color(0xFFFFB4AB)
)

@Composable
fun BookShelfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
