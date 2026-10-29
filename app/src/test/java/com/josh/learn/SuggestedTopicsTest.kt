package com.josh.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(Symbols.fallback("High"), s[0].emoji) // no emoji in the file: a fitting or hashed symbol, never the old ⭐
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
}
