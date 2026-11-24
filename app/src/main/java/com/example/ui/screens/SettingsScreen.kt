package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.LocalStudioHaptics
import com.example.model.ApiKeyStatus
import com.example.model.KeyTestResult
import com.example.model.ThemePreference
import com.example.model.UserPreferences

@Composable
fun SettingsScreen(
    apiKeyStatus: ApiKeyStatus,
    geminiKeyTest: KeyTestResult,
    openAiKeyTest: KeyTestResult,
    preferences: UserPreferences,
    onSaveGeminiKey: (String) -> Unit,
    onClearGeminiKey: () -> Unit,
    onToggleUsePersonalGemini: (Boolean) -> Unit,
    onSaveCustomGeminiModel: (String) -> Unit,
    onTestGeminiKey: (String?) -> Unit,
    onSaveOpenAiKey: (String) -> Unit,
    onClearOpenAiKey: () -> Unit,
    onTestOpenAiKey: (String?) -> Unit,
    onSelectTheme: (ThemePreference) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onToggleAutoCopy: (Boolean) -> Unit,
    onToggleIncludeWritten: (Boolean) -> Unit,
    onSaveGlossary: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalStudioHaptics.current
    var geminiDraft by remember { mutableStateOf("") }
    var showGeminiInput by remember { mutableStateOf(false) }
    var modelDraft by remember(apiKeyStatus.customGeminiModel) {
        mutableStateOf(apiKeyStatus.customGeminiModel)
    }

    var openAiDraft by remember { mutableStateOf("") }
    var showOpenAiInput by remember { mutableStateOf(false) }

    var glossaryDraft by remember(preferences.customGlossary) {
        mutableStateOf(preferences.customGlossary)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "BYOK VAULT & STUDIO SETTINGS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "API Keys & Preferences",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Hardware Keystore privacy assurance ribbon
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Keys are encrypted locally with Android Keystore (AES-256-GCM), excluded from cloud backups, and sent only to Google Gemini or OpenAI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 1. Gemini Personal API Key Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Gemini API Key",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        StatusBadge(
                            label = apiKeyStatus.geminiProviderLabel,
                            highlighted = apiKeyStatus.isUsingDirectGemini
                        )
                    }

                    Text(
                        text = "Required for Gemini engines (Direct REST, Live WebSocket, Written Chinese conversion & Deep Review). Paste your Google AI Studio API key to call generativelanguage.googleapis.com directly — no Firebase used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (apiKeyStatus.hasUserGeminiKey) {
                        SavedKeyPill(
                            maskedKey = apiKeyStatus.maskedGeminiKey.orEmpty(),
                            onClear = {
                                haptics.error()
                                onClearGeminiKey()
                            },
                            clearTag = "clear_gemini_key_button"
                        )
                    }

                    OutlinedTextField(
                        value = geminiDraft,
                        onValueChange = { geminiDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_api_key_input"),
                        singleLine = true,
                        label = {
                            Text(
                                if (apiKeyStatus.hasUserGeminiKey) "Replace Gemini API Key"
                                else "Paste Gemini API Key (AIza…)"
                            )
                        },
                        visualTransformation = if (showGeminiInput) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    haptics.tick()
                                    showGeminiInput = !showGeminiInput
                                }
                            ) {
                                Icon(
                                    imageVector = if (showGeminiInput) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showGeminiInput) "Hide key" else "Show key"
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                onSaveGeminiKey(geminiDraft)
                                geminiDraft = ""
                            },
                            enabled = geminiDraft.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("save_gemini_key_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save Key")
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onTestGeminiKey(geminiDraft.takeIf { it.isNotBlank() })
                            },
                            enabled = geminiDraft.isNotBlank() || apiKeyStatus.hasUserGeminiKey,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("test_gemini_key_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Verify")
                        }
                    }

                    KeyTestBanner(result = geminiKeyTest)

                    AnimatedVisibility(visible = apiKeyStatus.hasUserGeminiKey) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "Optional Custom Model Override",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = modelDraft,
                                    onValueChange = { modelDraft = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("custom_gemini_model_input"),
                                    singleLine = true,
                                    placeholder = { Text("Default (gemini-2.5-flash)") },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                OutlinedButton(
                                    onClick = {
                                        haptics.confirm()
                                        onSaveCustomGeminiModel(modelDraft)
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_custom_model_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }
            }

            // 2. OpenAI Personal API Key Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "OpenAI API Key",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        StatusBadge(
                            label = apiKeyStatus.openAiProviderLabel,
                            highlighted = apiKeyStatus.isOpenAiConfigured
                        )
                    }

                    Text(
                        text = "Enables OpenAI gpt-transcribe & Realtime WebSocket live Cantonese streaming alongside Gemini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (apiKeyStatus.hasUserOpenAiKey) {
                        SavedKeyPill(
                            maskedKey = apiKeyStatus.maskedOpenAiKey.orEmpty(),
                            onClear = {
                                haptics.error()
                                onClearOpenAiKey()
                            },
                            clearTag = "clear_openai_key_button"
                        )
                    }

                    OutlinedTextField(
                        value = openAiDraft,
                        onValueChange = { openAiDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("openai_api_key_input"),
                        singleLine = true,
                        label = {
                            Text(
                                if (apiKeyStatus.hasUserOpenAiKey) "Replace OpenAI API Key"
                                else "Paste OpenAI API Key (sk-…)"
                            )
                        },
                        visualTransformation = if (showOpenAiInput) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    haptics.tick()
                                    showOpenAiInput = !showOpenAiInput
                                }
                            ) {
                                Icon(
                                    imageVector = if (showOpenAiInput) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showOpenAiInput) "Hide key" else "Show key"
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                onSaveOpenAiKey(openAiDraft)
                                openAiDraft = ""
                            },
                            enabled = openAiDraft.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("save_openai_key_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save Key")
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onTestOpenAiKey(openAiDraft.takeIf { it.isNotBlank() })
                            },
                            enabled = openAiDraft.isNotBlank() || apiKeyStatus.isOpenAiConfigured,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("test_openai_key_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Verify")
                        }
                    }

                    KeyTestBanner(result = openAiKeyTest)
                }
            }

            // 3. Direct API Dictation Enhancements Note
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Direct Provider API Mode",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "All Gemini and OpenAI transcription calls connect directly to Google AI Studio and OpenAI using your saved keys, with near-field noise reduction, deterministic temperature = 0 decoding, and prompt-leakage protection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Studio Appearance & Transcription Preferences
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(text = "Studio Preferences", style = MaterialTheme.typography.titleMedium)
                    }

                    // Theme segmented selector
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ThemePreference.entries.forEach { option ->
                                val selected = option == preferences.themePreference
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                    shadowElevation = if (selected) 1.dp else 0.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (!selected) haptics.tick()
                                            onSelectTheme(option)
                                        }
                                        .testTag("theme_option_${option.name.lowercase()}")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Haptic Feedback", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Tactile pulses for voice controls, keyboard keys, and confirmations",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = preferences.hapticsEnabled,
                            onCheckedChange = { checked ->
                                onToggleHaptics(checked)
                                if (checked) {
                                    haptics.enabled = true
                                    haptics.confirm()
                                }
                            },
                            modifier = Modifier.testTag("settings_haptics_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Copy Finished Transcripts", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Copy Cantonese output to clipboard automatically",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = preferences.autoCopyOnFinish,
                            onCheckedChange = { checked ->
                                haptics.toggle(checked)
                                onToggleAutoCopy(checked)
                            },
                            modifier = Modifier.testTag("settings_autocopy_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Generate 書面語 in Single Pass", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Return both Spoken & Written Chinese on imported clips",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = preferences.includeWrittenInAuto,
                            onCheckedChange = { checked ->
                                haptics.toggle(checked)
                                onToggleIncludeWritten(checked)
                            },
                            modifier = Modifier.testTag("settings_written_auto_switch")
                        )
                    }
                }
            }

            // 5. Custom HK Cantonese Vocabulary Hints
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                            imageVector = Icons.Outlined.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(text = "Cantonese Vocabulary Hints", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Prioritize Hong Kong names, districts, or work jargon across Gemini and OpenAI engines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = glossaryDraft,
                        onValueChange = { glossaryDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("glossary_input"),
                        singleLine = true,
                        placeholder = { Text("e.g. 將軍澳, K11, Jira, Standup, 埋單") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(
                        onClick = {
                            haptics.confirm()
                            onSaveGlossary(glossaryDraft)
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .minimumInteractiveComponentSize()
                            .testTag("save_glossary_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Glossary")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun StatusBadge(label: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun SavedKeyPill(
    maskedKey: String,
    onClear: () -> Unit,
    clearTag: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = maskedKey,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(clearTag)
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Remove saved key",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun KeyTestBanner(result: KeyTestResult) {
    when (result) {
        is KeyTestResult.Idle -> Unit
        is KeyTestResult.Testing -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Verifying key with provider…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is KeyTestResult.Valid -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = result.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        is KeyTestResult.Invalid -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = result.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
