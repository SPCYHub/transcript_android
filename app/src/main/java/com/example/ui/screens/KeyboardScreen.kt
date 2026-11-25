package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.LocalStudioHaptics
import com.example.model.CantoneseFormat
import com.example.model.RoutingMode
import com.example.model.UserPreferences
import com.example.ui.components.VoiceKeyboardDeck
import com.example.ui.components.rememberReduceMotionEnabled
import com.example.viewmodel.SandboxState

@Composable
fun KeyboardScreen(
    sandboxState: SandboxState,
    preferences: UserPreferences,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onSelectRoutingMode: (RoutingMode) -> Unit,
    onSelectFormat: (CantoneseFormat) -> Unit,
    onStartSandboxLive: (isHoldToTalk: Boolean) -> Unit,
    onLockSandboxHandsFree: () -> Unit,
    onStopSandboxLive: () -> Unit,
    onCancelSandboxLive: () -> Unit,
    onSandboxTextChange: (String) -> Unit,
    onAppendSymbol: (String) -> Unit,
    onBackspaceSandbox: () -> Unit,
    onUndoSandbox: () -> Unit,
    onClearSandbox: () -> Unit,
    onCopySandbox: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalStudioHaptics.current
    val reduceMotion = rememberReduceMotionEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current

    var imeStatus by remember { mutableStateOf(inspectImeStatus(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeStatus = inspectImeStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openImePicker: () -> Unit = {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header row with tactile haptics indicator badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SYSTEM VOICE KEYBOARD",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Cantonese Voice IME",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.semantics { heading() }
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (preferences.hapticsEnabled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Vibration,
                            contentDescription = null,
                            tint = if (preferences.hapticsEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (preferences.hapticsEnabled) "Haptics On" else "Haptics Off",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (preferences.hapticsEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 2-Step Activation Card with Live Enabled / Active Detection
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Keyboard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Type in Any App",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() }
                                )
                                Text(
                                    text = "WhatsApp, Notes, Signal, Chrome",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val statusLabel = when {
                            imeStatus.isActiveDefault -> "Active"
                            imeStatus.isEnabled -> "Enabled"
                            else -> "Setup"
                        }
                        val statusHighlighted = imeStatus.isEnabled || imeStatus.isActiveDefault
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (statusHighlighted) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (statusHighlighted) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (statusHighlighted) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                haptics.tick()
                                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("open_ime_settings_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("1. Enable")
                        }

                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                openImePicker()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("open_ime_picker_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("2. Switch")
                        }
                    }
                }
            }

            // Editorial Live Dictation Composer Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (sandboxState.isStreaming) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary
                                    )
                            )
                            Text(
                                text = "Live Dictation Composer",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() }
                            )
                        }
                        Text(
                            text = "${sandboxState.text.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = sandboxState.text,
                        onValueChange = onSandboxTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 124.dp, max = 220.dp)
                            .testTag("keyboard_sandbox_input"),
                        placeholder = {
                            Text(
                                text = "Try the Cantonese voice keyboard deck below — live words stream right here…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )

                    AnimatedVisibility(
                        visible = sandboxState.livePartial.isNotBlank(),
                        enter = if (reduceMotion) EnterTransition.None else androidx.compose.animation.fadeIn(),
                        exit = if (reduceMotion) ExitTransition.None else androidx.compose.animation.fadeOut()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        ) {
                            Text(
                                text = "Streaming: ${sandboxState.livePartial} ▍",
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                haptics.keyTap()
                                onUndoSandbox()
                            },
                            enabled = sandboxState.lastInsertedLength > 0,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("sandbox_undo_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo last dictation",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Undo")
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.confirm()
                                onCopySandbox(sandboxState.text)
                            },
                            enabled = sandboxState.text.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("sandbox_copy_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy sandbox text",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy")
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.error()
                                onClearSandbox()
                            },
                            enabled = sandboxState.text.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("sandbox_clear_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Clear sandbox",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                }
            }

            // Docked Interactive Voice Keyboard Deck (Mirrors the System Voice IME!)
            VoiceKeyboardDeck(
                sandboxState = sandboxState,
                preferences = preferences,
                hasMicPermission = hasMicPermission,
                onRequestMicPermission = onRequestMicPermission,
                onCycleRoutingMode = {
                    val next = when (preferences.routingMode) {
                        RoutingMode.AUTO -> RoutingMode.GEMINI_ONLY
                        RoutingMode.GEMINI_ONLY -> RoutingMode.OPENAI_ONLY
                        RoutingMode.OPENAI_ONLY -> RoutingMode.AUTO
                    }
                    onSelectRoutingMode(next)
                },
                onToggleFormat = {
                    val next = if (preferences.defaultFormat == CantoneseFormat.WRITTEN_CHINESE) {
                        CantoneseFormat.SPOKEN_CANTONESE
                    } else {
                        CantoneseFormat.WRITTEN_CHINESE
                    }
                    onSelectFormat(next)
                },
                onStartSandboxLive = onStartSandboxLive,
                onLockSandboxHandsFree = onLockSandboxHandsFree,
                onStopSandboxLive = onStopSandboxLive,
                onCancelSandboxLive = onCancelSandboxLive,
                onAppendSymbol = onAppendSymbol,
                onBackspace = onBackspaceSandbox,
                onUndoSandbox = onUndoSandbox,
                onOpenImePicker = openImePicker
            )

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

private data class ImeActivationStatus(
    val isEnabled: Boolean,
    val isActiveDefault: Boolean
)

private fun inspectImeStatus(context: Context): ImeActivationStatus {
    return try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
        val currentDefault = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()
        val isActive = currentDefault.startsWith(context.packageName)
        ImeActivationStatus(isEnabled = enabled, isActiveDefault = isActive)
    } catch (_: Exception) {
        ImeActivationStatus(isEnabled = false, isActiveDefault = false)
    }
}
