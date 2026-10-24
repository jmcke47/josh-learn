package com.josh.learn

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PathActivity : BaseActivity() {
    private lateinit var path: LearningPath
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = Repo.path(intent.getStringExtra("id")) ?: run { finish(); return }
        scroll = ScrollView(this)
        screen(path.title, scroll)
    }

    override fun onResume() { super.onResume(); if (::path.isInitialized) render() }

    override fun buildMenu(menu: Menu) {
        addProviderSwitcher(menu)
        menu.add("Delete path").setOnMenuItemClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Delete this learning path?").setMessage("Lessons already created stay in your library.")
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton("Delete") { _, _ -> Repo.deletePath(path); finish() }.show(); true
        }
    }

    private fun render() {
        scroll.removeAllViews()
        scroll.addView(vbox(16) {
            addView(label(path.title, com.google.android.material.R.attr.textAppearanceHeadlineSmall))
            addView(muted("${path.difficulty.label} · ${path.steps.size} lessons. Work through them in order; each builds on the last."))
            path.steps.forEachIndexed { i, s ->
                val l = Repo.lesson(s.lessonId)
                addView(card {
                    addView(label("${i + 1}. ${s.title}", com.google.android.material.R.attr.textAppearanceTitleMedium))
                    if (s.goal.isNotBlank()) addView(muted(s.goal, top = 4))
                    if (l != null) {
                        addView(muted("${l.progressPercent}% learned", top = 6))
                        addView(button("Open") { startActivity(Intent(this@PathActivity, LessonActivity::class.java).putExtra("id", l.id)) })
                    } else {
                        val next = i == 0 || Repo.lesson(path.steps[i - 1].lessonId) != null
                        addView(if (next) button("Generate lesson") { generate(i) } else outlined("Generate lesson") { generate(i) })
                    }
                })
            }
        })
    }

    private fun generate(i: Int) {
        val s = path.steps[i]
        val prev = path.steps.take(i).joinToString("; ") { it.title }
        val ctx = "Path \"${path.title}\", step ${i + 1} of ${path.steps.size}. Goal: ${s.goal}." + if (prev.isNotEmpty()) " Already covered: $prev." else ""
        runAi("Creating lesson ${i + 1}…", task = { cfg, onDelta, cancelled ->
            Prompts.parseLesson(AiClient.complete(cfg, Prompts.lesson(s.title, path.difficulty, LessonLength.MEDIUM, pathContext = ctx), onDelta, cancelled),
                s.title, path.difficulty, LessonLength.MEDIUM)
        }) { parsed ->
            parsed.lesson.pathId = path.id
            s.lessonId = parsed.lesson.id
            Repo.addLesson(parsed.lesson, parsed.flashcards)
            startActivity(Intent(this, LessonActivity::class.java).putExtra("id", parsed.lesson.id))
        }
    }
}
