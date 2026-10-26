package com.josh.learn

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.josh.oldschool.OsAnswerButton
import com.josh.oldschool.OsBanner
import com.josh.oldschool.OsDialogs
import com.josh.oldschool.OsLessonCard
import com.josh.oldschool.OsXp
import com.josh.oldschool.OsXpDrops

/*
 * Glue between Josh Learn and the shared Old School kit (module :oldschool-theme, package com.josh.oldschool).
 * Everything visual comes from the kit; this file only decides when to use it (OSRS theme) and maps Learn's screens
 * onto kit pieces. Other themes keep the Material look.
 */

fun Context.isOsrs() = Display.theme(this) == "osrs"

/** Alert dialogs: the kit's chatbox / "Select an Option" / stone-panel dialogs in OSRS, Material otherwise. */
fun appDialog(ctx: Context): MaterialAlertDialogBuilder = if (ctx.isOsrs()) OsDialogs.alert(ctx) else MaterialAlertDialogBuilder(ctx)

/** Long-press option menus: the kit's "Choose Option" menu (target in orange) in OSRS, a list dialog otherwise. */
fun Context.optionsMenu(title: String, options: List<String>, onPick: (Int) -> Unit) {
    if (isOsrs()) OsDialogs.contextMenu(this, options, target = title, onPick = onPick).also { it.show(); lastMenu = it }
    else MaterialAlertDialogBuilder(this).setTitle(title).setItems(options.toTypedArray()) { _, i -> onPick(i) }.show().also { lastMenu = it }
}
var lastMenu: android.app.Dialog? = null

/** Quiz / challenge answer: kit OsAnswerButton (stone → green/red) in OSRS, an outlined Material button otherwise. */
fun Context.answerButton(text: String, onClick: (View) -> Unit): Button = answerButton0(text, onClick).apply { tag = "answer" }
private fun Context.answerButton0(text: String, onClick: (View) -> Unit): Button =
    if (isOsrs()) OsAnswerButton(this).apply {
        this.text = text; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    } else outlined(text, onClick).apply {
        isAllCaps = false; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

/** Marks answer buttons after a tap: the right one green, the tapped wrong one red, all disabled. */
fun Context.markAnswers(buttons: List<Button>, answer: Int, picked: Int) {
    buttons.forEach { it.isEnabled = false }
    fun mark(b: Button, ok: Boolean) {
        if (b is OsAnswerButton) b.result = if (ok) OsAnswerButton.Result.CORRECT else OsAnswerButton.Result.WRONG
        else if (b is MaterialButton) { b.strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, if (ok) R.color.good else R.color.bad)); b.strokeWidth = dp(3) }
    }
    buttons.getOrNull(answer)?.let { mark(it, true) }
    if (picked != answer) buttons.getOrNull(picked)?.let { mark(it, false) }
}

/** Right/wrong text colour: classic green/red in OSRS. */
fun Context.resultColor(ok: Boolean) = ContextCompat.getColor(this, if (isOsrs()) (if (ok) R.color.os_green else R.color.os_quest_red) else if (ok) R.color.good else R.color.bad)

/** Progress bar: the kit's HP bar (green on red) in OSRS, a Material linear indicator otherwise. */
fun Context.progressBarView(max: Int = 100): ProgressBar =
    if (isOsrs()) ProgressBar(this, null, 0, R.style.Widget_OldSchool_ProgressBar).apply { this.max = max; isIndeterminate = false }
    else com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { this.max = max; trackCornerRadius = dp(4) }

/**
 * Lesson content box: the kit's OsLessonCard (parchment scroll with rolled ends, black ink) in OSRS, a card otherwise.
 * Text views created inside [block] are switched to the kit's Ink text appearances.
 */
fun Context.paper(top: Int = 10, onClick: ((View) -> Unit)? = null, block: LinearLayout.() -> Unit): View {
    if (!isOsrs()) return card(top, onClick, block)
    val c = OsLessonCard(ContextThemeWrapper(this, R.style.ThemeOverlay_OldSchool_Parchment))
    c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    onClick?.let { c.setOnClickListener(it) }
    c.block()
    com.josh.oldschool.Os.parchmentize(c) // kit: ink text, no shadows, link-ink text buttons
    return c
}

/** Chips: the kit's stone chips (OsChips) in OSRS, Material chips otherwise. kind = "assist" | "choice" | "filter". */
fun Context.appChip(kind: String = "assist", text: CharSequence = ""): com.google.android.material.chip.Chip =
    if (isOsrs()) when (kind) {
        "choice" -> com.josh.oldschool.OsChips.choice(this, text)
        "filter" -> com.josh.oldschool.OsChips.filter(this, text)
        else -> com.josh.oldschool.OsChips.assist(this, text)
    } else com.google.android.material.chip.Chip(this, null, com.google.android.material.R.attr.chipStyle).apply { this.text = text }

/** Learning XP per topic, on the classic public XP curve (kit OsXp). Earned in every theme, shown in OSRS. */
object LearnXp {
    const val PER_CORRECT = 25
    private fun key(topic: String) = "xp:" + topic.trim().lowercase().take(80)
    fun xp(c: Context, topic: String) = LearnApp.prefs(c).getInt(key(topic), 0)
    fun level(c: Context, topic: String) = OsXp.levelFor(xp(c, topic))
    /** Adds [amount]; returns (old level, new level). */
    fun add(c: Context, topic: String, amount: Int): Pair<Int, Int> {
        val before = xp(c, topic); val after = (before + amount).coerceAtMost(200_000_000)
        LearnApp.prefs(c).edit().putInt(key(topic), after).apply()
        return OsXp.levelFor(before) to OsXp.levelFor(after)
    }
}

/** "+25" XP drop up the right edge on a correct answer (OSRS only). */
fun Activity.xpDrop(amount: Int = LearnXp.PER_CORRECT) {
    if (isOsrs()) OsXpDrops.rightSide(this, listOf(R.drawable.os_ic_book to amount))
}

/**
 * End of a challenge / 2-minute lesson / quiz: credits XP to the topic; in OSRS shows the kit's level-up banner
 * (with fireworks) when the topic's level went up, else a chatbox banner with the XP gained.
 */
fun Activity.finishXp(topicKey: String, skillName: String, correct: Int): Pair<Int, Int> {
    val gained = correct * LearnXp.PER_CORRECT
    val (old, new) = LearnXp.add(this, topicKey, gained)
    if (isOsrs()) {
        val skill = skillName.take(40)
        if (new > old) OsBanner.levelUp(this, skill, new, R.drawable.os_ic_book)
        else OsBanner.show(this, listOf(
            OsBanner.Line("You gained $gained $skill XP.", getColor(R.color.os_chat_blue_dark), 17f),
            OsBanner.Line("Level $new · ${OsXp.xpForLevel(new + 1) - LearnXp.xp(this, topicKey)} XP to level ${new + 1}", android.graphics.Color.BLACK, 15f),
            OsBanner.Line("Click here to continue", getColor(R.color.os_link_ink), 15f)), R.drawable.os_ic_book)
    }
    return old to new
}

/** XP key + display name for a topic: the category (built-in or your own) when known, else the free-text topic. */
fun Context.skillFor(catId: String?, topic: String): Pair<String, String> {
    val cat = catId?.let { id -> Topics.categories.firstOrNull { it.id == id } ?: Topics.suggested(this).map { it.asCategory }.firstOrNull { it.id == id } }
    return (cat?.id ?: topic) to (cat?.name ?: topic)
}
