package com.josh.learn

import android.content.Intent

/** Shared "make a lesson / path" flows used by the home tiles and the New lesson screen. */
fun BaseActivity.createLesson(input: String, d: Difficulty, len: LessonLength, category: String? = null, finishAfter: Boolean = false,
                              depth: Depth = Depths.get(this, category ?: input)) {
    fun isSourceText(t: String) = t.length > 280 || t.count { it == '\n' } >= 3
    runAi("Creating your lesson…", task = { cfg, onDelta, cancelled ->
        var topic = input; var source: String? = null; var url: String? = null
        if (HtmlText.isMostlyUrl(input)) {
            url = HtmlText.findUrl(input)
            onDelta(AiClient.STATUS + "Reading the web page…")
            runCatching {
                val html = UpdateHttp.readText(url!!)
                source = HtmlText.extract(html).takeIf { it.length > 200 }
                topic = HtmlText.title(html) ?: url!!
            }
            if (source == null) topic = if (cfg.provider == Provider.LOCAL) url!! else "the web page at $url (explain the topic it is about)"
        } else if (isSourceText(input)) {
            source = input; topic = "the provided text"
        }
        val parsed = Gen.lesson(cfg, topic, d, len, source, null, onDelta, cancelled, depth)
        if (parsed.lesson.topic.isEmpty() || parsed.lesson.topic == "the provided text") parsed.lesson.topic = parsed.lesson.title
        parsed.lesson.sourceUrl = url
        parsed.lesson.category = category
        parsed
    }) { parsed ->
        Repo.addLesson(parsed.lesson, parsed.flashcards)
        startActivity(Intent(this, LessonActivity::class.java).putExtra("id", parsed.lesson.id))
        if (finishAfter) finish()
    }
}

fun BaseActivity.createPath(input: String, d: Difficulty, finishAfter: Boolean = false) {
    runAi("Planning your learning path…", task = { cfg, onDelta, cancelled -> Gen.path(cfg, input.take(200), d, onDelta, cancelled) }) { path ->
        Repo.paths.add(0, path); Repo.save()
        startActivity(Intent(this, PathActivity::class.java).putExtra("id", path.id))
        if (finishAfter) finish()
    }
}

fun BaseActivity.defaultDifficulty() = enumOr(LearnApp.prefs(this).getString("difficulty", null), Difficulty.BEGINNER)
fun BaseActivity.defaultLength() = enumOr(LearnApp.prefs(this).getString("length", null), LessonLength.MEDIUM)
