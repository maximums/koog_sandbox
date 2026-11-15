package io.github.maximums.research

import ai.koog.agents.core.tools.ToolDescriptor
import io.github.maximums.decision.Choice
import io.github.maximums.decision.DecisionModel
import io.github.maximums.decision.ask
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Picks the tools that matter for a task, so the main model's prompt carries only those.
 *
 * It asks [decisions] a single [Choice] whose options are the tools (each tool's name, with its description
 * as when it applies), and gets back a probability for every tool. It keeps the most likely tools until their
 * probabilities add up to [coverage]: one tool when the decision model is sure, more when it isn't.
 *
 * It keeps every tool when there's nothing to choose, when the decision model fails or picks none of them, or
 * when there are more tools than a [Choice] can hold. Choosing to use no tools is left to the main model.
 */
class ToolFilter(
    private val decisions: DecisionModel,
    private val coverage: Double = 0.9,
) {

    init {
        require(coverage > 0.0 && coverage <= 1.0) { "coverage must be above 0 and at most 1, was $coverage" }
    }

    /** The [tools] relevant to [task], in their original order. */
    suspend fun select(task: String, tools: List<ToolDescriptor>): List<ToolDescriptor> {
        if (tools.size <= 1) return tools
        if (tools.size > MAX_OPTIONS) {
            logger.warn { "Not filtering ${tools.size} tools: a Choice holds at most $MAX_OPTIONS" }
            return tools
        }

        val question = Choice(INSTRUCTIONS, tools.associate { it.name to it.description })
        val answer = decisions.ask(task, mapOf("tool" to question)).fold(
            ifLeft = { error ->
                logger.warn { "Not filtering tools, the decision model failed: $error" }
                return tools
            },
            ifRight = { it.getValue("tool") },
        )

        val kept = mostLikely(answer.probabilities)
        logger.debug { "Tools kept for the task: $kept, from ${answer.probabilities}" }
        return tools.filter { it.name in kept }.ifEmpty { tools }
    }

    /** The most likely names, from the top, until their probabilities add up to [coverage]. */
    private fun mostLikely(probabilities: Map<String, Double>): Set<String> {
        val kept = mutableSetOf<String>()
        var total = 0.0
        for ((name, probability) in probabilities.entries.sortedByDescending { it.value }) {
            if (total >= coverage) break
            kept += name
            total += probability
        }
        return kept
    }

    private companion object {
        /** The most options a [Choice] takes. */
        const val MAX_OPTIONS = 255

        // The filtered tools last the whole run, so this asks about the task, not its next step.
        const val INSTRUCTIONS = "Which tool is most relevant to this task?"
    }
}
