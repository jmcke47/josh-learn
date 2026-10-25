package com.josh.learn

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.LocalDate

/**
 * Topic of the day: one of Josh's topics per local date (same all day, changes at midnight), with a short fun fact that the
 * app writes once with the active AI (offline model or cloud) and caches. Until then a fallback line is shown.
 */
object TopicOfDay {
    @Volatile private var inFlight: String? = null

    fun today(): Long = LocalDate.now().toEpochDay()

    fun pick(c: Context, day: Long = today()): TopicsWidget.Item? {
        val mine = Topics.suggested(c).map { TopicsWidget.Item(it.emoji, it.title, TopicsWidget.EXTRA_TOPIC, it.title) }
        val list = mine.ifEmpty { Topics.categories.map { TopicsWidget.Item(it.emoji, it.name, TopicsWidget.EXTRA_CATEGORY, it.id) } }
        if (list.isEmpty()) return null
        // splitmix64 so neighbouring days are well spread (java.util.Random's first value is poor for nearby seeds)
        var z = day + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L; z = z xor (z ushr 31)
        return list[Math.floorMod(z, list.size.toLong()).toInt()]
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("tod", Context.MODE_PRIVATE)
    fun cachedFact(c: Context, day: Long, title: String): String? {
        val p = prefs(c)
        return if (p.getLong("day", -1) == day && p.getString("title", null) == title) p.getString("fact", null) else null
    }
    fun saveFact(c: Context, day: Long, title: String, fact: String) = prefs(c).edit().putLong("day", day).putString("title", title).putString("fact", fact).apply()
    fun fallback(item: TopicsWidget.Item) = "Tap for today's lesson on ${item.title}."
    fun line(c: Context, day: Long = today()): String? = pick(c, day)?.let { cachedFact(c, day, it.title) ?: fallback(it) }

    /** Writes today's fact in the background if it isn't cached yet and an AI is ready (never prompts for setup). */
    fun ensureFact(c: Context, providers: ProviderStore, onDone: (() -> Unit)? = null) {
        val day = today(); val item = pick(c, day) ?: return
        if (cachedFact(c, day, item.title) != null) return
        val tag = "$day/${item.title}"
        if (inFlight == tag) return
        val cfg = providers.config(providers.active) ?: providers.ready().firstNotNullOfOrNull { providers.config(it) } ?: return
        inFlight = tag
        val app = c.applicationContext
        Thread {
            val fact = runCatching { Prompts.parseFact(AiClient.complete(cfg, Prompts.funFact(item.title), null) { false }) }.getOrNull()
            inFlight = null
            if (fact != null) {
                saveFact(app, day, item.title, fact)
                Handler(Looper.getMainLooper()).post { TopicsWidget.refresh(app); onDone?.invoke() }
            }
        }.apply { isDaemon = true; name = "topic-of-day" }.start()
    }
}
