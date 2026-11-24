package com.example.ime

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import com.example.model.ThemePreference

/**
 * Adaptive Porcelain Light / Nocturne Obsidian Dark color tokens for the system Voice IME,
 * matching CantoScribe's Compose Material 3 studio palette.
 */
data class ImePalette(
    val canvasBg: Int,
    val surface: Int,
    val keyBg: Int,
    val keyAccentBg: Int,
    val keyStroke: Int,
    val ripple: Int,
    val jadeStart: Int,
    val jadeEnd: Int,
    val jadeAccent: Int,
    val jadeOnPrimary: Int,
    val crimsonStart: Int,
    val crimsonEnd: Int,
    val crimsonAccent: Int,
    val crimsonOnPrimary: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val previewBg: Int
) {
    companion object {
        fun resolve(context: Context, preference: ThemePreference): ImePalette {
            val isSystemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val dark = when (preference) {
                ThemePreference.SYSTEM -> isSystemDark
                ThemePreference.DARK -> true
                ThemePreference.LIGHT -> false
            }
            return if (dark) Dark else Light
        }

        val Dark = ImePalette(
            canvasBg = Color.parseColor("#070C12"),
            surface = Color.parseColor("#0F1722"),
            keyBg = Color.parseColor("#182536"),
            keyAccentBg = Color.parseColor("#0D4E4A"),
            keyStroke = Color.parseColor("#25364C"),
            ripple = Color.parseColor("#332DD4BF"),
            jadeStart = Color.parseColor("#0D9488"),
            jadeEnd = Color.parseColor("#0B7A70"),
            jadeAccent = Color.parseColor("#2DD4BF"),
            jadeOnPrimary = Color.parseColor("#FFFFFF"),
            crimsonStart = Color.parseColor("#E11D48"),
            crimsonEnd = Color.parseColor("#BE123C"),
            crimsonAccent = Color.parseColor("#FB7185"),
            crimsonOnPrimary = Color.parseColor("#FFFFFF"),
            textPrimary = Color.parseColor("#F2F6FA"),
            textSecondary = Color.parseColor("#94A7BC"),
            previewBg = Color.parseColor("#121C29")
        )

        val Light = ImePalette(
            canvasBg = Color.parseColor("#EDF2F4"),
            surface = Color.parseColor("#FFFFFF"),
            keyBg = Color.parseColor("#FFFFFF"),
            keyAccentBg = Color.parseColor("#C8F7EE"),
            keyStroke = Color.parseColor("#D5DFE6"),
            ripple = Color.parseColor("#260B8A7E"),
            jadeStart = Color.parseColor("#0B8A7E"),
            jadeEnd = Color.parseColor("#086E65"),
            jadeAccent = Color.parseColor("#0B8A7E"),
            jadeOnPrimary = Color.parseColor("#FFFFFF"),
            crimsonStart = Color.parseColor("#E11D48"),
            crimsonEnd = Color.parseColor("#C8173F"),
            crimsonAccent = Color.parseColor("#E11D48"),
            crimsonOnPrimary = Color.parseColor("#FFFFFF"),
            textPrimary = Color.parseColor("#0B1320"),
            textSecondary = Color.parseColor("#4B5B70"),
            previewBg = Color.parseColor("#F8FAFB")
        )
    }
}

internal fun sculptedKeyDrawable(
    fillColor: Int,
    strokeColor: Int,
    rippleColor: Int,
    radiusPx: Float,
    strokeWidthPx: Int
): Drawable {
    val content = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = radiusPx
        if (strokeWidthPx > 0) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }
    val mask = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.WHITE)
        cornerRadius = radiusPx
    }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

internal fun gradientPadDrawable(
    startColor: Int,
    endColor: Int,
    strokeColor: Int,
    radiusPx: Float,
    strokeWidthPx: Int
): GradientDrawable {
    return GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(startColor, endColor)
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        if (strokeWidthPx > 0) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }
}

internal fun circleDrawable(fillColor: Int): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
    }
}
