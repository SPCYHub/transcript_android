package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.LocalStudioHaptics
import com.example.model.CantoneseFormat
import com.example.model.UserPreferences
import com.example.viewmodel.SandboxState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val PunctuationKeys = listOf("，", "。", "？", "！", "、", "啦", "㗎")
private val HkQuickPhraseKeys = listOf("唔該晒", "好呀", "收到", "等陣", "冇問題", "多謝")

/**
 * Interactive docked Cantonese Voice Keyboard Deck for the in-app Keyboard tab.
 * Mirrors the system `CantoVoiceImeService` with sculpted keycaps, live multi-bar waveform,
 * HK quick phrases, repeat-backspace, engine/format chips, and full `StudioHaptics`.
 */
@Composable
fun VoiceKeyboardDeck(
    sandboxState: SandboxState,
    preferences: UserPreferences,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onCycleRoutingMode: () -> Unit,
    onToggleFormat: () -> Unit,
    onStartSandboxLive: (isHoldToTalk: Boolean) -> Unit,
    onLockSandboxHandsFree: () -> Unit,
    onStopSandboxLive: () -> Unit,
    onCancelSandboxLive: () -> Unit,
    onAppendSymbol: (String) -> Unit,
    onBackspace: () -> Unit,
    onUndoSandbox: () -> Unit,
    onOpenImePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalStudioHaptics.current
    val scope = rememberCoroutineScope()
    var showQuickPhrases by remember { mutableStateOf(false) }
    var isVoicePadPressed by remember { mutableStateOf(false) }

    val isStreaming = sandboxState.isStreaming
    val latestIsStreaming by rememberUpdatedState(isStreaming)
    val latestOnStart by rememberUpdatedState(onStartSandboxLive)
    val latestOnLock by rememberUpdatedState(onLockSandboxHandsFree)
    val latestOnStop by rememberUpdatedState(onStopSandboxLive)
    val latestOnBackspace by rememberUpdatedState(onBackspace)

    val pulseTransition = rememberInfiniteTransition(label = "ime_live_dot")
    val liveDotAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ime_dot_alpha"
    )

    val padScale by animateFloatAsState(
        targetValue = if (isVoicePadPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "keyboard_pad_scale"
    )

    val deckBorder by animateColorAsState(
        targetValue = if (isStreaming) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "keyboard_deck_border"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_keyboard_deck"),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(if (isStreaming) 1.5.dp else 1.dp, deckBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: Telemetry & Mode Control Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isStreaming) {
                                    MaterialTheme.colorScheme.error.copy(alpha = liveDotAlpha)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                    Text(
                        text = if (isStreaming) {
                            "LIVE · ${formatDurationMs(sandboxState.elapsedMillis)}"
                        } else {
                            "CANTOSCRIBE IME"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isStreaming) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyboardControlChip(
                        label = preferences.routingMode.label,
                        highlighted = false,
                        onClick = {
                            haptics.tick()
                            onCycleRoutingMode()
                        },
                        modifier = Modifier.testTag("keyboard_deck_engine_chip")
                    )
                    KeyboardControlChip(
                        label = if (preferences.defaultFormat == CantoneseFormat.WRITTEN_CHINESE) "書面語" else "口語",
                        highlighted = false,
                        onClick = {
                            haptics.tick()
                            onToggleFormat()
                        },
                        modifier = Modifier.testTag("keyboard_deck_format_chip")
                    )
                    KeyboardControlChip(
                        label = "詞",
                        highlighted = showQuickPhrases,
                        onClick = {
                            haptics.tick()
                            showQuickPhrases = !showQuickPhrases
                        },
                        modifier = Modifier.testTag("keyboard_deck_phrases_toggle")
                    )
                }
            }

            // Row 2: Sculpted Quick Keycap Row (Punctuation/Particles <-> HK Quick Phrases)
            AnimatedContent(
                targetState = showQuickPhrases,
                label = "keyboard_quick_strip_mode"
            ) { phrasesMode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (phrasesMode) {
                        HkQuickPhraseKeys.forEachIndexed { idx, phrase ->
                            KeyboardKeycap(
                                label = phrase,
                                compact = true,
                                onClick = {
                                    haptics.keyTap()
                                    onAppendSymbol(phrase)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sandbox_phrase_$idx")
                            )
                        }
                    } else {
                        PunctuationKeys.forEachIndexed { idx, symbol ->
                            KeyboardKeycap(
                                label = symbol,
                                compact = false,
                                onClick = {
                                    haptics.keyTap()
                                    onAppendSymbol(symbol)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sandbox_punct_$idx")
                            )
                        }
                        KeyboardKeycap(
                            label = "Undo",
                            compact = true,
                            accent = sandboxState.lastInsertedLength > 0,
                            enabled = sandboxState.lastInsertedLength > 0,
                            onClick = {
                                haptics.keyTap()
                                onUndoSandbox()
                            },
                            modifier = Modifier
                                .weight(1.25f)
                                .testTag("sandbox_deck_undo_key")
                        )
                    }
                }
            }

            // Row 3: Embedded Acoustic Waveform Strip
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LiveWaveformVisualizer(
                        amplitudes = sandboxState.waveformHistory,
                        isRecording = isStreaming,
                        modifier = Modifier.height(34.dp)
                    )
                    Text(
                        text = sandboxState.statusNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Row 4: Sculpted Dual-Gesture Voice Pad + Inline Cancel Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            .height(62.dp)
                            .testTag("sandbox_mic_button"),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "Grant microphone access")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Allow Microphone for Live Dictation")
                    }
                } else {
                    val padBrush = if (isStreaming) {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.error,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.84f)
                            )
                        )
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.86f)
                            )
                        )
                    }
                    val padFg = if (isStreaming) {
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
                            .background(padBrush)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isVoicePadPressed = true
                                        val downAt = System.currentTimeMillis()
                                        try {
                                            if (latestIsStreaming) {
                                                haptics.recordStop()
                                                latestOnStop()
                                                tryAwaitRelease()
                                            } else {
                                                haptics.recordStart()
                                                latestOnStart(true)
                                                val released = tryAwaitRelease()
                                                val heldMs = System.currentTimeMillis() - downAt
                                                if (released && heldMs < 320L) {
                                                    haptics.handsFreeLock()
                                                    latestOnLock()
                                                } else {
                                                    haptics.recordStop()
                                                    latestOnStop()
                                                }
                                            }
                                        } finally {
                                            isVoicePadPressed = false
                                        }
                                    }
                                )
                            }
                            .testTag("sandbox_mic_button"),
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
                                    .background(padFg.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        isStreaming && !sandboxState.isHoldToTalk -> Icons.Filled.Stop
                                        isStreaming -> Icons.Filled.GraphicEq
                                        else -> Icons.Filled.Mic
                                    },
                                    contentDescription = "Live Cantonese voice pad",
                                    tint = padFg,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = when {
                                        isStreaming && sandboxState.isHoldToTalk -> "Live Streaming · Release"
                                        isStreaming -> "Hands-Free Live · Tap Stop"
                                        else -> "Hold to Speak · Tap Lock"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = padFg,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = if (isStreaming) {
                                        "Streaming Cantonese live into composer"
                                    } else {
                                        "Zero-wait 粵語 voice keyboard deck"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = padFg.copy(alpha = 0.85f),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isStreaming,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            IconButton(
                                onClick = {
                                    haptics.error()
                                    onCancelSandboxLive()
                                },
                                modifier = Modifier
                                    .size(64.dp)
                                    .testTag("sandbox_cancel_live_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Cancel live dictation",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Row 5: Bottom Action Keycap Row (Switch IME, Wide Space, Hold-to-Repeat Backspace, Newline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.keyTap()
                            onOpenImePicker()
                        }
                        .testTag("sandbox_switch_ime_key")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = "Switch keyboard",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(2.4f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.keyTap()
                            onAppendSymbol(" ")
                        }
                        .testTag("sandbox_space_key")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "空格 · Space",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Hold-to-repeat Backspace Keycap
                var backspacePressed by remember { mutableStateOf(false) }
                val backspaceScale by animateFloatAsState(
                    targetValue = if (backspacePressed) 0.94f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessHigh),
                    label = "backspace_scale"
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (backspacePressed) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(46.dp)
                        .scale(backspaceScale)
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    backspacePressed = true
                                    haptics.keyTap()
                                    latestOnBackspace()
                                    val repeatJob = scope.launch {
                                        delay(360L)
                                        while (isActive) {
                                            latestOnBackspace()
                                            haptics.backspaceTick()
                                            delay(65L)
                                        }
                                    }
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        repeatJob.cancel()
                                        backspacePressed = false
                                    }
                                }
                            )
                        }
                        .testTag("sandbox_backspace_key")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Backspace (hold to repeat)",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .weight(1.25f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.keyTap()
                            onAppendSymbol("\n")
                        }
                        .testTag("sandbox_return_key")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                            contentDescription = "Insert new line",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardControlChip(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun KeyboardKeycap(
    label: String,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when {
            accent -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        shadowElevation = if (enabled) 1.dp else 0.dp,
        border = BorderStroke(
            1.dp,
            if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                fontWeight = if (compact || accent) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    accent -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
        }
    }
}
