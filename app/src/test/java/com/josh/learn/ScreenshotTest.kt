package com.josh.learn

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Smoke tests for the main screens; also renders PNGs when run with -PscreensDir=… */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi", application = LearnApp::class)
class ScreenshotTest {
    private val dir = System.getProperty("screens.dir").orEmpty()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun seed() {
        LearnApp.prefs(ctx).edit().putBoolean("offline_setup_shown", true).putBoolean("notif_asked", true).commit()
        ModelDownloader.update(ModelDownloader.State())
    }

    private fun idle() { repeat(5) { shadowOf(Looper.getMainLooper()).idle() } }

    private fun snap(a: Activity, name: String) { idle(); snapView(a.window.decorView, name) }

    private fun snapView(v: View, name: String) {
        if (dir.isEmpty()) return
        val w = v.width.takeIf { it > 0 } ?: 1179; val h = v.height.takeIf { it > 0 } ?: 2556
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun texts(v: View): List<String> = when (v) {
        is android.widget.TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun home() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val t = texts(a.window.decorView)
        assertTrue(t.contains("Suggested for you"))
        assertTrue(t.contains("4WD & Car Mechanics"))
        assertTrue(t.contains("Science") && t.contains("Custom topic"))
        assertEquals(Provider.GEMINI, a.providers.active) // v1.1.0 default (offline AI held for v1.2.0)
        assertTrue(t.contains("✨ Set up AI"))
        snap(a, "01-home")
    }

    @Test fun settingsProviders() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        val t = texts(a.window.decorView).joinToString("\n")
        assertTrue(!t.contains("Offline AI (on this phone)") && t.contains("Gemini"))
        assertTrue(t.contains("free tier"))
        snap(a, "02-settings-providers")
    }
}
