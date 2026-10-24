package com.josh.learn

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import androidx.core.content.edit
import com.google.android.material.materialswitch.MaterialSwitch

/** Create a lesson (or learning path) from a topic, pasted text, or text/URL shared from another app. */
class NewLessonActivity : BaseActivity() {
    private var difficulty = Difficulty.BEGINNER
    private var length = LessonLength.MEDIUM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = LearnApp.prefs(this)
        difficulty = enumOr(prefs.getString("difficulty", null), Difficulty.BEGINNER)
        length = enumOr(prefs.getString("length", null), LessonLength.MEDIUM)

        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf {
                val t = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""; !t.contains(it) && HtmlText.isMostlyUrl(t) }, intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> intent.getStringExtra(EXTRA_TOPIC)
        } ?: ""

        val (til, et) = input("Topic, text or link", multiLine = true, value = shared)
        val hint = muted("", top = 4)
        val pathSwitch = MaterialSwitch(this).apply { text = "Make a learning path (a sequence of lessons)"; isChecked = intent.getBooleanExtra(EXTRA_PATH, false) }
        fun updateHint() {
            val t = et.text?.toString() ?: ""
            hint.text = when {
                pathSwitch.isChecked -> "The AI will break this into 5-8 lessons you can work through in order."
                HtmlText.isMostlyUrl(t) -> "Link detected: the page will be read and turned into a lesson."
                isSourceText(t) -> "Long text: the lesson will summarise and teach this text."
                else -> "Enter any topic, a question, or paste an article."
            }
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateHint() }
        })
        pathSwitch.setOnCheckedChangeListener { _, _ -> updateHint() }
        updateHint()

        val body = vbox(16) {
            addView(til); addView(hint)
            addView(title("Level"))
            addView(choiceChips(Difficulty.entries, difficulty, { it.label }) { difficulty = it })
            addView(title("Length"))
            addView(choiceChips(LessonLength.entries, length, { it.label }) { length = it })
            addView(pathSwitch.apply { setPadding(0, dp(12), 0, 0) })
            addView(button("Generate") {
                val t = et.text?.toString()?.trim() ?: ""
                if (t.isEmpty()) { til.error = "Enter a topic first"; return@button }
                prefs.edit { putString("difficulty", difficulty.name); putString("length", length.name) }
                if (pathSwitch.isChecked) generatePath(t) else generateLesson(t)
            }.apply { layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) } })
            addView(muted("Works offline with the on-device AI, or with an online provider you set up in Settings.", top = 12))
        }
        screen(if (intent.getBooleanExtra(EXTRA_PATH, false)) "New learning path" else "New lesson", body, scroll = true)
        if (shared.isEmpty()) et.requestFocus()
    }

    override fun buildMenu(menu: Menu) = addProviderSwitcher(menu)

    private fun isSourceText(t: String) = t.length > 280 || t.count { it == '\n' } >= 3
    private fun generateLesson(input: String) = createLesson(input, difficulty, length, finishAfter = true)
    private fun generatePath(input: String) = createPath(input, difficulty, finishAfter = true)

    companion object { const val EXTRA_TOPIC = "topic"; const val EXTRA_PATH = "path" }
}
