package com.josh.learn

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.Menu
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** AI-generated quiz: multiple choice (auto-marked) + short answer (self-marked against a model answer, saves API calls). */
class QuizActivity : BaseActivity() {
    private lateinit var lesson: Lesson
    private lateinit var scroll: ScrollView
    private var questions: List<Prompts.Question> = emptyList()
    private var index = 0
    private var correct = 0
    private val missed = mutableListOf<Prompts.Question>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lesson = Repo.lesson(intent.getStringExtra("id")) ?: run { finish(); return }
        scroll = ScrollView(this)
        screen("Quiz: ${lesson.title}", scroll)
        intro()
    }

    override fun buildMenu(menu: Menu) = addProviderSwitcher(menu)

    private fun intro() {
        scroll.removeAllViews()
        scroll.addView(vbox(16) {
            addView(label("Test yourself", com.google.android.material.R.attr.textAppearanceHeadlineSmall))
            addView(muted("Multiple-choice and short-answer questions, written fresh by the AI each time." +
                if (providers.active == Provider.LOCAL) " Offline questions can occasionally have a wrong answer key." else ""))
            if (lesson.quizScores.isNotEmpty()) {
                addView(title("Past scores"))
                lesson.quizScores.takeLast(8).reversed().forEach { addView(label("${it.correct}/${it.total}  ·  ${relTime(it.at)}")) }
            }
            addView(button("Start quiz") { generate() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) } })
        })
    }

    private fun generate() {
        runAi("Writing your quiz…", task = { cfg, onDelta, cancelled ->
            Gen.quiz(cfg, lesson, onDelta, cancelled)
        }) { qs -> questions = qs; index = 0; correct = 0; missed.clear(); show() }
    }

    private fun show() {
        if (index >= questions.size) { finishQuiz(); return }
        val q = questions[index]
        scroll.removeAllViews()
        val col = vbox(16)
        col.addView(muted("Question ${index + 1} of ${questions.size}  ·  score $correct"))
        col.addView(label(q.question, com.google.android.material.R.attr.textAppearanceTitleLarge, top = 8))
        val after = vbox()
        fun reveal(ok: Boolean, extra: String = "") {
            if (ok) correct++ else missed += q
            after.addView(label(if (ok) "✓ Correct" else "✗ Not quite", bold = true,
                color = resultColor(ok), top = 12))
            if (ok) xpDrop()
            if (extra.isNotBlank()) after.addView(label(extra, top = 4))
            if (q.explanation.isNotBlank()) after.addView(muted(q.explanation, top = 6))
            after.addView(button(if (index + 1 < questions.size) "Next" else "See results") { index++; show() }
                .apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) } })
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        when (q) {
            is Prompts.Question.Mcq -> {
                val buttons = mutableListOf<android.widget.Button>()
                q.options.forEachIndexed { i, opt ->
                    val b = answerButton("${"ABCDEFGH"[i]}.  $opt") {
                        markAnswers(buttons, q.answer, i)
                        reveal(i == q.answer, if (i == q.answer) "" else "Answer: ${q.options[q.answer]}")
                    }.apply {
                        isAllCaps = false; textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                    }
                    buttons += b; col.addView(b)
                }
            }
            is Prompts.Question.Short -> {
                val (til, et) = input("Your answer", multiLine = true)
                col.addView(til)
                val check = button("Check") {}
                check.setOnClickListener {
                    check.isEnabled = false; et.isEnabled = false
                    after.addView(label("Model answer", bold = true, top = 12))
                    after.addView(label(q.answer, top = 4))
                    after.addView(muted("Compare with yours. Did you get the main idea?", top = 8))
                    val row = hbox {}
                    row.addView(button("I got it") { row.removeAllViews(); reveal(true) })
                    row.addView(android.view.View(this), LinearLayout.LayoutParams(dp(8), 1))
                    row.addView(outlined("I missed it") { row.removeAllViews(); reveal(false) })
                    after.addView(row)
                }
                col.addView(check)
            }
        }
        col.addView(after)
        scroll.addView(col)
    }

    /** Test hook: start the quiz with ready-made questions. */
    fun start(qs: List<Prompts.Question>) { questions = qs; index = 0; correct = 0; missed.clear(); show() }
    /** Test hook: tap option i of the current multiple-choice question. */
    fun answer(i: Int) {
        fun all(v: View): List<View> = if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
        all(scroll).filter { it.tag == "answer" }[i].performClick()
    }

    private fun finishQuiz() {
        lesson.quizScores += QuizScore(correct, questions.size, System.currentTimeMillis())
        skillFor(lesson.category, lesson.topic.ifBlank { lesson.title }).let { (key, name) -> finishXp(key, name, correct) }
        Repo.save()
        scroll.removeAllViews()
        val pct = if (questions.isEmpty()) 0 else correct * 100 / questions.size
        scroll.addView(vbox(16) {
            addView(label("$correct / ${questions.size}", com.google.android.material.R.attr.textAppearanceDisplaySmall))
            addView(label(when { pct >= 90 -> "Excellent! 🎉"; pct >= 70 -> "Nice work!"; pct >= 50 -> "Getting there."; else -> "Worth another look at the lesson." }))
            addView(muted("Best score: ${(lesson.bestQuiz * 100).toInt()}% · lesson progress ${lesson.progressPercent}%", top = 4))
            if (missed.isNotEmpty()) {
                addView(title("To review"))
                missed.forEach { q ->
                    val ans = when (q) { is Prompts.Question.Mcq -> q.options[q.answer]; is Prompts.Question.Short -> q.answer }
                    addView(card { addView(label(q.question, bold = true)); addView(label(ans, top = 4)) })
                }
                addView(outlined("Add these as flashcards") { v ->
                    missed.forEach { q ->
                        val ans = when (q) { is Prompts.Question.Mcq -> q.options[q.answer]; is Prompts.Question.Short -> q.answer }
                        Repo.cards += Flashcard(lessonId = lesson.id, front = q.question, back = ans)
                    }
                    Repo.save(); v.isEnabled = false; toast("Added ${missed.size} flashcards")
                })
            }
            addView(button("New quiz") { generate() })
            addView(textBtn("Back to lesson") { finish() })
        })
    }
}
