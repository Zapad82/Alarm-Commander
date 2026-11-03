package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TacticalDarkColorScheme = darkColorScheme(
    primary = TacticalAmber,
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF3A2800),
    onPrimaryContainer = TacticalAmberLight,
    secondary = RadarEmerald,
    onSecondary = Color(0xFF00210F),
    secondaryContainer = Color(0xFF00391C),
    onSecondaryContainer = RadarEmerald,
    tertiary = CyanTelemetry,
    onTertiary = Color(0xFF00262B),
    error = AlertCrimson,
    onError = Color.White,
    errorContainer = Color(0xFF4A0E13),
    onErrorContainer = Color(0xFFFFB4AB),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = Color(0xFF334155)
)

private val TacticalLightColorScheme = lightColorScheme(
    primary = TacticalAmberDay,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF78350F),
    secondary = RadarEmeraldDay,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    error = AlertCrimsonDay,
    onError = Color.White,
    background = DaylightBg,
    onBackground = TextPrimaryLight,
    surface = DaylightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Tactical dark theme by default for bedroom & command-center ergonomics
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) TacticalDarkColorScheme else TacticalLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
