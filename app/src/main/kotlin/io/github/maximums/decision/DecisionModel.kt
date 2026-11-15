package io.github.maximums.decision

import arrow.core.Either
import io.github.maximums.web.ApiError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass

/**
 * A question for a [DecisionModel] about a state. Each type has its own answer type [A], so the answers to
 * a batch of [Noul]s come back as [NoulAnswer]s without casting.
 */
sealed interface Question<out A : Answer> {
    /** What to evaluate, choose or rate. It can point into the state, e.g. `hits[0]` or `question`. */
    val instructions: String

    /** The class of this question's answers, for a [DecisionModel] to check an answer's type against. */
    val answerType: KClass<out A>
}

/**
 * A yes/no question. [yesMeans] and [noMeans], when given, say what counts as each answer; they make the
 * answers much sharper.
 */
data class Noul(
    override val instructions: String,
    val yesMeans: String? = null,
    val noMeans: String? = null,
) : Question<NoulAnswer> {
    override val answerType get() = NoulAnswer::class
}

/** Pick one of [options], which maps each option's name to when it applies (null when the name says it all). */
data class Choice(override val instructions: String, val options: Map<String, String?>) : Question<ChoiceAnswer> {
    init {
        require(options.size in 1..255) { "A Choice needs 1 to 255 options, got ${options.size}" }
    }

    override val answerType get() = ChoiceAnswer::class
}

/** Rate on [levels], ordered from lowest (level 0) to highest. */
data class Score(override val instructions: String, val levels: List<String>) : Question<ScoreAnswer> {
    init {
        require(levels.size in 1..10) { "A Score needs 1 to 10 levels, got ${levels.size}" }
    }

    override val answerType get() = ScoreAnswer::class
}

sealed interface Answer

/** [probability] that the answer is yes. Near 0.5 means the model can't tell, not "somewhat". */
data class NoulAnswer(val probability: Double) : Answer

/**
 * [choice] is the most likely option, and [probabilities] covers every option, summing to about 1.
 * [confidence] (0 to 1) is how concentrated the probabilities are, not the chance that [choice] is right.
 */
data class ChoiceAnswer(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) : Answer

/**
 * [score] is the probability-weighted average level, so it can fall between levels. [probabilities] has one
 * entry per level, by level index. [confidence] (0 to 1) is how concentrated they are around the most likely
 * level.
 */
data class ScoreAnswer(val score: Double, val probabilities: List<Double>, val confidence: Double) : Answer

/**
 * A "System One" model: it answers typed questions about a state with probabilities instead of writing text.
 * Examples are TypeSafe's hosted Jev, or Kev served by Ollama.
 */
interface DecisionModel {
    /**
     * Answers every question in [questions] about the same [state], each independently, in one call. The
     * answers come back under the questions' names. A map of one question type gives answers of that type;
     * mixed types give [Answer]s.
     */
    suspend fun <A : Answer> ask(state: JsonElement, questions: Map<String, Question<A>>): Either<ApiError, Map<String, A>>
}

/** Asks about plain text. */
suspend fun <A : Answer> DecisionModel.ask(state: String, questions: Map<String, Question<A>>): Either<ApiError, Map<String, A>> =
    ask(JsonPrimitive(state), questions)
