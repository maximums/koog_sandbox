package io.github.maximums.research

import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.research.model.Source
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val CitationsTest by testSuite {
    val sources = listOf(Source(1, "https://a.com", "A"), Source(2, "https://b.com", "B"))

    test("reads single and grouped citations") {
        assertEquals(setOf(1, 2, 3), citedSourceIds("One [1]. Two [2, 3]."))
    }

    test("citations are valid when every id is a known source") {
        assertTrue(hasValidCitations("Fact [1]. Other fact [1, 2].", sources))
    }

    test("citations are invalid when an id is unknown") {
        assertFalse(hasValidCitations("Fact [1]. Made up [7].", sources))
    }

    test("a report without citations is invalid") {
        assertFalse(hasValidCitations("No sources here.", sources))
    }
}
