package com.josh.learn

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class LessonActivity : BaseActivity() {
    private lateinit var lesson: Lesson
    private lateinit var progress: LinearProgressIndicator
    private lateinit var progressText: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lesson = Repo.lesson(intent.getStringExtra("id")) ?: run { finish(); return }
        lesson.lastOpened = System.currentTimeMillis(); Repo.save()
        progress = LinearProgressIndicator(this).apply { max = 100; trackCornerRadius = dp(4) }
        progressText = muted("")
        screen(lesson.title, build(), scroll = true)
    }

    override fun onResume() { super.onResume(); if (::lesson.isInitialized) refreshProgress() }

    private fun refreshProgress() {
        progress.setProgressCompat(lesson.progressPercent, true)
        val quiz = if (lesson.quizScores.isEmpty()) "no quiz yet" else "best quiz ${(lesson.bestQuiz * 100).toInt()}%"
        progressText.text = "${lesson.progressPercent}% learned · ${lesson.understood.count { it < lesson.concepts.size }}/${lesson.concepts.size} concepts · $quiz"
    }

    override fun buildMenu(menu: Menu) {
        addProviderSwitcher(menu)
        fun item(t: String, f: () -> Unit) = menu.add(t).setOnMenuItemClickListener { f(); true }.also { it.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
        item("Take quiz") { openQuiz() }
        item("Ask the tutor") { openChat() }
        item("Review this lesson's flashcards") { startActivity(Intent(this, ReviewActivity::class.java).putExtra("lessonId", lesson.id)) }
        if (lesson.difficulty != Difficulty.BEGINNER) item("Make a simpler version") { regenerate(Difficulty.entries[lesson.difficulty.ordinal - 1]) }
        if (lesson.difficulty != Difficulty.EXPERT) item("Make a deeper version") { regenerate(Difficulty.entries[lesson.difficulty.ordinal + 1]) }
        item("Share lesson as text") { share() }
        item("Delete lesson") {
            MaterialAlertDialogBuilder(this).setTitle("Delete this lesson?").setMessage("Its flashcards and chat are deleted too.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete") { _, _ -> Repo.deleteLesson(lesson); finish() }.show()
        }
    }

    private fun build() = vbox(16) {
        addView(label(lesson.title, com.google.android.material.R.attr.textAppearanceHeadlineSmall))
        addView(muted(listOf(lesson.difficulty.label, lesson.length.label, "created ${relTime(lesson.createdAt)}").joinToString(" · ")))
        if (lesson.isOffline || lesson.model != null) addView(muted(
            if (lesson.isOffline) "⚠ Made offline by ${lesson.model!!.removePrefix("Offline · ")}. Offline lessons are simpler and may contain mistakes; double-check important facts."
            else "Made with ${lesson.model}", top = 6).apply { textSize = 12f })
        val depthKey = lesson.category ?: lesson.topic
        val rewrite = button("Rewrite at this depth") { regenerate(lesson.difficulty, Depths.get(this@LessonActivity, depthKey)) }.apply { visibility = android.view.View.GONE }
        addView(card {
            addView(depthSlider(lesson.depth) { d -> Depths.set(this@LessonActivity, depthKey, d); rewrite.visibility = if (d != lesson.depth) android.view.View.VISIBLE else android.view.View.GONE
                rewrite.text = "Rewrite as \"${d.label}\"" })
            addView(rewrite)
        })
        addView(hbox {
            addView(tonal("⚡ 2-minute version") { startActivity(Intent(this@LessonActivity, QuickActivity::class.java).putExtra("topic", lesson.topic.ifBlank { lesson.title }).putExtra("cat", lesson.category)) })
            addView(android.view.View(context), LinearLayout.LayoutParams(dp(8), 1))
            addView(outlined("⏱️ 60s challenge") { startActivity(Intent(this@LessonActivity, ChallengeActivity::class.java).putExtra("topic", lesson.topic.ifBlank { lesson.title }).putExtra("cat", lesson.category)) })
        })
        addView(progress.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } })
        addView(progressText)
        refreshProgress()

        addView(card {
            addView(label("Overview", com.google.android.material.R.attr.textAppearanceTitleMedium))
            addView(label(lesson.overview, top = 6).apply { setTextIsSelectable(true) })
        })
        if (lesson.takeaways.isNotEmpty()) addView(card {
            addView(label("Key takeaways", com.google.android.material.R.attr.textAppearanceTitleMedium))
            lesson.takeaways.forEach { addView(label("•  $it", top = 6)) }
        })

        if (lesson.concepts.isNotEmpty()) addView(title("Key concepts", top = 20))
        lesson.concepts.forEachIndexed { i, c ->
            addView(card {
                addView(label("${i + 1}. ${c.title}", com.google.android.material.R.attr.textAppearanceTitleMedium))
                addView(label(c.explanation, top = 6).apply { setTextIsSelectable(true) })
                if (c.analogy.isNotBlank()) addView(muted("💡 Analogy: ${c.analogy}", top = 8))
                if (c.example.isNotBlank()) addView(muted("📌 Example: ${c.example}", top = 6))
                addView(hbox {
                    addView(MaterialCheckBox(context).apply {
                        text = "Got it"; isChecked = i in lesson.understood
                        setOnCheckedChangeListener { _, ch -> if (ch) lesson.understood += i else lesson.understood -= i; Repo.save(); refreshProgress() }
                    })
                    addView(android.view.View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(textBtn("Simpler") { openChat("Explain \"${c.title}\" more simply, with an everyday analogy.") })
                    addView(textBtn("Deeper") { openChat("Go deeper on \"${c.title}\": how it really works, nuances and common misconceptions.") })
                    addView(textBtn("Ask") { openChat(draft = "About \"${c.title}\": ") })
                })
            })
        }

        if (lesson.glossary.isNotEmpty()) addView(card(top = 20) {
            addView(label("Key terms", com.google.android.material.R.attr.textAppearanceTitleMedium))
            lesson.glossary.forEach { t ->
                addView(label(android.text.SpannableStringBuilder().apply {
                    append(t.term, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0); append(" — "); append(t.definition)
                }, top = 8))
            }
        })

        addView(title("Practise", top = 20))
        addView(hbox {
            addView(button("Take quiz") { openQuiz() })
            addView(android.view.View(context), LinearLayout.LayoutParams(dp(8), 1))
            addView(outlined("Ask tutor") { openChat() })
        })
        val n = Repo.cardsFor(lesson.id).size
        addView(outlined("Flashcards ($n)") { startActivity(Intent(this@LessonActivity, ReviewActivity::class.java).putExtra("lessonId", lesson.id)) })

        lesson.sourceUrl?.let { url -> addView(textBtn("Source: ${url.take(60)}") { openUrl(url) }) }

        Repo.path(lesson.pathId)?.let { p ->
            val idx = p.steps.indexOfFirst { it.lessonId == lesson.id }
            if (idx >= 0) addView(card(top = 20, onClick = { startActivity(Intent(this@LessonActivity, PathActivity::class.java).putExtra("id", p.id)) }) {
                addView(label("Learning path: ${p.title}", bold = true))
                addView(muted(if (idx + 1 < p.steps.size) "Step ${idx + 1} of ${p.steps.size}. Next: ${p.steps[idx + 1].title}" else "Final step of ${p.steps.size}"))
            })
        }
    }

    private fun openQuiz() = startActivity(Intent(this, QuizActivity::class.java).putExtra("id", lesson.id))
    private fun openChat(prompt: String? = null, draft: String? = null) =
        startActivity(Intent(this, ChatActivity::class.java).putExtra("id", lesson.id).putExtra(ChatActivity.EXTRA_PROMPT, prompt).putExtra(ChatActivity.EXTRA_DRAFT, draft))

    private fun regenerate(d: Difficulty, depth: Depth = lesson.depth) {
        val l = lesson
        runAi(if (depth != l.depth) "Rewriting as \"${depth.label}\"…" else "Writing a ${d.label.lowercase()} version…", task = { cfg, onDelta, cancelled ->
            Gen.lesson(cfg, l.topic.ifBlank { l.title }, d, l.length,
                source = (if (cfg.provider == Provider.LOCAL) "" else "Existing lesson to adapt:\n" + l.contextText()).ifEmpty { null }, onDelta = onDelta, cancelled = cancelled, depth = depth)
        }) { parsed ->
            parsed.lesson.sourceUrl = l.sourceUrl; parsed.lesson.category = l.category
            Repo.addLesson(parsed.lesson, parsed.flashcards)
            startActivity(Intent(this, LessonActivity::class.java).putExtra("id", parsed.lesson.id))
        }
    }

    private fun share() {
        val text = buildString {
            append(lesson.title).append("\n\n").append(lesson.overview).append("\n\n")
            lesson.concepts.forEachIndexed { i, c -> append("${i + 1}. ${c.title}\n${c.explanation}\n"); if (c.analogy.isNotBlank()) append("Analogy: ${c.analogy}\n"); append('\n') }
            if (lesson.glossary.isNotEmpty()) { append("Key terms\n"); lesson.glossary.forEach { append("• ${it.term}: ${it.definition}\n") } }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, lesson.title).putExtra(Intent.EXTRA_TEXT, text), "Share lesson"))
    }
}
