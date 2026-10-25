package com.josh.learn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : BaseActivity() {
    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNavigationView
    private lateinit var updater: UpdateController
    private var tab = TAB_HOME
    private var pendingUpdate: UpdateController.Manifest? = null
    private var libraryQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        nav = BottomNavigationView(this).apply {
            menu.add(0, TAB_HOME, 0, "Learn").setIcon(R.drawable.ic_home)
            menu.add(0, TAB_LIBRARY, 1, "Library").setIcon(R.drawable.ic_library)
            menu.add(0, TAB_REVIEW, 2, "Review").setIcon(R.drawable.ic_review)
            setOnItemSelectedListener { tab = it.itemId; render(); true }
        }
        screen(getString(R.string.app_name), content, back = false, bottom = nav)
        tab = savedInstanceState?.getInt("tab") ?: TAB_HOME
        nav.selectedItemId = tab

        updater = UpdateController(this)
        if (savedInstanceState == null) {
            updater.quietCheck { m -> pendingUpdate = m; render() }
            val prefs = LearnApp.prefs(this)
            if (Features.OFFLINE_AI && LocalModels.supported(this) && LocalModels.installed(this).isEmpty() && !ModelDownloader.state.active && !prefs.getBoolean("offline_setup_shown", false)) {
                prefs.edit().putBoolean("offline_setup_shown", true).apply()
                OfflineSetup.show(this) { render() }
            }
            if (intent?.action == TopicsWidget.ACTION_TOPIC) { handleWidgetIntent(intent); return }
            if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("notif_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleWidgetIntent(intent) }

    /** Widget tile tapped: go straight into a lesson for that topic. */
    private fun handleWidgetIntent(i: Intent?) {
        if (i?.action != TopicsWidget.ACTION_TOPIC) return
        val topic = i.getStringExtra(TopicsWidget.EXTRA_TOPIC); val catId = i.getStringExtra(TopicsWidget.EXTRA_CATEGORY)
        val special = i.getStringExtra(TopicsWidget.EXTRA_SPECIAL)
        setIntent(Intent(this, MainActivity::class.java)) // handle once
        tab = TAB_HOME; nav.selectedItemId = TAB_HOME
        when {
            topic != null -> Topics.suggested(this).firstOrNull { it.title == topic }?.asCategory?.let { startTopic(Topics.pick(it), it) } ?: toast("That topic was removed")
            catId != null -> Topics.category(catId)?.let { startTopic(Topics.pick(it), it) }
            special == "surprise" -> { val (t, c) = Topics.surprise(); startTopic(t, c) }
            special == "custom" -> startActivity(Intent(this, NewLessonActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab) }

    override fun buildMenu(menu: Menu) {
        addProviderSwitcher(menu)
        menu.add("Check for updates").setOnMenuItemClickListener { updater.checkForUpdates(); true }
        menu.add("Settings").setOnMenuItemClickListener { startActivity(Intent(this, SettingsActivity::class.java)); true }
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
        render()
        TopicsWidget.refresh(this)
        TopicOfDay.ensureFact(this, providers) { if (!isFinishing && ::content.isInitialized && tab == TAB_HOME) render() }
    }

    private fun render() {
        content.removeAllViews()
        when (tab) {
            TAB_LIBRARY -> content.addView(library())
            TAB_REVIEW -> content.addView(scroll(review()))
            else -> content.addView(scroll(home()))
        }
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }

    // ------------------------------------------------------------- Learn tab

    private val dlListener: (ModelDownloader.State) -> Unit = { st -> offlineStatus?.let { updateOfflineCard(it, st) } }
    private var offlineStatus: android.widget.TextView? = null

    override fun onStart() { super.onStart(); ModelDownloader.listen(dlListener) }
    override fun onStop() { super.onStop(); ModelDownloader.unlisten(dlListener) }

    private fun updateOfflineCard(tv: android.widget.TextView, st: ModelDownloader.State) {
        tv.text = when (st.status) {
            ModelDownloader.Status.RUNNING -> "Downloading ${st.model?.short}: ${st.progressText()}"
            ModelDownloader.Status.VERIFYING -> "Checking the download…"
            ModelDownloader.Status.DONE -> { content.post { render() }; "Ready!" }
            ModelDownloader.Status.PAUSED, ModelDownloader.Status.FAILED -> st.message.ifEmpty { "Download stopped" } + "  Tap to resume."
            else -> "No API key needed. One-time download, then lessons work without internet. Tap to set up."
        }
    }

    override fun onTopicsChanged() { if (::content.isInitialized) render() }

    override fun onProviderChanged() { super.onProviderChanged(); if (::content.isInitialized) render() }

    fun newTopic() = showTopicEditor(null) { render() }

    private fun startTopic(topic: String, cat: Topics.Category?) =
        createLesson(topic, defaultDifficulty(), defaultLength(), category = cat?.id)

    fun startQuick(topic: String, cat: Topics.Category?) =
        startActivity(Intent(this, QuickActivity::class.java).putExtra("topic", topic).putExtra("cat", cat?.id))
    fun startChallenge(topic: String, cat: Topics.Category?) =
        startActivity(Intent(this, ChallengeActivity::class.java).putExtra("topic", topic).putExtra("cat", cat?.id))

    /** Topic of the day + Quick 2-minute mode + Challenge entry points at the top of home. */
    private fun topOfHome(col: LinearLayout) = with(col) {
        val todItem = TopicOfDay.pick(context)
        val todCat = todItem?.let { i -> if (i.extraKey == TopicsWidget.EXTRA_TOPIC) Topics.suggested(context).firstOrNull { it.title == i.title }?.asCategory else Topics.category(i.extraValue) }
        if (todItem != null && todCat != null) addView(card(top = 10, onClick = { subtopicSheet(todCat) }) {
            addView(muted("⭐ Topic of the day"))
            addView(label("${todItem.emoji} ${todItem.title}", com.google.android.material.R.attr.textAppearanceTitleMedium, bold = true, top = 2))
            addView(label(TopicOfDay.line(context) ?: "", com.google.android.material.R.attr.textAppearanceBodyMedium, top = 4))
        }.apply { setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSecondaryContainer)) })
        addView(hbox {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            fun pickTopic(then: (String, Topics.Category?) -> Unit) {
                val mine = Topics.suggested(context).map { it.asCategory }
                val cats = (mine + Topics.categories).distinctBy { it.name }
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Pick a topic")
                    .setItems((listOf("🎲 Surprise me") + cats.map { "${it.emoji} ${it.name}" }).toTypedArray()) { _, i ->
                        if (i == 0) { val (t, c) = Topics.surprise(); then(t, c) } else { val c = cats[i - 1]; then(Topics.pick(c), c) }
                    }.show()
            }
            addView(button("⚡ 2-minute lesson") { pickTopic(::startQuick) }.apply { contentDescription = "Quick 2-minute lesson" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            addView(outlined("⏱️ Challenge") { pickTopic(::startChallenge) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun home(): View = vbox(16) {
        pendingUpdate?.let { m ->
            addView(card(top = 0, onClick = { updater.startUpdate(m) }) {
                addView(label("Update available: v${m.versionName}", bold = true))
                addView(muted("Tap to download and install"))
            })
        }
        if (providers.active != Provider.LOCAL && providers.ready().isEmpty()) {
            addView(card(top = if (pendingUpdate != null) 10 else 0, onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }) {
                addView(label("✨ Set up AI", bold = true))
                LocalModels.unsupportedReason(context)?.takeIf { Features.OFFLINE_AI }?.let { addView(muted(it)) }
                addView(muted("Add a free Google Gemini key in Settings (takes a minute). Grok, ChatGPT and Claude work too. Tap to open Settings."))
            })
        }
        if (Features.OFFLINE_AI && providers.active == Provider.LOCAL && LocalModels.usable(context) == null) {
            addView(card(top = if (pendingUpdate != null) 10 else 0, onClick = {
                val st = ModelDownloader.state
                if (st.active) startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                else if ((st.status == ModelDownloader.Status.PAUSED || st.status == ModelDownloader.Status.FAILED) && st.model != null) {
                    ModelDownloader.start(this@MainActivity, st.model)?.let { toast(it) }
                } else OfflineSetup.show(this@MainActivity)
            }) {
                addView(label("📲 Offline AI", bold = true))
                val tv = muted(""); offlineStatus = tv; updateOfflineCard(tv, ModelDownloader.state); addView(tv)
            })
        }

        val mine = Topics.suggested(context)
        fun grid(cols: Int) = android.widget.GridLayout(context).apply { columnCount = cols; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } }
        fun place(g: android.widget.GridLayout, v: View) = g.addView(v, android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, android.widget.GridLayout.FILL), android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
            width = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        fun tile(g: android.widget.GridLayout, emoji: String, name: String, small: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
            val c = card(top = 0, onClick = { onClick() }) {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(if (small) 10 else 14), dp(4), dp(if (small) 10 else 14))
                addView(label(emoji).apply { textSize = if (small) 28f else 34f; gravity = android.view.Gravity.CENTER })
                addView(label(name, if (small) com.google.android.material.R.attr.textAppearanceLabelMedium else com.google.android.material.R.attr.textAppearanceLabelLarge)
                    .apply { gravity = android.view.Gravity.CENTER; maxLines = 1 })
            }
            onLong?.let { l -> c.setOnLongClickListener { l(); true } }
            c.contentDescription = name
            place(g, c)
        }
        fun builtIns(g: android.widget.GridLayout, small: Boolean) {
            Topics.categories.forEach { cat -> tile(g, cat.emoji, cat.name, small, { startTopic(Topics.pick(cat), cat) }, { subtopicSheet(cat) }) }
            tile(g, "🎲", "Surprise me", small, { val (t, c) = Topics.surprise(); startTopic(t, c) })
            tile(g, "➕", "Custom topic", small, { startActivity(Intent(this@MainActivity, NewLessonActivity::class.java)) })
        }

        if (mine.isNotEmpty()) {
            // ---- Your topics: the main section
            topOfHome(this)
            addView(label("Your topics", com.google.android.material.R.attr.textAppearanceTitleLarge, top = 16))
            val g = grid(2)
            mine.forEach { s ->
                val cat = s.asCategory
                val next = s.subtopics.firstOrNull { st -> Repo.lessons.none { it.topic.equals(st, true) } } ?: s.subtopics.firstOrNull() ?: s.category
                val c = card(top = 0, onClick = { startTopic(Topics.pick(cat), cat) }) {
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                    minimumHeight = dp(168)
                    addView(label(s.emoji).apply { textSize = 40f })
                    addView(label(s.title, com.google.android.material.R.attr.textAppearanceTitleMedium, bold = true).apply {
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 17f
                        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE; breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                        setPadding(0, dp(8), 0, 0) })
                    addView(muted(next.removePrefix("${s.title}: ").replaceFirstChar { it.uppercase() }).apply { textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
                }
                c.setOnLongClickListener { subtopicSheet(cat, removable = s.title); true }
                c.contentDescription = s.title
                place(g, c)
            }
            // "+ New topic" tile at the end of Your topics
            val add = card(top = 0, onClick = { newTopic() }) {
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(18), dp(16), dp(18))
                minimumHeight = dp(168)
                addView(label("➕").apply { textSize = 40f; gravity = android.view.Gravity.CENTER })
                addView(label("New topic", com.google.android.material.R.attr.textAppearanceTitleMedium, bold = true).apply { gravity = android.view.Gravity.CENTER; textSize = 17f; setPadding(0, dp(8), 0, 0) })
                addView(muted("Make your own").apply { gravity = android.view.Gravity.CENTER; textSize = 13f })
            }.apply { strokeWidth = dp(2); strokeColor = attrColor(androidx.appcompat.R.attr.colorPrimary); contentDescription = "+ New topic" }
            place(g, add)
            addView(g)
            addView(muted("Tap a topic to start a lesson · press and hold to edit, pick a sub-topic or delete it", top = 4).apply { textSize = 12f })

            addView(title("More to explore", top = 20))
            val more = grid(3); builtIns(more, small = true); addView(more)
        } else {
            addView(card(top = if (pendingUpdate != null) 10 else 0, onClick = { importTopics() }) {
                addView(label("📥 Import your Grok history", com.google.android.material.R.attr.textAppearanceTitleMedium, bold = true,
                    color = attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer)))
                addView(label("Pick your Grok data export (.zip) and Josh Learn builds your own topic tiles from what you chat about. It's analysed on this phone and stays private. A topics .json file works too.",
                    com.google.android.material.R.attr.textAppearanceBodyMedium, color = attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer), top = 4))
                addView(button("Import my topics") { importTopics() })
            }.apply { setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorPrimaryContainer)); strokeWidth = 0 })
            addView(card(top = 10, onClick = { newTopic() }) {
                addView(label("✏️ Make your own topic", com.google.android.material.R.attr.textAppearanceTitleMedium, bold = true))
                addView(label("Anything you want to learn: name it, pick an emoji, and it becomes your own tile here and on the widget.",
                    com.google.android.material.R.attr.textAppearanceBodyMedium, top = 4))
                addView(button("+ New topic") { newTopic() })
            })
            topOfHome(this)
            addView(label("What do you want to learn?", com.google.android.material.R.attr.textAppearanceTitleLarge, top = 16))
            val g = grid(3); builtIns(g, small = false); addView(g)
            addView(muted("Tap a tile to start a lesson right away · press and hold to choose a sub-topic", top = 4).apply { textSize = 12f })
        }

        val due = Repo.due().size
        if (Repo.cards.isNotEmpty()) addView(card(onClick = { startActivity(Intent(this@MainActivity, ReviewActivity::class.java)) }) {
            addView(label(if (due > 0) "🃏 $due flashcard${if (due == 1) "" else "s"} due" else "🃏 All caught up", bold = true))
            addView(muted(if (due > 0) "Tap to review now" else "Next review: ${nextDueText()}"))
        })

        val inProgress = Repo.lessons.filter { it.progressPercent in 1..99 || (it.progressPercent == 0 && it.lastOpened > it.createdAt + 60_000) }
            .sortedByDescending { it.lastOpened }.take(10)
        if (inProgress.isNotEmpty()) { addView(title("Continue learning")); addView(row(inProgress.map { miniLessonCard(it) })) }
        val recent = Repo.lessons.sortedByDescending { it.createdAt }.take(10)
        if (recent.isNotEmpty()) { addView(title("Recent")); addView(row(recent.map { miniLessonCard(it) })) }

        if (Repo.paths.isNotEmpty()) {
            addView(title("Learning paths"))
            Repo.paths.sortedByDescending { it.createdAt }.forEach { p ->
                val done = p.steps.count { s -> Repo.lesson(s.lessonId)?.let { it.progressPercent >= 50 } == true }
                addView(card(onClick = { startActivity(Intent(this@MainActivity, PathActivity::class.java).putExtra("id", p.id)) }) {
                    addView(label(p.title, bold = true))
                    addView(muted("${p.steps.size} lessons · ${p.difficulty.label} · $done done"))
                    addView(progressBar(if (p.steps.isEmpty()) 0 else done * 100 / p.steps.size))
                })
            }
        }
        addView(textBtn("+ New learning path") {
            startActivity(Intent(this@MainActivity, NewLessonActivity::class.java).putExtra(NewLessonActivity.EXTRA_PATH, true))
        })
    }

    private fun row(items: List<View>) = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(LinearLayout(context).apply {
            items.forEach { addView(it, LinearLayout.LayoutParams(dp(200), -1).apply { marginEnd = dp(10); topMargin = dp(6) }) }
        })
    }

    private fun miniLessonCard(l: Lesson) = card(top = 0, onClick = { startActivity(Intent(this, LessonActivity::class.java).putExtra("id", l.id)) }) {
        addView(label((Topics.category(l.category)?.emoji ?: "📘") + " " + l.title, bold = true).apply { maxLines = 2 })
        addView(muted("${l.difficulty.label} · ${relTime(l.lastOpened)}").apply { textSize = 12f })
        addView(progressBar(l.progressPercent))
    }

    private fun subtopicSheet(cat: Topics.Category, removable: String? = null) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val done = Repo.lessons.map { it.topic.lowercase() }.toSet()
        sheet.setContentView(ScrollView(this).apply {
            addView(vbox(20) {
                addView(label("${cat.emoji} ${cat.name}", com.google.android.material.R.attr.textAppearanceTitleLarge))
                addView(hbox {
                    setPadding(0, dp(8), 0, 0)
                    addView(button("⚡ 2-minute") { sheet.dismiss(); startQuick(Topics.pick(cat), cat) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
                    addView(outlined("⏱️ Challenge" + Challenge.best(context, cat.id).let { if (it > 0) " · 🏆$it" else "" }) { sheet.dismiss(); startChallenge(cat.name, cat) }, LinearLayout.LayoutParams(0, -2, 1f))
                })
                addView(depthSlider(Depths.get(context, cat.id)) { d -> Depths.set(this@MainActivity, cat.id, d) }.apply { setPadding(0, dp(12), 0, 0) })
                addView(muted("Pick a sub-topic to start a lesson", top = 8))
                addView(com.google.android.material.chip.ChipGroup(context).apply {
                    setPadding(0, dp(8), 0, 0)
                    cat.subtopics.forEach { t ->
                        addView(com.google.android.material.chip.Chip(context).apply {
                            text = (if (t.lowercase() in done) "✓ " else "") + t
                            setOnClickListener { sheet.dismiss(); startTopic(t, cat) }
                        })
                    }
                })
                addView(hbox {
                    addView(button("🎲 Random") { sheet.dismiss(); startTopic(Topics.pick(cat), cat) })
                    addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
                    addView(outlined("Learning path") { sheet.dismiss(); createPath(cat.name, defaultDifficulty()) })
                })
                val mineTopic = Topics.suggested(context).firstOrNull { it.asCategory.id == cat.id }
                if (mineTopic != null) addView(hbox {
                    setPadding(0, dp(8), 0, 0)
                    if (mineTopic.isCustom) {
                        addView(textBtn("✏️ Edit topic") { sheet.dismiss(); showTopicEditor(mineTopic) { render() } })
                        addView(textBtn("🗑️ Delete topic") { sheet.dismiss(); confirmDeleteTopic(mineTopic) { render() } })
                    } else addView(textBtn("Remove from my topics") { sheet.dismiss(); confirmDeleteTopic(mineTopic) { render() } })
                })
            })
        })
        sheet.show()
    }

    private fun nextDueText(): String {
        val next = Repo.cards.minOfOrNull { it.due } ?: return "—"
        val d = next - System.currentTimeMillis()
        return when { d < 3_600_000 -> "within the hour"; d < 86_400_000 -> "in ${d / 3_600_000} h"; else -> "in ${d / 86_400_000} d" }
    }

    private fun progressBar(pct: Int) = LinearProgressIndicator(this).apply {
        max = 100; setProgressCompat(pct, false); trackCornerRadius = dp(4)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun lessonCard(l: Lesson) = card(onClick = { startActivity(Intent(this, LessonActivity::class.java).putExtra("id", l.id)) }) {
        addView(label(l.title, bold = true))
        val cards = Repo.cardsFor(l.id).size
        addView(muted("${l.difficulty.label} · ${l.concepts.size} concepts · $cards cards · ${relTime(l.lastOpened)}"))
        addView(hbox {
            addView(progressBar(l.progressPercent).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(muted("  ${l.progressPercent}%"))
        })
    }

    // ------------------------------------------------------------- Library tab

    private fun library(): View {
        val list = vbox(16)
        fun fill() {
            list.removeAllViews()
            val res = Repo.search(libraryQuery)
            if (res.isEmpty()) list.addView(muted(if (Repo.lessons.isEmpty()) "No saved lessons yet. Create one from the Learn tab." else "No lessons match \"$libraryQuery\"."))
            res.forEach { list.addView(lessonCard(it)) }
        }
        val (til, et) = input("Search lessons", value = libraryQuery)
        til.setPadding(dp(16), dp(8), dp(16), 0)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { libraryQuery = s?.toString() ?: ""; fill() }
        })
        fill()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(til)
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    // ------------------------------------------------------------- Review tab

    private fun review(): View = vbox(16) {
        val now = System.currentTimeMillis()
        val due = Repo.due(now).size
        val tomorrow = Repo.cards.count { it.due in (now + 1)..(now + Sm2.DAY) }
        val mastered = Repo.cards.count { it.intervalDays >= 21 }
        addView(card(top = 0) {
            addView(label("$due due now", com.google.android.material.R.attr.textAppearanceHeadlineSmall))
            addView(muted("${Repo.cards.size} cards total · $tomorrow due in the next 24 h · $mastered mastered (3+ weeks)"))
            addView(button(if (due > 0) "Start review" else "Nothing due") {
                startActivity(Intent(this@MainActivity, ReviewActivity::class.java))
            }.apply { isEnabled = due > 0 })
            if (Repo.cards.isNotEmpty()) addView(textBtn("Practise all cards (doesn't change schedule)") {
                startActivity(Intent(this@MainActivity, ReviewActivity::class.java).putExtra(ReviewActivity.EXTRA_CRAM, true))
            })
        })
        addView(muted("Flashcards are created automatically with each lesson and scheduled with spaced repetition (SM-2). " +
            "Daily reminder: " + (if (Reminders.enabled(this@MainActivity)) "%02d:%02d".format(Reminders.hour(context), Reminders.minute(context)) else "off") + " (change in Settings).", top = 12))
        val byLesson = Repo.cards.groupBy { it.lessonId }
        if (byLesson.isNotEmpty()) addView(title("By lesson"))
        byLesson.forEach { (id, cs) ->
            val l = Repo.lesson(id) ?: return@forEach
            val d = cs.count { it.due <= now }
            addView(card(onClick = { startActivity(Intent(this@MainActivity, ReviewActivity::class.java).putExtra("lessonId", id)) }) {
                addView(label(l.title, bold = true))
                addView(muted("${cs.size} cards · $d due"))
            })
        }
    }

    companion object { const val TAB_HOME = 1; const val TAB_LIBRARY = 2; const val TAB_REVIEW = 3 }
}
