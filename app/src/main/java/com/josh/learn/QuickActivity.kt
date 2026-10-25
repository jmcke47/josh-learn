package com.josh.learn

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Quick 2-minute mode: one ~150-word lesson, 3 quiz questions, then a done screen. Works with every provider incl. offline. */
class QuickActivity : BaseActivity() {
    lateinit var scroll: ScrollView
    lateinit var topic: String
    private var category: String? = null
    var quick: Gen.Quick? = null
    private var index = 0
    var correct = 0
    var lessonId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topic = intent.getStringExtra("topic") ?: run { finish(); return }
        category = intent.getStringExtra("cat")
        scroll = ScrollView(this)
        screen("⚡ 2-minute lesson", scroll)
        if (intent.getBooleanExtra("noAuto", false)) intro() else generate()
    }

    override fun buildMenu(menu: Menu) = addProviderSwitcher(menu)

    private fun depthKey() = category ?: topic

    private fun intro() {
        scroll.removeAllViews()
        scroll.addView(vbox(16) {
            addView(label(topic, com.google.android.material.R.attr.textAppearanceHeadlineSmall))
            addView(muted("A short lesson (about 150 words), then 3 quick questions."))
            addView(button("Start") { generate() })
        })
    }

    fun generate() {
        val depth = Depths.get(this, depthKey())
        runAi("Making a 2-minute lesson…", task = { cfg, onDelta, cancelled -> Gen.quick(cfg, topic, depth, onDelta, cancelled) }) { show(it) }
    }

    /** Shows the lesson part (also the entry point for tests). */
    fun show(q: Gen.Quick) {
        quick = q; index = 0; correct = 0
        val l = Lesson(topic = topic, title = q.title, difficulty = Difficulty.BEGINNER, length = LessonLength.SHORT, overview = q.text,
            concepts = emptyList(), glossary = emptyList(), takeaways = emptyList(), category = category, depth = Depths.get(this, depthKey()),
            model = runCatching { providers.config()?.let { Gen.label(it) } }.getOrNull())
        Repo.addLesson(l, q.questions.map { it.question to it.options[it.answer] }); lessonId = l.id
        scroll.removeAllViews()
        scroll.addView(vbox(16) {
            addView(muted("⚡ 2 minutes · ${Depths.get(context, depthKey()).label}"))
            addView(label(q.title, com.google.android.material.R.attr.textAppearanceHeadlineSmall, top = 4))
            addView(card { addView(label(q.text).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.15f) }) })
            addView(button("Quiz me: ${q.questions.size} questions") { question() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) } })
        })
    }

    private fun question() {
        val q = quick ?: return
        if (index >= q.questions.size) { done(); return }
        val m = q.questions[index]
        scroll.removeAllViews()
        val col = vbox(16)
        col.addView(muted("Question ${index + 1} of ${q.questions.size}"))
        col.addView(label(m.question, com.google.android.material.R.attr.textAppearanceTitleLarge, top = 8))
        val after = vbox()
        val buttons = mutableListOf<MaterialButton>()
        m.options.forEachIndexed { i, opt ->
            val b = outlined("${"ABCDEFGH"[i]}.  $opt") {
                buttons.forEach { it.isEnabled = false }
                buttons[m.answer].strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.good)); buttons[m.answer].strokeWidth = dp(3)
                val ok = i == m.answer
                if (ok) correct++ else { buttons[i].strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bad)); buttons[i].strokeWidth = dp(3) }
                after.addView(label(if (ok) "✓ Correct" else "✗ Answer: ${m.options[m.answer]}", bold = true,
                    color = ContextCompat.getColor(this, if (ok) R.color.good else R.color.bad), top = 12))
                if (m.explanation.isNotBlank()) after.addView(muted(m.explanation, top = 4))
                after.addView(button(if (index + 1 < q.questions.size) "Next" else "Finish") { index++; question() }
                    .apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) } })
            }.apply { isAllCaps = false; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } }
            buttons += b; col.addView(b)
        }
        col.addView(after)
        scroll.addView(col)
    }

    fun answer(i: Int) { // test hook: tap option i of the current question
        val col = scroll.getChildAt(0) as LinearLayout
        (0 until col.childCount).map { col.getChildAt(it) }.filterIsInstance<MaterialButton>()[i].performClick()
    }
    fun next() { index++; question() }

    private fun done() {
        val q = quick ?: return
        Repo.lesson(lessonId)?.let { it.quizScores += QuizScore(correct, q.questions.size, System.currentTimeMillis()); Repo.save() }
        scroll.removeAllViews()
        scroll.addView(vbox(16) {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(label(if (correct == q.questions.size) "🎉" else if (correct > 0) "👍" else "📚").apply { textSize = 56f })
            addView(label("Done!", com.google.android.material.R.attr.textAppearanceHeadlineMedium))
            addView(label("$correct / ${q.questions.size} correct", com.google.android.material.R.attr.textAppearanceTitleLarge, top = 4))
            addView(muted("You learned something about ${q.title} in about 2 minutes. Saved to your Library with ${q.questions.size} flashcards.", top = 8)
                .apply { gravity = android.view.Gravity.CENTER })
            fun full(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } }
            addView(full(button("Full lesson on this") { createLesson(topic, defaultDifficulty(), defaultLength(), category = category, finishAfter = true) }))
            addView(full(outlined("Another 2-minute lesson") { generate() }))
            addView(full(outlined("⏱️ 60-second challenge") { startActivity(Intent(this@QuickActivity, ChallengeActivity::class.java).putExtra("topic", topic).putExtra("cat", category)); finish() }))
            addView(full(textBtn("Done") { finish() }))
        })
    }
}
