package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CantoneseFormat
import com.example.model.StudioUiState

@Composable
fun StudioThumbZoneDock(
    uiState: StudioUiState,
    displayFormat: CantoneseFormat,
    autoCopyEnabled: Boolean,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartLive: (isHoldToTalk: Boolean) -> Unit,
    onLockHandsFree: () -> Unit,
    onStopLive: () -> Unit,
    onCancelLive: () -> Unit,
    onImportAudio: () -> Unit,
    onToggleFormat: () -> Unit,
    onToggleAutoCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val liveState = uiState as? StudioUiState.LiveStreaming
    val isLive = liveState != null
    val isProcessing = uiState is StudioUiState.Processing
    var isPressed by remember { mutableStateOf(false) }

    val padScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "mic_pad_scale"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isLive) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "dock_border"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(if (isLive) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compact tactile studio toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockQuickChip(
                    label = if (displayFormat == CantoneseFormat.WRITTEN_CHINESE) "書面語" else "口語",
                    active = true,
                    onClick = onToggleFormat,
                    modifier = Modifier.testTag("dock_format_chip")
                )
                DockQuickChip(
                    label = if (autoCopyEnabled) "Auto-Copy" else "Manual Copy",
                    active = autoCopyEnabled,
                    showCheck = autoCopyEnabled,
                    onClick = onToggleAutoCopy,
                    modifier = Modifier.testTag("dock_autocopy_chip")
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isProcessing && !isLive, onClick = onImportAudio)
                        .testTag("import_audio_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = "Import audio file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Import",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Primary tactile Hold-to-Speak / Hands-Free trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!hasMicPermission) {
                    Button(
                        onClick = onRequestMicPermission,
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .testTag("grant_mic_button"),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "Grant microphone access")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Allow Microphone for Live Cantonese")
                    }
                } else {
                    val primaryBrush = if (isLive) {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.error,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.86f)
                            )
                        )
                    } else if (isProcessing) {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
                            )
                        )
                    }

                    val padFg = if (isProcessing) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else if (isLive) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .scale(padScale)
                            .clip(RoundedCornerShape(18.dp))
                            .background(primaryBrush)
                            .pointerInput(isLive, isProcessing) {
                                if (isProcessing) return@pointerInput
                                detectTapGestures(
                                    onPress = {
                                        isPressed = true
                                        val downAt = System.currentTimeMillis()
                                        try {
                                            if (isLive) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onStopLive()
                                                tryAwaitRelease()
                                            } else {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onStartLive(true)
                                                val released = tryAwaitRelease()
                                                val heldMs = System.currentTimeMillis() - downAt
                                                if (released && heldMs < 320L) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onLockHandsFree()
                                                } else {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onStopLive()
                                                }
                                            }
                                        } finally {
                                            isPressed = false
                                        }
                                    }
                                )
                            }
                            .testTag("studio_record_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(padFg.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        isLive && !liveState.isHoldToTalk -> Icons.Filled.Stop
                                        isLive -> Icons.Filled.GraphicEq
                                        else -> Icons.Filled.Mic
                                    },
                                    contentDescription = if (isLive) "Live streaming microphone" else "Hold to speak or tap for hands-free",
                                    tint = padFg,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = when {
                                        isLive && liveState.isHoldToTalk -> "Streaming Live · Release to Finish"
                                        isLive -> "Hands-Free Live · Tap to Stop"
                                        else -> "Hold to Speak · Tap Hands-Free"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = padFg,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isLive) {
                                        "Live Cantonese transcription active"
                                    } else {
                                        "Zero-wait real-time Cantonese stream"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = padFg.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }

                if (isLive) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        IconButton(
                            onClick = onCancelLive,
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("cancel_record_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel live stream",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockQuickChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCheck: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (showCheck) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
