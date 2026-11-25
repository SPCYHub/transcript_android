package com.example.ui.components

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.LocalStudioHaptics
import com.example.model.ApiKeyStatus
import com.example.model.RoutingMode
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun rememberReduceMotionEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        scale == 0f || !ValueAnimator.areAnimatorsEnabled()
    }
}

fun Modifier.studioGridBackground(
    dotColor: Color,
    primaryGlow: Color,
    tertiaryGlow: Color
): Modifier = this.drawBehind {
    // Dual ambient studio acoustic glow (top-right jade + subtle bottom-left amber warmth)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(primaryGlow.copy(alpha = 0.14f), Color.Transparent),
            center = Offset(size.width * 0.88f, size.height * 0.06f),
            radius = size.maxDimension * 0.56f
        )
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(tertiaryGlow.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(size.width * 0.12f, size.height * 0.82f),
            radius = size.maxDimension * 0.45f
        )
    )

    // Precision architectural micro-dot matrix
    val step = 28.dp.toPx()
    val radius = 0.9.dp.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(
                color = dotColor.copy(alpha = 0.18f),
                radius = radius,
                center = Offset(x, y)
            )
            x += step
        }
        y += step
    }
}

/**
 * Calm ambient studio acoustic emblem rendered in the Studio idle canvas.
 */
@Composable
fun AmbientAcousticEmblem(
    modifier: Modifier = Modifier
) {
    val reduceMotion = rememberReduceMotionEnabled()
    val phase = if (reduceMotion) {
        0.9f
    } else {
        val transition = rememberInfiniteTransition(label = "ambient_acoustic_rings")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600),
                repeatMode = RepeatMode.Restart
            ),
            label = "ambient_phase"
        )
        animatedPhase
    }

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val outline = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .size(116.dp)
            .clearAndSetSemantics { }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension * 0.48f
        val midRadius = size.minDimension * 0.36f
        val innerRadius = size.minDimension * 0.25f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.22f), Color.Transparent),
                center = center,
                radius = outerRadius
            ),
            radius = outerRadius,
            center = center
        )
        drawCircle(
            color = outline.copy(alpha = 0.26f),
            radius = outerRadius * 0.96f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = primary.copy(alpha = 0.22f),
            radius = midRadius,
            center = center,
            style = Stroke(width = 1.25.dp.toPx())
        )
        drawCircle(
            color = primaryContainer.copy(alpha = 0.82f),
            radius = innerRadius,
            center = center
        )

        // Subtle breathing 7-bar acoustic waveform inside the studio emblem
        val barCount = 7
        val barWidth = 3.2.dp.toPx()
        val gap = 3.5.dp.toPx()
        val totalW = barCount * barWidth + (barCount - 1) * gap
        val startX = center.x - totalW / 2f
        val maxHalf = innerRadius * 0.58f

        for (i in 0 until barCount) {
            val envelope = 1f - abs(i - 3) / 4.2f
            val wave = if (reduceMotion) 0.78f else 0.45f + 0.55f * ((sin(phase + i * 0.7f) + 1f) / 2f)
            val halfH = (maxHalf * envelope * wave).coerceAtLeast(3.dp.toPx())
            val x = startX + i * (barWidth + gap)
            drawRoundRect(
                color = primary,
                topLeft = Offset(x, center.y - halfH),
                size = Size(barWidth, halfH * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun LiveWaveformVisualizer(
    amplitudes: List<Float>,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val reduceMotion = rememberReduceMotionEnabled()
    val activeColor = if (isRecording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val softActiveColor = activeColor.copy(alpha = 0.42f)
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.26f)
    val targetBars = remember(amplitudes, isRecording) {
        if (amplitudes.isEmpty()) {
            List(40) { 0.1f }
        } else {
            amplitudes.map { it.coerceIn(0.06f, 1f) }
        }
    }

    var renderedBars by remember { mutableStateOf(targetBars) }

    LaunchedEffect(targetBars, reduceMotion) {
        if (reduceMotion) {
            renderedBars = targetBars
            return@LaunchedEffect
        }
        // Smoothly interpolate toward targetBars over short frame steps so transitions/decays fluidly settle
        val stepCount = if (isRecording) 3 else 6
        val current = if (renderedBars.size == targetBars.size) {
            renderedBars.toMutableList()
        } else {
            MutableList(targetBars.size) { idx ->
                renderedBars.getOrElse(idx) { targetBars[idx] }
            }
        }
        repeat(stepCount) {
            var maxDelta = 0f
            for (i in targetBars.indices) {
                val next = current[i] * 0.45f + targetBars[i] * 0.55f
                val delta = abs(targetBars[i] - next)
                if (delta > maxDelta) maxDelta = delta
                current[i] = next
            }
            renderedBars = current.toList()
            if (maxDelta < 0.008f) {
                renderedBars = targetBars
                return@LaunchedEffect
            }
            delay(20L)
        }
        renderedBars = targetBars
    }

    val bars = renderedBars
    val semanticLabel = if (isRecording) "Live voice waveform active" else "Audio waveform idle"

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .semantics {
                contentDescription = semanticLabel
            }
    ) {
        val count = bars.size.coerceAtLeast(1)
        val gap = 3.5.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2.2.dp.toPx())
        val centerY = size.height / 2f
        val maxHalfHeight = (size.height * 0.46f).coerceAtLeast(4f)

        // Subtle center acoustic baseline
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx()
        )

        bars.forEachIndexed { index, rawLevel ->
            val clamped = rawLevel.coerceIn(0.06f, 1f)
            val halfHeight = (clamped * maxHalfHeight).coerceAtLeast(3.dp.toPx())
            val x = index * (barWidth + gap)
            val color = when {
                isRecording && clamped > 0.18f -> activeColor
                isRecording -> softActiveColor
                else -> trackColor
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - halfHeight),
                size = Size(barWidth, halfHeight * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun EngineSelectorPillBar(
    selectedMode: RoutingMode,
    apiKeyStatus: ApiKeyStatus,
    onSelectMode: (RoutingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalStudioHaptics.current
    val reduceMotion = rememberReduceMotionEnabled()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoutingMode.entries.forEach { mode ->
                val isSelected = mode == selectedMode
                val containerColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessMedium),
                    label = "engine_pill_bg"
                )
                val labelColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = if (reduceMotion) snap() else tween(durationMillis = 150),
                    label = "engine_pill_fg"
                )

                val indicatorColor: Color? = when (mode) {
                    RoutingMode.GEMINI_ONLY -> if (apiKeyStatus.isUsingDirectGemini) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                    RoutingMode.OPENAI_ONLY -> if (apiKeyStatus.isOpenAiConfigured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                    RoutingMode.AUTO -> null
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = containerColor,
                    shadowElevation = if (isSelected) 1.dp else 0.dp,
                    border = if (isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    } else null,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }
                        .clickable {
                            if (!isSelected) haptics.tick()
                            onSelectMode(mode)
                        }
                        .testTag("routing_mode_${mode.name.lowercase()}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        if (indicatorColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(indicatorColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = labelColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedLevelBar(
    level: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val reduceMotion = rememberReduceMotionEnabled()
    val animated by animateFloatAsState(
        targetValue = if (isActive) level.coerceIn(0.06f, 1f) else 0.06f,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "level_meter"
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val fill = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .semantics {
                contentDescription = if (isActive) "Live audio input level" else "Audio input level idle"
            }
    ) {
        drawRect(color = track)
        drawRect(
            color = fill,
            size = Size(width = size.width * animated, height = size.height)
        )
    }
}

fun formatDurationMs(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (millis % 1000L) / 100L
    return if (minutes > 0L) {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
    }
}

fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val kb = bytes / 1024.0
    return if (kb < 1024.0) {
        String.format(Locale.US, "%.0f KB", kb)
    } else {
        String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }
}
