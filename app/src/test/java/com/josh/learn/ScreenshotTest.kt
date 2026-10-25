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
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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

    private fun widgetView(wDp: Int, hDp: Int): View {
        val g = TopicsWidget.grid(wDp, hDp)
        val rv = TopicsWidget.build(ctx, g)
        val parent = android.widget.FrameLayout(ctx)
        val v = rv.apply(ctx, parent)
        // Robolectric has no launcher to bind the collection, so host it like a launcher does: each tile is TopicsWidget.tile()'s RemoteViews
        val gv = findGrid(v)!!
        if (gv.adapter == null) {
            val items = TopicsWidget.items(ctx)
            gv.adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(p: Int) = items[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: View?, pg: android.view.ViewGroup) = TopicsWidget.tile(ctx, items[p], p, g).apply(ctx, pg)
            }
        }
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        repeat(2) { idle(); v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h) }
        return v
    }
    private fun gridOf(v: View): android.widget.GridView = findGrid(v)!!
    private fun findGrid(v: View): android.widget.GridView? = when (v) {
        is android.widget.GridView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findGrid(v.getChildAt(it)) }
        else -> null
    }
    /** Titles of the tiles laid out on screen (before scrolling). */
    private fun visibleTiles(v: View): List<String> { val g = gridOf(v); return (0 until g.childCount).map { texts(g.getChildAt(it)).last() } }
    private fun adapterTitles(v: View): List<String> { val g = gridOf(v); val a = g.adapter
        return (0 until a.count).map { texts(a.getView(it, null, g)).last() } }

    @Test fun widget() {
        assertEquals(TopicsWidget.Grid(1, 1, 134, 124), TopicsWidget.grid(150, 170))  // 2x2: one big tile, scrolls
        assertEquals(2, TopicsWidget.grid(340, 540).cols)    // 4x6 full screen on a normal phone: 2 columns
        assertEquals(3, TopicsWidget.grid(340, 540).rows) // the topic-of-the-day banner takes one row's worth
        assertEquals(3, TopicsWidget.grid(420, 640).cols)    // wide: 3 max
        assertEquals(3, TopicsWidget.grid(2000, 3000).cols)
        assertTrue(TopicsWidget.grid(340, 540).let { it.tileWidthDp >= 115 && it.tileHeightDp >= 115 })
        assertTrue(listOf(150 to 150, 250 to 250, 340 to 540, 420 to 640, 700 to 900).all { (w, h) -> TopicsWidget.grid(w, h).tileWidthDp >= 110 })
        // titles shrink before breaking a word; never below 11sp, never above 16sp
        assertEquals(16f, TopicsWidget.titleSp("Tech", 162))
        assertTrue(TopicsWidget.titleSp("Instruments", 134) in 11f..16f)
        assertTrue(TopicsWidget.titleSp("Cryptocurrency & Blockchain", 162) < TopicsWidget.titleSp("Tech", 162))
        // no topics imported: built-ins
        var v = widgetView(340, 300)
        assertEquals(listOf("Science", "History"), visibleTiles(v).take(2))
        val f = System.getProperty("topics.file").orEmpty()
        Topics.importJson(ctx, if (f.isNotEmpty() && File(f).exists()) File(f).readText() else sample)
        v = widgetView(170, 170)
        assertEquals(Topics.suggested(ctx)[0].title, visibleTiles(v)[0])
        assertTrue(texts(v).any { it.startsWith("🃏") })
        snapView(v, "05-widget-2x2")
        val square = widgetView(300, 300)
        snapView(square, "06-widget-4x3")
        val full = widgetView(340, 540)
        // every topic is in the (scrollable) grid, not squeezed into tiny tiles
        val all = adapterTitles(full)
        assertEquals(TopicsWidget.items(ctx).map { it.title }, all)
        assertTrue(Topics.suggested(ctx).all { s -> s.title in all })
        assertTrue(visibleTiles(full).size in 4..8)
        val tileH = gridOf(full).getChildAt(0).height / ctx.resources.displayMetrics.density
        assertTrue("tile height $tileH", tileH >= 110)
        snapView(full, "07-widget-4x6-fullscreen")
        val v57 = widgetView(420, 640)
        assertEquals(3, gridOf(v57).numColumns)
        snapView(v57, "08-widget-5x7")
        val i = TopicsWidget.items(ctx, 4)[0]
        assertEquals(TopicsWidget.EXTRA_TOPIC, i.extraKey)
    }

    // ================================================================= v1.4.0

    private val lessonSample get() = Lesson(topic = "How a Haldex AWD system works", title = "How a Haldex AWD system works", difficulty = Difficulty.BEGINNER,
        length = LessonLength.SHORT, overview = "A Haldex coupling sends power to the rear wheels only when the fronts start to slip, so the car drives like a front-wheel drive most of the time.",
        concepts = listOf(Concept("The clutch pack", "Wet plates squeezed by oil pressure lock the rear axle to the front.", "Like pressing two hands together harder to grip.", "Pulling away on wet grass.")),
        glossary = emptyList(), takeaways = emptyList(), category = "4wd & car mechanics", depth = Depth.ELI5, model = "Offline · Qwen3 0.6B")

    @Test fun depthPromptsAndMemory() {
        val eli5 = Prompts.lesson("Black holes", Difficulty.BEGINNER, LessonLength.SHORT, depth = Depth.ELI5)[1].content
        assertTrue(eli5.contains("Like I'm 5") && eli5.contains("5 years old"))
        assertTrue(!Prompts.lesson("Black holes", Difficulty.BEGINNER, LessonLength.SHORT)[1].content.contains("Depth:"))
        assertTrue(Prompts.localOverview("Black holes", Difficulty.BEGINNER, null, Depth.EXPERT)[1].content.contains("expert level"))
        assertTrue(Prompts.localConcepts("t", "o", Difficulty.BEGINNER, 3, null, Depth.ELI5)[1].content.contains("5 years old"))
        assertTrue(Prompts.chat(lessonSample, emptyList(), depth = Depth.EXPERT)[0].content.contains("Expert depth"))
        assertTrue(Prompts.localChat(lessonSample, emptyList(), Depth.ELI5)[0].content.contains("5 years old"))
        // remembered per topic (default) …
        assertEquals(Depth.NORMAL, Depths.get(ctx, "cars"))
        Depths.set(ctx, "cars", Depth.EXPERT)
        assertEquals(Depth.EXPERT, Depths.get(ctx, "Cars")); assertEquals(Depth.NORMAL, Depths.get(ctx, "space"))
        // … or globally
        LearnApp.prefs(ctx).edit().putBoolean("depth_per_topic", false).commit()
        Depths.set(ctx, "space", Depth.SIMPLE)
        assertEquals(Depth.SIMPLE, Depths.get(ctx, "cars")); assertEquals(Depth.SIMPLE, Depths.get(ctx, "anything"))
        // saved with the lesson
        assertEquals(Depth.ELI5, Lesson.fromJson(lessonSample.toJson()).depth)
        assertEquals(Depth.NORMAL, Lesson.fromJson(org.json.JSONObject("{\"id\":\"x\"}")).depth)
    }

    @Test fun depthSliderOnLessonAndTutor() {
        val l = lessonSample; Repo.addLesson(l, emptyList())
        val a = Robolectric.buildActivity(LessonActivity::class.java, android.content.Intent(ctx, LessonActivity::class.java).putExtra("id", l.id)).setup().get(); idle()
        val t = texts(a.window.decorView)
        assertTrue(t.contains("Depth: Like I'm 5") && t.contains("Normal") && t.contains("Expert"))
        assertTrue(t.contains("⚡ 2-minute version") && t.contains("⏱️ 60s challenge"))
        snap(a, "09-depth-slider")
        val c = Robolectric.buildActivity(ChatActivity::class.java, android.content.Intent(ctx, ChatActivity::class.java).putExtra("id", l.id)).setup().get(); idle()
        assertTrue(texts(c.window.decorView).contains("Answer depth"))
        Repo.deleteLesson(l)
    }

    @Test fun quickMode() {
        val p = Prompts.quickLesson("Black holes", Depth.ELI5, local = true)[1].content
        assertTrue(p.contains("150 words") && p.contains("5 years old") && p.contains("SUMMARY:"))
        val (title, text) = Prompts.parseLocalOverview("TITLE: Black holes\nSUMMARY: A black hole is a place where gravity is so strong that nothing escapes.", "x")
        assertEquals("Black holes", title); assertTrue(text.startsWith("A black hole"))
        val q = Gen.Quick("How a Haldex AWD system works",
            "Most of the time a Haldex-equipped car drives its front wheels only. Between the front and rear axles sits a clutch pack: a stack of thin plates bathed in oil. " +
                "When sensors notice the front wheels spinning faster than the rears, a pump squeezes the plates together and power flows to the back. " +
                "It reacts in a fraction of a second, so you rarely feel it working. Everyday example: pulling away on a wet boat ramp. " +
                "Surprising fact: the oil and filter need changing, and many owners never do it, which is why the system quietly stops working.",
            listOf(Prompts.Question.Mcq("When does a Haldex send power to the rear?", listOf("Always", "When the fronts slip", "Only in reverse", "Never"), 1, "It reacts to slip."),
                Prompts.Question.Mcq("What locks the rear axle in?", listOf("A clutch pack", "A chain", "A belt", "Magnets"), 0, ""),
                Prompts.Question.Mcq("Which service is often forgotten?", listOf("Tyres", "Wipers", "Haldex oil and filter", "Horn"), 2, "")))
        val a = Robolectric.buildActivity(QuickActivity::class.java, android.content.Intent(ctx, QuickActivity::class.java)
            .putExtra("topic", "How a Haldex AWD system works").putExtra("cat", "cars").putExtra("noAuto", true)).setup().get(); idle()
        a.show(q); idle()
        assertTrue(texts(a.window.decorView).contains("Quiz me: 3 questions"))
        snap(a, "10-quick-lesson")
        a.answer(0); idle() // wrong? no: question 1 answer is 1
        a.answer(0); idle() // (buttons disabled after the first tap, so this is ignored)
        a.next(); idle(); a.answer(0); idle(); a.next(); idle(); a.answer(2); idle(); a.next(); idle()
        val t = texts(a.window.decorView)
        assertTrue(t.contains("Done!") && t.contains("2 / 3 correct"))
        snap(a, "11-quick-done")
        val saved = Repo.lesson(a.lessonId)!!
        assertEquals(2, saved.quizScores.last().correct); assertEquals(3, Repo.cardsFor(saved.id).size)
        Repo.deleteLesson(saved)
    }

    @Test fun challengeMode() {
        val qs = (1..6).map { Prompts.Question.Mcq("Question $it: which part of a 4WD splits torque between axles?", listOf("Transfer case", "Radiator", "Alternator", "Muffler"), 0, "") }
        val a = Robolectric.buildActivity(ChallengeActivity::class.java, android.content.Intent(ctx, ChallengeActivity::class.java)
            .putExtra("topic", "4WD & Car Mechanics").putExtra("cat", "4wd")).setup().get(); idle()
        assertTrue(texts(a.window.decorView).contains("🏆 No personal best yet"))
        a.start(qs); idle()
        val lp = shadowOf(Looper.getMainLooper())
        a.answer(0); lp.idleFor(java.time.Duration.ofMillis(400))
        a.answer(0); lp.idleFor(java.time.Duration.ofMillis(400))
        a.answer(1); lp.idleFor(java.time.Duration.ofMillis(1000))
        lp.idleFor(java.time.Duration.ofSeconds(12))
        assertTrue(texts(a.window.decorView).any { it.startsWith("⏱️ 4") }) // ~46 s left
        assertTrue(texts(a.window.decorView).contains("Score 2"))
        snap(a, "12-challenge")
        lp.idleFor(java.time.Duration.ofSeconds(50))
        val t = texts(a.window.decorView)
        assertTrue(t.contains("Time's up!") && t.contains("Score: 2") && t.contains("New personal best! 🎉"))
        assertEquals(2, Challenge.best(ctx, "4wd"))
        snap(a, "13-challenge-done")
        assertTrue(!Challenge.record(ctx, "4wd", 1)); assertTrue(Challenge.record(ctx, "4wd", 3)); assertEquals(3, Challenge.best(ctx, "4wd"))
        assertEquals(0, Challenge.best(ctx, "space"))
    }

    @Test fun widgetTopicOfDay() {
        val f = System.getProperty("topics.file").orEmpty()
        Topics.importJson(ctx, if (f.isNotEmpty() && File(f).exists()) File(f).readText() else sample)
        val day = TopicOfDay.today()
        val item = TopicOfDay.pick(ctx, day)!!
        assertEquals(item, TopicOfDay.pick(ctx, day)) // same all day
        assertTrue(Topics.suggested(ctx).any { it.title == item.title }) // one of Josh's topics
        if (Topics.suggested(ctx).size > 3) assertTrue((0L..13L).map { TopicOfDay.pick(ctx, day + it)!!.title }.toSet().size > 2) // changes day to day
        assertTrue(TopicsWidget.grid(340, 640).showTopicOfDay); assertTrue(!TopicsWidget.grid(150, 170).showTopicOfDay)
        // no fact yet: fallback line
        var v = widgetView(340, 640)
        var t = texts(v)
        assertTrue(t.contains("⭐ Topic of the day · ${item.emoji} ${item.title}"))
        assertTrue(t.contains(TopicOfDay.fallback(item)))
        // cached fact (generated in-app) shows on the widget; the grid stays below
        assertEquals("Haldex couplings were invented by a Swedish company in 1998.", Prompts.parseFact("<think>hmm</think>\nFun fact: Haldex couplings were invented by a Swedish company in 1998. Neat!"))
        val fact = when {
            item.title.contains("Phone") || item.title.contains("Gadget") -> "The first text message, sent in 1992, simply said \"Merry Christmas\"."
            item.title.contains("4WD") || item.title.contains("Car") -> "The first car with permanent four-wheel drive, the Spyker 60 HP, raced in 1903."
            else -> "Fun fact about ${item.title}: there's always more to discover."
        }
        TopicOfDay.saveFact(ctx, day, item.title, fact)
        v = widgetView(340, 640); t = texts(v)
        assertTrue(t.contains(fact))
        assertTrue(visibleTiles(v).isNotEmpty())
        assertEquals(null, TopicOfDay.cachedFact(ctx, day + 1, item.title)) // tomorrow needs a new one
        snapView(v, "14-widget-topic-of-day")
        // home shows it too
        LocalModels.abiOverride = false
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
            val ht = texts(a.window.decorView)
            assertTrue(ht.contains("⭐ Topic of the day") && ht.contains("⚡ 2-minute lesson") && ht.contains("⏱️ Challenge"))
        } finally { LocalModels.abiOverride = true }
    }

    @Test fun amoledThemeAndFontSize() {
        Topics.importJson(ctx, sample)
        LearnApp.prefs(ctx).edit().putString("theme", "amoled").commit()
        org.robolectric.RuntimeEnvironment.setQualifiers("+night") // what LearnApp.applyTheme's MODE_NIGHT_YES gives on a phone
        LocalModels.abiOverride = false
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
            assertEquals(0xFF000000.toInt(), a.attrColor(com.google.android.material.R.attr.colorSurface))
            assertEquals(0xFF000000.toInt(), a.attrColor(android.R.attr.colorBackground))
            snap(a, "15-amoled-home")
            snapView(widgetView(340, 640), "15-widget-amoled")
        } finally { LocalModels.abiOverride = true }
        for ((k, scale) in Display.fontSizes) {
            LearnApp.prefs(ctx).edit().putString("font_size", k).commit()
            val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
            assertEquals(scale, a.resources.configuration.fontScale, 0.01f)
            if (k == "xlarge") {
                val t = texts(a.window.decorView)
                assertTrue(t.contains("Display") && t.contains("AMOLED black") && t.contains("OSRS") && t.contains("Extra large"))
                val sv = findScroll(a.window.decorView)!!
                fun find(v: View): View? = if (v is android.widget.TextView && v.text.toString() == "Display") v else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                val d = find(sv)!!; var y = 0; var x: View? = d; while (x != null && x !== sv) { y += x.top; x = x.parent as? View }
                sv.scrollTo(0, y - a.dp(8)); idle()
                snap(a, "18-settings-display-xl")
            }
        }
    }

    @Test fun osrsTheme() {
        val f = System.getProperty("topics.file").orEmpty()
        Topics.importJson(ctx, if (f.isNotEmpty() && File(f).exists()) File(f).readText() else sample)
        LearnApp.prefs(ctx).edit().putString("theme", "osrs").commit()
        org.robolectric.RuntimeEnvironment.setQualifiers("+night") // what LearnApp.applyTheme's MODE_NIGHT_YES gives on a phone
        LocalModels.abiOverride = false
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
            assertEquals(0xFFFFFF00.toInt(), a.attrColor(com.google.android.material.R.attr.colorOnSurface)) // yellow text
            assertEquals(ctx.resources.getColor(R.color.os_panel, null), a.attrColor(com.google.android.material.R.attr.colorSurface))
            snap(a, "16-osrs-home")
            val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
            assertTrue(texts(s.window.decorView).contains("OSRS font: Pixelify Sans (SIL Open Font License)"))
            assertTrue(ctx.assets.open("licenses/PixelifySans-OFL.txt").bufferedReader().readText().contains("SIL OPEN FONT LICENSE"))
            snapView(widgetView(340, 640), "17-widget-osrs")
        } finally { LocalModels.abiOverride = true }
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
