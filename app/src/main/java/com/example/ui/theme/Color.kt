package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// NoticeBuddy Global Visual Design System — Color Tokens
// Direction: Soft sky-blue & pastel gradients, white & translucent glassmorphism,
// electric-blue primary actions, subtle lavender, pink, and teal accents.
// ============================================================================

// 1. Soft Sky-Blue & Pastel Gradient Background Tokens
val SkyPastelTop = Color(0xFFDFF0FF)        // Soft Sky Blue
val SkyPastelUpperMid = Color(0xFFEAF4FF)   // Airy Ice Sky
val SkyPastelLowerMid = Color(0xFFF3EEFF)   // Subtle Pastel Lavender Mist
val SkyPastelBottom = Color(0xFFFFF4F8)     // Delicate Pastel Blush
val SkyPastelTealWash = Color(0xFFE6FAF8)   // Soft Aqua Mint Wash

// Alternate Daylight Frost Background Tokens
val DaylightSkyTop = Color(0xFFE6F3FF)
val DaylightSkyMid = Color(0xFFF2F6FF)
val DaylightSkyBottom = Color(0xFFF9F5FF)

// 2. White & Translucent Glassmorphism Surface Tokens
val GlassWhiteSurface = Color(0xFFFFFFFF).copy(alpha = 0.78f)
val GlassWhiteSurfaceElevated = Color(0xFFFFFFFF).copy(alpha = 0.90f)
val GlassWhiteSurfaceSubtle = Color(0xFFFFFFFF).copy(alpha = 0.62f)
val GlassWhiteHighlightTop = Color(0xFFFFFFFF).copy(alpha = 0.92f)
val GlassWhiteHighlightBottom = Color(0xFFF8FAFF).copy(alpha = 0.54f)
val GlassDelicateBorder = Color(0xFFFFFFFF).copy(alpha = 0.95f)
val GlassSubtleInnerBorder = Color(0xFFBFDBFE).copy(alpha = 0.55f)

// 3. Electric-Blue Primary Action Tokens
val ElectricBluePrimary = Color(0xFF1A6BFF)
val ElectricBluePrimaryDark = Color(0xFF1554D1)
val ElectricBluePrimaryLight = Color(0xFF3B82F6)
val ElectricBlueContainer = Color(0xFFDBEAFE)
val OnElectricBluePrimary = Color(0xFFFFFFFF)
val OnElectricBlueContainer = Color(0xFF1E3A8A)

// 4. Subtle Lavender, Pink, and Teal Accent Tokens
val PastelLavenderAccent = Color(0xFF7C3AED)
val PastelLavenderSoft = Color(0xFFEDE9FE)
val PastelLavenderBorder = Color(0xFFC4B5FD)

val PastelPinkAccent = Color(0xFFEC4899)
val PastelPinkSoft = Color(0xFFFCE7F3)
val PastelPinkBorder = Color(0xFFF9A8D4)

val PastelTealAccent = Color(0xFF0D9488)
val PastelTealSoft = Color(0xFFCCFBF1)
val PastelTealBorder = Color(0xFF99F6E4)

// 5. Accessible High-Contrast Text & Outline Tokens
val InkSlatePrimary = Color(0xFF0F172A)     // Deep Slate Navy (14:1 contrast on white glass)
val InkSlateSecondary = Color(0xFF475569)   // Balanced Slate (7:1 contrast on white glass)
val InkSlateMuted = Color(0xFF64748B)       // Muted Caption Slate
val DelicateOutline = Color(0xFFCBD5E1)     // Restrained Divider & Input Border

// 6. Semantic Status Tokens
val StatusWarningAmber = Color(0xFFD97706)
val StatusWarningContainer = Color(0xFFFEF3C7)
val StatusErrorCoral = Color(0xFFDC2626)
val StatusErrorContainer = Color(0xFFFEE2E2)
val StatusSuccessTeal = Color(0xFF059669)
val StatusSuccessContainer = Color(0xFFD1FAE5)

// 7. Restrained Elevation Shadow Tokens
val RestrainedShadowAmbient = Color(0xFF1E3A8A).copy(alpha = 0.08f)
val RestrainedShadowSpot = Color(0xFF1A6BFF).copy(alpha = 0.12f)

// Backwards-compatible aliases used in existing Material 3 ColorScheme mappings
val CobaltPrimary = ElectricBluePrimary
val CobaltOnPrimary = OnElectricBluePrimary
val CobaltPrimaryContainer = ElectricBlueContainer
val CobaltOnPrimaryContainer = OnElectricBlueContainer

val EmeraldSecondary = PastelTealAccent
val EmeraldOnSecondary = Color(0xFFFFFFFF)
val EmeraldSecondaryContainer = PastelTealSoft
val EmeraldOnSecondaryContainer = Color(0xFF042F2E)

val AmberTertiary = PastelPinkAccent
val AmberOnTertiary = Color(0xFFFFFFFF)
val AmberTertiaryContainer = PastelPinkSoft
val AmberOnTertiaryContainer = Color(0xFF831843)

val LightCobaltPrimary = ElectricBluePrimary
val LightCobaltOnPrimary = OnElectricBluePrimary
val LightCobaltPrimaryContainer = ElectricBlueContainer
val LightCobaltOnPrimaryContainer = OnElectricBlueContainer

val LightEmeraldSecondary = PastelTealAccent
val LightEmeraldOnSecondary = Color(0xFFFFFFFF)
val LightEmeraldSecondaryContainer = PastelTealSoft
val LightEmeraldOnSecondaryContainer = Color(0xFF042F2E)

val LightAmberTertiary = PastelPinkAccent
val LightAmberOnTertiary = Color(0xFFFFFFFF)
val LightAmberTertiaryContainer = PastelPinkSoft
val LightAmberOnTertiaryContainer = Color(0xFF831843)

val LightBackground = SkyPastelUpperMid
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF4FF)
val LightOnSurface = InkSlatePrimary
val LightOnSurfaceVariant = InkSlateSecondary
val LightOutline = DelicateOutline

val DarkBackground = SkyPastelTop
val DarkSurface = Color(0xFFFFFFFF)
val DarkSurfaceVariant = Color(0xFFEEF4FF)
val DarkOnSurface = InkSlatePrimary
val DarkOnSurfaceVariant = InkSlateSecondary
val DarkOutline = DelicateOutline
