package com.example.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.core.AppContainer
import com.example.model.AudioSourceType
import com.example.model.CantoneseFormat
import com.example.model.RoutingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * System-wide Real-Time Cantonese Voice Keyboard (`InputMethodService`).
 *
 * - Streams live Cantonese directly into the active text box WHILE speaking using
 *   `setComposingText` for real-time partial previews and `commitText` as each phrase completes
 *   — never waiting until recording ends to start transcribing.
 * - Supports BOTH effortless gestures on the central mic pad:
 *   - **Hold to Speak**: press & hold for quick sentences, release to auto-finish.
 *   - **Quick Tap**: tap (<300ms) to lock hands-free continuous live dictation; tap again to stop.
 */
class CantoVoiceImeService : InputMethodService() {

    private val imeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isLiveStreaming = false
    private var isHoldGestureLocked = false
    private var pressDownTimestampMs = 0L
    private var routingMode = RoutingMode.AUTO
    private var formatMode = CantoneseFormat.SPOKEN_CANTONESE
    private var lastCommittedLength = 0

    private var liveObserverJob: Job? = null
    private var prefsJob: Job? = null

    private var statusTextView: TextView? = null
    private var engineChip: TextView? = null
    private var formatChip: TextView? = null
    private var micButton: Button? = null
    private var levelBar: ProgressBar? = null

    private val colorBg = Color.parseColor("#0F172A")
    private val colorSurface = Color.parseColor("#1E293B")
    private val colorKey = Color.parseColor("#273449")
    private val colorJade = Color.parseColor("#0D9488")
    private val colorJadeBright = Color.parseColor("#2DD4BF")
    private val colorCrimson = Color.parseColor("#E11D48")
    private val colorTextPrimary = Color.parseColor("#F8FAFC")
    private val colorTextMuted = Color.parseColor("#94A3B8")

    override fun onCreate() {
        super.onCreate()
        AppContainer.ensureInitialized(applicationContext)
        prefsJob = imeScope.launch {
            AppContainer.preferencesRepository.preferencesFlow.collectLatest { prefs ->
                routingMode = prefs.routingMode
                formatMode = if (prefs.defaultFormat == CantoneseFormat.WRITTEN_CHINESE) {
                    CantoneseFormat.WRITTEN_CHINESE
                } else {
                    CantoneseFormat.SPOKEN_CANTONESE
                }
                refreshControlChips()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        // Row 1: Telemetry status + Engine chip + Format chip
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val statusTv = TextView(this).apply {
            text = "Hold or Tap · Live 粵語"
            setTextColor(colorTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTextView = statusTv

        val engineTv = createChip("Auto") {
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

        val formatTv = createChip("口語") {
            val next = if (formatMode == CantoneseFormat.SPOKEN_CANTONESE) {
                CantoneseFormat.WRITTEN_CHINESE
            } else {
                CantoneseFormat.SPOKEN_CANTONESE
            }
            formatMode = next
            imeScope.launch { AppContainer.preferencesRepository.setDefaultFormat(next) }
            refreshControlChips()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        }
        formatChip = formatTv

        topBar.addView(statusTv)
        topBar.addView(engineTv)
        topBar.addView(formatTv)
        root.addView(topBar)

        // Row 2: Cantonese quick punctuation + Undo
        val punctRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        listOf("，", "。", "？", "！", "、", "啦", "㗎").forEach { symbol ->
            punctRow.addView(createQuickKey(symbol) {
                commitToHost(symbol, trackAsDictation = false)
            })
        }
        punctRow.addView(createQuickKey("Undo", accent = true) {
            undoLastDictation()
        })
        root.addView(punctRow)

        // Row 3: Live acoustic level meter
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 6
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { bottomMargin = dp(10) }
        }
        levelBar = progress
        root.addView(progress)

        // Row 4: Ergonomic Dual-Gesture Voice Pad (Hold to Speak OR Quick Tap to Lock Hands-Free)
        val micBtn = Button(this).apply {
            text = "HOLD TO SPEAK · OR TAP FOR HANDS-FREE (粵語)"
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAllCaps = false
            background = roundedDrawable(colorJade, dp(16))
            minHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            ).apply { bottomMargin = dp(10) }

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        pressDownTimestampMs = System.currentTimeMillis()
                        if (!isLiveStreaming) {
                            isHoldGestureLocked = false
                            startLiveDictation()
                        } else if (isHoldGestureLocked) {
                            // Tapping again stops hands-free live mode.
                            stopLiveDictation()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val heldMs = System.currentTimeMillis() - pressDownTimestampMs
                        if (isLiveStreaming && !isHoldGestureLocked) {
                            if (heldMs < 320L) {
                                // Short tap -> lock into continuous hands-free live streaming!
                                isHoldGestureLocked = true
                                updateUiState()
                            } else {
                                // Hold-to-talk released -> finalize smoothly.
                                stopLiveDictation()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        micButton = micBtn
        root.addView(micBtn)

        // Row 5: Bottom action keys (Switch Keyboard, Space, Backspace, Send/Enter)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        bottomRow.addView(createActionKey("⌨ Switch", weight = 1.2f) {
            switchKeyboard()
        })
        bottomRow.addView(createActionKey("Space", weight = 2.0f) {
            commitToHost(" ", trackAsDictation = false)
        })
        bottomRow.addView(createActionKey("⌫", weight = 1.1f) {
            currentInputConnection?.finishComposingText()
            currentInputConnection?.deleteSurroundingText(1, 0)
        })
        bottomRow.addView(createActionKey("Send ↵", weight = 1.3f, primary = true) {
            if (isLiveStreaming) stopLiveDictation()
            triggerEditorAction()
        })
        root.addView(bottomRow)

        refreshControlChips()
        updateUiState()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshControlChips()
        updateUiState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isLiveStreaming) {
            stopAndDiscardSafely()
        }
    }

    override fun onDestroy() {
        stopAndDiscardSafely()
        prefsJob?.cancel()
        imeScope.cancel()
        super.onDestroy()
    }

    private fun startLiveDictation() {
        if (!hasMicPermission()) {
            statusTextView?.text = "Mic permission required — opening CantoScribe…"
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
                    // Show real-time underlined composing Cantonese text right inside the target app!
                    currentInputConnection?.setComposingText(partial, 1)
                },
                onCommitSegment = { segment ->
                    commitToHost(segment, trackAsDictation = true)
                }
            )

            if (started.isFailure) {
                statusTextView?.text = started.exceptionOrNull()?.message?.take(60) ?: "Unable to start live mic"
                return@launch
            }

            isLiveStreaming = true
            updateUiState()

            liveObserverJob?.cancel()
            liveObserverJob = imeScope.launch {
                AppContainer.liveTranscriptionController.state.collectLatest { state ->
                    val secs = state.metrics.elapsedMillis / 1000.0
                    statusTextView?.text = when {
                        !state.errorMessage.isNullOrBlank() -> state.errorMessage.take(54)
                        state.isConnecting -> "Connecting live stream…"
                        else -> String.format("LIVE · %.1fs · %s", secs, state.engineLabel.substringBefore("·").trim())
                    }
                    levelBar?.progress = (state.metrics.normalizedLevel * 100).toInt().coerceIn(6, 100)
                }
            }
        }
    }

    private fun stopLiveDictation() {
        if (!isLiveStreaming) return
        isLiveStreaming = false
        isHoldGestureLocked = false
        updateUiState()

        imeScope.launch {
            currentInputConnection?.finishComposingText()
            val record = AppContainer.liveTranscriptionController.stopAndFinalize(saveToSession = true)
            liveObserverJob?.cancel()
            liveObserverJob = null
            levelBar?.progress = 6
            statusTextView?.text = if (record != null) {
                "Done · ${record.engineUsed.displayName}"
            } else {
                "Hold or Tap · Live 粵語"
            }
        }
    }

    private fun stopAndDiscardSafely() {
        isLiveStreaming = false
        isHoldGestureLocked = false
        liveObserverJob?.cancel()
        liveObserverJob = null
        currentInputConnection?.finishComposingText()
        AppContainer.liveTranscriptionController.stopAndDiscard()
        levelBar?.progress = 6
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
        statusTextView?.text = "Undid last live phrase"
    }

    private fun triggerEditorAction() {
        val ic = currentInputConnection ?: return
        val handled = sendDefaultEditorAction(true)
        if (!handled) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun switchKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
    }

    private fun updateUiState() {
        val btn = micButton ?: return
        when {
            isLiveStreaming && isHoldGestureLocked -> {
                btn.text = "● LIVE HANDS-FREE — TAP TO STOP"
                btn.background = roundedDrawable(colorCrimson, dp(16))
            }
            isLiveStreaming -> {
                btn.text = "● LIVE STREAMING — RELEASE WHEN DONE"
                btn.background = roundedDrawable(colorCrimson, dp(16))
            }
            else -> {
                btn.text = "HOLD TO SPEAK · OR TAP FOR HANDS-FREE (粵語)"
                btn.background = roundedDrawable(colorJade, dp(16))
            }
        }
    }

    private fun createChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(colorJadeBright)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(32)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedDrawable(colorSurface, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun createQuickKey(label: String, accent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(if (accent) colorJadeBright else colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 2) 11f else 15f)
            gravity = Gravity.CENTER
            minHeight = dp(38)
            background = roundedDrawable(colorKey, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createActionKey(
        label: String,
        weight: Float,
        primary: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, if (primary) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            background = roundedDrawable(if (primary) colorJade else colorSurface, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), weight).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun roundedDrawable(fillColor: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radiusPx.toFloat()
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
