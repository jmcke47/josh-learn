package com.josh.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers for small on-device model output, which is often messy. */
class LocalParsingTest {
    @Test fun overviewWithLabelsAndThinking() {
        val (t, s) = Prompts.parseLocalOverview("<think>\n\n</think>\n**TITLE:** Black Holes\nSUMMARY: A black hole is a place where gravity is very strong. Not even light escapes.", "black holes")
        assertEquals("Black Holes", t); assertTrue(s.startsWith("A black hole"))
    }

    @Test fun overviewWithoutLabelsFallsBack() {
        val (t, s) = Prompts.parseLocalOverview("Inflation means prices go up over time. Money buys less.", "inflation explained")
        assertEquals("Inflation explained", t); assertTrue(s.contains("prices"))
    }

    @Test fun conceptsLabelled() {
        val txt = """Here are the ideas:
1. **IDEA:** Event horizon
EXPLAIN: The boundary where escape is impossible.
It is not a solid surface.
ANALOGY: Like the edge of a waterfall.
EXAMPLE: Sagittarius A* has one.

IDEA 2: Singularity
EXPLANATION: The centre where density is extreme.
ANALOGY: Like squeezing a mountain into a pea.
EXAMPLE: Predicted by general relativity."""
        val c = Prompts.parseLocalConcepts(txt)
        assertEquals(2, c.size)
        assertEquals("Event horizon", c[0].title)
        assertTrue(c[0].explanation.contains("not a solid surface"))
        assertEquals("Like the edge of a waterfall.", c[0].analogy)
        assertEquals("Singularity", c[1].title)
        assertEquals("Predicted by general relativity.", c[1].example)
    }

    @Test fun conceptsUnlabelledBullets() {
        val c = Prompts.parseLocalConcepts("- Supply: how much of something is available.\n- Demand: how much people want it.\n- Price: where they meet.")
        assertEquals(listOf("Supply", "Demand", "Price"), c.map { it.title })
    }

    @Test fun terms() {
        val t = Prompts.parseLocalTerms("TERM: Photon - a particle of light\n2. **Gravity**: the force that pulls things together\nTERM: Mass — how much matter something has\nnonsense")
        assertEquals(listOf("Photon", "Gravity", "Mass"), t.map { it.term })
        assertEquals("a particle of light", t[0].definition)
    }

    @Test fun cards() {
        val c = Prompts.parseLocalCards("Q: What is a photon?\nA: A particle of light.\n\n**Q:** What bends light?\n**A:** Gravity.\nQ: orphan")
        assertEquals(listOf("What is a photon?" to "A particle of light.", "What bends light?" to "Gravity."), c)
    }

    @Test fun quiz() {
        val txt = """Q: What can escape a black hole?
A) Light
B) Nothing
C) Sound
D) Rockets
ANSWER: B
WHY: Gravity is too strong.

Q: Where is the event horizon?
A. At the centre
B. The boundary
ANSWER: B) The boundary

Q: Broken question with no options
ANSWER: A

Question 4: Which is bigger?
(A) Sun
(B) Earth
Correct answer: Sun"""
        val q = Prompts.parseLocalQuiz(txt).map { it as Prompts.Question.Mcq }
        assertEquals(3, q.size)
        assertEquals(1, q[0].answer); assertEquals("Gravity is too strong.", q[0].explanation)
        assertEquals(1, q[1].answer)
        assertEquals("Which is bigger?", q[2].question); assertEquals(0, q[2].answer)
    }

    @Test fun path() {
        val p = Prompts.parseLocalPath("Here is your path:\n1. What is money?\n2. Saving basics\n3) Budgeting\n4. Investing\n", "personal finance", Difficulty.BEGINNER)
        assertEquals(listOf("What is money?", "Saving basics", "Budgeting", "Investing"), p.steps.map { it.title })
    }

    @Test fun localChatIsShort() {
        val l = Lesson(topic = "t", title = "T", difficulty = Difficulty.BEGINNER, length = LessonLength.SHORT, overview = "o".repeat(2000),
            concepts = listOf(Concept("a", "b", "", "")), glossary = emptyList(), takeaways = emptyList())
        val m = Prompts.localChat(l, (1..10).map { Msg("user", "m$it") })
        assertEquals(5, m.size); assertTrue(m[0].content.length < 1000)
    }

    @Test fun stripThinking() {
        assertEquals("Hi", Prompts.stripThinking("<think>hmm</think>\nHi"))
        assertEquals("Hi", Prompts.stripThinking("reasoning...</think>Hi"))
    }

    @Test fun modelCatalogue() {
        assertTrue(LocalModel.entries.all { it.url.startsWith("https://huggingface.co/litert-community/") && it.sha256.length == 64 })
        assertEquals("614 MB", LocalModel.FAST.sizeText.replace("613", "614"))
        assertEquals("2.6 GB", LocalModel.BETTER.sizeText)
    }
}
