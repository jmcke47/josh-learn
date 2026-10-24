package com.josh.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SuggestedTopicsTest {
    @Test fun parsesAndSortsByPriority() {
        val json = """{"topics":[
            {"title":"Low","category":"X","emoji":"🔧","priority":5,"subtopics":["a"]},
            {"title":"High","category":"Y","emoji":"","priority":90,"subtopics":["b"," ","c"]},
            {"category":"no title"}, 42,
            {"title":"high","priority":1}
        ]}"""
        val s = Topics.parseSuggested(json)
        assertEquals(listOf("High", "Low"), s.map { it.title })
        assertEquals("⭐", s[0].emoji)
        assertEquals(listOf("b", "c"), s[0].subtopics)
        assertTrue(s[0].asCategory.id.startsWith("s:high"))
    }

    @Test fun garbageNeverThrows() {
        assertTrue(Topics.parseSuggested("not json").isEmpty())
        assertTrue(Topics.parseSuggested("{}").isEmpty())
    }

    @Test fun emptySubtopicsFallBackToTitle() {
        val s = Topics.parseSuggested("""{"topics":[{"title":"Solo","priority":1}]}""")
        assertEquals(listOf("Solo"), s[0].asCategory.subtopics)
    }

    /** The bundled asset must be a copy of the project-root suggested_topics.json. */
    @Test fun bundledAssetMatchesSource() {
        val root = File(System.getProperty("project.root") ?: "..")
        val src = File(root, "suggested_topics.json"); val asset = File(root, "app/src/main/assets/suggested_topics.json")
        assertTrue("asset missing", asset.exists())
        if (src.exists()) assertEquals(src.readText(), asset.readText())
        val parsed = Topics.parseSuggested(asset.readText())
        assertTrue(parsed.size >= 5)
        assertEquals("4WD & Car Mechanics", parsed.first().title)
        assertTrue(parsed.all { it.subtopics.isNotEmpty() })
    }
}
