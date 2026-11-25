package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.AppContainer
import com.example.core.LocalStudioHaptics
import com.example.model.AudioSourceType
import com.example.ui.components.rememberReduceMotionEnabled
import com.example.ui.components.studioGridBackground
import com.example.ui.screens.KeyboardScreen
import com.example.ui.screens.SessionScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.CantoScribeTheme
import com.example.viewmodel.StudioViewModel
import kotlinx.coroutines.flow.MutableStateFlow

enum class AppDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    STUDIO("studio", "Studio", Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq),
    KEYBOARD("keyboard", "Keyboard", Icons.Filled.KeyboardVoice, Icons.Outlined.KeyboardVoice),
    SESSION("session", "Session", Icons.Filled.History, Icons.Outlined.History),
    KEYS("keys", "Keys", Icons.Filled.Key, Icons.Outlined.Key)
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START_LIVE = "extra_auto_start_live"
    }

    private val incomingSharedAudioUri = MutableStateFlow<Uri?>(null)
    private val openKeyboardTabFlag = MutableStateFlow(false)
    private val autoStartLiveFlag = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Initialize shared AppContainer.
        AppContainer.ensureInitialized(applicationContext)

        // STEP 2: Extract incoming WhatsApp/audio share or Quick Settings Tile intent.
        extractIncomingIntent(intent)

        enableEdgeToEdge()
        setContent {
            val viewModel: StudioViewModel = viewModel()
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()

            val studioHaptics = AppContainer.studioHaptics
            SideEffect {
                studioHaptics.enabled = preferences.hapticsEnabled
            }

            CompositionLocalProvider(LocalStudioHaptics provides studioHaptics) {
                CantoScribeTheme(themePreference = preferences.themePreference) {
                    val pendingUri by incomingSharedAudioUri.collectAsStateWithLifecycle()
                    val shouldOpenKeyboard by openKeyboardTabFlag.collectAsStateWithLifecycle()
                    val shouldAutoStartLive by autoStartLiveFlag.collectAsStateWithLifecycle()

                    CantoScribeRoot(
                        viewModel = viewModel,
                        pendingSharedUri = pendingUri,
                        onConsumeSharedUri = { incomingSharedAudioUri.value = null },
                        shouldOpenKeyboardTab = shouldOpenKeyboard,
                        onConsumeOpenKeyboardTab = { openKeyboardTabFlag.value = false },
                        shouldAutoStartLive = shouldAutoStartLive,
                        onConsumeAutoStartLive = { autoStartLiveFlag.value = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingIntent(intent)
    }

    private fun extractIncomingIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra("request_mic_from_ime", false)) {
            openKeyboardTabFlag.value = true
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_START_LIVE, false)) {
            autoStartLiveFlag.value = true
        }

        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> getParcelableUri(intent, Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE -> getParcelableUriList(intent, Intent.EXTRA_STREAM)?.firstOrNull()
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }

        if (uri != null) {
            incomingSharedAudioUri.value = uri
        }
    }

    private fun getParcelableUri(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }

    private fun getParcelableUriList(intent: Intent, key: String): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(key)
        }
    }
}

@Composable
private fun CantoScribeRoot(
    viewModel: StudioViewModel,
    pendingSharedUri: Uri?,
    onConsumeSharedUri: () -> Unit,
    shouldOpenKeyboardTab: Boolean,
    onConsumeOpenKeyboardTab: () -> Unit,
    shouldAutoStartLive: Boolean,
    onConsumeAutoStartLive: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalStudioHaptics.current
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val apiKeyStatus by viewModel.apiKeyStatus.collectAsStateWithLifecycle()
    val geminiKeyTest by viewModel.geminiKeyTest.collectAsStateWithLifecycle()
    val openAiKeyTest by viewModel.openAiKeyTest.collectAsStateWithLifecycle()
    val displayFormat by viewModel.displayFormat.collectAsStateWithLifecycle()
    val sessionRecords by viewModel.sessionRecords.collectAsStateWithLifecycle()
    val sandboxState by viewModel.sandboxState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val autoCopyText by viewModel.autoCopyTextEvent.collectAsStateWithLifecycle()

    var currentDestination by remember { mutableStateOf(AppDestination.STUDIO) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            viewModel.postToast("Microphone access is required for live Cantonese streaming.")
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            currentDestination = AppDestination.STUDIO
            viewModel.handleSharedAudioUri(uri, AudioSourceType.AUDIO_IMPORT)
        }
    }

    val copyToClipboard: (String, String) -> Unit = { text, toast ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("CantoScribe Transcript", text))
        haptics.confirm()
        viewModel.postToast(toast)
    }

    LaunchedEffect(pendingSharedUri) {
        if (pendingSharedUri != null) {
            currentDestination = AppDestination.STUDIO
            viewModel.handleSharedAudioUri(pendingSharedUri, AudioSourceType.WHATSAPP_SHARE)
            onConsumeSharedUri()
        }
    }

    LaunchedEffect(shouldOpenKeyboardTab) {
        if (shouldOpenKeyboardTab) {
            currentDestination = AppDestination.KEYBOARD
            if (!hasMicPermission) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            onConsumeOpenKeyboardTab()
        }
    }

    LaunchedEffect(shouldAutoStartLive) {
        if (shouldAutoStartLive) {
            currentDestination = AppDestination.STUDIO
            if (hasMicPermission) {
                viewModel.startStudioLive(isHoldToTalk = false)
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            onConsumeAutoStartLive()
        }
    }

    LaunchedEffect(autoCopyText) {
        if (!autoCopyText.isNullOrBlank()) {
            copyToClipboard(autoCopyText!!, "Auto-copied to clipboard")
            viewModel.consumeAutoCopyEvent()
        }
    }

    LaunchedEffect(snackbarMessage) {
        if (!snackbarMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(snackbarMessage!!)
            viewModel.consumeSnackbar()
        }
    }

    BackHandler(enabled = currentDestination != AppDestination.STUDIO) {
        haptics.tick()
        currentDestination = AppDestination.STUDIO
    }

    val shareText: (String) -> Unit = { text ->
        haptics.tick()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Cantonese Transcript"))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .studioGridBackground(
                dotColor = MaterialTheme.colorScheme.outline,
                primaryGlow = MaterialTheme.colorScheme.primary,
                tertiaryGlow = MaterialTheme.colorScheme.tertiary
            )
    ) {
        val useNavRail = maxWidth >= 680.dp

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 2.dp,
                        windowInsets = NavigationBarDefaults.windowInsets
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = currentDestination == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) haptics.tick()
                                    currentDestination = dest
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = {
                                    val labelText = if (dest == AppDestination.SESSION && sessionRecords.isNotEmpty()) {
                                        "${dest.label} (${sessionRecords.size})"
                                    } else {
                                        dest.label
                                    }
                                    Text(labelText)
                                },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = currentDestination == dest
                            val railLabel = if (dest == AppDestination.SESSION && sessionRecords.isNotEmpty()) {
                                "${dest.label} (${sessionRecords.size})"
                            } else {
                                dest.label
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) haptics.tick()
                                    currentDestination = dest
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = railLabel
                                    )
                                },
                                label = { Text(railLabel) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }

                val reduceMotion = rememberReduceMotionEnabled()
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = currentDestination,
                        transitionSpec = {
                            if (reduceMotion) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else {
                                fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                            }
                        },
                        label = "main_tab_content"
                    ) { destination ->
                        when (destination) {
                            AppDestination.STUDIO -> StudioScreen(
                            uiState = uiState,
                            preferences = preferences,
                            displayFormat = displayFormat,
                            apiKeyStatus = apiKeyStatus,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onSelectRoutingMode = viewModel::setRoutingMode,
                            onToggleAutoCopy = viewModel::setAutoCopyOnFinish,
                            onStartLive = viewModel::startStudioLive,
                            onLockHandsFree = viewModel::lockStudioHandsFree,
                            onStopLive = viewModel::stopStudioLive,
                            onCancelLive = viewModel::cancelStudioLive,
                            onImportAudio = {
                                audioPickerLauncher.launch(arrayOf("audio/*", "application/ogg"))
                            },
                            onOpenKeyboardTab = { currentDestination = AppDestination.KEYBOARD },
                            onOpenKeysTab = { currentDestination = AppDestination.KEYS },
                            onSelectFormat = viewModel::selectDisplayFormat,
                            onCopyText = { copyToClipboard(it, "Copied to clipboard") },
                            onShareText = shareText,
                            onRunDeepReview = viewModel::runDeepReviewOnCurrent,
                            onRetry = viewModel::retryLastAudio
                        )

                        AppDestination.KEYBOARD -> KeyboardScreen(
                            sandboxState = sandboxState,
                            preferences = preferences,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onSelectRoutingMode = viewModel::setRoutingMode,
                            onSelectFormat = viewModel::selectDisplayFormat,
                            onStartSandboxLive = viewModel::startSandboxLive,
                            onLockSandboxHandsFree = viewModel::lockSandboxHandsFree,
                            onStopSandboxLive = viewModel::stopSandboxLive,
                            onCancelSandboxLive = viewModel::cancelSandboxLive,
                            onSandboxTextChange = viewModel::updateSandboxText,
                            onAppendSymbol = viewModel::appendSandboxSymbol,
                            onBackspaceSandbox = viewModel::backspaceSandbox,
                            onUndoSandbox = viewModel::undoSandboxLastDictation,
                            onClearSandbox = viewModel::clearSandbox,
                            onCopySandbox = { copyToClipboard(it, "Copied sandbox text") }
                        )

                        AppDestination.SESSION -> SessionScreen(
                            records = sessionRecords,
                            onOpenRecord = { record ->
                                viewModel.openSessionRecord(record)
                                currentDestination = AppDestination.STUDIO
                            },
                            onCopyText = { copyToClipboard(it, "Copied to clipboard") },
                            onDeleteRecord = viewModel::removeSessionRecord,
                            onClearAll = viewModel::clearAllSessionHistory
                        )

                        AppDestination.KEYS -> SettingsScreen(
                            apiKeyStatus = apiKeyStatus,
                            geminiKeyTest = geminiKeyTest,
                            openAiKeyTest = openAiKeyTest,
                            preferences = preferences,
                            onSaveGeminiKey = viewModel::saveGeminiApiKey,
                            onClearGeminiKey = viewModel::clearGeminiApiKey,
                            onToggleUsePersonalGemini = viewModel::setUsePersonalGeminiKey,
                            onSaveCustomGeminiModel = viewModel::saveCustomGeminiModel,
                            onTestGeminiKey = viewModel::testGeminiKey,
                            onSaveOpenAiKey = viewModel::saveOpenAiApiKey,
                            onClearOpenAiKey = viewModel::clearOpenAiApiKey,
                            onTestOpenAiKey = viewModel::testOpenAiKey,
                            onSelectTheme = viewModel::setThemePreference,
                            onToggleHaptics = viewModel::setHapticsEnabled,
                            onToggleAutoCopy = viewModel::setAutoCopyOnFinish,
                            onToggleIncludeWritten = viewModel::setIncludeWrittenInAuto,
                            onSaveGlossary = viewModel::setCustomGlossary
                        )
                        }
                    }
                }
            }
        }
    }
}
