package com.josh.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sm2Test {
    @Test fun goodProgression() {
        var ef = 2.5; var iv = 0; var reps = 0; var lapses = 0
        val ivs = mutableListOf<Int>()
        repeat(4) {
            val r = Sm2.next(ef, iv, reps, lapses, Sm2.Grade.GOOD, 0)
            ef = r.ef; iv = r.intervalDays; reps = r.reps; lapses = r.lapses; ivs += iv
        }
        assertEquals(listOf(1, 6, 15, 38), ivs)
        assertEquals(2.5, ef, 1e-9)
    }

    @Test fun againResetsAndLowersEase() {
        val r = Sm2.next(2.5, 15, 3, 0, Sm2.Grade.AGAIN, 1000)
        assertEquals(0, r.reps); assertEquals(1, r.lapses)
        assertEquals(1000 + Sm2.AGAIN_DELAY, r.due)
        assertTrue(r.ef < 2.5)
    }

    @Test fun easeFloor() {
        var ef = 1.4
        repeat(10) { ef = Sm2.next(ef, 1, 2, 0, Sm2.Grade.HARD, 0).ef }
        assertEquals(1.3, ef, 1e-9)
    }

    @Test fun easyGrowsFasterThanHard() {
        val easy = Sm2.next(2.5, 6, 2, 0, Sm2.Grade.EASY, 0).intervalDays
        val hard = Sm2.next(2.5, 6, 2, 0, Sm2.Grade.HARD, 0).intervalDays
        assertTrue(easy > hard); assertTrue(hard > 6)
    }
}

class PromptsTest {
    private val lessonJson = """```json
{"title":"Photosynthesis","overview":"Plants make sugar from light.","concepts":[{"title":"Chlorophyll","explanation":"Absorbs light.","analogy":"Solar panel","example":"Green leaves"},{"title":"","explanation":""}],
 "glossary":[{"term":"ATP","definition":"Energy currency"}],"takeaways":["Light in, sugar out"],"flashcards":[{"front":"What absorbs light?","back":"Chlorophyll"},{"front":"","back":"x"}]}
```"""

    @Test fun parsesFencedLessonAndDropsEmpties() {
        val p = Prompts.parseLesson(lessonJson, "photosynthesis", Difficulty.BEGINNER, LessonLength.SHORT)
        assertEquals("Photosynthesis", p.lesson.title)
        assertEquals(1, p.lesson.concepts.size)
        assertEquals("Solar panel", p.lesson.concepts[0].analogy)
        assertEquals(1, p.lesson.glossary.size)
        assertEquals(listOf("What absorbs light?" to "Chlorophyll"), p.flashcards)
    }

    @Test fun parsesWithLeadingChatter() {
        val p = Prompts.parseLesson("Sure! Here you go:\n{\"title\":\"T\",\"overview\":\"O\",\"concepts\":[]}\nHope that helps", "t", Difficulty.EXPERT, LessonLength.LONG)
        assertEquals("O", p.lesson.overview)
    }

    @Test(expected = AiException::class) fun truncatedJsonIsParseError() {
        Prompts.parseLesson("{\"title\":\"T\",\"overview\":\"O\",\"concepts\":[{\"title\":\"a\"", "t", Difficulty.BEGINNER, LessonLength.SHORT)
    }

    @Test fun quizAnswerVariants() {
        val qs = Prompts.parseQuiz("""{"questions":[
            {"type":"mcq","question":"Q1","options":["a","b","c","d"],"answer":2,"explanation":"e"},
            {"type":"mcq","question":"Q2","options":["red","blue"],"answer":"blue"},
            {"type":"mcq","question":"Q3","options":["x","y","z"],"answer":"C"},
            {"type":"mcq","question":"Q4","options":["x","y"],"answer":7},
            {"type":"short","question":"Q5","answer":"Because.","explanation":""}]}""")
        assertEquals(4, qs.size)
        assertEquals(2, (qs[0] as Prompts.Question.Mcq).answer)
        assertEquals(1, (qs[1] as Prompts.Question.Mcq).answer)
        assertEquals(2, (qs[2] as Prompts.Question.Mcq).answer)
        assertEquals("Because.", (qs[3] as Prompts.Question.Short).answer)
    }

    @Test fun parsesPath() {
        val p = Prompts.parsePath("""{"title":"Learn Rust","steps":[{"title":"Ownership","goal":"g"},{"title":"Borrowing","goal":"g2"}]}""", "rust", Difficulty.INTERMEDIATE)
        assertEquals(2, p.steps.size); assertEquals("Learn Rust", p.title)
    }

    @Test fun chatKeepsOnlyRecentTurns() {
        val l = Prompts.parseLesson(lessonJson, "p", Difficulty.BEGINNER, LessonLength.SHORT).lesson
        val hist = (1..20).map { Msg(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val msgs = Prompts.chat(l, hist)
        assertEquals(9, msgs.size); assertEquals("system", msgs[0].role); assertEquals("m13", msgs[1].content)
        assertTrue(msgs[0].content.contains("Chlorophyll"))
    }

    @Test fun lessonPromptIncludesSourceAndLevel() {
        val m = Prompts.lesson("x", Difficulty.EXPERT, LessonLength.LONG, source = "SOURCE TEXT")
        assertTrue(m[1].content.contains("SOURCE TEXT")); assertTrue(m[1].content.contains("advanced"))
        assertTrue(m[1].content.contains("7-9"))
    }
}

class ModelsTest {
    @Test fun lessonRoundTripAndProgress() {
        val l = Lesson(topic = "t", title = "T", difficulty = Difficulty.EXPERT, length = LessonLength.LONG, overview = "o",
            concepts = listOf(Concept("a", "b", "c", "d"), Concept("e", "f", "g", "h")), glossary = listOf(Term("k", "v")),
            takeaways = listOf("x"), sourceUrl = "https://e.com", pathId = null)
        l.understood += 0; l.quizScores += QuizScore(3, 4, 5); l.chat += Msg("user", "hi")
        val r = Lesson.fromJson(org.json.JSONObject(l.toJson().toString()))
        assertEquals(l.id, r.id); assertEquals(Difficulty.EXPERT, r.difficulty); assertEquals(2, r.concepts.size)
        assertEquals("https://e.com", r.sourceUrl); assertNull(r.pathId)
        assertEquals(setOf(0), r.understood); assertEquals(1, r.chat.size)
        assertEquals(62, r.progressPercent) // 0.5*0.5 + 0.75*0.5
    }

    @Test fun cardAndPathRoundTrip() {
        val c = Flashcard(lessonId = "L", front = "f", back = "b", ef = 2.1, intervalDays = 6, reps = 2, due = 99, lapses = 1)
        val c2 = Flashcard.fromJson(org.json.JSONObject(c.toJson().toString()))
        assertEquals(2.1, c2.ef, 1e-9); assertEquals(6, c2.intervalDays); assertEquals(99, c2.due)
        val p = LearningPath(topic = "t", title = "T", difficulty = Difficulty.BEGINNER, steps = mutableListOf(PathStep("s", "g", "L1"), PathStep("s2", "g2")))
        val p2 = LearningPath.fromJson(org.json.JSONObject(p.toJson().toString()))
        assertEquals("L1", p2.steps[0].lessonId); assertNull(p2.steps[1].lessonId)
    }
}

class HtmlTextTest {
    @Test fun extractsReadableText() {
        val html = "<html><head><title>Black holes &amp; you</title><style>p{}</style></head><body><nav>Menu</nav>" +
            "<script>var x=1</script><h1>Black holes</h1><p>Gravity is so strong&nbsp;that light can&#39;t escape.</p><footer>(c)</footer></body></html>"
        assertEquals("Black holes & you", HtmlText.title(html))
        val t = HtmlText.extract(html)
        assertTrue(t.contains("Black holes")); assertTrue(t.contains("light can't escape"))
        assertFalse(t.contains("var x")); assertFalse(t.contains("Menu")); assertFalse(t.contains("p{}"))
    }

    @Test fun urlDetection() {
        assertEquals("https://en.wikipedia.org/wiki/Entropy", HtmlText.findUrl("Check this https://en.wikipedia.org/wiki/Entropy."))
        assertTrue(HtmlText.isMostlyUrl("Entropy - Wikipedia\nhttps://en.wikipedia.org/wiki/Entropy"))
        assertFalse(HtmlText.isMostlyUrl("quantum computing"))
        assertFalse(HtmlText.isMostlyUrl("A long article ".repeat(20) + "https://x.com/a"))
    }
}

