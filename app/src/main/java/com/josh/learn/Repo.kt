package com.josh.learn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Whole library in one JSON file (small; written atomically off the main thread). Access from the main thread. */
object Repo {
    val lessons = mutableListOf<Lesson>()
    val cards = mutableListOf<Flashcard>()
    val paths = mutableListOf<LearningPath>()
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "library.json"); file = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.optJSONArray("lessons").objs().forEach { runCatching { lessons += Lesson.fromJson(it) } }
            o.optJSONArray("cards").objs().forEach { runCatching { cards += Flashcard.fromJson(it) } }
            o.optJSONArray("paths").objs().forEach { runCatching { paths += LearningPath.fromJson(it) } }
        }.onFailure { f.copyTo(File(ctx.filesDir, "library.corrupt.json"), overwrite = true) }
    }

    fun save() {
        val f = file ?: return
        val json = JSONObject()
            .put("version", 1)
            .put("lessons", JSONArray().apply { lessons.forEach { put(it.toJson()) } })
            .put("cards", JSONArray().apply { cards.forEach { put(it.toJson()) } })
            .put("paths", JSONArray().apply { paths.forEach { put(it.toJson()) } })
            .toString()
        writer.execute {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun lesson(id: String?) = lessons.firstOrNull { it.id == id }
    fun path(id: String?) = paths.firstOrNull { it.id == id }
    fun cardsFor(lessonId: String) = cards.filter { it.lessonId == lessonId }
    fun due(now: Long = System.currentTimeMillis()) = cards.filter { it.due <= now }.sortedBy { it.due }

    fun addLesson(l: Lesson, flashcards: List<Pair<String, String>>) {
        if (l.emoji == null) l.emoji = Symbols.forLesson(l) // every new lesson (quick, one-off, path, topic) gets a fitting symbol
        lessons.add(0, l)
        flashcards.forEach { (f, b) -> cards += Flashcard(lessonId = l.id, front = f, back = b) }
        save()
    }

    fun deleteLesson(l: Lesson) {
        lessons.remove(l); cards.removeAll { it.lessonId == l.id }
        paths.forEach { p -> p.steps.forEach { if (it.lessonId == l.id) it.lessonId = null } }
        save()
    }

    fun deletePath(p: LearningPath) { paths.remove(p); save() }

    /** Lessons matching [q] in title, topic, overview, concepts or glossary. */
    fun search(q: String): List<Lesson> {
        val t = q.trim().lowercase()
        val list = lessons.sortedByDescending { it.lastOpened }
        if (t.isEmpty()) return list
        return list.filter { l ->
            l.title.lowercase().contains(t) || l.topic.lowercase().contains(t) || l.overview.lowercase().contains(t) ||
                l.concepts.any { it.title.lowercase().contains(t) || it.explanation.lowercase().contains(t) } ||
                l.glossary.any { it.term.lowercase().contains(t) }
        }
    }
}
