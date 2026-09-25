package com.example.musicdlp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = IndustrialPrimary,
    onPrimary = IndustrialOnPrimary,
    primaryContainer = IndustrialPrimaryContainer,
    onPrimaryContainer = IndustrialOnPrimaryContainer,
    secondary = IndustrialSecondary,
    onSecondary = IndustrialOnSecondary,
    secondaryContainer = IndustrialSecondaryContainer,
    onSecondaryContainer = IndustrialOnSecondaryContainer,
    tertiary = IndustrialTertiary,
    onTertiary = IndustrialOnTertiary,
    background = IndustrialDarkBackground,
    onBackground = Color(0xFFF1F5F9),
    surface = IndustrialDarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = IndustrialDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = IndustrialError,
    onError = IndustrialOnError,
    errorContainer = IndustrialErrorContainer,
    onErrorContainer = IndustrialOnErrorContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1E293B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E8F0),
    onPrimaryContainer = Color(0xFF0F172A),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF1E293B),
    background = IndustrialLightBackground,
    onBackground = Color(0xFF0F172A),
    surface = IndustrialLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = IndustrialLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF334155),
)

@Composable
fun MusicDLPTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Default dynamicColor to false so the industrial black/grey theme is used instead of system wallpaper colors
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme // Prefers industrial dark black/grey palette
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}