package com.example.core

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppCheckInitTest {

    @Test
    fun `configureAppCheck is a no-op without Firebase or a debug token`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "test-must-be-ignored")
        configureAppCheck(context, intent)
        configureAppCheck(context, null)
    }
}
