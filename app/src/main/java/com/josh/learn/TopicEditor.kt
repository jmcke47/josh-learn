package com.josh.learn

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Last editor shown (for tests). */
var lastTopicEditor: AlertDialog? = null

/**
 * "+ New topic" / "Edit topic": name (required), optional "what I want to learn", emoji (auto-suggested from the name, tap to change)
 * and an optional starting depth. Saved on this phone; importing never touches these.
 */
fun BaseActivity.showTopicEditor(existing: Topics.Suggested? = null, onSaved: (Topics.Suggested) -> Unit) {
    var emoji = existing?.emoji ?: Topics.suggestEmoji("")
    var picked = existing != null // once Josh picks an emoji himself (or is editing), stop auto-suggesting
    var byHand = existing?.emojiPicked == true // saved with the topic so updates never replace it
    var depth = if (existing != null) Depths.get(this, existing.customId) else Depth.NORMAL
    val big = label(emoji).apply { textSize = 44f; gravity = Gravity.CENTER; contentDescription = "Topic emoji" }
    val (nameTil, name) = input("Topic name", value = existing?.title ?: "")
    nameTil.helperText = "e.g. Welding basics, Sourdough, Fixing my ute's suspension"
    nameTil.counterMaxLength = Topics.MAX_TITLE; nameTil.isCounterEnabled = true
    val (descTil, desc) = input("What do you want to learn? (optional)", multiLine = true, value = existing?.description ?: "")
    descTil.helperText = "Put each idea on its own line to make them sub-topics"
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    fun setEmoji(e: String, hand: Boolean) { emoji = e; big.text = e; if (hand) { picked = true; byHand = true }
        for (i in 0 until chips.childCount) (chips.getChildAt(i) as Chip).isChecked = (chips.getChildAt(i) as Chip).text == e }
    fun suggestions() = Symbols.choices(Topics.suggestEmoji(name.text.toString(), desc.text.toString())).let { if (emoji in it) it else listOf(emoji) + it }
    fun fillChips() {
        chips.removeAllViews()
        suggestions().forEach { e -> chips.addView(appChip("choice").apply {
            text = e; textSize = 20f; isCheckable = true; isChecked = e == emoji; contentDescription = "Use $e"
            setOnClickListener { setEmoji(e, true) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
    }
    fillChips()
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            nameTil.error = null
            if (!picked) { val e = Topics.suggestEmoji(name.text.toString(), desc.text.toString()); if (e != emoji) { emoji = e; big.text = e; fillChips() } }
        }
    }
    name.addTextChangedListener(watcher); desc.addTextChangedListener(watcher)
    val body = vbox(20) {
        addView(hbox {
            addView(big, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(muted(if (existing == null) "Your own topic tile. Lessons, 2-minute lessons, challenges, learning paths and the widget all use it." else "Changes apply to new lessons. Lessons you already made stay.")
                .apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(muted("Emoji (tap to change)", top = 4).apply { textSize = 12f })
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        addView(nameTil); addView(descTil)
        addView(muted("Starting depth (optional)", top = 12))
        addView(depthSlider(depth) { depth = it })
    }
    val b = appDialog(this).setTitle(if (existing == null) "New topic" else "Edit topic")
        .setView(android.widget.ScrollView(this).apply { addView(body) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (existing == null) "Create topic" else "Save", null)
    if (existing != null) b.setNeutralButton("Delete") { _, _ -> confirmDeleteTopic(existing) { onSaved(existing) } }
    val dlg = b.create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val s = runCatching { Topics.saveCustomTopic(this, existing?.customId, name.text.toString(), emoji, desc.text.toString(), picked = byHand) }
                .getOrElse { e -> nameTil.error = e.message; name.requestFocus(); return@setOnClickListener }
            if (Depths.perTopic(this) && (existing != null || depth != Depth.NORMAL)) Depths.set(this, s.customId, depth)
            dlg.dismiss()
            toast(if (existing == null) "Added ${s.emoji} ${s.title} to Your topics" else "Saved")
            onSaved(s)
        }
    }
    dlg.show()
    lastTopicEditor = dlg
}

fun BaseActivity.confirmDeleteTopic(s: Topics.Suggested, onDone: () -> Unit) {
    appDialog(this).setTitle("Delete ${s.emoji} ${s.title}?")
        .setMessage(if (s.isCustom) "Removes this topic from Your topics and the widget. Lessons and flashcards you already made stay in your Library."
            else "Hides this imported topic from Your topics and the widget. Lessons you made stay. Re-importing your Grok export brings it back.")
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (s.isCustom) "Delete" else "Remove") { _, _ -> Topics.remove(this, s.title); onDone() }
        .show().also { lastTopicEditor = it }
}

/** Last symbol picker shown (for tests). */
var lastSymbolPicker: AlertDialog? = null

/**
 * "Change symbol" for a lesson: the same emoji chips as the new-topic dialog (suggestion first), a big preview, and
 * "Use suggested" to go back to the automatic pick. Saved with the lesson; updates never replace it.
 */
fun BaseActivity.showSymbolPicker(l: Lesson, onSaved: () -> Unit) {
    val suggested = Symbols.forLesson(l)
    var emoji = Symbols.of(l)
    val big = label(emoji).apply { textSize = 44f; gravity = Gravity.CENTER; contentDescription = "Lesson symbol" }
    val chips = com.google.android.material.chip.ChipGroup(this)
    fun pick(e: String) { emoji = e; big.text = e
        for (i in 0 until chips.childCount) (chips.getChildAt(i) as Chip).isChecked = (chips.getChildAt(i) as Chip).text == e }
    Symbols.choices(suggested).let { if (emoji in it) it else listOf(emoji) + it }.forEach { e ->
        chips.addView(appChip("choice").apply { text = e; textSize = 20f; isCheckable = true; isChecked = e == emoji; contentDescription = "Use $e"; setOnClickListener { pick(e) } })
    }
    val body = vbox(20) {
        addView(hbox {
            addView(big, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(muted("Symbol for \"${l.title}\". Shown in the Library, on home and in the lesson.").apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(muted("Tap a symbol (suggested: $suggested)", top = 4).apply { textSize = 12f })
        addView(chips)
    }
    val dlg = appDialog(this).setTitle("Change symbol")
        .setView(android.widget.ScrollView(this).apply { addView(body) })
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton("Use suggested") { _, _ -> l.emoji = suggested; l.emojiPicked = false; Repo.save(); onSaved() }
        .setPositiveButton("Save") { _, _ -> l.emoji = emoji; l.emojiPicked = emoji != suggested; Repo.save(); onSaved() }
        .create()
    dlg.show(); lastSymbolPicker = dlg
}
