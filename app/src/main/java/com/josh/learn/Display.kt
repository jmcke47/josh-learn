package com.josh.learn

import android.content.Context
import android.content.res.Configuration

/** Display settings: theme (system / light / dark / AMOLED black / OSRS) and app-wide font size. */
object Display {
    val themes = listOf("system", "light", "dark", "amoled", "osrs")
    fun themeLabel(t: String) = when (t) { "amoled" -> "AMOLED black"; "osrs" -> "OSRS"; else -> t.replaceFirstChar(Char::uppercase) }
    fun theme(c: Context): String = LearnApp.prefs(c).getString("theme", "system")!!.takeIf { it in themes } ?: "system"
    /** Themes with their own fixed palette (no Material You wallpaper colours). */
    fun isCustom(c: Context) = theme(c) in setOf("amoled", "osrs")
    fun themeRes(c: Context): Int = when (theme(c)) {
        "amoled" -> R.style.Theme_JoshLearn_Amoled
        "osrs" -> R.style.Theme_JoshLearn_Osrs
        else -> R.style.Theme_JoshLearn
    }

    val fontSizes = listOf("small" to 0.85f, "default" to 1f, "large" to 1.15f, "xlarge" to 1.3f)
    fun fontLabel(k: String) = when (k) { "small" -> "Small"; "large" -> "Large"; "xlarge" -> "Extra large"; else -> "Default" }
    fun fontSize(c: Context): String = LearnApp.prefs(c).getString("font_size", "default")!!.takeIf { k -> fontSizes.any { it.first == k } } ?: "default"
    fun fontScale(c: Context): Float = fontSizes.first { it.first == fontSize(c) }.second

    /** Wraps an activity's base context so every sp size follows the chosen font size (on top of the system setting). */
    fun wrap(base: Context): Context {
        val s = fontScale(base)
        if (s == 1f) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.fontScale = cfg.fontScale * s
        return base.createConfigurationContext(cfg)
    }
}

/** How a lesson is pitched: one tap from "Like I'm 5" through "Normal" to "Expert". */
enum class Depth(val label: String, val prompt: String, val local: String) {
    ELI5("Like I'm 5", "Explain it like the learner is 5 years old: tiny words, very short sentences, playful everyday comparisons, absolutely no jargon.",
        "Explain it like I am 5 years old. Use tiny words and short sentences."),
    SIMPLE("Simple", "Keep it simple: plain words, short sentences, explain every term you use.", "Keep it simple. Use plain words."),
    NORMAL("Normal", "", ""),
    DEEP("Deeper", "Go deeper than usual: underlying mechanisms, why it works, common misconceptions, precise terms.", "Go deeper: explain how and why it works."),
    EXPERT("Expert", "Expert depth: precise technical terminology, nuances, edge cases, quantitative detail and current debates. Do not oversimplify.",
        "Explain at expert level with precise technical terms.");

    companion object { fun of(name: String?) = enumOr(name, NORMAL) }
}

/** Remembers the depth globally, or per topic when "Remember depth per topic" is on (default). */
object Depths {
    fun perTopic(c: Context) = LearnApp.prefs(c).getBoolean("depth_per_topic", true)
    private fun key(topic: String?) = "depth_t:" + (topic ?: "").trim().lowercase().take(80)
    fun get(c: Context, topic: String?): Depth {
        val p = LearnApp.prefs(c)
        val global = Depth.of(p.getString("depth", null))
        if (!perTopic(c) || topic.isNullOrBlank()) return global
        return p.getString(key(topic), null)?.let { Depth.of(it) } ?: global
    }
    fun set(c: Context, topic: String?, d: Depth) {
        val e = LearnApp.prefs(c).edit()
        if (perTopic(c) && !topic.isNullOrBlank()) e.putString(key(topic), d.name) else e.putString("depth", d.name)
        e.apply()
    }
}
