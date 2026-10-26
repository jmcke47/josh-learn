package com.josh.learn

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Flashcard review with SM-2 scheduling. "Cram" mode practises without changing the schedule. */
class ReviewActivity : BaseActivity() {
    private lateinit var scroll: ScrollView
    private val queue = ArrayDeque<Flashcard>()
    private var reviewed = 0
    private var cram = false
    private var current: Flashcard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cram = intent.getBooleanExtra(EXTRA_CRAM, false)
        val lessonId = intent.getStringExtra("lessonId")
        val pool = if (lessonId != null) Repo.cardsFor(lessonId) else Repo.cards.toList()
        val due = pool.filter { it.due <= System.currentTimeMillis() }.sortedBy { it.due }
        if (!cram && due.isEmpty() && lessonId != null) cram = true // nothing due: practise this lesson's cards
        queue.addAll(if (cram) pool.shuffled() else due)
        scroll = ScrollView(this)
        val title = Repo.lesson(lessonId)?.title?.let { "Cards: $it" } ?: if (cram) "Practice" else "Review"
        screen(title, scroll)
        next()
    }

    override fun buildMenu(menu: Menu) {
        menu.add("Delete this card").setOnMenuItemClickListener {
            val c = current ?: return@setOnMenuItemClickListener true
            appDialog(this).setTitle("Delete this card?").setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete") { _, _ -> Repo.cards.remove(c); queue.remove(c); Repo.save(); next() }.show(); true
        }
    }

    private fun next() {
        scroll.removeAllViews()
        val c = queue.removeFirstOrNull()
        current = c
        if (c == null) {
            scroll.addView(vbox(24) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label(if (reviewed > 0) "Done! 🎉" else "Nothing to review", com.google.android.material.R.attr.textAppearanceHeadlineMedium))
                addView(muted(if (reviewed > 0) "You reviewed $reviewed card${if (reviewed == 1) "" else "s"}." else "No cards are due right now.", top = 8))
                addView(button("Finish") { finish() }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) } })
            })
            return
        }
        val lesson = Repo.lesson(c.lessonId)
        val answer = vbox()
        scroll.addView(vbox(16) {
            addView(muted("${queue.size + 1} left${if (cram) " · practice" else ""}${lesson?.let { " · ${it.title}" } ?: ""}"))
            addView(card {
                minimumHeight = dp(160)
                addView(label(c.front, com.google.android.material.R.attr.textAppearanceHeadlineSmall))
            })
            addView(answer)
            val show = button("Show answer") {}
            show.setOnClickListener {
                show.visibility = android.view.View.GONE
                answer.addView(card { addView(label(c.back, com.google.android.material.R.attr.textAppearanceTitleMedium)) })
                answer.addView(muted("How well did you remember?", top = 16))
                answer.addView(hbox {
                    Sm2.Grade.entries.forEach { g ->
                        val b = (if (g == Sm2.Grade.GOOD) button("${g.label}\n${Sm2.preview(c, g)}") { grade(c, g) }
                                 else outlined("${g.label}\n${Sm2.preview(c, g)}") { grade(c, g) })
                        b.isAllCaps = false; b.setPadding(0, dp(8), 0, dp(8))
                        addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                    }
                })
            }
            addView(show.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) } })
        })
    }

    private fun grade(c: Flashcard, g: Sm2.Grade) {
        reviewed++
        if (!cram) { Sm2.apply(c, g); Repo.save() }
        if (g == Sm2.Grade.AGAIN) queue.addLast(c) // see it again this session
        next()
    }

    companion object { const val EXTRA_CRAM = "cram" }
}
