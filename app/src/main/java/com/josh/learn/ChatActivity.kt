package com.josh.learn

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Menu
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip

/** Tutor chat about a lesson, with streaming replies and quick "Explain simpler / Go deeper" actions. */
class ChatActivity : BaseActivity() {
    private lateinit var lesson: Lesson
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: com.google.android.material.textfield.TextInputEditText
    private lateinit var send: MaterialButton
    private var busy = false
    @Volatile private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lesson = Repo.lesson(intent.getStringExtra("id")) ?: run { finish(); return }
        list = vbox(12)
        scroll = ScrollView(this).apply { addView(list) }
        if (isOsrs()) { // kit carved chatbox around the conversation
            scroll.setBackgroundResource(R.drawable.os_chatbox_carved)
            list.setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val chips = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        listOf("Explain simpler" to "Explain this lesson more simply, like I'm new to it. Use an everyday analogy.",
            "Go deeper" to "Go deeper: explain the underlying mechanisms, nuances and common misconceptions.",
            "Give an example" to "Give me a fresh, concrete real-world example of the main idea.",
            "Quiz me" to "Ask me one challenging question about this lesson. Wait for my answer before revealing it.",
            "Summarise" to "Summarise the lesson in 5 bullet points.").forEach { (t, prompt) ->
            if (isOsrs()) chips.addView(TextView(this, null, 0, R.style.Widget_OldSchool_ChatTab).apply { // kit chat tabs
                text = t; setPadding(dp(12), 0, dp(12), 0); com.josh.oldschool.Os.shadow(this); setOnClickListener { ask(prompt) } },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
            else chips.addView(Chip(this).apply { text = t; setOnClickListener { ask(prompt) } }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        val (til, et) = input("Ask a follow-up question", multiLine = true)
        input = et; et.maxLines = 4
        send = button("Send") { ask(et.text?.toString() ?: "") }
        val depthKey = lesson.category ?: lesson.topic
        val depthRow = depthSlider(Depths.get(this, depthKey), compact = true) { d -> Depths.set(this, depthKey, d); toast("Tutor answers: ${d.label}") }
            .apply { setPadding(dp(8), 0, dp(8), 0) }
        val bottom = vbox {
            addView(muted("Answer depth").apply { setPadding(dp(16), dp(4), 0, 0); textSize = 12f })
            addView(depthRow)
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
            addView(hbox {
                setPadding(dp(12), 0, dp(12), dp(8))
                addView(til, LinearLayout.LayoutParams(0, -2, 1f))
                addView(send, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8); topMargin = dp(4) })
            })
        }
        screen("Tutor: ${lesson.title}", scroll, bottom = bottom)
        if (lesson.chat.isEmpty()) list.addView(muted("Ask anything about \"${lesson.title}\". Use the quick buttons to get a simpler or deeper explanation." +
            if (providers.active == Provider.LOCAL) "\n\nYou're using the Offline AI: answers are shorter and can be wrong." else "").apply {
            if (isOsrs()) { setTextColor(getColor(R.color.os_ink_brown)); setShadowLayer(0f, 0f, 0f, 0) } })
        lesson.chat.forEach { bubble(it.role, it.content) }
        scrollDown()
        intent.getStringExtra(EXTRA_DRAFT)?.let { et.setText(it); et.setSelection(it.length); et.requestFocus() }
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_PROMPT)?.let { ask(it) }
    }

    override fun buildMenu(menu: Menu) {
        addProviderSwitcher(menu)
        menu.add("Clear chat").setOnMenuItemClickListener {
            lesson.chat.clear(); Repo.save(); list.removeAllViews(); true
        }
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }

    private fun bubble(role: String, text: String): TextView {
        val mine = role == "user"
        val tv = label(format(text)).apply { setTextIsSelectable(true) }
        if (isOsrs()) { // chatbox line: coloured speaker name, then the message (classic public-chat colours, no shadow on parchment)
            tv.setTextColor(getColor(if (mine) R.color.os_chat_blue else R.color.os_ink)); tv.setShadowLayer(0f, 0f, 0f, 0)
            tv.typeface = com.josh.oldschool.Os.font(this)
            val row = vbox {
                setBackgroundResource(R.drawable.os_chat_row); setPadding(dp(8), dp(4), dp(8), dp(6))
                addView(label(if (mine) "You:" else "Tutor:", bold = true).apply {
                    setTextColor(getColor(if (mine) R.color.os_ink else R.color.os_chat_red)); setShadowLayer(0f, 0f, 0f, 0)
                    typeface = com.josh.oldschool.Os.font(this@ChatActivity, true) })
                addView(tv)
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            return tv
        }
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(attrColor(if (mine) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerHigh))
            addView(tv.apply { setPadding(dp(14), dp(10), dp(14), dp(10)) })
        }
        list.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8); gravity = if (mine) Gravity.END else Gravity.START
            if (mine) marginStart = dp(48) else marginEnd = dp(24)
        })
        return tv
    }

    /** Tiny markdown: **bold**, leading "- "/"* " bullets, "#" headings. */
    private fun format(md: String): CharSequence {
        val sb = SpannableStringBuilder()
        md.lines().forEachIndexed { i, raw ->
            if (i > 0) sb.append('\n')
            var line = raw
            var heading = false
            Regex("^#{1,6}\\s+").find(line)?.let { line = line.substring(it.value.length); heading = true }
            Regex("^\\s*[-*]\\s+").find(line)?.let { line = "•  " + line.substring(it.value.length) }
            val start = sb.length
            var rest = line
            while (true) {
                val a = rest.indexOf("**"); val b = if (a >= 0) rest.indexOf("**", a + 2) else -1
                if (a < 0 || b < 0) { sb.append(rest); break }
                sb.append(rest.substring(0, a))
                val s = sb.length; sb.append(rest.substring(a + 2, b)); sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, 0)
                rest = rest.substring(b + 2)
            }
            if (heading) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
        }
        return sb
    }

    private fun ask(text: String, provider: Provider = providers.active, resend: Boolean = false) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        val cfg = requireProvider(provider) ?: return
        if (!resend) {
            if (lesson.chat.isEmpty()) list.removeAllViews()
            input.setText("")
            lesson.chat += Msg("user", q); bubble("user", q)
        }
        busy = true; send.isEnabled = false; cancelled = false
        val tv = bubble("assistant", "…")
        scrollDown()
        val messages = Gen.chat(cfg, lesson, lesson.chat, Depths.get(this, lesson.category ?: lesson.topic))
        val acc = StringBuilder()
        var last = 0L
        io.execute {
            val r = runCatching {
                AiClient.complete(cfg, messages, { d ->
                    if (d.startsWith(AiClient.STATUS)) { val s = d.substring(1); main.post { if (acc.isEmpty()) tv.text = s }; return@complete }
                    synchronized(acc) { acc.append(d) }
                    val now = System.currentTimeMillis()
                    if (now - last > 120) { last = now; val s = synchronized(acc) { acc.toString() }; main.post { tv.text = format(s); scrollDown() } }
                }, { cancelled || isFinishing })
            }
            main.post {
                if (isDestroyed) return@post
                busy = false; send.isEnabled = true
                r.onSuccess { full0 ->
                    val full = Prompts.stripThinking(full0)
                    tv.text = format(full)
                    lesson.chat += Msg("assistant", full)
                    while (lesson.chat.size > 40) lesson.chat.removeAt(0)
                    Repo.save(); scrollDown()
                }
                r.onFailure { e ->
                    list.removeView(tv.parent as android.view.View)
                    showAiError(e, cfg.provider) { p -> ask(q, p, resend = true) }
                }
            }
        }
    }

    override fun onPause() { super.onPause(); if (isFinishing) cancelled = true }

    companion object { const val EXTRA_PROMPT = "prompt"; const val EXTRA_DRAFT = "draft" }
}
