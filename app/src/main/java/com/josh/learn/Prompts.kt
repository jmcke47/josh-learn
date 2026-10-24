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

    // ============================================================= small on-device models
    // Short prompts, one section per request, simple line-based formats with tolerant parsing.

    const val LOCAL_SYSTEM = "You are a friendly, careful tutor. Use simple words and short sentences. Be accurate. If you are not sure, say so."

    private fun shortLevel(d: Difficulty) = when (d) { Difficulty.BEGINNER -> "a beginner"; Difficulty.INTERMEDIATE -> "a student who knows the basics"; Difficulty.EXPERT -> "an expert" }
    fun localConceptCount(len: LessonLength) = when (len) { LessonLength.SHORT -> 3; LessonLength.MEDIUM -> 4; LessonLength.LONG -> 5 }

    fun localOverview(topic: String, d: Difficulty, source: String?): List<Msg> = listOf(Msg("system", LOCAL_SYSTEM), Msg("user",
        (if (!source.isNullOrBlank()) "Read this text:\n\"\"\"\n${source.take(2500)}\n\"\"\"\n\nSummarise it for ${shortLevel(d)}." else "Explain \"${topic.take(200)}\" to ${shortLevel(d)}.") +
            "\nReply in exactly this format:\nTITLE: <short title>\nSUMMARY: <3 or 4 short sentences>"))

    fun localConcepts(title: String, overview: String, d: Difficulty, n: Int, source: String?): List<Msg> = listOf(Msg("system", LOCAL_SYSTEM), Msg("user",
        "Topic: $title\nSummary: ${overview.take(600)}\n" + (if (!source.isNullOrBlank()) "Source text: ${source.take(1500)}\n" else "") +
            "\nList $n key ideas of this topic for ${shortLevel(d)}. For each idea write exactly these 4 lines:\n" +
            "IDEA: <name>\nEXPLAIN: <2 short sentences>\nANALOGY: <compare it to something from everyday life>\nEXAMPLE: <one real example>"))

    fun localGlossary(title: String, ideas: List<String>): List<Msg> = listOf(Msg("system", LOCAL_SYSTEM), Msg("user",
        "Topic: $title\nKey ideas: ${ideas.joinToString(", ")}\n\nList 5 important terms for this topic. One per line, in this format:\nTERM: <word> - <short definition>"))

    fun localFlashcards(title: String, ideas: List<String>, n: Int): List<Msg> = listOf(Msg("system", LOCAL_SYSTEM), Msg("user",
        "Topic: $title\nKey ideas: ${ideas.joinToString(", ")}\n\nWrite $n flashcards to help remember this topic. For each write 2 lines:\nQ: <question>\nA: <short answer>"))

    fun localQuiz(l: Lesson, n: Int = 4): List<Msg> = listOf(Msg("system", LOCAL_SYSTEM), Msg("user",
        "Lesson: ${l.title}\n${l.overview.take(500)}\nKey ideas: ${l.concepts.joinToString("; ") { it.title + ": " + it.explanation.take(120) }}\n\n" +
            "Write $n multiple-choice questions about this lesson. For each use exactly this format:\n" +
            "Q: <question>\nA) <option>\nB) <option>\nC) <option>\nD) <option>\nANSWER: <letter>\nWHY: <one sentence>"))

    fun localPath(topic: String, d: Difficulty): List<Msg> = listOf(Msg("system", LOCAL_SYSTEM), Msg("user",
        "List 6 lesson titles that teach \"${topic.take(200)}\" step by step to ${shortLevel(d)}, from basics to advanced. One title per line, numbered 1 to 6. No other text."))

    fun localChat(l: Lesson, history: List<Msg>): List<Msg> {
        val sys = LOCAL_SYSTEM + " Answer in under 120 words.\nThe learner is studying: ${l.title}\n${l.overview.take(500)}\nKey ideas: ${l.concepts.joinToString(", ") { it.title }}"
        return listOf(Msg("system", sys)) + history.takeLast(4)
    }

    fun stripThinking(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*?</think>"), "").replace("<think>", "").trim()

    /** Removes markdown decoration and list numbering from a line. */
    fun cleanLine(line: String): String = line.trim()
        .replace("**", "").replace("__", "").replace(Regex("^#+\\s*"), "")
        .replace(Regex("^(?:[-*•]\\s+|\\d+[.)]\\s+)"), "").trim()

    private val labelRe = Regex("^([A-Za-z][A-Za-z0-9 ]{0,15})\\s*[:：]\\s*(.*)$")

    /** Splits "LABEL: value" (case-insensitive), returns (LABEL upper, value) or null. */
    fun label(line: String): Pair<String, String>? = labelRe.find(cleanLine(line))?.let { it.groupValues[1].trim().uppercase().replace(Regex("\\s*\\d+$"), "") to it.groupValues[2].trim() }

    fun parseLocalOverview(text: String, fallbackTitle: String): Pair<String, String> {
        var title = ""; val summary = StringBuilder()
        stripThinking(text).lines().forEach { raw ->
            val l = label(raw)
            when {
                l != null && l.first == "TITLE" -> title = l.second
                l != null && l.first == "SUMMARY" -> summary.append(l.second)
                cleanLine(raw).isNotEmpty() && title.isEmpty() && summary.isEmpty() && raw.length < 60 && !raw.trim().endsWith(".") -> title = cleanLine(raw)
                cleanLine(raw).isNotEmpty() -> { if (summary.isNotEmpty()) summary.append(' '); summary.append(cleanLine(raw)) }
            }
        }
        val s = summary.toString().trim()
        if (s.isEmpty()) throw AiException(AiException.Kind.PARSE, "The offline model gave an empty summary. Try again.")
        return title.trim().trim('"').ifEmpty { fallbackTitle.replaceFirstChar { it.uppercase() } }.take(80) to s
    }

    fun parseLocalConcepts(text: String): List<Concept> {
        data class B(var t: String = "", var e: String = "", var a: String = "", var x: String = "", var last: Char = 'e')
        val out = mutableListOf<B>()
        stripThinking(text).lines().forEach { raw ->
            val line = cleanLine(raw); if (line.isEmpty()) return@forEach
            val l = label(raw)
            when (l?.first) {
                "IDEA", "CONCEPT", "KEY IDEA", "NAME", "TITLE" -> out += B(t = l.second)
                "EXPLAIN", "EXPLANATION", "DESCRIPTION", "MEANING" -> (out.lastOrNull() ?: B().also { out += it }).apply { e = l.second; last = 'e' }
                "ANALOGY" -> out.lastOrNull()?.apply { a = l.second; last = 'a' }
                "EXAMPLE" -> out.lastOrNull()?.apply { x = l.second; last = 'x' }
                else -> {
                    val b = out.lastOrNull()
                    val bullet = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+").containsMatchIn(raw)
                    if (b == null || (b.e.isNotEmpty() && (b.x.isNotEmpty() || bullet || l != null))) {
                        // Unlabelled "Name: explanation" or plain bullet -> new idea
                        val i = line.indexOf(':')
                        out += if (i in 2..60) B(t = line.substring(0, i).trim(), e = line.substring(i + 1).trim()) else B(t = line.take(60), e = if (line.length > 60) line else "")
                    } else when (b.last) { 'a' -> b.a += " $line"; 'x' -> b.x += " $line"; else -> b.e = (b.e + " " + line).trim() }
                }
            }
        }
        return out.filter { it.t.isNotBlank() && (it.e.isNotBlank() || it.a.isNotBlank()) }
            .map { Concept(it.t.trim().trim('"'), it.e.trim(), it.a.trim(), it.x.trim()) }.distinctBy { it.title.lowercase() }
    }

    fun parseLocalTerms(text: String): List<Term> = stripThinking(text).lines().mapNotNull { raw ->
        var line = cleanLine(raw)
        line = line.replace(Regex("^(?i)term\\s*[:：]\\s*"), "")
        val m = Regex("^(.{1,50}?)\\s*(?: - | – | — |:|\\|)\\s*(?:(?i)definition\\s*[:：]\\s*)?(.+)$").find(line) ?: return@mapNotNull null
        val t = m.groupValues[1].trim().trim('"'); val d = m.groupValues[2].trim()
        if (t.isEmpty() || d.length < 3 || t.uppercase() in setOf("TOPIC", "KEY IDEAS")) null else Term(t, d)
    }.distinctBy { it.term.lowercase() }.take(8)

    fun parseLocalCards(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>(); var q: String? = null
        stripThinking(text).lines().forEach { raw ->
            val l = label(raw) ?: return@forEach
            when (l.first) {
                "Q", "QUESTION", "FRONT" -> q = l.second
                "A", "ANSWER", "BACK" -> { val qq = q; if (!qq.isNullOrBlank() && l.second.isNotBlank()) out += qq to l.second; q = null }
            }
        }
        return out
    }

    fun parseLocalQuiz(text: String): List<Question> {
        val out = mutableListOf<Question>()
        var q: String? = null; val opts = mutableListOf<String>(); var ans: String? = null; var why = ""
        fun flush() {
            val qq = q
            if (qq != null && opts.size >= 2 && ans != null) {
                val a = ans!!.trim()
                val idx = "ABCDEFGH".indexOf(a.uppercase().firstOrNull { it.isLetter() } ?: '?').takeIf { it in opts.indices && (a.length <= 3 || a.length > 1 && a[1] in ").: ") }
                    ?: opts.indexOfFirst { it.equals(a, true) }.takeIf { it >= 0 }
                    ?: "ABCDEFGH".indexOf(a.uppercase().first()).takeIf { it in opts.indices }
                if (idx != null) out += Question.Mcq(qq, opts.toList(), idx, why.trim())
            }
            q = null; opts.clear(); ans = null; why = ""
        }
        val optRe = Regex("^\\(?([A-Ha-h])[).:]\\s*(.+)$")
        stripThinking(text).lines().forEach { raw ->
            val line = cleanLine(raw); if (line.isEmpty()) return@forEach
            val opt = optRe.find(raw.trim().replace("**", ""))
            val l = label(raw)
            when {
                l != null && (l.first == "Q" || l.first == "QUESTION" || l.first.startsWith("QUESTION")) && opt == null -> { flush(); q = l.second }
                opt != null && q != null && ans == null -> opts += opt.groupValues[2].trim()
                l != null && (l.first == "ANSWER" || l.first == "CORRECT" || l.first == "CORRECT ANSWER") -> ans = l.second
                l != null && (l.first == "WHY" || l.first == "EXPLANATION" || l.first == "REASON") -> why = l.second
                q != null && opts.isEmpty() && ans == null -> q += " $line"
            }
        }
        flush()
        if (out.isEmpty()) throw AiException(AiException.Kind.PARSE, "The offline model didn't write usable questions. Try again.")
        return out
    }

    fun parseLocalPath(text: String, topic: String, d: Difficulty): LearningPath {
        val steps = stripThinking(text).lines().map { cleanLine(it).trim('"') }
            .filter { it.length in 3..90 && !it.endsWith(":") && !it.lowercase().startsWith("here") }
            .map { t -> val l = label(t); if (l != null && l.first.startsWith("LESSON")) l.second else t }
            .distinct().take(8).map { PathStep(it, "") }
        if (steps.size < 2) throw AiException(AiException.Kind.PARSE, "The offline model didn't produce a usable learning path. Try again.")
        return LearningPath(topic = topic, title = topic.replaceFirstChar { it.uppercase() }.take(60), difficulty = d, steps = steps.toMutableList())
    }
}
