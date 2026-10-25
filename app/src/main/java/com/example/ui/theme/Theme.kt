package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class GlassPalette(
    val isDark: Boolean,
    val baseBackground: Color,
    val bokehOrb1: Color,
    val bokehOrb2: Color,
    val bokehOrb3: Color,
    val bokehOrb4: Color,
    val bokehOrb5: Color,
    val glassSurface: Color,
    val glassSurfaceElevated: Color,
    val glassBorder: Color,
    val glassHighlightStart: Color,
    val glassHighlightEnd: Color,
    val buttonGradientStart: Color,
    val buttonGradientEnd: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentGlow: Color,
    val secondaryGlow: Color
)

val DarkGlassPalette = GlassPalette(
    isDark = true,
    baseBackground = Color(0xFF050814),
    bokehOrb1 = Color(0xFF00F0FF).copy(alpha = 0.86f), // Electric Cyan
    bokehOrb2 = Color(0xFFFF1E8E).copy(alpha = 0.82f), // Hyper Magenta
    bokehOrb3 = Color(0xFF8B3DFF).copy(alpha = 0.82f), // Ultraviolet Indigo
    bokehOrb4 = Color(0xFF00F5A0).copy(alpha = 0.78f), // Neon Emerald Aqua
    bokehOrb5 = Color(0xFFFF8A00).copy(alpha = 0.78f), // Solar Amber Coral
    glassSurface = Color(0xFF101930).copy(alpha = 0.38f),
    glassSurfaceElevated = Color(0xFF182442).copy(alpha = 0.50f),
    glassBorder = Color.White.copy(alpha = 0.38f),
    glassHighlightStart = Color.White.copy(alpha = 0.28f),
    glassHighlightEnd = Color.White.copy(alpha = 0.05f),
    buttonGradientStart = Color(0xFF00E0FF).copy(alpha = 0.62f),
    buttonGradientEnd = Color(0xFF9333EA).copy(alpha = 0.56f),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFD8E4F8),
    accentGlow = Color(0xFF38BDF8),
    secondaryGlow = Color(0xFFFF4791)
)

val LightGlassPalette = GlassPalette(
    isDark = false,
    baseBackground = Color(0xFFEEF4FF),
    bokehOrb1 = Color(0xFF00C6FF).copy(alpha = 0.84f), // Vibrant Sky Cyan
    bokehOrb2 = Color(0xFFFF4791).copy(alpha = 0.80f), // Vibrant Rose Pink
    bokehOrb3 = Color(0xFF8E54E9).copy(alpha = 0.80f), // Vibrant Violet
    bokehOrb4 = Color(0xFF00E1B4).copy(alpha = 0.76f), // Vibrant Mint Aqua
    bokehOrb5 = Color(0xFFFF9F1C).copy(alpha = 0.76f), // Vibrant Solar Amber
    glassSurface = Color.White.copy(alpha = 0.44f),
    glassSurfaceElevated = Color.White.copy(alpha = 0.64f),
    glassBorder = Color.White.copy(alpha = 0.92f),
    glassHighlightStart = Color.White.copy(alpha = 0.84f),
    glassHighlightEnd = Color.White.copy(alpha = 0.32f),
    buttonGradientStart = Color(0xFF2563EB).copy(alpha = 0.88f),
    buttonGradientEnd = Color(0xFF7C3AED).copy(alpha = 0.82f),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF334155),
    accentGlow = Color(0xFF2563EB),
    secondaryGlow = Color(0xFFDB2777)
)

val LocalGlassPalette = staticCompositionLocalOf { DarkGlassPalette }

private val DarkColorScheme = darkColorScheme(
    primary = CobaltPrimary,
    onPrimary = CobaltOnPrimary,
    primaryContainer = CobaltPrimaryContainer,
    onPrimaryContainer = CobaltOnPrimaryContainer,
    secondary = EmeraldSecondary,
    onSecondary = EmeraldOnSecondary,
    secondaryContainer = EmeraldSecondaryContainer,
    onSecondaryContainer = EmeraldOnSecondaryContainer,
    tertiary = AmberTertiary,
    onTertiary = AmberOnTertiary,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = LightCobaltPrimary,
    onPrimary = LightCobaltOnPrimary,
    primaryContainer = LightCobaltPrimaryContainer,
    onPrimaryContainer = LightCobaltOnPrimaryContainer,
    secondary = LightEmeraldSecondary,
    onSecondary = LightEmeraldOnSecondary,
    secondaryContainer = LightEmeraldSecondaryContainer,
    onSecondaryContainer = LightEmeraldOnSecondaryContainer,
    tertiary = LightAmberTertiary,
    onTertiary = LightAmberOnTertiary,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline
)

@Composable
fun NoticeMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val glassPalette = if (darkTheme) DarkGlassPalette else LightGlassPalette

    CompositionLocalProvider(LocalGlassPalette provides glassPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
