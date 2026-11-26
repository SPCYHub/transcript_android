package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.LocalStudioHaptics
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
    val haptics = LocalStudioHaptics.current
    val reduceMotion = rememberReduceMotionEnabled()
    val liveState = uiState as? StudioUiState.LiveStreaming
    val isLive = liveState != null
    val isProcessing = uiState is StudioUiState.Processing
    var isPressed by remember { mutableStateOf(false) }

    val latestIsLive by rememberUpdatedState(isLive)
    val latestIsProcessing by rememberUpdatedState(isProcessing)
    val latestOnStartLive by rememberUpdatedState(onStartLive)
    val latestOnLockHandsFree by rememberUpdatedState(onLockHandsFree)
    val latestOnStopLive by rememberUpdatedState(onStopLive)

    val padScale by animateFloatAsState(
        targetValue = if (isPressed && !reduceMotion) 0.97f else 1f,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessMedium),
        label = "mic_pad_scale"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isLive) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 180),
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
                    semanticLabel = if (displayFormat == CantoneseFormat.WRITTEN_CHINESE) {
                        "Transcript format: Standard Written Chinese. Tap to switch to Spoken Cantonese"
                    } else {
                        "Transcript format: Spoken Cantonese. Tap to switch to Standard Written Chinese"
                    },
                    onClick = {
                        haptics.tick()
                        onToggleFormat()
                    },
                    modifier = Modifier.testTag("dock_format_chip")
                )
                DockQuickChip(
                    label = if (autoCopyEnabled) "Auto-Copy" else "Manual Copy",
                    active = autoCopyEnabled,
                    showCheck = autoCopyEnabled,
                    semanticLabel = "Auto-copy finished transcripts",
                    semanticState = if (autoCopyEnabled) "Enabled" else "Paused",
                    onClick = {
                        haptics.toggle(!autoCopyEnabled)
                        onToggleAutoCopy()
                    },
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
                        .clickable(enabled = !isProcessing && !isLive) {
                            haptics.tick()
                            onImportAudio()
                        }
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
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
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
                        onClick = {
                            haptics.tick()
                            onRequestMicPermission()
                        },
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

                    val padSemanticLabel = when {
                        isProcessing -> "Transcribing audio, microphone disabled"
                        isLive -> "Stop live Cantonese transcription"
                        else -> "Start hands-free live Cantonese transcription, or hold to speak"
                    }
                    val padSemanticState = when {
                        isProcessing -> "Processing"
                        isLive -> "Recording"
                        else -> "Idle"
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .scale(padScale)
                            .clip(RoundedCornerShape(18.dp))
                            .background(primaryBrush)
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription = padSemanticLabel
                                stateDescription = padSemanticState
                                onClick(label = padSemanticLabel) {
                                    if (latestIsProcessing) return@onClick false
                                    if (latestIsLive) {
                                        haptics.recordStop()
                                        latestOnStopLive()
                                    } else {
                                        haptics.recordStart()
                                        latestOnStartLive(false)
                                    }
                                    true
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        if (latestIsProcessing) return@detectTapGestures
                                        isPressed = true
                                        val downAt = System.currentTimeMillis()
                                        try {
                                            if (latestIsLive) {
                                                haptics.recordStop()
                                                latestOnStopLive()
                                                tryAwaitRelease()
                                            } else {
                                                haptics.recordStart()
                                                latestOnStartLive(true)
                                                val released = tryAwaitRelease()
                                                val heldMs = System.currentTimeMillis() - downAt
                                                if (released && heldMs < 320L) {
                                                    haptics.handsFreeLock()
                                                    latestOnLockHandsFree()
                                                } else {
                                                    haptics.recordStop()
                                                    latestOnStopLive()
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
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
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
                                    contentDescription = null,
                                    tint = padFg,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = when {
                                        isLive && liveState.isHoldToTalk -> "Streaming Live · Release"
                                        isLive -> "Hands-Free Live · Tap Stop"
                                        else -> "Hold to Speak · Tap Lock"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = padFg,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isLive) {
                                        "Live Cantonese transcription active"
                                    } else {
                                        "Zero-wait Cantonese stream"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = padFg.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isLive,
                    enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandHorizontally(),
                    exit = if (reduceMotion) ExitTransition.None else fadeOut() + shrinkHorizontally()
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        IconButton(
                            onClick = {
                                haptics.error()
                                onCancelLive()
                            },
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
    showCheck: Boolean = false,
    semanticLabel: String = label,
    semanticState: String? = null
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
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = semanticLabel
                if (semanticState != null) {
                    stateDescription = semanticState
                }
            }
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
                maxLines = 1,
                softWrap = false,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
