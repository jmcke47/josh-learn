package com.josh.learn

/**
 * Picks the right strategy per provider: one compact JSON request for cloud models, or several short
 * section-by-section requests with line-based parsing for small on-device models.
 * Progress messages are sent through onDelta prefixed with AiClient.STATUS.
 */
object Gen {
    fun label(cfg: ProviderConfig) = if (cfg.provider == Provider.LOCAL) "Offline · ${LocalModel.byId(cfg.model)?.short ?: cfg.model}" else "${cfg.provider.short} · ${cfg.model}"

    private fun status(onDelta: (String) -> Unit, s: String) = onDelta(AiClient.STATUS + s)

    fun lesson(cfg: ProviderConfig, topic: String, d: Difficulty, len: LessonLength, source: String? = null, pathContext: String? = null,
               onDelta: (String) -> Unit, cancelled: () -> Boolean, depth: Depth = Depth.NORMAL): Prompts.ParsedLesson {
        val parsed = if (cfg.provider != Provider.LOCAL) {
            Prompts.parseLesson(AiClient.complete(cfg, Prompts.lesson(topic, d, len, source, pathContext, depth), onDelta, cancelled), topic, d, len)
        } else {
            val t = if (pathContext != null) "$topic (part of: ${pathContext.take(120)})" else topic
            status(onDelta, "Step 1 of 4: summary")
            val (title, overview) = Prompts.parseLocalOverview(AiClient.complete(cfg, Prompts.localOverview(t, d, source, depth), onDelta, cancelled),
                if (topic == "the provided text") "Your text" else topic)
            status(onDelta, "Step 2 of 4: key ideas")
            var concepts = Prompts.parseLocalConcepts(AiClient.complete(cfg, Prompts.localConcepts(title, overview, d, Prompts.localConceptCount(len), source, depth), onDelta, cancelled))
            if (concepts.isEmpty()) { // one retry: small models sometimes ignore the format
                status(onDelta, "Step 2 of 4: key ideas (retrying)")
                concepts = Prompts.parseLocalConcepts(AiClient.complete(cfg, Prompts.localConcepts(title, overview, d, Prompts.localConceptCount(len), null), onDelta, cancelled))
            }
            if (concepts.isEmpty()) throw AiException(AiException.Kind.PARSE, "The offline model didn't list key ideas in a usable way. Try again, or use the Better model.")
            val ideas = concepts.map { it.title }
            status(onDelta, "Step 3 of 4: key terms")
            val terms = runCatching { Prompts.parseLocalTerms(AiClient.complete(cfg, Prompts.localGlossary(title, ideas), onDelta, cancelled)) }
                .getOrElse { if (it is AiException && it.kind == AiException.Kind.CANCELLED) throw it else emptyList() }
            status(onDelta, "Step 4 of 4: flashcards")
            val cards = runCatching { Prompts.parseLocalCards(AiClient.complete(cfg, Prompts.localFlashcards(title, ideas, len.cards.toInt()), onDelta, cancelled)) }
                .getOrElse { if (it is AiException && it.kind == AiException.Kind.CANCELLED) throw it else emptyList() }
                .ifEmpty { concepts.map { "What is ${it.title}?" to it.explanation } }
            Prompts.ParsedLesson(Lesson(topic = topic, title = title, difficulty = d, length = len, overview = overview,
                concepts = concepts, glossary = terms, takeaways = emptyList()), cards)
        }
        parsed.lesson.depth = depth
        parsed.lesson.model = label(if (cfg.provider == Provider.LOCAL) cfg else cfg.copy(model = AiClient.lastModel.get() ?: cfg.model))
        return parsed
    }

    fun quiz(cfg: ProviderConfig, l: Lesson, onDelta: (String) -> Unit, cancelled: () -> Boolean): List<Prompts.Question> {
        if (cfg.provider != Provider.LOCAL) return Prompts.parseQuiz(AiClient.complete(cfg, Prompts.quiz(l), onDelta, cancelled))
        status(onDelta, "Writing questions on your phone…")
        val mcq = Prompts.parseLocalQuiz(AiClient.complete(cfg, Prompts.localQuiz(l), onDelta, cancelled))
        // Short-answer questions come from the lesson's flashcards (no extra model call).
        val shorts = Repo.cardsFor(l.id).shuffled().take(2).map { Prompts.Question.Short(it.front, it.back, "") }
        return mcq + shorts
    }

    fun path(cfg: ProviderConfig, topic: String, d: Difficulty, onDelta: (String) -> Unit, cancelled: () -> Boolean): LearningPath =
        if (cfg.provider != Provider.LOCAL) Prompts.parsePath(AiClient.complete(cfg, Prompts.path(topic, d), onDelta, cancelled), topic, d)
        else Prompts.parseLocalPath(AiClient.complete(cfg, Prompts.localPath(topic, d), onDelta, cancelled), topic, d)

    fun chat(cfg: ProviderConfig, l: Lesson, history: List<Msg>, depth: Depth = Depth.NORMAL) =
        if (cfg.provider == Provider.LOCAL) Prompts.localChat(l, history, depth) else Prompts.chat(l, history, depth = depth)

    class Quick(val title: String, val text: String, val questions: List<Prompts.Question.Mcq>)

    /** Quick 2-minute mode: a ~150-word lesson, then 3 multiple-choice questions (two short requests on every provider). */
    fun quick(cfg: ProviderConfig, topic: String, depth: Depth, onDelta: (String) -> Unit, cancelled: () -> Boolean): Quick {
        val local = cfg.provider == Provider.LOCAL
        status(onDelta, "Step 1 of 2: your 2-minute lesson")
        val (title, text) = Prompts.parseLocalOverview(AiClient.complete(cfg, Prompts.quickLesson(topic, depth, local), onDelta, cancelled), topic)
        status(onDelta, "Step 2 of 2: 3 quick questions")
        val tmp = Lesson(topic = topic, title = title, difficulty = Difficulty.BEGINNER, length = LessonLength.SHORT, overview = text,
            concepts = emptyList(), glossary = emptyList(), takeaways = emptyList())
        val qs = mcqs(cfg, tmp, 3, onDelta, cancelled)
        return Quick(title, text, qs.take(3))
    }

    /** Multiple-choice questions only (quick mode and challenge mode). One retry if the model's format is unusable. */
    fun mcqs(cfg: ProviderConfig, l: Lesson, n: Int, onDelta: (String) -> Unit, cancelled: () -> Boolean): List<Prompts.Question.Mcq> {
        fun once() = (if (cfg.provider == Provider.LOCAL) Prompts.parseLocalQuiz(AiClient.complete(cfg, Prompts.localQuiz(l, n), onDelta, cancelled))
            else runCatching { Prompts.parseQuiz(AiClient.complete(cfg, Prompts.quiz(l, n, 0), onDelta, cancelled)) }
                .getOrElse { if (it is AiException && it.kind == AiException.Kind.PARSE) emptyList() else throw it }).filterIsInstance<Prompts.Question.Mcq>()
        val qs = once().ifEmpty { once() }
        if (qs.isEmpty()) throw AiException(AiException.Kind.PARSE, "The AI didn't write usable questions. Try again.")
        return qs
    }

    /** Questions for a 60-second challenge on [topic]: from the newest lesson on it if there is one, else from the topic itself. */
    fun challenge(cfg: ProviderConfig, topic: String, category: String?, depth: Depth, onDelta: (String) -> Unit, cancelled: () -> Boolean): List<Prompts.Question.Mcq> {
        val base = Repo.lessons.filter { (category != null && it.category == category) || it.topic.equals(topic, true) }.maxByOrNull { it.createdAt }
        val l = base ?: Lesson(topic = topic, title = topic, difficulty = Difficulty.INTERMEDIATE, length = LessonLength.SHORT,
            overview = "General knowledge quiz about $topic: key facts, ideas and examples." + Prompts.depthLine(depth).replace("\n", " "),
            concepts = emptyList(), glossary = emptyList(), takeaways = emptyList())
        status(onDelta, "Writing challenge questions…")
        val n = if (cfg.provider == Provider.LOCAL) 6 else 15
        return mcqs(cfg, l, n, onDelta, cancelled).shuffled()
    }
}
