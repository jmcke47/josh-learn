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
        LocalModels.abiOverride = true // Robolectric's ABI list isn't arm64
        Topics.clearImported(ctx)
    }

    private val sample = """{"topics":[
        {"title":"Home Lab & Networking","category":"Tech","emoji":"🖥️","priority":78,"subtopics":["Proxmox for beginners","Mesh Wi-Fi explained"]},
        {"title":"4WD & Car Mechanics","category":"Cars","emoji":"🚗","priority":100,"subtopics":["How a Haldex AWD system works"]}]}"""

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
        var t = texts(a.window.decorView)
        assertTrue(t.contains("Suggested for you"))
        assertTrue(t.contains("Import my topics")) // empty by default: nothing bundled
        assertTrue(!t.contains("4WD & Car Mechanics"))
        assertEquals(2, Topics.importJson(ctx, sample))
        a.onTopicsChanged(); idle()
        t = texts(a.window.decorView)
        assertTrue(t.contains("4WD & Car Mechanics") && t.contains("Home Lab & Networking"))
        assertTrue(t.contains("Science") && t.contains("Custom topic"))
        assertEquals(Provider.LOCAL, a.providers.active) // offline is the default
        assertTrue(t.contains("📲 Offline AI"))
        snap(a, "01-home")
    }

    @Test fun settingsProviders() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        val t = texts(a.window.decorView).joinToString("\n")
        assertTrue(t.contains("Offline AI (on this phone)") && t.contains("Gemini"))
        assertTrue(t.contains("free tier"))
        snap(a, "02-settings-providers")
    }

    @Test fun modelDownload() {
        val m = LocalModel.FAST
        ModelDownloader.update(ModelDownloader.State(m, ModelDownloader.Status.RUNNING, 251_000_000L, m.sizeBytes, 4_200_000L, "Downloading…"))
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        assertTrue(texts(a.window.decorView).any { it.startsWith("Downloading Qwen3 0.6B: 40%") })
        snap(a, "03-model-download")
        ModelDownloader.update(ModelDownloader.State())
    }

    @Test fun nonArmPhoneFallsBackToCloud() {
        LocalModels.abiOverride = false
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            idle()
            assertEquals(Provider.GEMINI, a.providers.active)
            assertTrue(!a.providers.isReady(Provider.LOCAL))
            assertTrue(LocalModels.unsupportedReason(a)!!.contains("64-bit ARM"))
            assertTrue(ModelDownloader.start(a, LocalModel.FAST)!!.contains("64-bit ARM"))
            val t = texts(a.window.decorView)
            assertTrue(t.contains("✨ Set up AI"))
            val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
            assertTrue(texts(s.window.decorView).any { it.startsWith("⚠ Offline AI needs a 64-bit ARM phone") })
        } finally { LocalModels.abiOverride = true }
    }

    @Test fun crashDuringModelLoadDisablesOfflineAi() {
        LearnApp.prefs(ctx).edit().putBoolean("llm_loading", true).commit()
        LocalLlm.install(ctx) // what the next app start does
        assertTrue(LocalModels.loadCrashed(ctx))
        assertTrue(!LocalModels.supported(ctx))
        LocalModels.clearLoadCrashed(ctx)
        assertTrue(LocalModels.supported(ctx))
    }

    @Test fun firstRunPromptsOfflineDownload() {
        LearnApp.prefs(ctx).edit().putBoolean("offline_setup_shown", false).commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(d != null && d.isShowing)
    }

    @Test fun importSavesPrivatelyAndClears() {
        assertTrue(Topics.suggested(ctx).isEmpty())
        assertEquals(2, Topics.importJson(ctx, sample))
        val f = Topics.importedFile(ctx)
        assertTrue(f.exists() && f.parentFile == ctx.filesDir)
        assertEquals("4WD & Car Mechanics", Topics.suggested(ctx).first().title) // priority order
        assertTrue(Topics.category(Topics.suggested(ctx).first().asCategory.id) != null)
        val bad = runCatching { Topics.importJson(ctx, "{\"nope\":1}") }
        assertTrue(bad.isFailure)
        assertEquals(2, Topics.suggested(ctx).size) // a bad file doesn't wipe the good one
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        val t = texts(s.window.decorView)
        assertTrue(t.contains("Clear imported topics"))
        Topics.clearImported(ctx)
        assertTrue(!f.exists() && Topics.suggested(ctx).isEmpty())
    }
}
