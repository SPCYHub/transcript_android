package com.example.core

import android.content.Context
import android.content.Intent

/**
 * Historical entry point for Firebase App Check.
 *
 * Firebase AI Logic and App Check were removed in d7c64d2 so sideloaded / debug
 * builds can run with only a user-entered Gemini or OpenAI API key. MainActivity
 * no longer calls this. The function is kept as a no-op so a clean checkout
 * compiles without those libraries and without any debug token.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    // no-op: no Firebase or App Check runtime dependency
}
