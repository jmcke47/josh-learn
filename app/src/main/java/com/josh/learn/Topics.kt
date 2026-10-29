package com.josh.learn

import android.content.Context
import androidx.core.content.edit

/** Built-in topic catalogue ("More to explore") plus the user's own imported topics ("Your topics"). */
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

    // ---- "Your topics": topics the user imports from a JSON file {topics:[{title,category,emoji,priority,subtopics[]}]}.
    // Nothing is bundled in the APK; the import is saved privately in filesDir (kept across app updates).
    data class Suggested(val title: String, val category: String, val emoji: String, val priority: Int, val subtopics: List<String>,
                         /** Set for topics Josh made himself ("c:…", stable across renames); null for imported ones. */
                         val customId: String? = null, val description: String = "", val createdAt: Long = 0L,
                         /** Josh chose this emoji himself (never replaced automatically). */
                         val emojiPicked: Boolean = false) {
        val isCustom get() = customId != null
        /** As a tile category (id prefixed "s:", or "c:" for his own) so the sub-topic sheet, paths and lesson emoji all work. */
        val asCategory get() = Category(customId ?: ("s:" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')), title, emoji, subtopics.ifEmpty { listOf(title) })
    }

    @Volatile private var suggestedCache: List<Suggested>? = null

    /** Lenient parse: skips bad entries, sorts by priority (high first). Never throws. */
    fun parseSuggested(json: String): List<Suggested> = runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("topics") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val subs = o.optJSONArray("subtopics")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            Suggested(title, o.optString("category").trim(), o.optString("emoji").trim().takeUnless { Symbols.isDefault(it) } ?: suggestEmoji(title, o.optString("category")), o.optInt("priority", 0), subs)
        }.distinctBy { it.title.lowercase() }.sortedByDescending { it.priority }
    }.getOrDefault(emptyList())

    fun importedFile(c: Context) = java.io.File(c.applicationContext.filesDir, "suggested_topics.json")

    /** Only the imported topics (from the Grok export / topics file). */
    fun imported(c: Context): List<Suggested> = runCatching {
        importedFile(c).takeIf { it.exists() }?.readText()?.let { parseSuggested(it) } ?: emptyList()
    }.getOrDefault(emptyList())

    /** "Your topics": Josh's own topics (newest first), then imported ones by priority. Same title: his own wins. */
    fun suggested(c: Context): List<Suggested> = suggestedCache ?: (custom(c) + imported(c)).distinctBy { it.title.trim().lowercase() }.also { suggestedCache = it }

    const val MAX_IMPORT_BYTES = 1_000_000

    /** Parses [json] leniently and saves it privately. Returns the number of topics, or throws with a friendly message. */
    // ---- Josh's own topics: filesDir/custom_topics.json (kept across app updates; importing never touches it)
    fun customFile(c: Context) = java.io.File(c.applicationContext.filesDir, "custom_topics.json")
    const val MAX_TITLE = 60; const val MAX_DESCRIPTION = 300

    fun custom(c: Context): List<Suggested> = runCatching {
        val arr = org.json.JSONObject(customFile(c).takeIf { it.exists() }?.readText() ?: return emptyList()).optJSONArray("topics") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val desc = o.optString("description").trim()
            val picked = o.optBoolean("emojiPicked", false)
            val e = o.optString("emoji").trim()
            Suggested(title, "Mine", if (picked) e.ifEmpty { suggestEmoji(title, desc) } else e.takeUnless { Symbols.isDefault(it) } ?: suggestEmoji(title, desc), 1000, customSubtopics(title, desc),
                o.optString("id").ifEmpty { "c:" + title.lowercase().hashCode().toUInt().toString(16) }, desc, o.optLong("createdAt"), picked)
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    /** Lesson prompts for a custom topic: the title, or "title: what I want to learn"; extra lines of the description become sub-topics. */
    fun customSubtopics(title: String, desc: String): List<String> {
        val lines = desc.lines().map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.length >= 3 }
        return if (lines.size >= 2) lines.map { "$title: $it" } else listOf(if (desc.isBlank()) title else "$title: ${desc.trim()}")
    }

    private fun saveCustom(c: Context, list: List<Suggested>) {
        val json = org.json.JSONObject().put("version", 1).put("topics", org.json.JSONArray().apply {
            list.forEach { s -> put(org.json.JSONObject().put("id", s.customId).put("title", s.title).put("emoji", s.emoji)
                .put("description", s.description).put("createdAt", s.createdAt).put("emojiPicked", s.emojiPicked)) }
        }).toString(2)
        val f = customFile(c); val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        suggestedCache = null; init(c); TopicsWidget.refresh(c)
    }

    /**
     * Creates (id == null) or edits one of Josh's own topics. Returns it, or throws with a friendly message
     * (empty name, or a name already used by another topic).
     */
    fun saveCustomTopic(c: Context, id: String?, title: String, emoji: String, description: String, now: Long = System.currentTimeMillis(), picked: Boolean = false): Suggested {
        val t = title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE)
        if (t.isEmpty()) throw IllegalArgumentException("Give your topic a name")
        val all = custom(c)
        if (suggested(c).any { it.title.equals(t, true) && it.customId != id }) throw IllegalArgumentException("You already have a topic called \"$t\"")
        val d = description.trim().take(MAX_DESCRIPTION)
        val old = all.firstOrNull { it.customId == id }
        val e = emoji.trim()
        val keep = (picked || old?.emojiPicked == true) && e.isNotEmpty()
        val s = Suggested(t, "Mine", if (keep) e else e.takeUnless { Symbols.isDefault(it) } ?: suggestEmoji(t, d), 1000, customSubtopics(t, d),
            id ?: ("c:" + java.util.UUID.randomUUID().toString().take(8)), d, old?.createdAt ?: now, keep)
        saveCustom(c, if (old != null) all.map { if (it.customId == id) s else it } else listOf(s) + all)
        return s
    }

    fun deleteCustomTopic(c: Context, id: String): Boolean {
        val all = custom(c); val rest = all.filterNot { it.customId == id }
        if (rest.size == all.size) return false
        saveCustom(c, rest); return true
    }

    /** Removes any of "Your topics" by title: his own topic is deleted, an imported one is removed from the import. */
    fun remove(c: Context, title: String): Boolean {
        custom(c).firstOrNull { it.title.equals(title, true) }?.let { return deleteCustomTopic(c, it.customId!!) }
        return removeImported(c, title)
    }

    private val emojiRules: List<Pair<Regex, String>> = listOf(
        "cars?\\b|4wd|4x4|engine|mechanic|ute|truck|drive" to "🚗", "motorbike|motorcycle" to "🏍️", "plane|fly|flying|aviation|pilot" to "✈️",
        "boat|sail|fishing|fish" to "🎣", "cook|recipe|food|bak|kitchen|bbq" to "🍳", "coffee" to "☕", "beer|brew|wine" to "🍺",
        "guitar|music|song|piano|drum|band" to "🎸", "paint|draw|arts?\\b|sketch" to "🎨", "photo|camera" to "📷", "film|movie|cinema" to "🎬",
        "game|gaming|runescape|osrs|xbox|playstation|nintendo" to "🎮", "code|coding|program|python|kotlin|software|developer" to "💻",
        "network|wifi|router|server|homelab|home lab|linux" to "🖥️", "phone|android|iphone|gadget" to "📱", "ai\\b|llm|machine learning|robot" to "🤖",
        "electr|solar|battery|power|energy" to "🔋", "space|star|planet|astronom|nasa|rocket" to "🚀", "physics|quantum|atom" to "⚛️",
        "chem|lab|dna|forensic|biolog|microscope" to "🔬", "health|fitness|gym|muscle|exercise|run\\b|running" to "💪", "sleep" to "😴", "medic|doctor|nurse|anatomy" to "🩺",
        "brain|psych|mind|memory|habit" to "🧠", "money|invest|finance|budget|tax|super\\b|superannuation|shares|stock" to "💰", "crypto|bitcoin|blockchain" to "₿",
        "history|ancient|war|roman|medieval" to "🏛️", "politic|government|election|laws?\\b" to "⚖️", "language|spanish|french|japanese|german|italian|words" to "🗣️",
        "book|read|writ|poem|poetry|story" to "📚", "garden|plant|tree|grow" to "🌱", "dog|cat|pet|animal|wildlife|bird" to "🐾",
        "ocean|sea\\b|reef|beach|surf" to "🌊", "weather|climate|storm" to "⛅", "travel|trip|country|geograph" to "🌏", "build|diy|wood|tool|carpent|weld" to "🔧",
        "math|maths|algebra|calculus|number" to "➗", "ufo|alien|uap" to "🛸", "sport|footy|football|cricket|rugby|soccer" to "🏉",
    ).map { (k, e) -> Regex("\\b(?:$k)", RegexOption.IGNORE_CASE) to e }

    /** A fitting emoji for a topic name (and description): the original rules, then the wide Symbols map, then a hash fallback (never ⭐). */
    fun suggestEmoji(title: String, description: String = ""): String =
        Symbols.match(title)?.takeIf { Symbols.isFlag(it) } ?: emojiRules.firstOrNull { it.first.containsMatchIn(title) }?.second ?: Symbols.match(title)
            ?: emojiRules.firstOrNull { it.first.containsMatchIn(description) }?.second ?: Symbols.match(description) ?: Symbols.fallback(title)

    /** Old suggester (v1.4.1–1.4.4), only to tell a default ⭐ from one Josh picked when migrating. */
    private fun oldSuggestion(title: String, description: String) =
        emojiRules.firstOrNull { it.first.containsMatchIn(title) }?.second ?: emojiRules.firstOrNull { it.first.containsMatchIn(description) }?.second ?: "⭐"

    /**
     * Migration: his own topics still stored with the old default ⭐ get a fitting symbol. A ⭐ he must have picked by hand
     * (the old suggester would have offered something else for that name) is kept and marked as his choice.
     */
    fun migrateCustomEmoji(c: Context): Int {
        val f = customFile(c); if (!f.exists()) return 0
        val raw = runCatching { org.json.JSONObject(f.readText()).optJSONArray("topics") }.getOrNull() ?: return 0
        val stored = (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }.associateBy { it.optString("id") }
        var n = 0
        val list = custom(c).map { s ->
            val o = stored[s.customId] ?: return@map s
            if (o.has("emojiPicked")) return@map s
            val was = o.optString("emoji").trim()
            when {
                !Symbols.isDefault(was) -> s.copy(emoji = was, emojiPicked = was != oldSuggestion(s.title, s.description))
                oldSuggestion(s.title, s.description) != "⭐" -> s.copy(emoji = was, emojiPicked = true) // ⭐ chosen by hand
                else -> { n++; s.copy(emoji = suggestEmoji(s.title, s.description), emojiPicked = false) }
            }
        }
        saveCustom(c, list)
        return n
    }

    val emojiChoices = listOf("⭐", "🚗", "✈️", "🎣", "🍳", "🎸", "🎨", "📷", "🎮", "💻", "🖥️", "📱", "🤖", "🔋", "🚀", "⚛️", "🔬", "💪", "🧠", "💰",
        "🏛️", "⚖️", "🗣️", "📚", "🌱", "🐾", "🌊", "🌏", "🔧", "➗", "🛸", "🏉", "🎲", "💡")

    fun importJson(c: Context, json: String): Int {
        if (json.length > MAX_IMPORT_BYTES) throw IllegalArgumentException("That file is too big for a topics list.")
        val parsed = parseSuggested(json)
        if (parsed.isEmpty()) throw IllegalArgumentException("No topics found. The file should look like {\"topics\":[{\"title\":…,\"subtopics\":[…]}]}.")
        val f = importedFile(c); val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        suggestedCache = null; init(c)
        TopicsWidget.refresh(c)
        return parsed.size
    }

    fun toJson(list: List<Suggested>): String = org.json.JSONObject().put("topics", org.json.JSONArray().apply {
        list.forEach { s -> put(org.json.JSONObject().put("title", s.title).put("category", s.category).put("emoji", s.emoji)
            .put("priority", s.priority).put("subtopics", org.json.JSONArray(s.subtopics))) }
    }).toString(2)

    /** Removes one imported topic (by title). Returns true if it was there. */
    fun removeImported(c: Context, title: String): Boolean {
        val all = imported(c); val rest = all.filterNot { it.title.equals(title, ignoreCase = true) }
        if (rest.size == all.size) return false
        if (rest.isEmpty()) clearImported(c) else importJson(c, toJson(rest))
        return true
    }

    fun clearImported(c: Context) { importedFile(c).delete(); suggestedCache = null; init(c); TopicsWidget.refresh(c) }

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
