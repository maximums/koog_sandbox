package io.github.maximums.research

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val ResearchStateTest by testSuite {
    val limits = ResearchLimits()
    val inProgress = ResearchState(question = "q", round = 1, gaps = listOf(Gap("sq1", "missing")))

    test("continues while gaps remain within budget") {
        assertFalse(inProgress.isDone(limits))
    }

    test("is done when no gaps remain") {
        assertTrue(inProgress.copy(gaps = emptyList()).isDone(limits))
    }

    test("is done after the last round") {
        assertTrue(inProgress.copy(round = limits.maxRounds).isDone(limits))
    }

    test("is done when the total page budget is spent") {
        assertTrue(inProgress.copy(pagesFetched = limits.maxPagesTotal).isDone(limits))
    }

    test("page budget is the per-round limit while plenty remains") {
        assertEquals(limits.maxPagesPerRound, inProgress.pageBudget(limits))
    }

    test("page budget shrinks to what is left of the total") {
        assertEquals(3, inProgress.copy(pagesFetched = limits.maxPagesTotal - 3).pageBudget(limits))
    }
}
