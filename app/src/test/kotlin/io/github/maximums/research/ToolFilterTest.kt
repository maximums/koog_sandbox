package io.github.maximums.research

import ai.koog.agents.core.tools.ToolDescriptor
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.decision.Answer
import io.github.maximums.decision.Choice
import io.github.maximums.decision.ChoiceAnswer
import io.github.maximums.decision.DecisionModel
import io.github.maximums.decision.Question
import io.github.maximums.web.ApiError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.cast
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TASK = "Who builds Koog?"

private val search = ToolDescriptor("webSearch", "Searches the web.")
private val read = ToolDescriptor("readPages", "Reads web pages.")
private val calculator = ToolDescriptor("calculate", "Evaluates arithmetic.")
private val tools = listOf(search, read, calculator)

/** Answers every question with [reply] (when it's a [ChoiceAnswer]), or fails with it. Records what it's asked. */
private class FakeDecisions(private val reply: Either<ApiError, ChoiceAnswer>) : DecisionModel {
    val asked = mutableListOf<Pair<JsonElement, Map<String, Question<*>>>>()

    override suspend fun <A : Answer> ask(state: JsonElement, questions: Map<String, Question<A>>): Either<ApiError, Map<String, A>> {
        asked += state to questions
        return reply.map { answer -> questions.mapValues { (_, question) -> question.answerType.cast(answer) } }
    }
}

private fun picks(vararg probabilities: Pair<String, Double>) =
    FakeDecisions(ChoiceAnswer(probabilities.maxBy { it.second }.first, probabilities.toMap(), confidence = 0.5).right())

val ToolFilterTest by testSuite {

    test("asks one Choice about the task, with each tool's description as its option") {
        val decisions = picks("webSearch" to 1.0)
        ToolFilter(decisions).select(TASK, tools)

        val (state, questions) = decisions.asked.single()
        assertEquals(JsonPrimitive(TASK), state)
        val choice = questions.values.single() as Choice
        assertEquals(mapOf("webSearch" to "Searches the web.", "readPages" to "Reads web pages.", "calculate" to "Evaluates arithmetic."), choice.options)
    }

    test("keeps only the top tool when the decision model is sure of it") {
        val decisions = picks("webSearch" to 0.95, "readPages" to 0.04, "calculate" to 0.01)

        assertEquals(listOf(search), ToolFilter(decisions).select(TASK, tools))
    }

    test("keeps the most likely tools until they reach the coverage, in their original order") {
        val decisions = picks("readPages" to 0.6, "webSearch" to 0.35, "calculate" to 0.05)

        assertEquals(listOf(search, read), ToolFilter(decisions, coverage = 0.9).select(TASK, tools))
    }

    test("keeps every tool when the decision model fails") {
        val decisions = FakeDecisions(ApiError.Unavailable("Kev is down").left())

        assertEquals(tools, ToolFilter(decisions).select(TASK, tools))
    }

    test("keeps every tool when the decision model picks none of them") {
        val decisions = picks("translate" to 1.0)

        assertEquals(tools, ToolFilter(decisions).select(TASK, tools))
    }

    test("doesn't ask when there's nothing to choose from") {
        val decisions = picks("webSearch" to 1.0)

        assertEquals(listOf(search), ToolFilter(decisions).select(TASK, listOf(search)))
        assertEquals(emptyList(), ToolFilter(decisions).select(TASK, emptyList()))
        assertTrue(decisions.asked.isEmpty())
    }

    test("keeps every tool, without asking, when there are more than a Choice holds") {
        val decisions = picks("tool 0" to 1.0)
        val many = (0..255).map { ToolDescriptor("tool $it", "Tool number $it.") }

        assertEquals(many, ToolFilter(decisions).select(TASK, many))
        assertTrue(decisions.asked.isEmpty())
    }

    test("rejects a coverage outside (0, 1] up front") {
        val decisions = picks("webSearch" to 1.0)
        assertFailsWith<IllegalArgumentException> { ToolFilter(decisions, coverage = 0.0) }
        assertFailsWith<IllegalArgumentException> { ToolFilter(decisions, coverage = 1.1) }
    }
}
