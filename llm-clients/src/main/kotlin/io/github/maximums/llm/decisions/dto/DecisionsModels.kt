package io.github.maximums.llm.decisions.dto

import ai.koog.prompt.executor.clients.serialization.AdditionalPropertiesFlatteningSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Gemini Decisions API: https://aistudio.google.com/api-reference/decisions/create-decision
// Text input only: the API also takes image, audio, document and video blocks, which this client doesn't use.

/**
 * Request for the `v1beta/decisions` endpoint: [questions] about the shared [input], keyed by IDs the caller picks
 * (1 to 64 characters of `[A-Za-z0-9_-]`). The response's answers come back under the same IDs.
 * [additionalProperties] are flattened into the request's top level by [DecisionsRequestDTOSerializer].
 */
@Serializable
internal data class DecisionsRequestDTO(
    val model: String,
    val input: String,
    val questions: Map<String, DecisionsQuestionDTO>,
    val additionalProperties: Map<String, JsonElement>? = null,
)

internal object DecisionsRequestDTOSerializer :
    AdditionalPropertiesFlatteningSerializer<DecisionsRequestDTO>(DecisionsRequestDTO.serializer())

/**
 * A question, told apart by its `type`: [Noul], [Choice] or [Score].
 */
@Serializable
internal sealed interface DecisionsQuestionDTO {
    val instructions: String

    /**
     * Yes/no question. [criteria], when given, says what counts as each answer.
     */
    @Serializable
    @SerialName("noul")
    data class Noul(
        override val instructions: String,
        val criteria: Criteria? = null,
    ) : DecisionsQuestionDTO {
        /**
         * What a true and a false answer mean.
         */
        @Serializable
        data class Criteria(
            @SerialName("true_description") val trueDescription: String? = null,
            @SerialName("false_description") val falseDescription: String? = null,
        )
    }

    /**
     * Single choice: [criteria] maps each option's ID to its description, 2 to 255 options.
     */
    @Serializable
    @SerialName("choice")
    data class Choice(
        override val instructions: String,
        val criteria: Map<String, String>,
    ) : DecisionsQuestionDTO

    /**
     * Rating: [criteria] are the levels' descriptions, 2 to 255, level `i` at index `i`.
     */
    @Serializable
    @SerialName("score")
    data class Score(
        override val instructions: String,
        val criteria: List<String>,
    ) : DecisionsQuestionDTO
}

/**
 * Response from the `v1beta/decisions` endpoint.
 *
 * [answers] is kept as the API wrote it, keyed by question ID, each answer with its `type`; the client passes it
 * on as the reply's text. It's empty when [promptFeedback] says the input was blocked.
 */
@Serializable
internal data class DecisionsResponseDTO(
    val answers: JsonObject = JsonObject(emptyMap()),
    val usage: Usage? = null,
    @SerialName("prompt_feedback") val promptFeedback: PromptFeedback? = null,
) {
    /**
     * Token counts for the call; output is always 0, since decisions generate no text.
     */
    @Serializable
    internal data class Usage(
        @SerialName("total_input_tokens") val totalInputTokens: Int? = null,
        @SerialName("total_output_tokens") val totalOutputTokens: Int? = null,
        @SerialName("total_tokens") val totalTokens: Int? = null,
    )

    /**
     * Why the input was blocked, if it was: [blockReason] is `safety`, `other`, `blocklist`, `prohibited_content`
     * or `image_safety`.
     */
    @Serializable
    internal data class PromptFeedback(
        @SerialName("block_reason") val blockReason: String? = null,
        @SerialName("block_reason_message") val blockReasonMessage: String? = null,
    )
}

/**
 * Error response. Google documents two shapes and doesn't say which the Decisions API uses: the Interactions API's
 * `{"error":{"code":"invalid_request","message":"..."}}` and generateContent's
 * `{"error":{"code":400,"message":"...","status":"INVALID_ARGUMENT"}}` (https://aistudio.google.com/docs/api-errors).
 * So [Error.code] may be a string or a number, and [Error.status] may be missing.
 */
@Serializable
internal data class DecisionsErrorResponseDTO(val error: Error) {
    /**
     * The error's code, description and, in generateContent's shape, gRPC status.
     */
    @Serializable
    internal data class Error(
        val code: JsonPrimitive? = null,
        val message: String? = null,
        val status: String? = null,
    )
}
