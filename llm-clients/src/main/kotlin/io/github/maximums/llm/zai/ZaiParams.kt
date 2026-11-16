package io.github.maximums.llm.zai

import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonElement

internal fun LLMParams.toZaiParams(): ZaiParams {
    if (this is ZaiParams) return this
    return ZaiParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
    )
}

/**
 * How hard GLM thinks: Z.ai's `reasoning_effort`, `max` by default. GLM-5.3 and GLM-5.3-Flash take only [LOW],
 * [HIGH] and [MAX].
 */
enum class ZaiReasoningEffort(val value: String) {
    MAX("max"),
    XHIGH("xhigh"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    MINIMAL("minimal"),
    NONE("none"),
}

/**
 * Z.ai chat-completions parameters layered on top of [LLMParams]
 * (https://docs.z.ai/api-reference/llm/chat-completion). Z.ai's defaults apply to anything left null.
 *
 * @property temperature Sampling temperature in [0.0, 1.0], 1 by default.
 * @property maxTokens Maximum number of tokens to generate, at most 131072.
 * @property numberOfChoices not used for Z.ai
 * @property speculation not used for Z.ai
 * @property schema not supported by Z.ai, which has no JSON schemas: requests with one are rejected
 * @property toolChoice not used for Z.ai: it only has `auto`
 * @property user The end user's ID, sent as `user_id`: 6 to 128 characters.
 * @property additionalProperties Extra request fields, sent at the request's top level.
 * @property topP Nucleus sampling in [0.01, 1.0], 0.95 by default.
 * @property doSample Whether to sample; `false` makes [temperature] and [topP] have no effect.
 * @property stop Stop words; Z.ai supports only one.
 * @property thinking Whether the model thinks. GLM-5.3 and GLM-5.3-Flash always do and reject `false`.
 * @property clearThinking Whether Z.ai drops earlier turns' reasoning: `true` by default on the standard endpoint,
 *   `false` keeps it (preserved thinking, https://docs.z.ai/guides/capabilities/thinking-mode).
 * @property reasoningEffort How hard the model thinks.
 */
@Suppress("LongParameterList")
class ZaiParams(
    temperature: Double? = null,
    maxTokens: Int? = null,
    numberOfChoices: Int? = null,
    speculation: String? = null,
    schema: Schema? = null,
    toolChoice: ToolChoice? = null,
    user: String? = null,
    additionalProperties: Map<String, JsonElement>? = null,
    val topP: Double? = null,
    val doSample: Boolean? = null,
    val stop: List<String>? = null,
    val thinking: Boolean? = null,
    val clearThinking: Boolean? = null,
    val reasoningEffort: ZaiReasoningEffort? = null,
) : LLMParams(
    temperature,
    maxTokens,
    numberOfChoices,
    speculation,
    schema,
    toolChoice,
    user,
    additionalProperties,
) {
    init {
        require(temperature == null || temperature in 0.0..1.0) {
            "temperature must be in [0.0, 1.0], but was $temperature"
        }
        require(maxTokens == null || maxTokens in 1..131_072) {
            "maxTokens must be in [1, 131072], but was $maxTokens"
        }
        require(user == null || user.length in 6..128) {
            "user must be 6 to 128 characters, but was ${user?.length}"
        }
        require(topP == null || topP in 0.01..1.0) {
            "topP must be in [0.01, 1.0], but was $topP"
        }
        require(stop == null || stop.size == 1) {
            "stop supports only one word, but had ${stop?.size}"
        }
    }

    override fun copy(
        temperature: Double?,
        maxTokens: Int?,
        numberOfChoices: Int?,
        speculation: String?,
        schema: Schema?,
        toolChoice: ToolChoice?,
        user: String?,
        additionalProperties: Map<String, JsonElement>?,
    ): ZaiParams = copy(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        topP = topP,
        doSample = doSample,
        stop = stop,
        thinking = thinking,
        clearThinking = clearThinking,
        reasoningEffort = reasoningEffort,
    )

    /**
     * Creates a copy of this instance with the ability to modify any of its properties.
     */
    fun copy(
        temperature: Double? = this.temperature,
        maxTokens: Int? = this.maxTokens,
        numberOfChoices: Int? = this.numberOfChoices,
        speculation: String? = this.speculation,
        schema: Schema? = this.schema,
        toolChoice: ToolChoice? = this.toolChoice,
        user: String? = this.user,
        additionalProperties: Map<String, JsonElement>? = this.additionalProperties,
        topP: Double? = this.topP,
        doSample: Boolean? = this.doSample,
        stop: List<String>? = this.stop,
        thinking: Boolean? = this.thinking,
        clearThinking: Boolean? = this.clearThinking,
        reasoningEffort: ZaiReasoningEffort? = this.reasoningEffort,
    ): ZaiParams = ZaiParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        topP = topP,
        doSample = doSample,
        stop = stop,
        thinking = thinking,
        clearThinking = clearThinking,
        reasoningEffort = reasoningEffort,
    )

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is ZaiParams -> false
        else ->
            temperature == other.temperature &&
                maxTokens == other.maxTokens &&
                numberOfChoices == other.numberOfChoices &&
                speculation == other.speculation &&
                schema == other.schema &&
                toolChoice == other.toolChoice &&
                user == other.user &&
                additionalProperties == other.additionalProperties &&
                topP == other.topP &&
                doSample == other.doSample &&
                stop == other.stop &&
                thinking == other.thinking &&
                clearThinking == other.clearThinking &&
                reasoningEffort == other.reasoningEffort
    }

    override fun hashCode(): Int = listOf(
        temperature, maxTokens, numberOfChoices,
        speculation, schema, toolChoice, user,
        additionalProperties, topP, doSample,
        stop, thinking, clearThinking, reasoningEffort,
    ).fold(0) { acc, element ->
        31 * acc + (element?.hashCode() ?: 0)
    }

    override fun toString(): String = buildString {
        append("ZaiParams(")
        append("temperature=$temperature")
        append(", maxTokens=$maxTokens")
        append(", numberOfChoices=$numberOfChoices")
        append(", speculation=$speculation")
        append(", schema=$schema")
        append(", toolChoice=$toolChoice")
        append(", user=$user")
        append(", additionalProperties=$additionalProperties")
        append(", topP=$topP")
        append(", doSample=$doSample")
        append(", stop=$stop")
        append(", thinking=$thinking")
        append(", clearThinking=$clearThinking")
        append(", reasoningEffort=$reasoningEffort")
        append(")")
    }
}
