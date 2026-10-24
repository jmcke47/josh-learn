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
        assertTrue(t.none { it.contains("Suggested") })
        assertTrue(t.contains("📥 Import your Grok history")) // empty by default: nothing bundled
        assertTrue(t.contains("What do you want to learn?") && t.contains("Science") && t.contains("Custom topic"))
        assertTrue(!t.contains("Your topics"))
        assertEquals(Provider.LOCAL, a.providers.active) // offline is the default
        assertTrue(t.contains("📲 Offline AI"))
        snap(a, "01-home-no-topics")
        assertEquals(2, Topics.importJson(ctx, sample))
        a.onTopicsChanged(); idle()
        t = texts(a.window.decorView)
        assertTrue(t.contains("Your topics") && t.contains("More to explore"))
        assertTrue(t.indexOf("4WD & Car Mechanics") < t.indexOf("Home Lab & Networking")) // by priority
        assertTrue(t.indexOf("Home Lab & Networking") < t.indexOf("More to explore"))
        assertTrue(t.contains("How a Haldex AWD system works")) // top sub-topic line
        assertTrue(t.contains("Science") && t.contains("Surprise me") && t.contains("Custom topic"))
        assertTrue(!t.contains("📥 Import your Grok history") && t.none { it.contains("Suggested") })
    }

    /** Home with imported topics; uses -PtopicsFile=… (local, never committed) for a realistic render, else the sample. */
    @Test fun homeWithTopicsScreenshot() {
        val f = System.getProperty("topics.file").orEmpty()
        Topics.importJson(ctx, if (f.isNotEmpty() && File(f).exists()) File(f).readText() else sample)
        LocalModels.abiOverride = false // hide the offline-setup card for a clean shot
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
            snap(a, "01-home")
            val sv = findScroll(a.window.decorView)
            if (sv != null && dir.isNotEmpty()) {
                val child = sv.getChildAt(0); val w = sv.width.takeIf { it > 0 } ?: 1179
                child.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                child.layout(0, 0, w, child.measuredHeight)
                val bmp = Bitmap.createBitmap(w, child.measuredHeight, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp); cv.drawColor(a.attrColor(com.google.android.material.R.attr.colorSurface)); child.draw(cv)
                File(dir, "01-home-full.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { LocalModels.abiOverride = true }
    }

    private fun findScroll(v: View): android.widget.ScrollView? = when (v) {
        is android.widget.ScrollView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findScroll(v.getChildAt(it)) }
        else -> null
    }

    @Test fun removeTopic() {
        Topics.importJson(ctx, sample)
        assertTrue(Topics.removeImported(ctx, "Home Lab & Networking"))
        assertEquals(listOf("4WD & Car Mechanics"), Topics.suggested(ctx).map { it.title })
        assertEquals(listOf("How a Haldex AWD system works"), Topics.suggested(ctx).first().subtopics)
        assertTrue(!Topics.removeImported(ctx, "nope"))
        assertTrue(Topics.removeImported(ctx, "4WD & Car Mechanics"))
        assertTrue(Topics.suggested(ctx).isEmpty() && !Topics.importedFile(ctx).exists())
    }

    private fun widgetView(cols: Int, rows: Int, wDp: Int, hDp: Int): View {
        val rv = TopicsWidget.build(ctx, cols, rows)
        val parent = android.widget.FrameLayout(ctx)
        val v = rv.apply(ctx, parent)
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h)
        return v
    }
    private fun visibleTiles(v: View) = texts(v).filter { it.contains("\n") }.let { all ->
        val out = mutableListOf<String>()
        fun walk(x: View) { if (x is android.widget.TextView && x.visibility == View.VISIBLE && x.text.contains("\n") && (x.parent as View).visibility == View.VISIBLE) out += x.text.toString()
            if (x is android.view.ViewGroup) (0 until x.childCount).forEach { walk(x.getChildAt(it)) } }
        walk(v); out
    }

    @Test fun widget() {
        assertEquals(2 to 2, TopicsWidget.grid(150, 150))
        assertEquals(4 to 2, TopicsWidget.grid(320, 180))
        assertEquals(4 to 3, TopicsWidget.grid(320, 300))
        // no topics imported: built-ins
        var v = widgetView(2, 2, 170, 170)
        assertEquals(listOf("🔬\nScience", "🏛️\nHistory", "💻\nTech", "💰\nMoney"), visibleTiles(v))
        val f = System.getProperty("topics.file").orEmpty()
        Topics.importJson(ctx, if (f.isNotEmpty() && File(f).exists()) File(f).readText() else sample)
        v = widgetView(2, 2, 170, 170)
        val tiles = visibleTiles(v)
        assertEquals(4, tiles.size)
        assertTrue(tiles[0].endsWith(Topics.suggested(ctx)[0].title))
        assertTrue(texts(v).any { it.startsWith("🃏") })
        snapView(v, "05-widget-2x2")
        val big = widgetView(4, 3, 330, 300)
        assertEquals(12, visibleTiles(big).size)
        snapView(big, "06-widget-4x3")
        // the tile deep-links into a lesson for that topic
        val i = TopicsWidget.items(ctx, 4)[0]
        assertEquals(TopicsWidget.EXTRA_TOPIC, i.extraKey)
    }

    @Test fun widgetTapStartsLesson() {
        Topics.importJson(ctx, sample)
        val intent = android.content.Intent(ctx, MainActivity::class.java).setAction(TopicsWidget.ACTION_TOPIC)
            .putExtra(TopicsWidget.EXTRA_TOPIC, "4WD & Car Mechanics")
        LocalModels.abiOverride = false
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get(); idle()
            // no provider set up on this "phone" -> the add-key prompt appears, i.e. the lesson flow was started
            assertTrue(a.lastKeyPrompt?.isShowing == true)
        } finally { LocalModels.abiOverride = true }
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
        assertTrue(t.contains("Remove all") && t.contains("Re-import"))
        Topics.clearImported(ctx)
        assertTrue(!f.exists() && Topics.suggested(ctx).isEmpty())
    }

    @Test fun noKeyShowsAddKeyPromptInsteadOfCalling() {
        LocalModels.abiOverride = false // no offline fallback on this "phone"
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
            assertEquals(Provider.GEMINI, a.providers.active)
            assertTrue(a.requireProvider() == null)
            idle()
            val d = a.lastKeyPrompt!!
            assertTrue(d.isShowing)
            assertEquals("Get a free key", d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).text.toString())
        } finally { LocalModels.abiOverride = true }
    }

    @Test fun grokPreviewLetsYouUntickBeforeSaving() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        val topics = listOf(
            GrokImport.Topic("4WD & Car Mechanics", "Cars", "🚗", 500.0, 40, 90, 30, listOf("A", "B", "C", "D", "E")),
            GrokImport.Topic("Gaming & Handhelds", "Gaming", "🎮", 300.0, 20, 50, 15, listOf("F", "G", "H", "I", "J")),
            GrokImport.Topic("Psychology & Personality", "Health", "🧠", 100.0, 5, 9, 4, listOf("K", "L", "M", "N", "O")))
        a.showTopicPreview(GrokImport.Result(topics, 120, 300, 7, 20)); idle()
        val d = a.lastTopicPreview!!
        assertTrue(d.isShowing)
        val boxes = mutableListOf<com.google.android.material.checkbox.MaterialCheckBox>()
        fun walk(v: View) { if (v is com.google.android.material.checkbox.MaterialCheckBox) boxes += v; if (v is android.view.ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }
        walk(d.window!!.decorView)
        assertEquals(3, boxes.size); assertTrue(boxes.all { it.isChecked })
        assertTrue(texts(d.window!!.decorView).any { it.contains("skipping 7 personal or sensitive") })
        snapView(d.window!!.decorView, "04-grok-import-preview")
        boxes[2].isChecked = false
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(listOf("4WD & Car Mechanics", "Gaming & Handhelds"), Topics.suggested(ctx).map { it.title })
        assertTrue(texts(a.window.decorView).contains("Gaming & Handhelds"))
    }
}
