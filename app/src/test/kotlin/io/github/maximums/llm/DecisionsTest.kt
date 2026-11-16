package io.github.maximums.llm

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.llm.decisions.DecisionAnswer
import io.github.maximums.llm.decisions.DecisionModels
import io.github.maximums.llm.decisions.DecisionParams
import io.github.maximums.llm.decisions.DecisionQuestion
import io.github.maximums.llm.decisions.decisionAnswers
import io.github.maximums.web.RecordingServer
import io.github.maximums.web.Reply
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A minimal Decisions reply, in the shape of the create-decision reference's examples. */
private const val REPLY = """{"answers": {"refund": {"type": "noul", "noul": 0.97, "confidence": 0.97}}}"""

val DecisionsTest by testSuite {

    test("asks Gemini Decision 1 the questions on the Decisions endpoint with the API key") {
        val server = RecordingServer(Reply(body = REPLY))
        val executor = simpleDecisionsExecutor("gemini-test", KtorKoogHttpClient.Factory(server.http))
        val params = DecisionParams(mapOf("refund" to DecisionQuestion.Noul("Does the customer ask for a refund?")))

        val reply = executor.execute(prompt("ticket", params) { user("Please refund me.") }, DecisionModels.GEMINI_DECISION_1)

        assertEquals(mapOf("refund" to DecisionAnswer.Noul(probability = 0.97, confidence = 0.97)), reply.decisionAnswers())
        val request = server.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", request.url.toString())
        assertEquals("gemini-test", request.headers["x-goog-api-key"])
    }

    test("rejects a blank API key up front") {
        assertFailsWith<IllegalArgumentException> { simpleDecisionsExecutor(" ") }
    }
}
