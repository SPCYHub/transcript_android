package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CantoneseFormat
import com.example.model.RoutingMode
import com.example.model.StudioUiState
import com.example.model.UserPreferences
import com.example.ui.components.EngineSelectorPillBar
import com.example.ui.components.LiveWaveformVisualizer
import com.example.ui.components.TranscriptResultCard
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatDurationMs

@Composable
fun StudioScreen(
    uiState: StudioUiState,
    preferences: UserPreferences,
    displayFormat: CantoneseFormat,
    isOpenAiConfigured: Boolean,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onSelectRoutingMode: (RoutingMode) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onImportAudio: () -> Unit,
    onSelectFormat: (CantoneseFormat) -> Unit,
    onCopyText: (String) -> Unit,
    onShareText: (String) -> Unit,
    onRunDeepReview: () -> Unit,
    onRetry: (RoutingMode?) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        val isWide = maxWidth >= 700.dp

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.46f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StudioHeader(isOpenAiConfigured = isOpenAiConfigured)
                    EngineSelectorPillBar(
                        selectedMode = preferences.routingMode,
                        isOpenAiConfigured = isOpenAiConfigured,
                        onSelectMode = onSelectRoutingMode
                    )
                    CaptureDeckCard(
                        uiState = uiState,
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = onRequestMicPermission,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onCancelRecording = onCancelRecording,
                        onImportAudio = onImportAudio
                    )
                    WhatsAppIntakeBanner()
                }

                Column(
                    modifier = Modifier
                        .weight(0.54f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StudioOutputPane(
                        uiState = uiState,
                        displayFormat = displayFormat,
                        onSelectFormat = onSelectFormat,
                        onCopyText = onCopyText,
                        onShareText = onShareText,
                        onRunDeepReview = onRunDeepReview,
                        onRetry = onRetry
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StudioHeader(isOpenAiConfigured = isOpenAiConfigured)

                    EngineSelectorPillBar(
                        selectedMode = preferences.routingMode,
                        isOpenAiConfigured = isOpenAiConfigured,
                        onSelectMode = onSelectRoutingMode
                    )

                    CaptureDeckCard(
                        uiState = uiState,
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = onRequestMicPermission,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onCancelRecording = onCancelRecording,
                        onImportAudio = onImportAudio
                    )

                    StudioOutputPane(
                        uiState = uiState,
                        displayFormat = displayFormat,
                        onSelectFormat = onSelectFormat,
                        onCopyText = onCopyText,
                        onShareText = onShareText,
                        onRunDeepReview = onRunDeepReview,
                        onRetry = onRetry
                    )

                    WhatsAppIntakeBanner()
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun StudioHeader(isOpenAiConfigured: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "CANTOSCRIBE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Cantonese Audio Studio",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = if (isOpenAiConfigured) "Dual AI Ready" else "Gemini Ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CaptureDeckCard(
    uiState: StudioUiState,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onImportAudio: () -> Unit
) {
    val recordingState = uiState as? StudioUiState.Recording
    val isRecording = recordingState != null
    val isProcessing = uiState is StudioUiState.Processing

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = if (isRecording) 1.5.dp else 1.dp,
            color = if (isRecording) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Acoustic status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isRecording -> MaterialTheme.colorScheme.error
                                    isProcessing -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                    Text(
                        text = when {
                            isRecording && recordingState.metrics.isCurrentlySilent -> "STANDBY · LISTENING"
                            isRecording -> "VOICE ACTIVE · 16KHZ AAC"
                            isProcessing -> "PROCESSING STREAM"
                            else -> "READY · HK CANTONESE"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = formatDurationMs(recordingState?.metrics?.elapsedMillis ?: 0L),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                    color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }

            LiveWaveformVisualizer(
                amplitudes = recordingState?.metrics?.waveformHistory.orEmpty(),
                isRecording = isRecording
            )

            // Action Controls Row
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
                            .height(54.dp)
                            .testTag("grant_mic_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Grant microphone access",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Mic Access")
                    }
                } else {
                    Button(
                        onClick = {
                            if (isRecording) onStopRecording() else onStartRecording()
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("studio_record_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecording) "Stop recording" else "Start Cantonese recording",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) "Stop & Transcribe" else "Record Cantonese",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }

                if (isRecording) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        IconButton(
                            onClick = onCancelRecording,
                            modifier = Modifier
                                .size(54.dp)
                                .testTag("cancel_record_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel recording",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onImportAudio,
                        enabled = !isProcessing,
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("import_audio_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = "Import audio file",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import")
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioOutputPane(
    uiState: StudioUiState,
    displayFormat: CantoneseFormat,
    onSelectFormat: (CantoneseFormat) -> Unit,
    onCopyText: (String) -> Unit,
    onShareText: (String) -> Unit,
    onRunDeepReview: () -> Unit,
    onRetry: (RoutingMode?) -> Unit
) {
    when (uiState) {
        is StudioUiState.Idle, is StudioUiState.Recording -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Smart Dual-Engine Cantonese Pipeline",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "Record live voice notes, import audio files (.opus, .ogg, .m4a, .mp3, .wav), or share WhatsApp voice messages directly to CantoScribe. Outputs authentic Hong Kong 口語 with one-tap 書面語 conversion and Gemini 4 Carbon High Thinking review.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is StudioUiState.Processing -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("processing_status_card"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
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
                            text = uiState.stageLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${uiState.fileName} · ${uiState.codecLabel} · ${formatByteSize(uiState.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Engine: ${uiState.activeEngineHint}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        is StudioUiState.Success -> {
            TranscriptResultCard(
                record = uiState.record,
                displayFormat = displayFormat,
                isConvertingWritten = uiState.isConvertingWritten,
                isRunningDeepReview = uiState.isRunningDeepReview,
                deepReviewError = uiState.deepReviewError,
                onSelectFormat = onSelectFormat,
                onCopyText = onCopyText,
                onShareText = onShareText,
                onRunDeepReview = onRunDeepReview
            )
        }

        is StudioUiState.Error -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("error_status_card"),
                shape = RoundedCornerShape(20.dp),
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
                            text = "Transcription Issue",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
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
                        if (uiState.canRetryWithAlternateEngine && uiState.suggestedEngine != null) {
                            OutlinedButton(
                                onClick = { onRetry(uiState.suggestedEngine) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("switch_engine_retry_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Try ${uiState.suggestedEngine.label}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsAppIntakeBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(18.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "WhatsApp Voice Ready (.opus / .ogg)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Long-press any WhatsApp voice message -> Share -> CantoScribe for instant zero-permission Cantonese transcription.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}
