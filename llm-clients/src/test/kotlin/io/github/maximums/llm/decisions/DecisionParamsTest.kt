package io.github.maximums.llm.decisions

import ai.koog.prompt.params.LLMParams
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.llm.decisions.DecisionQuestion.Choice
import io.github.maximums.llm.decisions.DecisionQuestion.Noul
import io.github.maximums.llm.decisions.DecisionQuestion.Score
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val REFUND = Noul("Does the customer ask for a refund?")

val DecisionParamsTest by testSuite {

    test("rejects questions outside the Decisions API's limits") {
        assertFailsWith<IllegalArgumentException> { DecisionParams(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { DecisionParams(mapOf("not an id" to REFUND)) }
        assertFailsWith<IllegalArgumentException> { Choice("Which team?", mapOf("billing" to "Payments")) }
        assertFailsWith<IllegalArgumentException> {
            Choice("Which team?", mapOf(" billing" to "Payments", "tech" to "Outages"))
        }
        assertFailsWith<IllegalArgumentException> { Score("How clear is the reply?", listOf("Unclear")) }
        assertFailsWith<IllegalArgumentException> { Noul(" ") }
    }

    test("keeps its questions when Koog merges in default params") {
        val merged = DecisionParams(mapOf("refund" to REFUND)).default(LLMParams(temperature = 0.3))

        assertEquals(DecisionParams(mapOf("refund" to REFUND), temperature = 0.3), merged)
    }
}
