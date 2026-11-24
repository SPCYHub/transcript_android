package com.example.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Semantic tactile haptic feedback engine shared across CantoScribe Studio and the system Voice IME.
 *
 * Uses Android `VibrationEffect.Composition` hardware primitives on API 30+ when supported,
 * predefined effects on API 29+, shaped waveforms on API 26+, and graceful fallbacks on API 24–25.
 */
interface StudioHaptics {
    var enabled: Boolean

    /** Ultra-crisp micro-tick for tabs, chips, segmented controls, and subtle selections. */
    fun tick(view: View? = null)

    /** Tactile keycap click for keyboard punctuation, space, phrases, and action keys. */
    fun keyTap(view: View? = null)

    /** Lighter repeating tick for held backspace deletions. */
    fun backspaceTick(view: View? = null)

    /** Directional tactile snap for toggle switches. */
    fun toggle(isOn: Boolean, view: View? = null)

    /** Confident, resonant studio-switch engagement when live voice recording starts. */
    fun recordStart(view: View? = null)

    /** Crisp detent double-snap when quick-tap locks into hands-free live dictation. */
    fun handsFreeLock(view: View? = null)

    /** Smooth release click when ending a hold-to-speak or hands-free session. */
    fun recordStop(view: View? = null)

    /** Pleasant confirmation double-pulse (clipboard copy, transcript finished, key verified). */
    fun confirm(view: View? = null)

    /** Distinct rejection / destructive feedback (error, cancel recording, clear/delete). */
    fun error(view: View? = null)

    /** Subtle whisper-light micro-pulse when a live Cantonese phrase commits. */
    fun phraseCommit()
}

object NoOpStudioHaptics : StudioHaptics {
    override var enabled: Boolean = false
    override fun tick(view: View?) = Unit
    override fun keyTap(view: View?) = Unit
    override fun backspaceTick(view: View?) = Unit
    override fun toggle(isOn: Boolean, view: View?) = Unit
    override fun recordStart(view: View?) = Unit
    override fun handsFreeLock(view: View?) = Unit
    override fun recordStop(view: View?) = Unit
    override fun confirm(view: View?) = Unit
    override fun error(view: View?) = Unit
    override fun phraseCommit() = Unit
}

class AndroidStudioHaptics(context: Context) : StudioHaptics {

    @Volatile
    override var enabled: Boolean = true

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private val hasHardwareVibrator: Boolean = runCatching {
        vibrator?.hasVibrator() == true
    }.getOrDefault(false)

    private val hasAmplitudeControl: Boolean = runCatching {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator?.hasAmplitudeControl() == true
    }.getOrDefault(false)

    override fun tick(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.45f
            )
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && playPredefined(VibrationEffect.EFFECT_TICK)) {
            return
        }
        if (performViewFallback(view, HapticFeedbackConstants.CLOCK_TICK)) return
        playOneShot(durationMs = 10L, amplitude = 85)
    }

    override fun keyTap(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK to 0.52f
            )
        ) {
            return
        }
        if (performViewFallback(view, HapticFeedbackConstants.KEYBOARD_TAP)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && playPredefined(VibrationEffect.EFFECT_CLICK)) {
            return
        }
        playOneShot(durationMs = 14L, amplitude = 120)
    }

    override fun backspaceTick(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.34f
            )
        ) {
            return
        }
        if (performViewFallback(view, HapticFeedbackConstants.CLOCK_TICK)) return
        playOneShot(durationMs = 8L, amplitude = 65)
    }

    override fun toggle(isOn: Boolean, view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val scale = if (isOn) 0.72f else 0.42f
            val primitive = if (isOn) {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_TICK
            }
            if (playPrimitives(primitive to scale)) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val constant = if (isOn) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            if (performViewFallback(view, constant)) return
        }
        playOneShot(durationMs = if (isOn) 18L else 10L, amplitude = if (isOn) 150 else 80)
    }

    override fun recordStart(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.65f,
                VibrationEffect.Composition.PRIMITIVE_CLICK to 0.95f,
                delayBetweenMs = 26
            )
        ) {
            return
        }
        if (playWaveform(
                timings = longArrayOf(0, 18, 24, 28),
                amplitudes = intArrayOf(0, 125, 0, 225)
            )
        ) {
            return
        }
        performViewFallback(view, HapticFeedbackConstants.LONG_PRESS)
    }

    override fun handsFreeLock(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.75f,
                VibrationEffect.Composition.PRIMITIVE_CLICK to 0.9f,
                delayBetweenMs = 45
            )
        ) {
            return
        }
        if (playWaveform(
                timings = longArrayOf(0, 14, 42, 20),
                amplitudes = intArrayOf(0, 160, 0, 210)
            )
        ) {
            return
        }
        performViewFallback(view, HapticFeedbackConstants.LONG_PRESS)
    }

    override fun recordStop(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK to 0.72f,
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.4f,
                delayBetweenMs = 24
            )
        ) {
            return
        }
        if (playWaveform(
                timings = longArrayOf(0, 20, 22, 12),
                amplitudes = intArrayOf(0, 180, 0, 90)
            )
        ) {
            return
        }
        performViewFallback(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun confirm(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.55f,
                VibrationEffect.Composition.PRIMITIVE_CLICK to 0.85f,
                delayBetweenMs = 55
            )
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            performViewFallback(view, HapticFeedbackConstants.CONFIRM)
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            playPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        ) {
            return
        }
        playWaveform(
            timings = longArrayOf(0, 16, 48, 22),
            amplitudes = intArrayOf(0, 130, 0, 200)
        )
    }

    override fun error(view: View?) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.85f,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.85f,
                delayBetweenMs = 55
            )
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            performViewFallback(view, HapticFeedbackConstants.REJECT)
        ) {
            return
        }
        playWaveform(
            timings = longArrayOf(0, 24, 42, 24, 42, 30),
            amplitudes = intArrayOf(0, 190, 0, 190, 0, 220)
        )
    }

    override fun phraseCommit() {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPrimitives(
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.25f
            )
        ) {
            return
        }
        playOneShot(durationMs = 7L, amplitude = 48)
    }

    private fun playPrimitives(
        first: Pair<Int, Float>,
        second: Pair<Int, Float>? = null,
        delayBetweenMs: Int = 0
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val vib = vibrator ?: return false
        if (!hasHardwareVibrator) return false
        return runCatching {
            val primitives = if (second != null) {
                intArrayOf(first.first, second.first)
            } else {
                intArrayOf(first.first)
            }
            if (!vib.areAllPrimitivesSupported(*primitives)) return false
            val composition = VibrationEffect.startComposition()
                .addPrimitive(first.first, first.second)
            if (second != null) {
                composition.addPrimitive(second.first, second.second, delayBetweenMs)
            }
            vib.vibrate(composition.compose())
            true
        }.getOrDefault(false)
    }

    private fun playPredefined(effectId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val vib = vibrator ?: return false
        if (!hasHardwareVibrator) return false
        return runCatching {
            vib.vibrate(VibrationEffect.createPredefined(effectId))
            true
        }.getOrDefault(false)
    }

    private fun playOneShot(durationMs: Long, amplitude: Int): Boolean {
        val vib = vibrator ?: return false
        if (!hasHardwareVibrator) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (hasAmplitudeControl) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                vib.vibrate(VibrationEffect.createOneShot(durationMs, amp))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
            true
        }.getOrDefault(false)
    }

    private fun playWaveform(timings: LongArray, amplitudes: IntArray): Boolean {
        val vib = vibrator ?: return false
        if (!hasHardwareVibrator) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (hasAmplitudeControl) {
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
            true
        }.getOrDefault(false)
    }

    private fun performViewFallback(view: View?, constant: Int): Boolean {
        if (view == null) return false
        return runCatching {
            view.performHapticFeedback(
                constant,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }.getOrDefault(false)
    }
}

val LocalStudioHaptics = compositionLocalOf<StudioHaptics> { NoOpStudioHaptics }

@Composable
fun rememberStudioHaptics(enabled: Boolean): StudioHaptics {
    val context = LocalContext.current.applicationContext
    val haptics = remember(context) { AndroidStudioHaptics(context) }
    SideEffect {
        haptics.enabled = enabled
    }
    return haptics
}
