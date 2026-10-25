package com.example.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.model.NoticeLanguage
import com.example.ui.theme.LocalGlassPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val LIQUID_CAUSTIC_AGSL = """
    uniform float2 resolution;
    uniform float time;
    uniform float2 touch;
    uniform float isDark;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(resolution, float2(1.0, 1.0));
        float2 p = uv * 2.0 - 1.0;
        float distToTouch = length(uv - touch);
        float ripple = sin(distToTouch * 18.0 - time * 3.5) * exp(-distToTouch * 3.2) * 0.12;

        float wave1 = sin(p.x * 2.6 + time * 1.1 + ripple) * cos(p.y * 2.2 - time * 0.9);
        float wave2 = cos(p.x * 1.9 - time * 0.8) * sin(p.y * 2.8 + time * 1.3 + ripple);
        float blend = clamp((wave1 + wave2) * 0.25 + 0.5, 0.0, 1.0);

        half3 c1 = isDark > 0.5 ? half3(0.0, 0.95, 1.0) : half3(0.0, 0.78, 1.0);
        half3 c2 = isDark > 0.5 ? half3(1.0, 0.12, 0.58) : half3(1.0, 0.28, 0.62);
        half3 c3 = isDark > 0.5 ? half3(0.55, 0.24, 1.0) : half3(0.56, 0.33, 0.96);

        half3 col = mix(mix(c1, c2, half(blend)), c3, half(abs(wave1)));
        half alpha = isDark > 0.5 ? half(0.28) : half(0.25);
        return half4(col * alpha, alpha);
    }
"""

/**
 * Dynamic Moving Color Background + Liquid Glass Caustic Shader:
 * Continuously animates 5 super-vibrant multi-color aurora orbs along organic Lissajous curves
 * using 3-stop luminous core+halo gradients and responds to touch with a fluid light ripple so
 * foreground Liquid Glass surfaces constantly refract living, moving colors.
 */
@Composable
fun BokehGlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalGlassPalette.current
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_moving_colors")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_phase"
    )

    val secondaryPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondary_phase"
    )

    var normalizedTouch by remember { mutableStateOf(Offset(0.5f, 0.35f)) }

    val agslShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(LIQUID_CAUSTIC_AGSL) }.getOrNull()
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(glass.baseBackground)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pos = event.changes.firstOrNull()?.position
                        if (pos != null && size.width > 0 && size.height > 0) {
                            normalizedTouch = Offset(
                                x = (pos.x / size.width.toFloat()).coerceIn(0f, 1f),
                                y = (pos.y / size.height.toFloat()).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minDim = minOf(w, h).coerceAtLeast(120f)

            // Orb 1: Electric Cyan / Sky Blue — sweeping top-left to center-right
            val orb1Center = Offset(
                x = w * (0.22f + 0.25f * sin(phase)),
                y = h * (0.18f + 0.16f * cos(phase * 1.2f))
            )
            val orb1Radius = minDim * 0.88f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb1,
                        glass.bokehOrb1.copy(alpha = glass.bokehOrb1.alpha * 0.55f),
                        Color.Transparent
                    ),
                    center = orb1Center,
                    radius = orb1Radius
                ),
                radius = orb1Radius,
                center = orb1Center
            )

            // Orb 2: Hyper Magenta / Rose — drifting top-right to center-left
            val orb2Center = Offset(
                x = w * (0.78f + 0.23f * cos(phase + 1.1f)),
                y = h * (0.28f + 0.20f * sin(secondaryPhase))
            )
            val orb2Radius = minDim * 0.84f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb2,
                        glass.bokehOrb2.copy(alpha = glass.bokehOrb2.alpha * 0.55f),
                        Color.Transparent
                    ),
                    center = orb2Center,
                    radius = orb2Radius
                ),
                radius = orb2Radius,
                center = orb2Center
            )

            // Orb 3: Ultraviolet Indigo — figure-eight center flow
            val orb3Center = Offset(
                x = w * (0.30f + 0.27f * sin(secondaryPhase + 0.8f)),
                y = h * (0.56f + 0.20f * cos(phase))
            )
            val orb3Radius = minDim * 0.92f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb3,
                        glass.bokehOrb3.copy(alpha = glass.bokehOrb3.alpha * 0.56f),
                        Color.Transparent
                    ),
                    center = orb3Center,
                    radius = orb3Radius
                ),
                radius = orb3Radius,
                center = orb3Center
            )

            // Orb 4: Neon Emerald Aqua — lower-right tide
            val orb4Center = Offset(
                x = w * (0.74f + 0.24f * sin(phase * 0.9f + 2.4f)),
                y = h * (0.78f + 0.16f * cos(secondaryPhase))
            )
            val orb4Radius = minDim * 0.86f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb4,
                        glass.bokehOrb4.copy(alpha = glass.bokehOrb4.alpha * 0.54f),
                        Color.Transparent
                    ),
                    center = orb4Center,
                    radius = orb4Radius
                ),
                radius = orb4Radius,
                center = orb4Center
            )

            // Orb 5: Solar Amber Coral — touch-attracted & orbiting accent orb
            val touchTargetX = w * normalizedTouch.x
            val touchTargetY = h * normalizedTouch.y
            val orb5Center = Offset(
                x = (w * (0.50f + 0.26f * cos(secondaryPhase))) * 0.62f + touchTargetX * 0.38f,
                y = (h * (0.44f + 0.22f * sin(phase))) * 0.62f + touchTargetY * 0.38f
            )
            val orb5Radius = minDim * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glass.bokehOrb5,
                        glass.bokehOrb5.copy(alpha = glass.bokehOrb5.alpha * 0.55f),
                        Color.Transparent
                    ),
                    center = orb5Center,
                    radius = orb5Radius
                ),
                radius = orb5Radius,
                center = orb5Center
            )

            // Optional Android 13+ AGSL Liquid Caustic Shader overlay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && agslShader != null) {
                runCatching {
                    agslShader.setFloatUniform("resolution", w, h)
                    agslShader.setFloatUniform("time", phase)
                    agslShader.setFloatUniform("touch", normalizedTouch.x, normalizedTouch.y)
                    agslShader.setFloatUniform("isDark", if (glass.isDark) 1f else 0f)
                    drawRect(brush = ShaderBrush(agslShader))
                }
            }
        }

        content()
    }
}

/**
 * Liquid Glass / Dynamic Glass Card:
 * Features physics-driven touch compression, dynamic shifting specular rim reflection,
 * chromatic edge dispersion, and translucent frosted depth over the moving background colors.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    elevated: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalGlassPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val baseFill = if (elevated) glass.glassSurfaceElevated else glass.glassSurface

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "liquid_card_scale"
    )

    val shimmerTransition = rememberInfiniteTransition(label = "card_rim_shimmer")
    val rimAngle by shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rim_angle"
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
                elevation = if (elevated) 16.dp else 10.dp,
                shape = shape,
                ambientColor = glass.accentGlow.copy(alpha = 0.25f),
                spotColor = glass.secondaryGlow.copy(alpha = 0.28f)
            )
            .clip(shape)
            .background(baseFill)
            .drawWithContent {
                val w = size.width
                val h = size.height
                val shiftX = w * (0.5f + 0.45f * cos(rimAngle))
                val shiftY = h * (0.5f + 0.45f * sin(rimAngle))

                // 1. Liquid Glass interior specular refraction sheen
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            glass.glassHighlightStart,
                            glass.accentGlow.copy(alpha = if (isPressed) 0.20f else 0.08f),
                            glass.glassHighlightEnd
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                )

                // 2. Top-edge liquid meniscus highlight (Apple/One UI liquid glass signature)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (glass.isDark) 0.35f else 0.75f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = h * 0.28f
                    ),
                    cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                )

                drawContent()

                // 3. Prismatic moving liquid border
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            glass.glassBorder,
                            glass.accentGlow.copy(alpha = 0.65f),
                            glass.secondaryGlow.copy(alpha = 0.50f),
                            glass.glassBorder.copy(alpha = 0.25f)
                        ),
                        start = Offset(shiftX * 0.3f, 0f),
                        end = Offset(w, h - shiftY * 0.3f)
                    ),
                    cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
            .then(clickableModifier)
            .padding(contentPadding),
        content = content
    )
}

/**
 * Liquid Glass Interactive Button with spring-physics press bounce and refractive gradient surface.
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
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "liquid_button_scale"
    )

    val bgBrush = if (isPrimary) {
        Brush.horizontalGradient(
            colors = listOf(
                glass.buttonGradientStart,
                glass.buttonGradientEnd
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                glass.glassHighlightStart,
                glass.glassHighlightEnd
            )
        )
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isPrimary) 10.dp else 4.dp,
                shape = shape,
                ambientColor = glass.accentGlow.copy(alpha = 0.22f),
                spotColor = glass.secondaryGlow.copy(alpha = 0.24f)
            )
            .clip(shape)
            .background(if (isPrimary) glass.glassSurfaceElevated else glass.glassSurface)
            .background(bgBrush)
            .border(
                width = 1.3.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        if (enabled) glass.glassBorder else glass.glassBorder.copy(alpha = 0.2f),
                        glass.accentGlow.copy(alpha = if (isPrimary) 0.65f else 0.35f)
                    )
                ),
                shape = shape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPrimary && !glass.isDark) Color.White else glass.textPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPrimary && !glass.isDark) Color.White else glass.textPrimary
        )
    }
}

/**
 * Glossy, refractive Liquid Glass Orb / Icon Badge with caustic inner highlight.
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
        targetValue = if (isPressed && onClick != null) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "icon_orb_scale"
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

    val orbGlow = accentTint ?: glass.accentGlow

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                ambientColor = orbGlow.copy(alpha = 0.30f),
                spotColor = orbGlow.copy(alpha = 0.35f)
            )
            .clip(CircleShape)
            .background(glass.glassSurfaceElevated)
            .drawBehind {
                // Caustic lens glow inside the icon orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (glass.isDark) 0.36f else 0.80f),
                            orbGlow.copy(alpha = 0.24f),
                            Color.Transparent
                        ),
                        center = Offset(this.size.width * 0.32f, this.size.height * 0.28f),
                        radius = this.size.width * 0.75f
                    )
                )
            }
            .border(
                width = 1.3.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.75f),
                        orbGlow.copy(alpha = 0.55f),
                        glass.glassBorder.copy(alpha = 0.25f)
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
            tint = glass.textPrimary,
            modifier = Modifier.size(size * 0.48f)
        )
    }
}

/**
 * Fluid Liquid Glass Language Switcher Pill (`English` | `हिंदी`) for the Notice Result screen.
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
            .background(glass.glassSurface)
            .border(
                width = 1.2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        glass.glassBorder,
                        glass.accentGlow.copy(alpha = 0.55f),
                        glass.secondaryGlow.copy(alpha = 0.55f)
                    )
                ),
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
                targetValue = if (isSelected) glass.accentGlow.copy(alpha = if (glass.isDark) 0.42f else 0.85f) else Color.Transparent,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "lang_bg_${lang.name}"
            )
            val textColor = if (isSelected && !glass.isDark) Color.White else glass.textPrimary
            val tag = if (lang == NoticeLanguage.ENGLISH) "language_toggle_en" else "language_toggle_hi"

            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(bgColor)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), shape)
                        } else {
                            Modifier
                        }
                    )
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
 * Glassmorphic modal dialogue suspended over the foreground.
 */
@Composable
fun GlassModalDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        GlassCard(
            elevated = true,
            cornerRadius = 26.dp,
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}
