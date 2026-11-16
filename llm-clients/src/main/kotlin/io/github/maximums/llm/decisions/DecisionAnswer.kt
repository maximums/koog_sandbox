package io.github.maximums.llm.decisions

import ai.koog.prompt.message.Message
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An answer from the Gemini Decisions API to a [DecisionQuestion] of the same kind
 * (https://aistudio.google.com/docs/decisions-api). Read them from a [DecisionsLLMClient] reply with
 * [decisionAnswers].
 */
@Serializable
sealed interface DecisionAnswer {
    /** Estimated probability (0 to 1) that the top answer is right. */
    val confidence: Double

    /**
     * Answer to a [DecisionQuestion.Noul]: the calibrated [probability] that the answer is yes, 0.01 to 0.99.
     * [confidence] is `max(probability, 1 - probability)`.
     */
    @Serializable
    @SerialName("noul")
    data class Noul(
        @SerialName("noul") val probability: Double,
        override val confidence: Double,
    ) : DecisionAnswer

    /**
     * Answer to a [DecisionQuestion.Choice]: the most likely [choice], and [probabilities] for every option, summing
     * to 1 even when none fits.
     */
    @Serializable
    @SerialName("choice")
    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
    ) : DecisionAnswer

    /**
     * Answer to a [DecisionQuestion.Score]: the probability-weighted average level, so it can fall between levels.
     * [probabilities] and [legend] (each level's description) are keyed by level index.
     */
    @Serializable
    @SerialName("score")
    data class Score(
        val score: Double,
        val probabilities: Map<Int, Double>,
        val legend: Map<Int, String>,
        override val confidence: Double,
    ) : DecisionAnswer
}

private val decisionAnswersJson = Json { ignoreUnknownKeys = true }

/**
 * The answers in a [DecisionsLLMClient] reply, keyed by question ID. Empty when the input was blocked; the reply's
 * finish reason then says why.
 */
fun Message.Assistant.decisionAnswers(): Map<String, DecisionAnswer> =
    decisionAnswersJson.decodeFromString(textContent())
