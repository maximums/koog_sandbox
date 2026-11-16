package io.github.maximums.llm.decisions

import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonElement

/**
 * A question for the Gemini Decisions API about the prompt's input (https://aistudio.google.com/docs/decisions-api).
 * Write the whole question in [instructions]: the model never sees the question's ID.
 */
sealed interface DecisionQuestion {
    val instructions: String

    /**
     * Yes/no: the answer is the probability of yes. [trueDescription] and [falseDescription], when given, say what
     * counts as each answer.
     */
    data class Noul(
        override val instructions: String,
        val trueDescription: String? = null,
        val falseDescription: String? = null,
    ) : DecisionQuestion {
        init {
            require(instructions.isNotBlank()) { "instructions must not be blank" }
        }
    }

    /**
     * Pick one of [options], which maps each option's ID to its description: 2 to 255 options. The answer has a
     * probability per option, even when none fits.
     */
    data class Choice(
        override val instructions: String,
        val options: Map<String, String>,
    ) : DecisionQuestion {
        init {
            require(instructions.isNotBlank()) { "instructions must not be blank" }
            require(options.size in 2..255) { "a Choice needs 2 to 255 options, but had ${options.size}" }
            options.keys.forEach { id ->
                require(id.isNotEmpty() && id == id.trim() && id.none { it.isISOControl() }) {
                    "option IDs must be non-empty, with no control characters or surrounding whitespace: '$id'"
                }
                require(id.length <= 128 && id.encodeToByteArray().size <= 512) {
                    "option IDs must be at most 128 characters and 512 bytes: '$id'"
                }
            }
        }
    }

    /**
     * Rate on [levels], ordered from lowest (level 0) to highest: 2 to 255, 2 to 10 recommended. The answer is the
     * probability-weighted average level, so it can fall between levels.
     */
    data class Score(
        override val instructions: String,
        val levels: List<String>,
    ) : DecisionQuestion {
        init {
            require(instructions.isNotBlank()) { "instructions must not be blank" }
            require(levels.size in 2..255) { "a Score needs 2 to 255 levels, but had ${levels.size}" }
            require(levels.none { it.isEmpty() }) { "a Score's levels must not be empty" }
        }
    }
}

private val questionId = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * Gemini Decisions parameters layered on top of [LLMParams]: the [questions] to answer about the prompt's input
 * (https://aistudio.google.com/api-reference/decisions/create-decision).
 *
 * @property questions 1 to 1,000 questions, keyed by IDs of 1 to 64 characters of `[A-Za-z0-9_-]`. The answers
 *   come back under the same IDs.
 * @property temperature not used by the Decisions API, which generates no text
 * @property maxTokens not used by the Decisions API, which generates no text
 * @property numberOfChoices not used by the Decisions API
 * @property speculation not used by the Decisions API
 * @property schema not used by the Decisions API
 * @property toolChoice not used by the Decisions API, which takes no tools
 * @property user not used by the Decisions API
 * @property additionalProperties Extra request fields, sent at the request's top level.
 */
@Suppress("LongParameterList")
class DecisionParams(
    val questions: Map<String, DecisionQuestion>,
    temperature: Double? = null,
    maxTokens: Int? = null,
    numberOfChoices: Int? = null,
    speculation: String? = null,
    schema: Schema? = null,
    toolChoice: ToolChoice? = null,
    user: String? = null,
    additionalProperties: Map<String, JsonElement>? = null,
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
        require(questions.size in 1..1000) { "questions must have 1 to 1000 entries, but had ${questions.size}" }
        questions.keys.forEach { id ->
            require(questionId.matches(id)) { "question IDs must be 1 to 64 characters of [A-Za-z0-9_-]: '$id'" }
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
    ): DecisionParams = copy(
        questions = questions,
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
    )

    /**
     * Creates a copy of this instance with the ability to modify any of its properties.
     */
    fun copy(
        questions: Map<String, DecisionQuestion> = this.questions,
        temperature: Double? = this.temperature,
        maxTokens: Int? = this.maxTokens,
        numberOfChoices: Int? = this.numberOfChoices,
        speculation: String? = this.speculation,
        schema: Schema? = this.schema,
        toolChoice: ToolChoice? = this.toolChoice,
        user: String? = this.user,
        additionalProperties: Map<String, JsonElement>? = this.additionalProperties,
    ): DecisionParams = DecisionParams(
        questions = questions,
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
    )

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is DecisionParams -> false
        else ->
            questions == other.questions &&
                temperature == other.temperature &&
                maxTokens == other.maxTokens &&
                numberOfChoices == other.numberOfChoices &&
                speculation == other.speculation &&
                schema == other.schema &&
                toolChoice == other.toolChoice &&
                user == other.user &&
                additionalProperties == other.additionalProperties
    }

    override fun hashCode(): Int = listOf(
        questions, temperature, maxTokens, numberOfChoices,
        speculation, schema, toolChoice, user, additionalProperties,
    ).fold(0) { acc, element ->
        31 * acc + (element?.hashCode() ?: 0)
    }

    override fun toString(): String = buildString {
        append("DecisionParams(")
        append("questions=$questions")
        append(", temperature=$temperature")
        append(", maxTokens=$maxTokens")
        append(", numberOfChoices=$numberOfChoices")
        append(", speculation=$speculation")
        append(", schema=$schema")
        append(", toolChoice=$toolChoice")
        append(", user=$user")
        append(", additionalProperties=$additionalProperties")
        append(")")
    }
}
