package com.example.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.LiveFinalizeResult
import com.example.core.AppContainer
import com.example.model.AudioSourceType
import com.example.model.CantoneseFormat
import com.example.model.RoutingMode
import com.example.model.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Enhanced System-Wide Real-Time Cantonese Voice Keyboard (`InputMethodService`).
 *
 * Features:
 * - Adaptive Porcelain Light / Nocturne Obsidian Dark studio theme synced with user preferences.
 * - Symmetrical 28-bar live acoustic waveform (`ImeWaveformView`) + live composing preview strip.
 * - Dual-mode quick keycap row: Cantonese punctuation & particles <-> HK quick reply phrases (`詞`).
 * - Sculpted Dual-Gesture Voice Deck (Hold to Speak OR Quick Tap for Hands-Free) + inline Cancel.
 * - Hold-to-repeat Backspace, EditorInfo-aware primary action key, and full semantic `StudioHaptics`.
 */
class CantoVoiceImeService : InputMethodService() {

    private val imeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isLiveStreaming = false
    private var isHoldGestureLocked = false
    private var pressDownTimestampMs = 0L
    private var routingMode = RoutingMode.AUTO
    private var formatMode = CantoneseFormat.SPOKEN_CANTONESE
    private var themePreference = ThemePreference.SYSTEM
    private var showQuickPhrases = false
    private var lastCommittedLength = 0
    private var activeComposingText = ""
    private var currentActionLabel = "Send ↵"

    private var palette: ImePalette = ImePalette.Dark

    private var liveObserverJob: Job? = null
    private var prefsJob: Job? = null
    private var backspaceRepeatJob: Job? = null

    private var rootLayout: LinearLayout? = null
    private var statusDot: View? = null
    private var statusTextView: TextView? = null
    private var previewBanner: TextView? = null
    private var engineChip: TextView? = null
    private var formatChip: TextView? = null
    private var phrasesChip: TextView? = null
    private var quickStripRow: LinearLayout? = null
    private var waveformView: ImeWaveformView? = null
    private var micPadContainer: LinearLayout? = null
    private var micBadgeCircle: FrameLayout? = null
    private var micIconView: ImageView? = null
    private var micTitleView: TextView? = null
    private var micSubtitleView: TextView? = null
    private var cancelPadButton: FrameLayout? = null
    private var primaryActionKey: TextView? = null

    private val punctSymbols = listOf("，", "。", "？", "！", "、", "啦", "㗎")
    private val hkQuickPhrases = listOf("唔該晒", "好呀", "收到", "等陣", "冇問題", "多謝")

    override fun onCreate() {
        super.onCreate()
        AppContainer.ensureInitialized(applicationContext)
        palette = ImePalette.resolve(this, themePreference)
        prefsJob = imeScope.launch {
            AppContainer.preferencesRepository.preferencesFlow.collectLatest { prefs ->
                routingMode = prefs.routingMode
                formatMode = if (prefs.defaultFormat == CantoneseFormat.WRITTEN_CHINESE) {
                    CantoneseFormat.WRITTEN_CHINESE
                } else {
                    CantoneseFormat.SPOKEN_CANTONESE
                }
                AppContainer.studioHaptics.enabled = prefs.hapticsEnabled
                val themeChanged = themePreference != prefs.themePreference
                themePreference = prefs.themePreference
                palette = ImePalette.resolve(this@CantoVoiceImeService, themePreference)
                if (themeChanged && rootLayout != null) {
                    setInputView(onCreateInputView())
                } else {
                    refreshControlChips()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        palette = ImePalette.resolve(this, themePreference)
        val baseBottomPad = dp(12)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.canvasBg)
            setPadding(dp(10), dp(10), dp(10), baseBottomPad)
        }
        rootLayout = root

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(dp(10), dp(10), dp(10), baseBottomPad + navBottom)
            insets
        }

        // Row 1: Studio Telemetry Header (live dot + status + engine + format + quick phrases + open app)
        root.addView(buildTopTelemetryBar())

        // Row 2: Live composing preview capsule (visible while streaming or showing feedback)
        val preview = TextView(this).apply {
            text = "講廣東話 · Live Cantonese streams directly into your text box"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = sculptedKeyDrawable(
                fillColor = palette.previewBg,
                strokeColor = palette.keyStroke,
                rippleColor = Color.TRANSPARENT,
                radiusPx = dp(10).toFloat(),
                strokeWidthPx = dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        previewBanner = preview
        root.addView(preview)

        // Row 3: Dual-Mode Cantonese Quick Keycap Strip (Punctuation/Particles OR HK Quick Phrases)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        quickStripRow = quickRow
        rebuildQuickStrip()
        root.addView(quickRow)

        // Row 4: Symmetrical 28-Bar Live Acoustic Waveform
        val wave = ImeWaveformView(this).apply {
            applyPalette(
                activeCrimson = palette.crimsonAccent,
                idleAccent = palette.jadeAccent,
                trackColor = palette.textSecondary
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30)
            ).apply { bottomMargin = dp(8) }
        }
        waveformView = wave
        root.addView(wave)

        // Row 5: Sculpted Dual-Gesture Voice Deck + Inline Cancel Button
        root.addView(buildVoicePadRow())

        // Row 6: Tactile Bottom Navigation & Editing Row
        root.addView(buildBottomActionRow())

        refreshControlChips()
        updateVoicePadUi()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        palette = ImePalette.resolve(this, themePreference)
        currentActionLabel = resolveEditorActionLabel(info)
        primaryActionKey?.text = currentActionLabel
        refreshControlChips()
        updateVoicePadUi()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopBackspaceRepeat()
        if (isLiveStreaming) {
            stopAndDiscardSafely()
        }
    }

    override fun onDestroy() {
        stopBackspaceRepeat()
        stopAndDiscardSafely()
        prefsJob?.cancel()
        imeScope.cancel()
        super.onDestroy()
    }

    private fun buildTopTelemetryBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val dot = View(this).apply {
            background = circleDrawable(palette.jadeAccent)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(7)
            }
        }
        statusDot = dot
        bar.addView(dot)

        val statusTv = TextView(this).apply {
            text = "CANTOSCRIBE · 粵語"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTextView = statusTv
        bar.addView(statusTv)

        val engineTv = createControlChip("Auto") { v ->
            AppContainer.studioHaptics.tick(v)
            val next = when (routingMode) {
                RoutingMode.AUTO -> RoutingMode.GEMINI_ONLY
                RoutingMode.GEMINI_ONLY -> RoutingMode.OPENAI_ONLY
                RoutingMode.OPENAI_ONLY -> RoutingMode.AUTO
            }
            routingMode = next
            imeScope.launch { AppContainer.preferencesRepository.setRoutingMode(next) }
            refreshControlChips()
        }
        engineChip = engineTv
        bar.addView(engineTv)

        val formatTv = createControlChip("口語") { v ->
            AppContainer.studioHaptics.tick(v)
            val next = if (formatMode == CantoneseFormat.SPOKEN_CANTONESE) {
                CantoneseFormat.WRITTEN_CHINESE
            } else {
                CantoneseFormat.SPOKEN_CANTONESE
            }
            formatMode = next
            imeScope.launch { AppContainer.preferencesRepository.setDefaultFormat(next) }
            refreshControlChips()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        }
        formatChip = formatTv
        bar.addView(formatTv)

        val phraseTv = createControlChip("詞") { v ->
            AppContainer.studioHaptics.tick(v)
            showQuickPhrases = !showQuickPhrases
            refreshControlChips()
            rebuildQuickStrip()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        }
        phrasesChip = phraseTv
        bar.addView(phraseTv)

        val studioBtn = createControlChip("↗") { v ->
            AppContainer.studioHaptics.tick(v)
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.apply {
            contentDescription = "Open CantoScribe Studio"
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        }
        bar.addView(studioBtn)

        return bar
    }

    private fun rebuildQuickStrip() {
        val row = quickStripRow ?: return
        row.removeAllViews()
        if (showQuickPhrases) {
            hkQuickPhrases.forEachIndexed { index, phrase ->
                val isLast = index == hkQuickPhrases.lastIndex
                row.addView(
                    createQuickKey(
                        label = phrase,
                        accent = false,
                        compactText = true,
                        isLast = isLast
                    ) { v ->
                        AppContainer.studioHaptics.keyTap(v)
                        commitToHost(phrase, trackAsDictation = true)
                        previewBanner?.text = "Inserted \"$phrase\""
                    }
                )
            }
        } else {
            punctSymbols.forEach { symbol ->
                row.addView(
                    createQuickKey(symbol, accent = false, compactText = false, isLast = false) { v ->
                        AppContainer.studioHaptics.keyTap(v)
                        commitToHost(symbol, trackAsDictation = false)
                    }
                )
            }
            row.addView(
                createQuickKey("Undo", accent = true, compactText = true, isLast = true) { v ->
                    if (lastCommittedLength > 0) {
                        AppContainer.studioHaptics.keyTap(v)
                        undoLastDictation()
                    } else {
                        AppContainer.studioHaptics.tick(v)
                    }
                }
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildVoicePadRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66)
            ).apply { bottomMargin = dp(9) }
        }

        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = gradientPadDrawable(
                startColor = palette.jadeStart,
                endColor = palette.jadeEnd,
                strokeColor = palette.jadeAccent,
                radiusPx = dp(18).toFloat(),
                strokeWidthPx = dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            contentDescription = "Hold to speak or tap for hands-free Cantonese voice typing"
        }

        val badge = FrameLayout(this).apply {
            background = circleDrawable(Color.argb(46, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(12)
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_ime_mic)
            setColorFilter(palette.jadeOnPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        badge.addView(icon)
        micBadgeCircle = badge
        micIconView = icon
        pad.addView(badge)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "Hold to Speak · Tap Hands-Free"
            setTextColor(palette.jadeOnPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
        }
        val subtitleTv = TextView(this).apply {
            text = "Live 粵語 · Zero-wait real-time streaming"
            setTextColor(Color.argb(215, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        }
        micTitleView = titleTv
        micSubtitleView = subtitleTv
        textCol.addView(titleTv)
        textCol.addView(subtitleTv)
        pad.addView(textCol)

        pad.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(85L).start()
                    pressDownTimestampMs = System.currentTimeMillis()
                    if (!isLiveStreaming) {
                        isHoldGestureLocked = false
                        AppContainer.studioHaptics.recordStart(v)
                        startLiveDictation()
                    } else if (isHoldGestureLocked) {
                        AppContainer.studioHaptics.recordStop(v)
                        stopLiveDictation()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                    val heldMs = System.currentTimeMillis() - pressDownTimestampMs
                    if (isLiveStreaming && !isHoldGestureLocked) {
                        if (heldMs < 320L) {
                            isHoldGestureLocked = true
                            AppContainer.studioHaptics.handsFreeLock(v)
                            updateVoicePadUi()
                        } else {
                            AppContainer.studioHaptics.recordStop(v)
                            stopLiveDictation()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        micPadContainer = pad
        row.addView(pad)

        val cancelBtn = FrameLayout(this).apply {
            visibility = View.GONE
            background = sculptedKeyDrawable(
                fillColor = palette.keyBg,
                strokeColor = palette.keyStroke,
                rippleColor = palette.ripple,
                radiusPx = dp(18).toFloat(),
                strokeWidthPx = dp(1)
            )
            contentDescription = "Cancel live dictation"
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(8)
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_ime_close)
                    setColorFilter(palette.textSecondary)
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                }
            )
            setOnClickListener { v ->
                AppContainer.studioHaptics.error(v)
                stopAndDiscardSafely()
                updateVoicePadUi()
                statusTextView?.text = "CANTOSCRIBE · 粵語"
                previewBanner?.text = "Dictation cancelled"
            }
        }
        cancelPadButton = cancelBtn
        row.addView(cancelBtn)

        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBottomActionRow(): LinearLayout {
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        }

        // Globe / Switch keyboard key (tap = previous IME, long-press = picker)
        val switchBtn = FrameLayout(this).apply {
            background = sculptedKeyDrawable(
                fillColor = palette.surface,
                strokeColor = palette.keyStroke,
                rippleColor = palette.ripple,
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = dp(1)
            )
            contentDescription = "Switch keyboard"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.05f).apply {
                marginEnd = dp(6)
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_ime_globe)
                    setColorFilter(palette.textPrimary)
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                }
            )
            setOnClickListener { v ->
                AppContainer.studioHaptics.keyTap(v)
                switchKeyboard(forcePicker = false)
            }
            setOnLongClickListener { v ->
                AppContainer.studioHaptics.handsFreeLock(v)
                switchKeyboard(forcePicker = true)
                true
            }
        }
        bottomRow.addView(switchBtn)

        // Wide sculpted Space bar
        val spaceKey = TextView(this).apply {
            text = "空格 · Space"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = sculptedKeyDrawable(
                fillColor = palette.keyBg,
                strokeColor = palette.keyStroke,
                rippleColor = palette.ripple,
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.35f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { v ->
                AppContainer.studioHaptics.keyTap(v)
                commitToHost(" ", trackAsDictation = false)
            }
        }
        bottomRow.addView(spaceKey)

        // Hold-to-repeat Backspace key
        val backspaceBtn = FrameLayout(this).apply {
            background = sculptedKeyDrawable(
                fillColor = palette.surface,
                strokeColor = palette.keyStroke,
                rippleColor = palette.ripple,
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = dp(1)
            )
            contentDescription = "Backspace"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f).apply {
                marginEnd = dp(6)
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_ime_backspace)
                    setColorFilter(palette.textPrimary)
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                }
            )
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70L).start()
                        AppContainer.studioHaptics.keyTap(v)
                        deleteSingleChar()
                        startBackspaceRepeat(v)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        v.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                        stopBackspaceRepeat()
                        true
                    }
                    else -> false
                }
            }
        }
        bottomRow.addView(backspaceBtn)

        // Dynamic EditorInfo-aware Primary Action Key (Send / Search / Done / Next / Enter)
        val actionKey = TextView(this).apply {
            text = currentActionLabel
            setTextColor(palette.jadeOnPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = sculptedKeyDrawable(
                fillColor = palette.jadeStart,
                strokeColor = palette.jadeAccent,
                rippleColor = Color.argb(60, 255, 255, 255),
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.35f)
            setOnClickListener { v ->
                AppContainer.studioHaptics.keyTap(v)
                if (isLiveStreaming) stopLiveDictation()
                triggerEditorAction()
            }
        }
        primaryActionKey = actionKey
        bottomRow.addView(actionKey)

        return bottomRow
    }

    private fun startLiveDictation() {
        if (!hasMicPermission()) {
            AppContainer.studioHaptics.error()
            statusTextView?.text = "MIC ACCESS NEEDED"
            previewBanner?.text = "Grant microphone access in CantoScribe to start live voice typing"
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("request_mic_from_ime", true)
            }
            startActivity(intent)
            return
        }

        imeScope.launch {
            val prefs = AppContainer.preferencesRepository.currentPreferences().copy(
                routingMode = routingMode,
                defaultFormat = formatMode
            )

            val started = AppContainer.liveTranscriptionController.startLiveStream(
                scope = imeScope,
                preferences = prefs,
                sourceType = AudioSourceType.KEYBOARD_LIVE,
                onPartial = { partial ->
                    if (partial.isBlank()) {
                        if (activeComposingText.isNotEmpty()) {
                            currentInputConnection?.setComposingText("", 1)
                            activeComposingText = ""
                        }
                    } else {
                        activeComposingText = partial
                        currentInputConnection?.setComposingText(partial, 1)
                        previewBanner?.text = "Streaming: $partial ▍"
                        previewBanner?.setTextColor(palette.jadeAccent)
                    }
                },
                onCommitSegment = { segment ->
                    activeComposingText = ""
                    commitToHost(segment, trackAsDictation = true)
                    AppContainer.studioHaptics.phraseCommit()
                    previewBanner?.text = "Committed: $segment"
                    previewBanner?.setTextColor(palette.textPrimary)
                }
            )

            if (started.isFailure) {
                AppContainer.studioHaptics.error()
                val err = started.exceptionOrNull()?.message?.take(64) ?: "Unable to start live mic"
                statusTextView?.text = "MIC ERROR"
                previewBanner?.text = err
                previewBanner?.setTextColor(palette.crimsonAccent)
                return@launch
            }

            isLiveStreaming = true
            updateVoicePadUi()

            liveObserverJob?.cancel()
            liveObserverJob = imeScope.launch {
                AppContainer.liveTranscriptionController.state.collectLatest { state ->
                    val secs = state.metrics.elapsedMillis / 1000.0
                    statusTextView?.text = when {
                        !state.errorMessage.isNullOrBlank() -> "LIVE ISSUE"
                        state.isConnecting -> "CONNECTING…"
                        else -> String.format(
                            Locale.US,
                            "LIVE · %.1fs · %s",
                            secs,
                            state.engineLabel.substringBefore("·").trim()
                        )
                    }
                    if (!state.errorMessage.isNullOrBlank()) {
                        previewBanner?.text = state.errorMessage.take(72)
                        previewBanner?.setTextColor(palette.crimsonAccent)
                    } else if (state.partialText.isBlank() && state.confirmedText.isBlank()) {
                        previewBanner?.text = if (state.isConnecting) {
                            "Connecting live Cantonese stream…"
                        } else {
                            "講廣東話啦… Streaming directly into active field"
                        }
                        previewBanner?.setTextColor(palette.textSecondary)
                    }
                    waveformView?.updateWaveform(state.metrics.waveformHistory, live = true)
                }
            }
        }
    }

    private fun stopLiveDictation() {
        if (!isLiveStreaming) return
        isLiveStreaming = false
        isHoldGestureLocked = false
        updateVoicePadUi()

        imeScope.launch {
            // Do NOT call finishComposingText() before stopAndFinalize() — doing so bakes the live
            // partial hypothesis into the host field, and then the incoming `.completed` event commits
            // the same phrase a second time. Leaving the composing span active allows `commitText`
            // inside `onCommitSegment` to replace it cleanly with zero duplication.
            statusTextView?.text = "FINALIZING…"
            val result = AppContainer.liveTranscriptionController.stopAndFinalize(saveToSession = true)
            if (activeComposingText.isNotEmpty()) {
                // Only finalize composing text if no final `.completed` segment replaced it.
                currentInputConnection?.finishComposingText()
                activeComposingText = ""
            }
            liveObserverJob?.cancel()
            liveObserverJob = null
            waveformView?.updateWaveform(emptyList(), live = false)
            when (result) {
                is LiveFinalizeResult.Success -> {
                    AppContainer.studioHaptics.confirm()
                    statusTextView?.text = "DONE · ${result.record.engineUsed.displayName.uppercase(Locale.US)}"
                    previewBanner?.text = result.record.spokenCantonese
                    previewBanner?.setTextColor(palette.textPrimary)
                }
                is LiveFinalizeResult.Error -> {
                    AppContainer.studioHaptics.error()
                    statusTextView?.text = "TRANSCRIPTION ISSUE"
                    previewBanner?.text = result.message.take(72)
                    previewBanner?.setTextColor(palette.crimsonAccent)
                }
                LiveFinalizeResult.Empty -> {
                    statusTextView?.text = "CANTOSCRIBE · 粵語"
                    previewBanner?.text = "No speech detected — hold to speak or tap hands-free"
                    previewBanner?.setTextColor(palette.textSecondary)
                }
            }
        }
    }

    private fun stopAndDiscardSafely() {
        isLiveStreaming = false
        isHoldGestureLocked = false
        liveObserverJob?.cancel()
        liveObserverJob = null
        if (activeComposingText.isNotEmpty()) {
            currentInputConnection?.setComposingText("", 1)
            activeComposingText = ""
        }
        currentInputConnection?.finishComposingText()
        AppContainer.liveTranscriptionController.stopAndDiscard()
        waveformView?.updateWaveform(emptyList(), live = false)
    }

    private fun deleteSingleChar() {
        currentInputConnection?.finishComposingText()
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (lastCommittedLength > 0) {
            lastCommittedLength = (lastCommittedLength - 1).coerceAtLeast(0)
        }
    }

    private fun startBackspaceRepeat(view: View) {
        stopBackspaceRepeat()
        backspaceRepeatJob = imeScope.launch {
            delay(380L)
            while (isActive) {
                deleteSingleChar()
                AppContainer.studioHaptics.backspaceTick(view)
                delay(65L)
            }
        }
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatJob?.cancel()
        backspaceRepeatJob = null
    }

    private fun commitToHost(text: String, trackAsDictation: Boolean) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        if (trackAsDictation) {
            lastCommittedLength = text.length
        }
    }

    private fun undoLastDictation() {
        if (lastCommittedLength <= 0) return
        currentInputConnection?.finishComposingText()
        currentInputConnection?.deleteSurroundingText(lastCommittedLength, 0)
        lastCommittedLength = 0
        previewBanner?.text = "Undid last live phrase"
        previewBanner?.setTextColor(palette.textSecondary)
    }

    private fun triggerEditorAction() {
        val ic = currentInputConnection ?: return
        val handled = sendDefaultEditorAction(true)
        if (!handled) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun resolveEditorActionLabel(info: EditorInfo?): String {
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_SEND -> "Send ↵"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_GO -> "Go →"
            EditorInfo.IME_ACTION_NEXT -> "Next →"
            EditorInfo.IME_ACTION_DONE -> "Done ✓"
            else -> "Return ↵"
        }
    }

    private fun switchKeyboard(forcePicker: Boolean) {
        val switched = if (!forcePicker && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            false
        }
        if (!switched) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshControlChips() {
        engineChip?.text = when (routingMode) {
            RoutingMode.AUTO -> "Auto"
            RoutingMode.GEMINI_ONLY -> "Gemini"
            RoutingMode.OPENAI_ONLY -> "OpenAI"
        }
        formatChip?.text = if (formatMode == CantoneseFormat.WRITTEN_CHINESE) "書面語" else "口語"
        phrasesChip?.let { chip ->
            chip.background = sculptedKeyDrawable(
                fillColor = if (showQuickPhrases) palette.keyAccentBg else palette.surface,
                strokeColor = if (showQuickPhrases) palette.jadeAccent else palette.keyStroke,
                rippleColor = palette.ripple,
                radiusPx = dp(10).toFloat(),
                strokeWidthPx = dp(1)
            )
            chip.setTextColor(if (showQuickPhrases) palette.jadeAccent else palette.textSecondary)
        }
    }

    private fun updateVoicePadUi() {
        val pad = micPadContainer ?: return
        val icon = micIconView ?: return
        val title = micTitleView ?: return
        val subtitle = micSubtitleView ?: return

        statusDot?.background = circleDrawable(
            if (isLiveStreaming) palette.crimsonAccent else palette.jadeAccent
        )
        cancelPadButton?.visibility = if (isLiveStreaming) View.VISIBLE else View.GONE

        when {
            isLiveStreaming && isHoldGestureLocked -> {
                icon.setImageResource(R.drawable.ic_ime_stop)
                title.text = "Hands-Free Live · Tap to Stop"
                subtitle.text = "Keep speaking Cantonese · Tap pad when finished"
                pad.background = gradientPadDrawable(
                    startColor = palette.crimsonStart,
                    endColor = palette.crimsonEnd,
                    strokeColor = palette.crimsonAccent,
                    radiusPx = dp(18).toFloat(),
                    strokeWidthPx = dp(1)
                )
            }
            isLiveStreaming -> {
                icon.setImageResource(R.drawable.ic_ime_waveform)
                title.text = "Live Streaming · Release to Finish"
                subtitle.text = "Streaming real-time Cantonese into host app"
                pad.background = gradientPadDrawable(
                    startColor = palette.crimsonStart,
                    endColor = palette.crimsonEnd,
                    strokeColor = palette.crimsonAccent,
                    radiusPx = dp(18).toFloat(),
                    strokeWidthPx = dp(1)
                )
            }
            else -> {
                icon.setImageResource(R.drawable.ic_ime_mic)
                title.text = "Hold to Speak · Tap Hands-Free"
                subtitle.text = "Live 粵語 · Zero-wait real-time streaming"
                pad.background = gradientPadDrawable(
                    startColor = palette.jadeStart,
                    endColor = palette.jadeEnd,
                    strokeColor = palette.jadeAccent,
                    radiusPx = dp(18).toFloat(),
                    strokeWidthPx = dp(1)
                )
            }
        }
    }

    private fun createControlChip(label: String, onClick: (View) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(palette.jadeAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(32)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = sculptedKeyDrawable(
                fillColor = palette.surface,
                strokeColor = palette.keyStroke,
                rippleColor = palette.ripple,
                radiusPx = dp(10).toFloat(),
                strokeWidthPx = dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick(it) }
        }
    }

    private fun createQuickKey(
        label: String,
        accent: Boolean,
        compactText: Boolean,
        isLast: Boolean,
        onClick: (View) -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(if (accent) palette.jadeAccent else palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactText) 11.5f else 15f)
            typeface = Typeface.create(Typeface.DEFAULT, if (accent || compactText) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            background = sculptedKeyDrawable(
                fillColor = if (accent) palette.keyAccentBg else palette.keyBg,
                strokeColor = if (accent) palette.jadeAccent else palette.keyStroke,
                rippleColor = palette.ripple,
                radiusPx = dp(10).toFloat(),
                strokeWidthPx = dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (!isLast) marginEnd = dp(4)
            }
            setOnClickListener { onClick(it) }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
