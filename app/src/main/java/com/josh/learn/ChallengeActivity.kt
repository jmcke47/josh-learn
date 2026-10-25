package com.josh.learn

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Personal bests for challenge mode, per topic, stored on the phone. */
object Challenge {
    const val SECONDS = 60
    private fun key(topic: String) = "pb:" + topic.trim().lowercase().take(80)
    fun best(c: Context, topic: String) = LearnApp.prefs(c).getInt(key(topic), 0)
    /** Saves [score] if it beats the personal best; returns true if it's a new best. */
    fun record(c: Context, topic: String, score: Int): Boolean {
        if (score <= best(c, topic)) return false
        LearnApp.prefs(c).edit().putInt(key(topic), score).apply(); return true
    }
}

/** Challenge mode: answer as many questions as you can in 60 seconds. Score + personal best per topic. */
class ChallengeActivity : BaseActivity() {
    lateinit var scroll: ScrollView
    lateinit var topic: String
    private var category: String? = null
    private var questions: List<Prompts.Question.Mcq> = emptyList()
    private var index = 0
    var score = 0
    var answered = 0
    private var endAt = 0L
    var running = false
    private lateinit var timer: LinearProgressIndicator
    private lateinit var timeText: android.widget.TextView
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val left = endAt - SystemClock.uptimeMillis()
            if (left <= 0) { finishChallenge(false); return }
            timer.setProgressCompat((left / 100).toInt(), false)
            timeText.text = "⏱️ ${(left + 999) / 1000}s"
            main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topic = intent.getStringExtra("topic") ?: run { finish(); return }
        category = intent.getStringExtra("cat")
        scroll = ScrollView(this)
        screen("⏱️ Challenge", scroll)
        intro()
    }

    override fun onPause() { super.onPause(); if (running) finishChallenge(false) }
    override fun buildMenu(menu: Menu) = addProviderSwitcher(menu)
    private fun pbKey() = category ?: topic

    private fun intro() {
        scroll.removeAllViews()
        scroll.addView(vbox(16) {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(label("⏱️").apply { textSize = 56f })
            addView(label("60-second challenge", com.google.android.material.R.attr.textAppearanceHeadlineSmall))
            addView(label(topic, com.google.android.material.R.attr.textAppearanceTitleMedium, top = 4).apply { gravity = android.view.Gravity.CENTER })
            addView(muted("Answer as many questions as you can before the timer runs out. The clock starts once the questions are ready.", top = 8)
                .apply { gravity = android.view.Gravity.CENTER })
            val pb = Challenge.best(context, pbKey())
            addView(label(if (pb > 0) "🏆 Personal best: $pb" else "🏆 No personal best yet", bold = true, top = 16))
            addView(button("Start challenge") { generate() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) } })
        })
    }

    private fun generate() {
        val depth = Depths.get(this, pbKey())
        runAi("Getting your challenge ready…", task = { cfg, onDelta, cancelled -> Gen.challenge(cfg, topic, category, depth, onDelta, cancelled) }) { start(it) }
    }

    /** Starts the clock with [qs] (also the entry point for tests). */
    fun start(qs: List<Prompts.Question.Mcq>) {
        questions = qs; index = 0; score = 0; answered = 0
        endAt = SystemClock.uptimeMillis() + Challenge.SECONDS * 1000L
        running = true
        show()
        main.post(tick)
    }

    private fun show() {
        if (index >= questions.size) { finishChallenge(true); return }
        val m = questions[index]
        scroll.removeAllViews()
        val col = vbox(16)
        timeText = label("⏱️ ${((endAt - SystemClock.uptimeMillis()).coerceAtLeast(0) + 999) / 1000}s", com.google.android.material.R.attr.textAppearanceTitleLarge, bold = true)
        col.addView(hbox {
            addView(timeText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("Score $score", com.google.android.material.R.attr.textAppearanceTitleLarge, bold = true))
        })
        timer = LinearProgressIndicator(this).apply { max = Challenge.SECONDS * 10; trackCornerRadius = dp(4)
            setProgressCompat(((endAt - SystemClock.uptimeMillis()) / 100).toInt().coerceAtLeast(0), false) }
        col.addView(timer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(muted("Question ${index + 1} of ${questions.size}", top = 12))
        col.addView(label(m.question, com.google.android.material.R.attr.textAppearanceTitleLarge, top = 4))
        val buttons = mutableListOf<MaterialButton>()
        m.options.forEachIndexed { i, opt ->
            val b = outlined("${"ABCDEFGH"[i]}.  $opt") { pick(i, buttons) }.apply {
                isAllCaps = false; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            buttons += b; col.addView(b)
        }
        scroll.addView(col)
    }

    private fun pick(i: Int, buttons: List<MaterialButton>) {
        if (!running) return
        val m = questions[index]; answered++
        val ok = i == m.answer
        if (ok) score++
        buttons.forEach { it.isEnabled = false }
        buttons[m.answer].strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.good)); buttons[m.answer].strokeWidth = dp(3)
        if (!ok) { buttons[i].strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bad)); buttons[i].strokeWidth = dp(3) }
        main.postDelayed({ if (running) { index++; show() } }, if (ok) 350 else 900)
    }

    fun answer(i: Int) { // test hook
        val col = scroll.getChildAt(0) as LinearLayout
        (0 until col.childCount).map { col.getChildAt(it) }.filterIsInstance<MaterialButton>()[i].performClick()
    }

    private fun finishChallenge(clearedAll: Boolean) {
        if (!running) return
        running = false; main.removeCallbacks(tick)
        val newBest = Challenge.record(this, pbKey(), score)
        val best = Challenge.best(this, pbKey())
        scroll.removeAllViews()
        scroll.addView(vbox(16) {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(label(if (newBest) "🏆" else "⏱️").apply { textSize = 56f })
            addView(label(if (clearedAll) "You answered them all!" else "Time's up!", com.google.android.material.R.attr.textAppearanceHeadlineMedium))
            addView(label("Score: $score", com.google.android.material.R.attr.textAppearanceDisplaySmall, top = 8))
            addView(muted("$score correct out of $answered answered", top = 4))
            addView(label(if (newBest) "New personal best! 🎉" else "Personal best: $best", bold = true, top = 12))
            fun full(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } }
            addView(full(button("Play again") { generate() }))
            addView(full(textBtn("Done") { finish() }))
        })
    }
}
