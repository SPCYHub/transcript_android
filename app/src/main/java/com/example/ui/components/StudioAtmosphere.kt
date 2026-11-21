package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.RoutingMode
import java.util.Locale

fun Modifier.studioGridBackground(
    dotColor: Color,
    glowColor: Color
): Modifier = this.drawBehind {
    // Soft radial acoustic glow at top-end for Crisp Audio Studio depth.
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(glowColor.copy(alpha = 0.14f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.08f),
            radius = size.maxDimension * 0.55f
        )
    )
    // Subtle architectural studio dot grid.
    val step = 28.dp.toPx()
    val radius = 1.dp.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(
                color = dotColor.copy(alpha = 0.22f),
                radius = radius,
                center = Offset(x, y)
            )
            x += step
        }
        y += step
    }
}

@Composable
fun LiveWaveformVisualizer(
    amplitudes: List<Float>,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isRecording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val bars = if (amplitudes.isEmpty()) List(36) { 0.12f } else amplitudes

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val count = bars.size.coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2.dp.toPx())
        val centerY = size.height / 2f
        val maxHalfHeight = (size.height * 0.44f).coerceAtLeast(4f)

        bars.forEachIndexed { index, rawLevel ->
            val clamped = rawLevel.coerceIn(0.06f, 1f)
            val halfHeight = (clamped * maxHalfHeight).coerceAtLeast(3.dp.toPx())
            val x = index * (barWidth + gap)
            val color = if (isRecording && clamped > 0.16f) {
                activeColor
            } else if (isRecording) {
                activeColor.copy(alpha = 0.45f)
            } else {
                trackColor
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
    isOpenAiConfigured: Boolean,
    onSelectMode: (RoutingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoutingMode.entries.forEach { mode ->
                val isSelected = mode == selectedMode
                val containerColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "engine_pill_bg"
                )
                val labelColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "engine_pill_fg"
                )

                val showDot = mode == RoutingMode.OPENAI_ONLY
                val dotColor = if (isOpenAiConfigured) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(containerColor)
                        .clickable { onSelectMode(mode) }
                        .testTag("routing_mode_${mode.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        if (showDot) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
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
    val animated by animateFloatAsState(
        targetValue = if (isActive) level.coerceIn(0.06f, 1f) else 0.06f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "level_meter"
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
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
