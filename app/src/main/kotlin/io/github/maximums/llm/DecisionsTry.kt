@file:JvmName("DecisionsTry")

package io.github.maximums.llm

import ai.koog.prompt.dsl.prompt
import io.github.maximums.llm.decisions.DecisionModels
import io.github.maximums.llm.decisions.DecisionParams
import io.github.maximums.llm.decisions.DecisionQuestion
import io.github.maximums.llm.decisions.decisionAnswers
import kotlinx.coroutines.runBlocking

private const val SAMPLE_TICKET = "I was charged twice for my subscription this month. Please refund the extra payment."

/**
 * Tries Gemini Decision 1 against the real API with the key in `GEMINI_API_KEY`: one question of each kind about a
 * support ticket. The program's arguments, if any, replace the sample ticket.
 */
fun main(args: Array<String>) = runBlocking {
    val apiKey = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("Set the GEMINI_API_KEY environment variable to your Gemini API key")
    val ticket = args.joinToString(" ").ifBlank { SAMPLE_TICKET }

    val params = DecisionParams(
        mapOf(
            "refund" to DecisionQuestion.Noul(
                "Does the customer ask for a refund?",
                trueDescription = "They want money back",
                falseDescription = "They want anything else",
            ),
            "team" to DecisionQuestion.Choice(
                "Which team should handle the ticket?",
                mapOf(
                    "billing" to "Payments, invoices and refunds",
                    "tech" to "Outages, bugs and errors",
                    "sales" to "Plans, pricing and upgrades",
                ),
            ),
            "urgency" to DecisionQuestion.Score(
                "How urgent is the ticket?",
                listOf("Not urgent", "Somewhat urgent", "Urgent"),
            ),
        )
    )

    simpleDecisionsExecutor(apiKey).use { executor ->
        val reply = executor.execute(prompt("ticket", params) { user(ticket) }, DecisionModels.GEMINI_DECISION_1)

        println("Ticket: $ticket")
        reply.finishReason?.let { println("Blocked: $it") }
        reply.decisionAnswers().forEach { (id, answer) -> println("$id: $answer") }
        println("Tokens: ${reply.metaInfo.totalTokensCount}")
    }
}
