package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standardized NoticeBuddy spacing & touch-target tokens.
 */
@Immutable
data class NoticeBuddySpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val screenHorizontal: Dp = 20.dp,
    val screenVertical: Dp = 14.dp,
    val cardPadding: Dp = 18.dp,
    val cardSpacing: Dp = 14.dp,
    val itemGap: Dp = 14.dp,
    val minTouchTarget: Dp = 48.dp
)

/**
 * Standardized NoticeBuddy corner-radius tokens.
 */
@Immutable
data class NoticeBuddyRadius(
    val badge: Dp = 12.dp,
    val smallCard: Dp = 16.dp,
    val innerCard: Dp = 16.dp,
    val button: Dp = 16.dp,
    val card: Dp = 22.dp,
    val heroCard: Dp = 26.dp,
    val modal: Dp = 28.dp
)

val NoticeBuddyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Centralized NoticeBuddy Glassmorphism & Pastel Palette.
 * Preserves all existing properties for full screen compatibility while introducing
 * standardized tokens for soft sky-blue/pastel gradients, crisp white glass surfaces,
 * electric-blue primary actions, and subtle lavender, pink, and teal accents.
 */
@Immutable
data class GlassPalette(
    val isDark: Boolean,
    val baseBackground: Color,
    val gradientTop: Color,
    val gradientUpperMid: Color,
    val gradientLowerMid: Color,
    val gradientBottom: Color,
    val bokehOrb1: Color,
    val bokehOrb2: Color,
    val bokehOrb3: Color,
    val bokehOrb4: Color,
    val bokehOrb5: Color,
    val glassSurface: Color,
    val glassSurfaceElevated: Color,
    val glassSurfaceSubtle: Color,
    val glassBorder: Color,
    val glassInnerBorder: Color,
    val glassHighlightStart: Color,
    val glassHighlightEnd: Color,
    val buttonGradientStart: Color,
    val buttonGradientEnd: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentGlow: Color,
    val secondaryGlow: Color,
    val electricBlue: Color = ElectricBluePrimary,
    val electricBlueSoft: Color = ElectricBlueContainer,
    val lavenderAccent: Color,
    val lavenderSoft: Color,
    val pinkAccent: Color,
    val pinkSoft: Color,
    val tealAccent: Color,
    val tealSoft: Color,
    val shadowAmbient: Color,
    val shadowSpot: Color
)

/**
 * Primary NoticeBuddy Soft Sky & Pastel Breeze Palette (used for AppThemeMode.NIGHT default).
 * Features soft sky-blue and pastel gradient backgrounds, crisp white translucent glass surfaces,
 * electric-blue primary actions, and delicate lavender, pink, and teal accents.
 */
val DarkGlassPalette = GlassPalette(
    isDark = false,
    baseBackground = SkyPastelUpperMid,
    gradientTop = SkyPastelTop,
    gradientUpperMid = SkyPastelUpperMid,
    gradientLowerMid = SkyPastelLowerMid,
    gradientBottom = SkyPastelBottom,
    bokehOrb1 = Color(0xFF93C5FD).copy(alpha = 0.45f), // Soft Sky Blue wash
    bokehOrb2 = Color(0xFFF9A8D4).copy(alpha = 0.36f), // Subtle Pastel Pink wash
    bokehOrb3 = Color(0xFFC4B5FD).copy(alpha = 0.38f), // Subtle Lavender wash
    bokehOrb4 = Color(0xFF99F6E4).copy(alpha = 0.36f), // Subtle Teal Mint wash
    bokehOrb5 = Color(0xFFBAE6FD).copy(alpha = 0.40f), // Airy Ice Blue wash
    glassSurface = GlassWhiteSurface,
    glassSurfaceElevated = GlassWhiteSurfaceElevated,
    glassSurfaceSubtle = GlassWhiteSurfaceSubtle,
    glassBorder = GlassDelicateBorder,
    glassInnerBorder = GlassSubtleInnerBorder,
    glassHighlightStart = GlassWhiteHighlightTop,
    glassHighlightEnd = GlassWhiteHighlightBottom,
    buttonGradientStart = ElectricBluePrimary,
    buttonGradientEnd = ElectricBluePrimaryDark,
    textPrimary = InkSlatePrimary,
    textSecondary = InkSlateSecondary,
    textMuted = InkSlateMuted,
    accentGlow = ElectricBluePrimary,
    secondaryGlow = PastelPinkAccent,
    lavenderAccent = PastelLavenderAccent,
    lavenderSoft = PastelLavenderSoft,
    pinkAccent = PastelPinkAccent,
    pinkSoft = PastelPinkSoft,
    tealAccent = PastelTealAccent,
    tealSoft = PastelTealSoft,
    shadowAmbient = RestrainedShadowAmbient,
    shadowSpot = RestrainedShadowSpot
)

/**
 * Secondary NoticeBuddy Crisp Daylight Frost Palette (used for AppThemeMode.DAY).
 * Shares the same accessible white glassmorphism and electric-blue primary actions
 * with an extra-bright sky-white pastel backdrop.
 */
val LightGlassPalette = GlassPalette(
    isDark = false,
    baseBackground = DaylightSkyMid,
    gradientTop = DaylightSkyTop,
    gradientUpperMid = DaylightSkyMid,
    gradientLowerMid = DaylightSkyBottom,
    gradientBottom = Color(0xFFFFF8FB),
    bokehOrb1 = Color(0xFFBAE6FD).copy(alpha = 0.42f), // Crisp Sky Blue wash
    bokehOrb2 = Color(0xFFFBCFE8).copy(alpha = 0.34f), // Crisp Blush Pink wash
    bokehOrb3 = Color(0xFFDDD6FE).copy(alpha = 0.35f), // Crisp Lavender wash
    bokehOrb4 = Color(0xFFCCFBF1).copy(alpha = 0.36f), // Crisp Teal wash
    bokehOrb5 = Color(0xFFE0F2FE).copy(alpha = 0.40f), // Soft Daylight Cyan wash
    glassSurface = Color.White.copy(alpha = 0.84f),
    glassSurfaceElevated = Color.White.copy(alpha = 0.94f),
    glassSurfaceSubtle = Color.White.copy(alpha = 0.68f),
    glassBorder = Color.White,
    glassInnerBorder = Color(0xFFBFDBFE).copy(alpha = 0.65f),
    glassHighlightStart = Color.White.copy(alpha = 0.96f),
    glassHighlightEnd = Color(0xFFF8FAFF).copy(alpha = 0.65f),
    buttonGradientStart = ElectricBluePrimary,
    buttonGradientEnd = ElectricBluePrimaryDark,
    textPrimary = InkSlatePrimary,
    textSecondary = InkSlateSecondary,
    textMuted = InkSlateMuted,
    accentGlow = ElectricBluePrimary,
    secondaryGlow = PastelPinkAccent,
    lavenderAccent = PastelLavenderAccent,
    lavenderSoft = PastelLavenderSoft,
    pinkAccent = PastelPinkAccent,
    pinkSoft = PastelPinkSoft,
    tealAccent = PastelTealAccent,
    tealSoft = PastelTealSoft,
    shadowAmbient = RestrainedShadowAmbient,
    shadowSpot = RestrainedShadowSpot
)

val LocalGlassPalette = staticCompositionLocalOf { DarkGlassPalette }
val LocalNoticeBuddySpacing = staticCompositionLocalOf { NoticeBuddySpacing() }
val LocalNoticeBuddyRadius = staticCompositionLocalOf { NoticeBuddyRadius() }

private val NoticeBuddyPrimaryColorScheme = lightColorScheme(
    primary = ElectricBluePrimary,
    onPrimary = OnElectricBluePrimary,
    primaryContainer = ElectricBlueContainer,
    onPrimaryContainer = OnElectricBlueContainer,
    secondary = PastelTealAccent,
    onSecondary = Color.White,
    secondaryContainer = PastelTealSoft,
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = PastelLavenderAccent,
    onTertiary = Color.White,
    tertiaryContainer = PastelLavenderSoft,
    onTertiaryContainer = Color(0xFF2E1065),
    background = SkyPastelUpperMid,
    onBackground = InkSlatePrimary,
    surface = Color.White,
    onSurface = InkSlatePrimary,
    surfaceVariant = Color(0xFFEEF4FF),
    onSurfaceVariant = InkSlateSecondary,
    outline = DelicateOutline,
    error = StatusErrorCoral,
    onError = Color.White,
    errorContainer = StatusErrorContainer,
    onErrorContainer = Color(0xFF7F1D1D)
)

private val NoticeBuddyDaylightColorScheme = darkColorScheme(
    primary = ElectricBluePrimary,
    onPrimary = OnElectricBluePrimary,
    primaryContainer = ElectricBlueContainer,
    onPrimaryContainer = OnElectricBlueContainer,
    secondary = PastelTealAccent,
    onSecondary = Color.White,
    secondaryContainer = PastelTealSoft,
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = PastelLavenderAccent,
    onTertiary = Color.White,
    tertiaryContainer = PastelLavenderSoft,
    onTertiaryContainer = Color(0xFF2E1065),
    background = SkyPastelTop,
    onBackground = InkSlatePrimary,
    surface = Color.White,
    onSurface = InkSlatePrimary,
    surfaceVariant = Color(0xFFEEF4FF),
    onSurfaceVariant = InkSlateSecondary,
    outline = DelicateOutline,
    error = StatusErrorCoral,
    onError = Color.White,
    errorContainer = StatusErrorContainer,
    onErrorContainer = Color(0xFF7F1D1D)
)

@Composable
fun NoticeMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NoticeBuddyDaylightColorScheme else NoticeBuddyPrimaryColorScheme
    val glassPalette = if (darkTheme) DarkGlassPalette else LightGlassPalette

    CompositionLocalProvider(
        LocalGlassPalette provides glassPalette,
        LocalNoticeBuddySpacing provides NoticeBuddySpacing(),
        LocalNoticeBuddyRadius provides NoticeBuddyRadius()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = NoticeBuddyShapes,
            content = content
        )
    }
}
