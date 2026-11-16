package io.github.maximums.llm.zai.dto

import ai.koog.prompt.executor.clients.serialization.AdditionalPropertiesFlatteningSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Z.ai's Chat Completions API: https://docs.z.ai/api-reference/llm/chat-completion

/**
 * Message for the chat API, sent in requests and returned in responses.
 *
 * [content] is required for system, user and tool messages. An assistant's may be absent, and a response's is
 * null when the model calls a function.
 *
 * [reasoningContent] is the model's thinking (https://docs.z.ai/guides/capabilities/thinking). An assistant
 * message sends it back unchanged, as https://docs.z.ai/guides/capabilities/thinking-mode asks.
 *
 * [toolCalls] are an assistant's function calls; a tool message answers one of them by [toolCallId]
 * (https://docs.z.ai/guides/capabilities/function-calling).
 */
@Serializable
internal data class ZaiChatMessageDTO(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ZaiToolCallDTO>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/**
 * Tool call for the chat API.
 *
 * [type] is `function`; requests must send it, but replies may leave it out.
 */
@Serializable
internal data class ZaiToolCallDTO(
    val id: String,
    val function: Call,
    val type: String? = null,
) {
    /**
     * The called function and its arguments.
     *
     * [arguments] is a JSON string in requests and in the function-calling guide's replies, but the reply schema
     * types it as an object, so it's kept as either.
     */
    @Serializable
    internal data class Call(
        val name: String,
        val arguments: JsonElement,
    )
}

/**
 * Tool definition for the chat API.
 */
@Serializable
internal data class ZaiToolDTO(
    val type: String,
    val function: Definition,
) {
    /**
     * A function the model may call, with its parameters as a JSON schema.
     */
    @Serializable
    internal data class Definition(
        val name: String,
        val description: String,
        val parameters: JsonElement,
    )
}

/**
 * Request for the `/chat/completions` endpoint.
 *
 * Z.ai's defaults apply to anything left null; the ranges are checked in [io.github.maximums.llm.zai.ZaiParams].
 * `tool_choice` is never sent: Z.ai only accepts `auto`, its default. [additionalProperties] are flattened into
 * the request's top level by [ZaiChatRequestDTOSerializer].
 */
@Serializable
internal data class ZaiChatRequestDTO(
    val model: String,
    val messages: List<ZaiChatMessageDTO>,
    val tools: List<ZaiToolDTO>? = null,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("do_sample") val doSample: Boolean? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val thinking: Thinking? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val additionalProperties: Map<String, JsonElement>? = null,
) {
    /**
     * Thinking options: [type] is `enabled` or `disabled`.
     */
    @Serializable
    internal data class Thinking(
        val type: String? = null,
        @SerialName("clear_thinking") val clearThinking: Boolean? = null,
    )
}

internal object ZaiChatRequestDTOSerializer :
    AdditionalPropertiesFlatteningSerializer<ZaiChatRequestDTO>(ZaiChatRequestDTO.serializer())

/**
 * Response from the `/chat/completions` endpoint.
 *
 * Z.ai marks no field as required; only what a reply can't do without is non-null here.
 */
@Serializable
internal data class ZaiChatResponseDTO(
    val choices: List<Choice>,
    val id: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val usage: Usage? = null,
) {
    /**
     * One of the model's replies.
     *
     * [finishReason] is `stop`, `tool_calls`, `length`, `sensitive`, `model_context_window_exceeded` or
     * `network_error`.
     */
    @Serializable
    internal data class Choice(
        val message: ZaiChatMessageDTO,
        val index: Int? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    /**
     * Token counts for the call.
     */
    @Serializable
    internal data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int? = null,
        @SerialName("completion_tokens") val completionTokens: Int? = null,
        @SerialName("total_tokens") val totalTokens: Int? = null,
    )
}

/**
 * Streaming chunk from the `/chat/completions` endpoint, sent as SSE `data:` lines until `data: [DONE]`
 * (https://docs.z.ai/guides/capabilities/streaming). Only the last chunk has a finish reason and [usage].
 */
@Serializable
internal data class ZaiChatStreamResponseDTO(
    val choices: List<Choice> = emptyList(),
    val id: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val usage: ZaiChatResponseDTO.Usage? = null,
) {
    /**
     * The new piece of one of the model's replies.
     */
    @Serializable
    internal data class Choice(
        val delta: Delta = Delta(),
        val index: Int? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    /**
     * What the chunk adds to the reply.
     */
    @Serializable
    internal data class Delta(
        val role: String? = null,
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    )

    /**
     * A tool call, or with `tool_stream` a piece of one: its arguments then come in fragments across chunks,
     * matched by [index] (https://docs.z.ai/guides/tools/stream-tool).
     */
    @Serializable
    internal data class ToolCall(
        val index: Int? = null,
        val id: String? = null,
        val type: String? = null,
        val function: Function? = null,
    ) {
        /**
         * The called function's name and its arguments, or the next fragment of them.
         */
        @Serializable
        internal data class Function(
            val name: String? = null,
            val arguments: JsonElement? = null,
        )
    }
}

/**
 * Error response, e.g. `{"error":{"code":"1214","message":"..."}}`.
 *
 * [Error.code] is Z.ai's business code as a string (https://docs.z.ai/api-reference/api-code).
 */
@Serializable
internal data class ZaiErrorResponseDTO(val error: Error) {
    /**
     * The error's business code and description.
     */
    @Serializable
    internal data class Error(
        val code: String,
        val message: String,
    )
}
