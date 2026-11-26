package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.model.ThemeMode

val LocalPlexoIsDark = staticCompositionLocalOf { true }

private val PlexoDarkColorScheme = darkColorScheme(
    primary = CyanElectric,
    onPrimary = Color(0xFF04222A),
    primaryContainer = Color(0xFF0C3644),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = EmeraldMint,
    onSecondary = Color(0xFF04261B),
    secondaryContainer = Color(0xFF0D382A),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = AmberPulse,
    onTertiary = Color(0xFF2D1404),
    tertiaryContainer = Color(0xFF42210B),
    onTertiaryContainer = Color(0xFFFFEDD5),
    background = PlexoDarkBackground,
    onBackground = PlexoDarkOnSurface,
    surface = PlexoDarkSurface,
    onSurface = PlexoDarkOnSurface,
    surfaceVariant = PlexoDarkSurfaceVariant,
    onSurfaceVariant = PlexoDarkOnSurfaceVariant,
    surfaceContainerLowest = PlexoDarkBackground,
    surfaceContainerLow = PlexoDarkSurface,
    surfaceContainer = PlexoDarkSurfaceVariant,
    surfaceContainerHigh = PlexoDarkSurfaceElevated,
    surfaceContainerHighest = Color(0xFF243554),
    outline = PlexoDarkOutline,
    outlineVariant = Color(0xFF1D2C46),
    error = CoralError,
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF4C151B),
    onErrorContainer = Color(0xFFFEE2E2)
)

private val PlexoLightColorScheme = lightColorScheme(
    primary = CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF073B58),
    secondary = EmeraldDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = AmberDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = PlexoLightBackground,
    onBackground = PlexoLightOnSurface,
    surface = PlexoLightSurface,
    onSurface = PlexoLightOnSurface,
    surfaceVariant = PlexoLightSurfaceVariant,
    onSurfaceVariant = PlexoLightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = PlexoLightSurfaceVariant,
    surfaceContainerHigh = Color(0xFFDDE7F3),
    outline = PlexoLightOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = CoralErrorDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val PlexoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PlexoTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> PlexoDarkColorScheme
        else -> PlexoLightColorScheme
    }

    CompositionLocalProvider(LocalPlexoIsDark provides useDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PlexoTypography,
            shapes = PlexoShapes,
            content = content
        )
    }
}
