package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.model.RoutingMode
import com.example.model.StudioUiState

@Composable
fun LiveStreamingCanvasCard(
    state: StudioUiState.LiveStreaming,
    onCopyLiveNow: () -> Unit,
    onOpenKeys: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "live_cursor_pulse")
    val cursorAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    val hasAnyText = state.confirmedText.isNotBlank() || state.partialText.isNotBlank()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_streaming_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = cursorAlpha))
                    )
                    Column {
                        Text(
                            text = when {
                                state.isConnecting -> "CONNECTING LIVE STREAM…"
                                state.isHoldToTalk -> "LIVE · RELEASE WHEN DONE"
                                else -> "LIVE · HANDS-FREE LOCKED"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.engineLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = formatDurationMs(state.metrics.elapsedMillis),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            LiveWaveformVisualizer(
                amplitudes = state.metrics.waveformHistory,
                isRecording = true,
                modifier = Modifier.height(54.dp)
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!hasAnyText) {
                            val liveHint = when {
                                state.isConnecting -> "Connecting to live transcription engine… ▍"
                                state.isTranscribingChunk -> "Hearing Cantonese · streaming words live… ▍"
                                state.metrics.speechFrames > 0 -> "Speech detected · streaming shortly… ▍"
                                else -> "講廣東話啦… Words stream live as you speak ▍"
                            }
                            Text(
                                text = liveHint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (state.isTranscribingChunk || state.metrics.speechFrames > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        } else {
                            val primaryColor = MaterialTheme.colorScheme.primary
                            val inkColor = MaterialTheme.colorScheme.onSurface
                            val styled = buildAnnotatedString {
                                withStyle(SpanStyle(color = inkColor)) {
                                    append(state.confirmedText)
                                }
                                if (state.partialText.isNotEmpty()) {
                                    if (state.confirmedText.isNotEmpty()) append(" ")
                                    withStyle(
                                        SpanStyle(
                                            color = primaryColor,
                                            fontStyle = FontStyle.Italic
                                        )
                                    ) {
                                        append(state.partialText)
                                    }
                                }
                                withStyle(SpanStyle(color = primaryColor.copy(alpha = cursorAlpha))) {
                                    append(" ▍")
                                }
                            }
                            Text(
                                text = styled,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            if (!state.warningMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.warningMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.warningMessage.contains("Key", ignoreCase = true) ||
                            state.warningMessage.contains("App Check", ignoreCase = true)
                        ) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onOpenKeys,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Keys")
                            }
                        }
                    }
                }
            }

            if (hasAnyText) {
                OutlinedButton(
                    onClick = onCopyLiveNow,
                    modifier = Modifier
                        .align(Alignment.End)
                        .minimumInteractiveComponentSize()
                        .testTag("copy_live_now_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy live transcript",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Live Text")
                }
            }
        }
    }
}

@Composable
fun ProcessingStatusCard(state: StudioUiState.Processing) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("processing_status_card"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = state.stageLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${state.fileName} · ${state.codecLabel} · ${formatByteSize(state.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Engine: ${state.activeEngineHint}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ErrorRecoveryCard(
    state: StudioUiState.Error,
    onRetry: (RoutingMode?) -> Unit,
    onOpenKeys: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_status_card"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = "Transcription error",
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (state.isAppCheckOrKeyError) "API Key or App Check Needed" else "Transcription Issue",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            if (state.isAppCheckOrKeyError) {
                Text(
                    text = "Tip: If you built this APK locally from a ZIP export, add your own Gemini or OpenAI API key in Keys to run directly without Firebase App Check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isAppCheckOrKeyError) {
                    Button(
                        onClick = onOpenKeys,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("error_open_keys_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Own API Key")
                    }
                }

                OutlinedButton(
                    onClick = { onRetry(null) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("retry_transcribe_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry")
                }

                if (state.canRetryWithAlternateEngine && state.suggestedEngine != null) {
                    OutlinedButton(
                        onClick = { onRetry(state.suggestedEngine) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("switch_engine_retry_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Use ${state.suggestedEngine.label}")
                    }
                }
            }
        }
    }
}
