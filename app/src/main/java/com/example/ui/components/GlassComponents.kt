package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.model.NoticeLanguage
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.LocalNoticeBuddyRadius
import com.example.ui.theme.LocalNoticeBuddySpacing

/**
 * NoticeBuddy Soft Sky-Blue & Pastel Gradient Background:
 * Renders a calm, high-performance multi-stop vertical sky-blue to pastel gradient
 * layered with soft radial washes of sky blue, pastel pink, lavender, and mint teal.
 * Avoids continuous infinite-loop animations or heavy blur shaders for optimal readability and frame rate.
 */
@Composable
fun BokehGlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalGlassPalette.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glass.gradientTop,
                        glass.gradientUpperMid,
                        glass.gradientLowerMid,
                        glass.gradientBottom
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minDim = minOf(w, h).coerceAtLeast(160f)

            // Soft Sky-Blue top-left ambient wash
            val skyCenter = Offset(x = w * 0.18f, y = h * 0.14f)
            val skyRadius = minDim * 0.90f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb1,
                        glass.bokehOrb1.copy(alpha = glass.bokehOrb1.alpha * 0.45f),
                        Color.Transparent
                    ),
                    center = skyCenter,
                    radius = skyRadius
                ),
                radius = skyRadius,
                center = skyCenter
            )

            // Subtle Pastel Pink upper-right ambient wash
            val pinkCenter = Offset(x = w * 0.85f, y = h * 0.24f)
            val pinkRadius = minDim * 0.80f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb2,
                        glass.bokehOrb2.copy(alpha = glass.bokehOrb2.alpha * 0.40f),
                        Color.Transparent
                    ),
                    center = pinkCenter,
                    radius = pinkRadius
                ),
                radius = pinkRadius,
                center = pinkCenter
            )

            // Subtle Lavender center-left ambient wash
            val lavenderCenter = Offset(x = w * 0.22f, y = h * 0.58f)
            val lavenderRadius = minDim * 0.85f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb3,
                        glass.bokehOrb3.copy(alpha = glass.bokehOrb3.alpha * 0.42f),
                        Color.Transparent
                    ),
                    center = lavenderCenter,
                    radius = lavenderRadius
                ),
                radius = lavenderRadius,
                center = lavenderCenter
            )

            // Subtle Teal/Mint lower-right ambient wash
            val tealCenter = Offset(x = w * 0.82f, y = h * 0.80f)
            val tealRadius = minDim * 0.82f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb4,
                        glass.bokehOrb4.copy(alpha = glass.bokehOrb4.alpha * 0.40f),
                        Color.Transparent
                    ),
                    center = tealCenter,
                    radius = tealRadius
                ),
                radius = tealRadius,
                center = tealCenter
            )
        }

        content()
    }
}

/**
 * NoticeBuddy White & Translucent Glassmorphism Card:
 * Provides crisp white translucent surface depth, restrained shadows, delicate 1.dp borders,
 * high text contrast, and gentle touch feedback when interactive.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LocalNoticeBuddyRadius.current.card,
    elevated: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(LocalNoticeBuddySpacing.current.cardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalGlassPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val baseFill = if (elevated) glass.glassSurfaceElevated else glass.glassSurface

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "noticebuddy_card_scale"
    )

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (elevated) 6.dp else 3.dp,
                shape = shape,
                ambientColor = glass.shadowAmbient,
                spotColor = glass.shadowSpot
            )
            .clip(shape)
            .background(baseFill)
            .drawWithContent {
                val w = size.width
                val h = size.height

                // Subtle white glassmorphic surface gradient
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            glass.glassHighlightStart,
                            glass.glassHighlightEnd
                        ),
                        startY = 0f,
                        endY = h
                    )
                )

                drawContent()

                // Delicate 1.dp white + soft sky-blue border
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            glass.glassBorder,
                            glass.glassInnerBorder,
                            glass.glassBorder.copy(alpha = 0.85f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            .then(clickableModifier)
            .padding(contentPadding),
        content = content
    )
}

/**
 * NoticeBuddy Standardized Button:
 * - Primary (`isPrimary = true`): Electric-blue gradient fill (`#1A6BFF` -> `#1554D1`) with white text.
 * - Secondary (`isPrimary = false`): White translucent glassmorphism fill with deep slate text and delicate border.
 * Enforces a minimum 48.dp touch target height for accessibility.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isPrimary: Boolean = true
) {
    val glass = LocalGlassPalette.current
    val radius = LocalNoticeBuddyRadius.current.button
    val shape = RoundedCornerShape(radius)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "noticebuddy_button_scale"
    )

    val bgBrush = if (isPrimary) {
        Brush.horizontalGradient(
            colors = if (enabled) {
                listOf(glass.buttonGradientStart, glass.buttonGradientEnd)
            } else {
                listOf(
                    glass.buttonGradientStart.copy(alpha = 0.45f),
                    glass.buttonGradientEnd.copy(alpha = 0.45f)
                )
            }
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                glass.glassHighlightStart,
                glass.glassSurfaceElevated
            )
        )
    }

    val borderBrush = if (isPrimary) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.35f),
                glass.buttonGradientStart.copy(alpha = 0.85f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                glass.glassBorder,
                glass.glassInnerBorder
            )
        )
    }

    val contentColor = if (isPrimary) {
        Color.White.copy(alpha = if (enabled) 1f else 0.75f)
    } else {
        glass.textPrimary.copy(alpha = if (enabled) 1f else 0.5f)
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isPrimary && enabled) 5.dp else 2.dp,
                shape = shape,
                ambientColor = glass.shadowAmbient,
                spotColor = glass.shadowSpot
            )
            .clip(shape)
            .background(bgBrush)
            .border(
                width = 1.dp,
                brush = borderBrush,
                shape = shape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

/**
 * NoticeBuddy Translucent Glass Icon Badge:
 * Renders a soft pastel/tinted circular or rounded-square icon container with a delicate border.
 */
@Composable
fun GlassIconBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    accentTint: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val glass = LocalGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "noticebuddy_icon_badge_scale"
    )

    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }

    val resolvedAccent = accentTint ?: glass.accentGlow

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 2.dp,
                shape = CircleShape,
                ambientColor = glass.shadowAmbient,
                spotColor = resolvedAccent.copy(alpha = 0.14f)
            )
            .clip(CircleShape)
            .background(glass.glassSurfaceElevated)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            resolvedAccent.copy(alpha = 0.16f),
                            resolvedAccent.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = Offset(this.size.width * 0.5f, this.size.height * 0.5f),
                        radius = this.size.width * 0.65f
                    )
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        glass.glassBorder,
                        resolvedAccent.copy(alpha = 0.32f)
                    )
                ),
                shape = CircleShape
            )
            .then(clickMod),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedAccent,
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

/**
 * Reusable NoticeBuddy Pastel Accent Pill for category tags, feature badges, and status chips.
 */
@Composable
fun NoticeBuddyAccentPill(
    text: String,
    accentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    leadingDotColor: Color? = null
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor.copy(alpha = 0.85f))
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.25f),
                shape = shape
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (leadingDotColor != null) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(leadingDotColor)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accentColor
        )
    }
}

/**
 * Reusable NoticeBuddy Action Card:
 * Standardized glassmorphic feature card with a pastel/electric-blue icon badge,
 * optional accent pill, clear title/subtitle hierarchy, and comfortable touch target.
 */
@Composable
fun NoticeBuddyActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    badgeText: String? = null,
    badgeBackgroundColor: Color = accentColor.copy(alpha = 0.12f),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassPalette.current
    val spacing = LocalNoticeBuddySpacing.current
    val radius = LocalNoticeBuddyRadius.current

    GlassCard(
        elevated = true,
        cornerRadius = radius.card,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = spacing.cardPadding, vertical = 16.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlassIconBadge(
                icon = icon,
                contentDescription = title,
                size = 50.dp,
                accentTint = accentColor
            )
            Column(modifier = Modifier.weight(1f)) {
                if (!badgeText.isNullOrBlank()) {
                    NoticeBuddyAccentPill(
                        text = badgeText,
                        accentColor = accentColor,
                        backgroundColor = badgeBackgroundColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Glassmorphic Language Switcher Pill (`English` | `हिंदी`) with Electric-Blue active state.
 */
@Composable
fun LiquidLanguageTogglePill(
    selectedLanguage: NoticeLanguage,
    onSelectLanguage: (NoticeLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassPalette.current
    val shape = RoundedCornerShape(50)

    Row(
        modifier = modifier
            .clip(shape)
            .background(glass.glassSurfaceElevated)
            .border(
                width = 1.dp,
                color = glass.glassInnerBorder,
                shape = shape
            )
            .padding(4.dp)
            .testTag("language_toggle_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NoticeLanguage.entries.forEach { lang ->
            val isSelected = selectedLanguage == lang
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) glass.buttonGradientStart else Color.Transparent,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "lang_bg_${lang.name}"
            )
            val textColor = if (isSelected) Color.White else glass.textPrimary
            val tag = if (lang == NoticeLanguage.ENGLISH) "language_toggle_en" else "language_toggle_hi"

            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(bgColor)
                    .clickable { onSelectLanguage(lang) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(tag),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lang.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Glassmorphic modal dialog using NoticeBuddy's standardized modal corner radius and surface tokens.
 */
@Composable
fun GlassModalDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val radius = LocalNoticeBuddyRadius.current
    Dialog(onDismissRequest = onDismissRequest) {
        GlassCard(
            elevated = true,
            cornerRadius = radius.modal,
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}
