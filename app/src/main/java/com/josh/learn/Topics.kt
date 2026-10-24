package com.josh.learn

import android.content.Context
import androidx.core.content.edit

/** Built-in topic catalogue for the home tiles, plus local "Suggested for you" logic. */
object Topics {
    data class Category(val id: String, val name: String, val emoji: String, val subtopics: List<String>)

    val categories = listOf(
        Category("science", "Science", "🔬", listOf("How atoms work", "Photosynthesis", "DNA and genes", "Evolution by natural selection", "Climate and the greenhouse effect",
            "Electricity basics", "The periodic table", "How vaccines work", "Plate tectonics", "Quantum physics basics", "Chemical reactions", "How the human eye works")),
        Category("history", "History", "🏛️", listOf("The Roman Empire", "Ancient Egypt", "World War I causes", "World War II overview", "The Industrial Revolution",
            "The Cold War", "The French Revolution", "The Silk Road", "Indigenous Australian history", "The Renaissance", "The moon landing", "The fall of the Berlin Wall")),
        Category("tech", "Tech", "💻", listOf("How the internet works", "What is AI and machine learning", "How large language models work", "Cybersecurity basics", "How computers store data",
            "Cloud computing", "How smartphones work", "Blockchain explained", "Programming basics", "How GPS works", "Encryption and passwords", "How search engines work")),
        Category("money", "Money", "💰", listOf("Budgeting basics", "Compound interest", "How the share market works", "Index funds and ETFs", "Superannuation in Australia",
            "How mortgages work", "Inflation explained", "Credit scores", "Tax basics in Australia", "Crypto risks and basics", "Emergency funds", "How banks make money")),
        Category("health", "Health", "🩺", listOf("How sleep works", "Nutrition basics", "How exercise builds muscle", "The immune system", "Stress and the body",
            "Heart health", "Gut microbiome", "How painkillers work", "Hydration", "Posture and back health", "How habits form", "First aid basics")),
        Category("space", "Space", "🚀", listOf("The solar system", "Black holes", "How stars are born and die", "The Big Bang", "Mars exploration",
            "Exoplanets", "How rockets work", "The International Space Station", "Galaxies", "Dark matter and dark energy", "Eclipses", "Searching for alien life")),
        Category("psychology", "Psychology", "🧠", listOf("Cognitive biases", "How memory works", "Motivation", "Emotional intelligence", "Habits and willpower",
            "The psychology of persuasion", "Growth mindset", "Anxiety explained", "How we make decisions", "Body language", "Learning techniques that work", "Flow state")),
        Category("languages", "Languages", "🗣️", listOf("Spanish basics for travel", "Japanese hiragana", "French greetings and phrases", "How languages evolve", "English grammar essentials",
            "Italian basics", "German basics", "Mandarin tones", "Sign language basics", "Etymology: where words come from", "Indonesian basics", "Common Latin phrases")),
        Category("nature", "Nature", "🌿", listOf("How ecosystems work", "Australian wildlife", "Coral reefs", "How trees communicate", "Bees and pollination",
            "Weather and clouds", "Oceans and tides", "Volcanoes", "Animal migration", "Endangered species", "Rainforests", "Fungi")),
        Category("arts", "Arts & Music", "🎨", listOf("Music theory basics", "Colour theory", "Famous painters and their styles", "How films are made", "Photography basics",
            "History of rock music", "Poetry basics", "Architecture styles", "How to read sheet music", "Storytelling structure", "Graphic design basics", "Classical composers")),
    )

    // ---- "Suggested for you": bundled asset suggested_topics.json {topics:[{title,category,emoji,priority,subtopics[]}]}
    data class Suggested(val title: String, val category: String, val emoji: String, val priority: Int, val subtopics: List<String>) {
        /** As a tile category (id prefixed "s:") so the sub-topic sheet, paths and lesson emoji all work. */
        val asCategory get() = Category("s:" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), title, emoji, subtopics.ifEmpty { listOf(title) })
    }

    @Volatile private var suggestedCache: List<Suggested>? = null

    /** Lenient parse: skips bad entries, sorts by priority (high first). Never throws. */
    fun parseSuggested(json: String): List<Suggested> = runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("topics") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val subs = o.optJSONArray("subtopics")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            Suggested(title, o.optString("category").trim(), o.optString("emoji").trim().ifEmpty { "⭐" }, o.optInt("priority", 0), subs)
        }.distinctBy { it.title.lowercase() }.sortedByDescending { it.priority }
    }.getOrDefault(emptyList())

    fun suggested(c: Context): List<Suggested> = suggestedCache ?: runCatching {
        c.applicationContext.assets.open("suggested_topics.json").bufferedReader().use { it.readText() }
    }.map { parseSuggested(it) }.getOrDefault(emptyList()).also { suggestedCache = it }

    private var suggestedCats: List<Category> = emptyList()
    fun init(c: Context) { suggestedCats = suggested(c).map { it.asCategory } }

    fun category(id: String?) = categories.firstOrNull { it.id == id } ?: suggestedCats.firstOrNull { it.id == id }

    private fun studied(): Set<String> = Repo.lessons.flatMap { listOf(it.topic.lowercase(), it.title.lowercase()) }.toSet()

    /** A not-yet-studied sub-topic of [c] (random), or any if all are done. */
    fun pick(c: Category): String {
        val done = studied()
        return c.subtopics.filter { it.lowercase() !in done }.ifEmpty { c.subtopics }.random()
    }

    fun surprise(): Pair<String, Category> { val c = categories.random(); return pick(c) to c }

    // ---- personalisation (local only)
    fun interests(c: Context): List<String> = LearnApp.prefs(c).getString("interests", "")!!
        .split(',', '\n', ';').map { it.trim() }.filter { it.length > 1 }
    fun setInterests(c: Context, text: String) = LearnApp.prefs(c).edit { putString("interests", text.trim()) }

    /**
     * Suggestions: user interests first (from Settings; can be filled from an exported chat history later),
     * then unstudied sub-topics from the categories studied most, then a varied mix.
     */
    fun suggestions(c: Context, n: Int = 8): List<Pair<String, Category?>> {
        val done = studied()
        val out = LinkedHashMap<String, Category?>()
        interests(c).filter { it.lowercase() !in done }.shuffled().take(n / 2).forEach { out[it] = null }
        val favCats = Repo.lessons.mapNotNull { category(it.category) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        favCats.forEach { cat -> cat.subtopics.filter { it.lowercase() !in done }.shuffled().take(2).forEach { if (out.size < n) out[it] = cat } }
        val rnd = java.util.Random(java.time.LocalDate.now().toEpochDay()) // stable for the day
        categories.shuffled(rnd).forEach { cat -> if (out.size < n) cat.subtopics.filter { it.lowercase() !in done }.shuffled(rnd).firstOrNull()?.let { out[it] = cat } }
        return out.entries.take(n).map { it.key to it.value }
    }
}
