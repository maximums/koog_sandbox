package io.github.maximums.research

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val CitationsTest by testSuite {
    val sources = listOf(Source(1, "https://a.com", "A"), Source(2, "https://b.com", "B"))
    val limits = ResearchLimits()

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

    test("asks for a rewrite only while rewrites remain") {
        val bad = ResearchState(question = "q", sources = sources, draft = "Made up [7].", reportAttempts = 1)
        assertTrue(bad.needsRewrite(limits))
        assertFalse(bad.copy(reportAttempts = limits.maxReportRewrites + 1).needsRewrite(limits))
    }

    test("never asks for a rewrite when there are no sources") {
        assertFalse(ResearchState(question = "q", draft = "Nothing found.", reportAttempts = 1).needsRewrite(limits))
    }

    test("report drops unknown citations and lists only cited sources") {
        val state = ResearchState(question = "q", sources = sources, draft = "A [2]. B [2, 9]. C [9].")
        val report = state.toReport()
        assertEquals("A [2]. B [2]. C .", report.markdown)
        assertEquals(listOf(sources[1]), report.sources)
    }
}
