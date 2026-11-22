package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    onToggleAutoCopy: (Boolean) -> Unit,
    onStartLive: (isHoldToTalk: Boolean) -> Unit,
    onLockHandsFree: () -> Unit,
    onStopLive: () -> Unit,
    onCancelLive: () -> Unit,
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
                        .weight(0.45f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StudioHeader(isOpenAiConfigured = isOpenAiConfigured)
                    EngineSelectorPillBar(
                        selectedMode = preferences.routingMode,
                        isOpenAiConfigured = isOpenAiConfigured,
                        onSelectMode = onSelectRoutingMode
                    )
                    ThumbZoneLiveDock(
                        uiState = uiState,
                        displayFormat = displayFormat,
                        autoCopyEnabled = preferences.autoCopyOnFinish,
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = onRequestMicPermission,
                        onStartLive = onStartLive,
                        onLockHandsFree = onLockHandsFree,
                        onStopLive = onStopLive,
                        onCancelLive = onCancelLive,
                        onImportAudio = onImportAudio,
                        onToggleFormat = {
                            val next = if (displayFormat == CantoneseFormat.WRITTEN_CHINESE) {
                                CantoneseFormat.SPOKEN_CANTONESE
                            } else {
                                CantoneseFormat.WRITTEN_CHINESE
                            }
                            onSelectFormat(next)
                        },
                        onToggleAutoCopy = { onToggleAutoCopy(!preferences.autoCopyOnFinish) }
                    )
                    WhatsAppIntakeBanner()
                }

                Column(
                    modifier = Modifier
                        .weight(0.55f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StudioStageContent(
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
            // Compact one-handed layout: Eyes-up live transcript stage + Thumb-zone bottom dock!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StudioHeader(isOpenAiConfigured = isOpenAiConfigured)

                    EngineSelectorPillBar(
                        selectedMode = preferences.routingMode,
                        isOpenAiConfigured = isOpenAiConfigured,
                        onSelectMode = onSelectRoutingMode
                    )

                    StudioStageContent(
                        uiState = uiState,
                        displayFormat = displayFormat,
                        onSelectFormat = onSelectFormat,
                        onCopyText = onCopyText,
                        onShareText = onShareText,
                        onRunDeepReview = onRunDeepReview,
                        onRetry = onRetry
                    )

                    if (uiState is StudioUiState.Idle) {
                        WhatsAppIntakeBanner()
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Pinned ergonomic bottom thumb-zone dock for effortless Hold-to-Talk or Tap Hands-Free
                ThumbZoneLiveDock(
                    uiState = uiState,
                    displayFormat = displayFormat,
                    autoCopyEnabled = preferences.autoCopyOnFinish,
                    hasMicPermission = hasMicPermission,
                    onRequestMicPermission = onRequestMicPermission,
                    onStartLive = onStartLive,
                    onLockHandsFree = onLockHandsFree,
                    onStopLive = onStopLive,
                    onCancelLive = onCancelLive,
                    onImportAudio = onImportAudio,
                    onToggleFormat = {
                        val next = if (displayFormat == CantoneseFormat.WRITTEN_CHINESE) {
                            CantoneseFormat.SPOKEN_CANTONESE
                        } else {
                            CantoneseFormat.WRITTEN_CHINESE
                        }
                        onSelectFormat(next)
                    },
                    onToggleAutoCopy = { onToggleAutoCopy(!preferences.autoCopyOnFinish) },
                    modifier = Modifier.widthIn(max = 600.dp)
                )
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
                text = "CANTOSCRIBE · LIVE STREAM",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Cantonese Studio",
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
                    text = if (isOpenAiConfigured) "Dual Live AI" else "Gemini Live",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StudioStageContent(
    uiState: StudioUiState,
    displayFormat: CantoneseFormat,
    onSelectFormat: (CantoneseFormat) -> Unit,
    onCopyText: (String) -> Unit,
    onShareText: (String) -> Unit,
    onRunDeepReview: () -> Unit,
    onRetry: (RoutingMode?) -> Unit
) {
    when (uiState) {
        is StudioUiState.Idle -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Real-Time Streaming — Zero Wait",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "Words stream live as you speak — no waiting until you stop recording.\n• Press & Hold the jade mic pad below for quick sentences, then release.\n• Quick Tap to lock hands-free continuous Cantonese streaming.\n• Pull down Quick Settings -> CantoScribe Live to start from any app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is StudioUiState.LiveStreaming -> {
            LiveStreamingCanvasCard(
                state = uiState,
                onCopyLiveNow = {
                    val liveText = buildString {
                        append(uiState.confirmedText)
                        if (uiState.partialText.isNotBlank()) append(uiState.partialText)
                    }.trim()
                    if (liveText.isNotEmpty()) onCopyText(liveText)
                }
            )
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
                                Text("Use ${uiState.suggestedEngine.label}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStreamingCanvasCard(
    state: StudioUiState.LiveStreaming,
    onCopyLiveNow: () -> Unit
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
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.72f))
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = cursorAlpha))
                    )
                    Column {
                        Text(
                            text = if (state.isConnecting) {
                                "CONNECTING LIVE STREAM…"
                            } else if (state.isHoldToTalk) {
                                "LIVE · RELEASE WHEN DONE"
                            } else {
                                "LIVE · HANDS-FREE LOCKED"
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

                Text(
                    text = formatDurationMs(state.metrics.elapsedMillis),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                    color = MaterialTheme.colorScheme.error
                )
            }

            LiveWaveformVisualizer(
                amplitudes = state.metrics.waveformHistory,
                isRecording = true,
                modifier = Modifier.height(52.dp)
            )

            // Live real-time Cantonese transcript text
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!hasAnyText) {
                            Text(
                                text = "講廣東話啦… Words appear here live while you speak ▍",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Text(
                    text = state.warningMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
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
private fun ThumbZoneLiveDock(
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        border = BorderStroke(
            width = if (isLive) 1.5.dp else 1.dp,
            color = if (isLive) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Instant 1-tap controls row (口語/書面語, Auto-Copy, Import Audio)
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
                    label = if (autoCopyEnabled) "Auto-Copy ON" else "Auto-Copy OFF",
                    active = autoCopyEnabled,
                    showCheck = autoCopyEnabled,
                    onClick = onToggleAutoCopy,
                    modifier = Modifier.testTag("dock_autocopy_chip")
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isProcessing && !isLive, onClick = onImportAudio)
                        .testTag("import_audio_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Dual-Gesture Ergonomic Mic Pad (Hold to Speak OR Tap to Lock Hands-Free)
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
                            .height(58.dp)
                            .testTag("grant_mic_button"),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "Grant mic access")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Mic for Live Cantonese")
                    }
                } else {
                    val padBg = if (isLive) {
                        MaterialTheme.colorScheme.error
                    } else if (isProcessing) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    val padFg = if (isProcessing) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(padBg)
                            .pointerInput(isLive, isProcessing) {
                                if (isProcessing) return@pointerInput
                                detectTapGestures(
                                    onPress = {
                                        val downAt = System.currentTimeMillis()
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
                                                // Short tap -> lock into continuous hands-free mode!
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onLockHandsFree()
                                            } else {
                                                // Hold-to-talk released -> finalize live stream immediately!
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onStopLive()
                                            }
                                        }
                                    }
                                )
                            }
                            .testTag("studio_record_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    isLive && !liveState.isHoldToTalk -> Icons.Filled.Stop
                                    isLive -> Icons.Filled.GraphicEq
                                    else -> Icons.Filled.Mic
                                },
                                contentDescription = if (isLive) "Live streaming microphone" else "Hold to speak or tap for hands-free",
                                tint = padFg,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = when {
                                        isLive && liveState.isHoldToTalk -> "Streaming Live · Release to Finish"
                                        isLive -> "Hands-Free Live · Tap to Stop"
                                        else -> "Hold to Speak · Tap for Hands-Free"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = padFg,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isLive) {
                                        "Live Cantonese transcription active"
                                    } else {
                                        "Instant live streaming — zero post-record wait"
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
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        IconButton(
                            onClick = onCancelLive,
                            modifier = Modifier
                                .size(60.dp)
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
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    text = "WhatsApp Voice Share Ready (.opus / .ogg)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Long-press a WhatsApp voice message -> Share -> CantoScribe for instant Cantonese transcription.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}
