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
               onDelta: (String) -> Unit, cancelled: () -> Boolean): Prompts.ParsedLesson {
        val parsed = if (cfg.provider != Provider.LOCAL) {
            Prompts.parseLesson(AiClient.complete(cfg, Prompts.lesson(topic, d, len, source, pathContext), onDelta, cancelled), topic, d, len)
        } else {
            val t = if (pathContext != null) "$topic (part of: ${pathContext.take(120)})" else topic
            status(onDelta, "Step 1 of 4: summary")
            val (title, overview) = Prompts.parseLocalOverview(AiClient.complete(cfg, Prompts.localOverview(t, d, source), onDelta, cancelled),
                if (topic == "the provided text") "Your text" else topic)
            status(onDelta, "Step 2 of 4: key ideas")
            var concepts = Prompts.parseLocalConcepts(AiClient.complete(cfg, Prompts.localConcepts(title, overview, d, Prompts.localConceptCount(len), source), onDelta, cancelled))
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
        parsed.lesson.model = label(cfg)
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

    fun chat(cfg: ProviderConfig, l: Lesson, history: List<Msg>) =
        if (cfg.provider == Provider.LOCAL) Prompts.localChat(l, history) else Prompts.chat(l, history)
}
