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
            if (providers.ready().isEmpty() && !prefs.getBoolean("setup_shown", false)) {
                prefs.edit().putBoolean("setup_shown", true).apply()
                Setup.show(this) { onProviderChanged() }
            }
            if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("notif_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
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

    private fun home(): View = vbox(16) {
        pendingUpdate?.let { m ->
            addView(card(top = 0, onClick = { updater.startUpdate(m) }) {
                addView(label("Update available: v${m.versionName}", bold = true))
                addView(muted("Tap to download and install"))
            })
        }
        addView(card(top = if (pendingUpdate != null) 10 else 0) {
            addView(label("What do you want to learn?", com.google.android.material.R.attr.textAppearanceTitleLarge))
            addView(muted("Type a topic, or paste text or a link. You can also share text or web pages to Josh Learn from other apps."))
            val (til, et) = input("e.g. How vaccines work, Roman Empire, Kotlin coroutines")
            et.imeOptions = EditorInfo.IME_ACTION_GO
            val go = { startActivity(Intent(this@MainActivity, NewLessonActivity::class.java).putExtra(NewLessonActivity.EXTRA_TOPIC, et.text?.toString() ?: "")) }
            et.setOnEditorActionListener { _, _, _ -> go(); true }
            addView(til)
            addView(hbox {
                addView(button("Create lesson") { go() })
                addView(textBtn("Learning path") {
                    startActivity(Intent(this@MainActivity, NewLessonActivity::class.java)
                        .putExtra(NewLessonActivity.EXTRA_TOPIC, et.text?.toString() ?: "").putExtra(NewLessonActivity.EXTRA_PATH, true))
                })
            })
        })

        val due = Repo.due().size
        if (Repo.cards.isNotEmpty()) addView(card(onClick = { startActivity(Intent(this@MainActivity, ReviewActivity::class.java)) }) {
            addView(label(if (due > 0) "$due flashcard${if (due == 1) "" else "s"} due" else "All caught up", bold = true))
            addView(muted(if (due > 0) "Tap to review now" else "Next review: ${nextDueText()}"))
        })

        val recent = Repo.lessons.sortedByDescending { it.lastOpened }.take(3)
        if (recent.isNotEmpty()) {
            addView(title("Continue learning"))
            recent.forEach { addView(lessonCard(it)) }
        }
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
        if (recent.isEmpty()) addView(muted("Your lessons will appear here.", top = 16))
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
