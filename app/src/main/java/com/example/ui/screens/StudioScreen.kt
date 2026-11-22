package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.model.ApiKeyStatus
import com.example.model.CantoneseFormat
import com.example.model.RoutingMode
import com.example.model.StudioUiState
import com.example.model.UserPreferences
import com.example.ui.components.AmbientAcousticEmblem
import com.example.ui.components.EngineSelectorPillBar
import com.example.ui.components.ErrorRecoveryCard
import com.example.ui.components.LiveStreamingCanvasCard
import com.example.ui.components.ProcessingStatusCard
import com.example.ui.components.StudioThumbZoneDock
import com.example.ui.components.TranscriptResultCard

@Composable
fun StudioScreen(
    uiState: StudioUiState,
    preferences: UserPreferences,
    displayFormat: CantoneseFormat,
    apiKeyStatus: ApiKeyStatus,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onSelectRoutingMode: (RoutingMode) -> Unit,
    onToggleAutoCopy: (Boolean) -> Unit,
    onStartLive: (isHoldToTalk: Boolean) -> Unit,
    onLockHandsFree: () -> Unit,
    onStopLive: () -> Unit,
    onCancelLive: () -> Unit,
    onImportAudio: () -> Unit,
    onOpenKeyboardTab: () -> Unit,
    onOpenKeysTab: () -> Unit,
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
                    StudioHeader(
                        apiKeyStatus = apiKeyStatus,
                        onOpenKeysTab = onOpenKeysTab
                    )
                    EngineSelectorPillBar(
                        selectedMode = preferences.routingMode,
                        apiKeyStatus = apiKeyStatus,
                        onSelectMode = onSelectRoutingMode
                    )
                    StudioThumbZoneDock(
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
                        onRetry = onRetry,
                        onImportAudio = onImportAudio,
                        onOpenKeyboardTab = onOpenKeyboardTab,
                        onOpenKeysTab = onOpenKeysTab
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp, bottom = 10.dp),
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
                    StudioHeader(
                        apiKeyStatus = apiKeyStatus,
                        onOpenKeysTab = onOpenKeysTab
                    )

                    EngineSelectorPillBar(
                        selectedMode = preferences.routingMode,
                        apiKeyStatus = apiKeyStatus,
                        onSelectMode = onSelectRoutingMode
                    )

                    StudioStageContent(
                        uiState = uiState,
                        displayFormat = displayFormat,
                        onSelectFormat = onSelectFormat,
                        onCopyText = onCopyText,
                        onShareText = onShareText,
                        onRunDeepReview = onRunDeepReview,
                        onRetry = onRetry,
                        onImportAudio = onImportAudio,
                        onOpenKeyboardTab = onOpenKeyboardTab,
                        onOpenKeysTab = onOpenKeysTab
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                StudioThumbZoneDock(
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
private fun StudioHeader(
    apiKeyStatus: ApiKeyStatus,
    onOpenKeysTab: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "CANTOSCRIBE · 粵語轉錄",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Voice Studio",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        val statusLabel = when {
            apiKeyStatus.isUsingDirectGemini && apiKeyStatus.isOpenAiConfigured -> "BYOK · Dual"
            apiKeyStatus.isUsingDirectGemini -> "Personal Key"
            apiKeyStatus.isOpenAiConfigured -> "Dual Live AI"
            else -> "Firebase AI"
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenKeysTab)
                .testTag("header_provider_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = "Manage API Keys",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
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
    onRetry: (RoutingMode?) -> Unit,
    onImportAudio: () -> Unit,
    onOpenKeyboardTab: () -> Unit,
    onOpenKeysTab: () -> Unit
) {
    when (uiState) {
        is StudioUiState.Idle -> {
            StudioIdleHeroStage(
                onImportAudio = onImportAudio,
                onOpenKeyboardTab = onOpenKeyboardTab,
                onOpenKeysTab = onOpenKeysTab
            )
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
                },
                onOpenKeys = onOpenKeysTab
            )
        }

        is StudioUiState.Processing -> ProcessingStatusCard(state = uiState)

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
            ErrorRecoveryCard(
                state = uiState,
                onRetry = onRetry,
                onOpenKeys = onOpenKeysTab
            )
        }
    }
}

@Composable
private fun StudioIdleHeroStage(
    onImportAudio: () -> Unit,
    onOpenKeyboardTab: () -> Unit,
    onOpenKeysTab: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Minimalist central acoustic instrument canvas
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AmbientAcousticEmblem()

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "講廣東話 · Ready to Stream",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Press & hold below for quick replies, or quick-tap for hands-free Cantonese transcription.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Compact 3-tile quick studio entry strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickActionTile(
                icon = Icons.Filled.AudioFile,
                title = "Audio File",
                subtitle = ".opus / .m4a",
                onClick = onImportAudio,
                modifier = Modifier
                    .weight(1f)
                    .testTag("idle_import_tile")
            )
            QuickActionTile(
                icon = Icons.Filled.KeyboardVoice,
                title = "Voice IME",
                subtitle = "Type anywhere",
                onClick = onOpenKeyboardTab,
                modifier = Modifier
                    .weight(1f)
                    .testTag("idle_keyboard_tile")
            )
            QuickActionTile(
                icon = Icons.Filled.Key,
                title = "API Keys",
                subtitle = "Bring own key",
                onClick = onOpenKeysTab,
                modifier = Modifier
                    .weight(1f)
                    .testTag("idle_keys_tile")
            )
        }

        // Subtle single-line WhatsApp share intake strip
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Share any WhatsApp voice note directly to CantoScribe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
