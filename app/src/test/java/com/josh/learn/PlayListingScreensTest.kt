package com.josh.learn

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Play Console phone screenshots (1080×1920). Generic demo topics only — never Josh's private / Grok topics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi", application = LearnApp::class)
class PlayListingScreensTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val dir: String get() {
        val fromProp = System.getProperty("play.screens.dir").orEmpty()
        if (fromProp.isNotEmpty()) return fromProp
        val root = System.getProperty("project.root").orEmpty()
        return if (root.isNotEmpty()) File(root, "play/listing-screenshots").absolutePath else ""
    }

    @Before fun seed() {
        LearnApp.prefs(ctx).edit().clear().putBoolean("offline_setup_shown", true).putString("theme", "light").commit()
        Topics.clearImported(ctx)
        runCatching { Topics.customFile(ctx).delete() }
        Topics.init(ctx)
        Repo.init(ctx)
        Repo.lessons.clear(); Repo.cards.clear(); Repo.paths.clear()
        val space = Topics.categories.first { it.id == "space" }
        val lang = Topics.categories.first { it.id == "languages" }
        val arts = Topics.categories.first { it.id == "arts" }
        Repo.addLesson(Lesson(
            topic = "Black holes", title = "Black holes", difficulty = Difficulty.BEGINNER, length = LessonLength.SHORT,
            overview = "A black hole is a region of space where gravity is so strong that not even light can escape. They form when a massive star collapses at the end of its life.",
            concepts = listOf(
                Concept("Event horizon", "The boundary around a black hole. Cross it, and there's no way back — not even for light.", "Like a waterfall you can't swim up once you've gone over the edge.", "The size of the event horizon depends on the black hole's mass."),
                Concept("Spaghettification", "Tidal forces stretch objects into long, thin shapes as they fall in.", "Like dough pulled through a pasta machine.", "Closer to a smaller black hole, the stretch is more extreme."),
            ),
            glossary = listOf(Term("Singularity", "The theoretical point at the centre where density becomes infinite.")),
            takeaways = listOf("Light cannot escape from inside the event horizon."),
            category = space.id, emoji = "🕳️", emojiPicked = true,
        ), listOf("What is an event horizon?" to "The boundary around a black hole that light cannot cross.",
            "How do most black holes form?" to "When a massive star collapses at the end of its life."))
        Repo.addLesson(Lesson(
            topic = "Spanish basics for travel", title = "Spanish basics for travel", difficulty = Difficulty.BEGINNER, length = LessonLength.SHORT,
            overview = "A handful of phrases and a little grammar will get you through greetings, food and finding your way.",
            concepts = listOf(Concept("Greetings", "Hola (hello), buenos días (good morning) and gracias (thank you) go a long way.", "Start every conversation the way you'd say hello at home.", "Buenos días, ¿dónde está la estación?")),
            glossary = emptyList(), takeaways = listOf("Learn greetings first."), category = lang.id, emoji = "🇪🇸", emojiPicked = true,
        ), listOf("How do you say thank you in Spanish?" to "Gracias."))
        Repo.addLesson(Lesson(
            topic = "Music theory basics", title = "Music theory basics", difficulty = Difficulty.BEGINNER, length = LessonLength.SHORT,
            overview = "Music theory is the map of how notes, scales and chords fit together so you can play and write with intention.",
            concepts = listOf(Concept("The major scale", "A seven-note pattern of whole and half steps that much Western music is built on.", "Like a recipe that always produces the same flavour of scale.", "C major is all the white keys from C to C.")),
            glossary = emptyList(), takeaways = listOf("The major scale is a pattern of tones and semitones."), category = arts.id, emoji = "🎵", emojiPicked = true,
        ), emptyList())
        Repo.cards.forEach { it.due = 0L }
        Repo.save()
        BillingStore.resetForTests(); Entitlements.clearForTests(ctx); Premium.resetTestHooks()
        LocalModels.abiOverride = true
        ModelDownloader.update(ModelDownloader.State())
    }

    @After fun tidy() {
        BillingStore.resetForTests(); Premium.resetTestHooks()
        com.josh.oldschool.OsAnimations.reset()
    }

    private fun idle() { repeat(5) { shadowOf(Looper.getMainLooper()).idle() } }

    private fun snap(a: Activity, name: String, scrollTo: String? = null) {
        idle()
        if (dir.isEmpty()) return
        val v = a.window.decorView
        val w = 1080; val h = 1920
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        if (scrollTo != null) {
            val sv = findAll(v) { it is android.widget.ScrollView }.firstOrNull() as? android.widget.ScrollView
            val head = sv?.let { findAll(it) { t -> t is android.widget.TextView && t.text.toString() == scrollTo }.firstOrNull() }
            if (sv != null && head != null) {
                var y = 0; var cur: View? = head; while (cur != null && cur !== sv) { y += cur.top; cur = cur.parent as? View }
                sv.scrollTo(0, (y - 16 * a.resources.displayMetrics.density).toInt().coerceAtLeast(0))
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun texts(v: View): List<String> = when (v) {
        is android.widget.TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun findNav(v: View): BottomNavigationView? = when (v) {
        is BottomNavigationView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findNav(v.getChildAt(it)) }
        else -> null
    }
    private fun findAll(v: View, pred: (View) -> Boolean): List<View> =
        (if (pred(v)) listOf(v) else emptyList()) + if (v is ViewGroup) (0 until v.childCount).flatMap { findAll(v.getChildAt(it), pred) } else emptyList()
    private fun scrollToText(a: Activity, title: String) {
        val sv = findAll(a.window.decorView) { it is android.widget.ScrollView }.firstOrNull() as? android.widget.ScrollView ?: return
        val head = findAll(sv) { it is android.widget.TextView && it.text.toString() == title }.firstOrNull() ?: return
        var y = 0; var v: View? = head; while (v != null && v !== sv) { y += v.top; v = v.parent as? View }
        sv.scrollTo(0, (y - 16 * a.resources.displayMetrics.density).toInt().coerceAtLeast(0)); idle()
    }
    private fun unlock() { Entitlements.setPremium(ctx, true); Premium.testUnlocked = true }

    private fun theme(t: String) {
        LearnApp.prefs(ctx).edit().putString("theme", t).commit()
        LearnApp.applyTheme(ctx)
    }

    @Test fun listingScreensLight() {
        unlock(); theme("light")
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        val t = texts(home.window.decorView)
        assertTrue(t.any { it.contains("Josh Learn") || it.contains("Space") || it.contains("Science") })
        assertTrue("no private topics", t.none { it.contains("Haldex", true) || it.contains("4WD") || it.contains("Home Lab") })
        snap(home, "01-home", scrollTo = "What do you want to learn?")

        findNav(home.window.decorView)!!.selectedItemId = MainActivity.TAB_LIBRARY; idle()
        assertTrue(texts(home.window.decorView).any { it.contains("Black holes") })
        snap(home, "02-library")

        findNav(home.window.decorView)!!.selectedItemId = MainActivity.TAB_REVIEW; idle()
        snap(home, "04-review")
        home.finish()

        val lesson = Repo.lessons.first { it.title == "Black holes" }
        val la = Robolectric.buildActivity(LessonActivity::class.java,
            Intent(ctx, LessonActivity::class.java).putExtra("id", lesson.id)).setup().get(); idle()
        assertTrue(texts(la.window.decorView).any { it.contains("Event horizon") })
        snap(la, "03-lesson")
        la.finish()
    }

    @Test fun listingScreensOsrsAndUpsell() {
        unlock(); theme("osrs")
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        snap(home, "06-home-osrs", scrollTo = "What do you want to learn?")
        val lesson = Repo.lessons.first { it.title == "Black holes" }
        val la = Robolectric.buildActivity(LessonActivity::class.java,
            Intent(ctx, LessonActivity::class.java).putExtra("id", lesson.id)).setup().get(); idle()
        snap(la, "07-lesson-osrs")
        la.finish(); home.finish()

        // Upsell (free user) — light theme so the listing isn't only OSRS
        theme("light"); Premium.testUnlocked = false; Entitlements.clearForTests(ctx)
        val fake = FakeBillingClient()
        fake.product = BillingStore.ProductInfo(Premium.PRODUCT_ID, "Josh Learn Premium", "One-time unlock", "")
        BillingStore.resetForTests(); BillingStore.clientFactory = { fake }
        Premium.testPlayDistribution = true
        BillingStore.init(ctx)
        val p = Robolectric.buildActivity(PremiumActivity::class.java).setup().get(); idle()
        assertTrue(texts(p.window.decorView).any { it.contains("Premium") })
        snap(p, "05-premium-upsell")
        p.finish()

        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        val st = texts(s.window.decorView)
        assertTrue(st.none { it.contains("notif", true) || it.contains("Remind me") })
        snap(s, "08-settings")
        s.finish()
    }
}
