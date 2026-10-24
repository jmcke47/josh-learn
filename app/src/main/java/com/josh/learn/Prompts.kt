package com.josh.learn

import org.json.JSONObject

/** Prompt builders + tolerant JSON parsers. Pure Kotlin (unit-tested). Prompts are kept compact for free tiers. */
object Prompts {
    const val SYSTEM = "You are a clear, friendly expert tutor. Explain accurately and simply, use concrete examples, " +
        "avoid fluff. When asked for JSON, reply with ONE valid JSON object only: no markdown fences, no commentary."

    private fun level(d: Difficulty) = when (d) {
        Difficulty.BEGINNER -> "a complete beginner (no jargon without explaining it, everyday analogies)"
        Difficulty.INTERMEDIATE -> "an intermediate learner who knows the basics"
        Difficulty.EXPERT -> "an advanced learner (precise terminology, nuances, edge cases, current debates)"
    }

    /** [source] is optional pasted/shared text the lesson must be based on. */
    fun lesson(topic: String, d: Difficulty, len: LessonLength, source: String? = null, pathContext: String? = null): List<Msg> {
        val src = source?.takeIf { it.isNotBlank() }?.let { "\nBase the lesson on this source material (summarise and teach it; fill gaps only if needed):\n\"\"\"\n${it.take(9000)}\n\"\"\"\n" } ?: ""
        val path = pathContext?.let { "\nThis lesson is one step of a learning path: $it\n" } ?: ""
        val user = """Create a lesson about: ${topic.take(300)}
Audience: ${level(d)}. Length: ${len.label.lowercase()} (${len.concepts} key concepts).$path$src
Return JSON with exactly these keys:
{"title":"short lesson title","overview":"3-5 sentence summary","concepts":[{"title":"","explanation":"2-4 short sentences","analogy":"one simple analogy","example":"one concrete example"}],"glossary":[{"term":"","definition":"one sentence"}],"takeaways":["3-5 one-line key takeaways"],"flashcards":[{"front":"question","back":"short answer"}]}
Give ${len.concepts} concepts, 4-8 glossary terms and ${len.cards} flashcards."""
        return listOf(Msg("system", SYSTEM), Msg("user", user))
    }

    fun path(topic: String, d: Difficulty): List<Msg> = listOf(Msg("system", SYSTEM), Msg("user",
        """Break the big topic "${topic.take(300)}" into a learning path for ${level(d)}: an ordered sequence of 5-8 lessons, each building on the previous.
Return JSON: {"title":"path title","steps":[{"title":"lesson title","goal":"one sentence: what the learner will understand"}]}"""))

    fun quiz(l: Lesson, mcq: Int = 5, short: Int = 2): List<Msg> = listOf(Msg("system", SYSTEM), Msg("user",
        """${l.contextText()}
Write a quiz on this lesson: $mcq multiple-choice and $short short-answer questions, testing understanding (not trivia), at ${l.difficulty.label.lowercase()} level.
Return JSON: {"questions":[{"type":"mcq","question":"","options":["","","",""],"answer":0,"explanation":"why the answer is right"},{"type":"short","question":"","answer":"model answer in 1-2 sentences","explanation":""}]}
"answer" for mcq is the 0-based index of the correct option. Vary the position of the correct option."""))

    /** Chat about a lesson. Only the most recent turns are sent, to stay within free-tier limits. */
    fun chat(l: Lesson, history: List<Msg>, maxTurns: Int = 8): List<Msg> {
        val sys = SYSTEM + "\nYou are tutoring the learner on this lesson. Answer in plain text (light markdown ok), concise (under ~200 words unless asked for more). " +
            "If asked to explain simpler, use an everyday analogy and short sentences. If asked to go deeper, add mechanisms, nuance and precise terms.\n\n" + l.contextText()
        return listOf(Msg("system", sys)) + history.takeLast(maxTurns)
    }

    // ------------------------------------------------------------- parsing

    /** Extracts the first JSON object from a model reply (handles ```json fences and stray text). */
    fun extractJson(text: String): JSONObject {
        var t = text.trim()
        t = t.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```\\s*$"), "")
        val start = t.indexOf('{'); val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException(AiException.Kind.PARSE, "The AI didn't return a lesson in the expected format. Try again.")
        return try { JSONObject(t.substring(start, end + 1)) } catch (e: org.json.JSONException) {
            throw AiException(AiException.Kind.PARSE, "The AI's reply was cut off or malformed. Try again (or a shorter length).", cause = e)
        }
    }

    data class ParsedLesson(val lesson: Lesson, val flashcards: List<Pair<String, String>>)

    fun parseLesson(text: String, topic: String, d: Difficulty, len: LessonLength): ParsedLesson {
        val o = extractJson(text)
        val concepts = o.optJSONArray("concepts").objs().map {
            Concept(it.optString("title").trim(), it.optString("explanation").trim(), it.optString("analogy").trim(), it.optString("example").trim())
        }.filter { it.title.isNotEmpty() || it.explanation.isNotEmpty() }
        val overview = o.optString("overview").trim()
        if (concepts.isEmpty() && overview.isEmpty()) throw AiException(AiException.Kind.PARSE, "The AI returned an empty lesson. Try again.")
        val l = Lesson(topic = topic, title = o.optString("title").trim().ifEmpty { topic.take(60) }, difficulty = d, length = len,
            overview = overview, concepts = concepts,
            glossary = o.optJSONArray("glossary").objs().map { Term(it.optString("term").trim(), it.optString("definition").trim()) }.filter { it.term.isNotEmpty() },
            takeaways = o.optJSONArray("takeaways").strings())
        val cards = o.optJSONArray("flashcards").objs().map { it.optString("front").trim() to it.optString("back").trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        return ParsedLesson(l, cards)
    }

    fun parsePath(text: String, topic: String, d: Difficulty): LearningPath {
        val o = extractJson(text)
        val steps = o.optJSONArray("steps").objs().map { PathStep(it.optString("title").trim(), it.optString("goal").trim()) }.filter { it.title.isNotEmpty() }
        if (steps.isEmpty()) throw AiException(AiException.Kind.PARSE, "The AI returned an empty learning path. Try again.")
        return LearningPath(topic = topic, title = o.optString("title").trim().ifEmpty { topic }, difficulty = d, steps = steps.toMutableList())
    }

    sealed class Question(val question: String, val explanation: String) {
        class Mcq(question: String, val options: List<String>, val answer: Int, explanation: String) : Question(question, explanation)
        class Short(question: String, val answer: String, explanation: String) : Question(question, explanation)
    }

    fun parseQuiz(text: String): List<Question> {
        val qs = extractJson(text).optJSONArray("questions").objs().mapNotNull { q ->
            val question = q.optString("question").trim()
            if (question.isEmpty()) return@mapNotNull null
            val opts = q.optJSONArray("options").strings()
            if (q.optString("type") == "mcq" || opts.size >= 2) {
                val ans = q.opt("answer")
                val idx = when (ans) {
                    is Number -> ans.toInt()
                    is String -> ans.trim().toIntOrNull() ?: opts.indexOfFirst { it.equals(ans.trim(), true) }
                        .takeIf { it >= 0 } ?: ("ABCDEFGH".indexOf(ans.trim().uppercase().take(1)))
                    else -> -1
                }
                if (opts.size < 2 || idx !in opts.indices) null else Question.Mcq(question, opts, idx, q.optString("explanation").trim())
            } else Question.Short(question, q.optString("answer").trim(), q.optString("explanation").trim())
        }
        if (qs.isEmpty()) throw AiException(AiException.Kind.PARSE, "The AI didn't return any usable questions. Try again.")
        return qs
    }
}
