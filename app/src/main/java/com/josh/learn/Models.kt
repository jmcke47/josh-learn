package com.josh.learn

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Difficulty(val label: String) { BEGINNER("Beginner"), INTERMEDIATE("Intermediate"), EXPERT("Expert") }
enum class LessonLength(val label: String, val concepts: String, val cards: String) {
    SHORT("Short", "3-4", "4"), MEDIUM("Medium", "5-6", "6"), LONG("Long", "7-9", "8")
}

data class Concept(val title: String, val explanation: String, val analogy: String, val example: String)
data class Term(val term: String, val definition: String)
data class QuizScore(val correct: Int, val total: Int, val at: Long)

class Lesson(
    val id: String = UUID.randomUUID().toString(),
    var topic: String,
    var title: String,
    var difficulty: Difficulty,
    var length: LessonLength,
    var overview: String,
    var concepts: List<Concept>,
    var glossary: List<Term>,
    var takeaways: List<String>,
    var sourceUrl: String? = null,
    var pathId: String? = null,
    var category: String? = null,
    /** Who generated it, e.g. "Offline · Qwen3 0.6B" or "Gemini · gemini-3.8-flash". */
    var model: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var lastOpened: Long = System.currentTimeMillis(),
    val understood: MutableSet<Int> = mutableSetOf(),
    val quizScores: MutableList<QuizScore> = mutableListOf(),
    val chat: MutableList<Msg> = mutableListOf(),
) {
    val isOffline get() = model?.startsWith("Offline") == true
    val bestQuiz: Double get() = quizScores.maxOfOrNull { if (it.total == 0) 0.0 else it.correct.toDouble() / it.total } ?: 0.0
    val conceptProgress: Double get() = if (concepts.isEmpty()) 0.0 else understood.count { it < concepts.size }.toDouble() / concepts.size

    /** 0..100: half for concepts marked understood, half for best quiz score. */
    val progressPercent: Int get() = ((conceptProgress * 0.5 + bestQuiz * 0.5) * 100).toInt()

    /** Compact text used as context for chat/quiz prompts (keeps free-tier requests small). */
    fun contextText(): String = buildString {
        append("Lesson: ").append(title).append(" (").append(difficulty.label).append(")\n")
        append(overview).append('\n')
        concepts.forEachIndexed { i, c -> append(i + 1).append(". ").append(c.title).append(": ").append(c.explanation).append('\n') }
        if (glossary.isNotEmpty()) append("Terms: ").append(glossary.joinToString("; ") { it.term }).append('\n')
    }.take(6000)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("topic", topic); put("title", title); put("difficulty", difficulty.name); put("length", length.name)
        put("overview", overview); put("sourceUrl", sourceUrl ?: JSONObject.NULL); put("pathId", pathId ?: JSONObject.NULL)
        put("category", category ?: JSONObject.NULL); put("model", model ?: JSONObject.NULL)
        put("createdAt", createdAt); put("lastOpened", lastOpened)
        put("concepts", JSONArray().apply { concepts.forEach { put(JSONObject().put("title", it.title).put("explanation", it.explanation).put("analogy", it.analogy).put("example", it.example)) } })
        put("glossary", JSONArray().apply { glossary.forEach { put(JSONObject().put("term", it.term).put("definition", it.definition)) } })
        put("takeaways", JSONArray(takeaways))
        put("understood", JSONArray(understood.toList()))
        put("quizScores", JSONArray().apply { quizScores.forEach { put(JSONObject().put("c", it.correct).put("t", it.total).put("at", it.at)) } })
        put("chat", JSONArray().apply { chat.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = Lesson(
            id = o.getString("id"), topic = o.optString("topic"), title = o.optString("title"),
            difficulty = enumOr(o.optString("difficulty"), Difficulty.BEGINNER), length = enumOr(o.optString("length"), LessonLength.MEDIUM),
            overview = o.optString("overview"),
            concepts = o.optJSONArray("concepts").objs().map { Concept(it.optString("title"), it.optString("explanation"), it.optString("analogy"), it.optString("example")) },
            glossary = o.optJSONArray("glossary").objs().map { Term(it.optString("term"), it.optString("definition")) },
            takeaways = o.optJSONArray("takeaways").strings(),
            sourceUrl = o.optStringOrNull("sourceUrl"), pathId = o.optStringOrNull("pathId"),
            category = o.optStringOrNull("category"), model = o.optStringOrNull("model"),
            createdAt = o.optLong("createdAt"), lastOpened = o.optLong("lastOpened"),
            understood = (0 until (o.optJSONArray("understood")?.length() ?: 0)).map { o.getJSONArray("understood").getInt(it) }.toMutableSet(),
            quizScores = o.optJSONArray("quizScores").objs().map { QuizScore(it.optInt("c"), it.optInt("t"), it.optLong("at")) }.toMutableList(),
            chat = o.optJSONArray("chat").objs().map { Msg(it.optString("role"), it.optString("content")) }.toMutableList(),
        )
    }
}

class Flashcard(
    val id: String = UUID.randomUUID().toString(),
    val lessonId: String,
    var front: String,
    var back: String,
    var ef: Double = 2.5,
    var intervalDays: Int = 0,
    var reps: Int = 0,
    var due: Long = System.currentTimeMillis(),
    var lapses: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("lessonId", lessonId).put("front", front).put("back", back)
        .put("ef", ef).put("interval", intervalDays).put("reps", reps).put("due", due).put("lapses", lapses)
    companion object {
        fun fromJson(o: JSONObject) = Flashcard(o.getString("id"), o.optString("lessonId"), o.optString("front"), o.optString("back"),
            o.optDouble("ef", 2.5), o.optInt("interval"), o.optInt("reps"), o.optLong("due"), o.optInt("lapses"))
    }
}

data class PathStep(val title: String, val goal: String, var lessonId: String? = null)

class LearningPath(
    val id: String = UUID.randomUUID().toString(),
    var topic: String,
    var title: String,
    var difficulty: Difficulty,
    val steps: MutableList<PathStep>,
    var createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("topic", topic).put("title", title).put("difficulty", difficulty.name)
        .put("createdAt", createdAt)
        .put("steps", JSONArray().apply { steps.forEach { put(JSONObject().put("title", it.title).put("goal", it.goal).put("lessonId", it.lessonId ?: JSONObject.NULL)) } })
    companion object {
        fun fromJson(o: JSONObject) = LearningPath(o.getString("id"), o.optString("topic"), o.optString("title"),
            enumOr(o.optString("difficulty"), Difficulty.BEGINNER),
            o.optJSONArray("steps").objs().map { PathStep(it.optString("title"), it.optString("goal"), it.optStringOrNull("lessonId")) }.toMutableList(),
            o.optLong("createdAt"))
    }
}

// ---- small org.json helpers
internal fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
internal fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
internal inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E = runCatching { enumValueOf<E>(name!!) }.getOrDefault(def)
