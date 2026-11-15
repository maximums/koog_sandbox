package io.github.maximums.decision

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import io.github.maximums.web.ApiError
import io.github.maximums.web.errorDetails
import io.github.maximums.web.postJson
import io.github.maximums.web.retryAfter
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.reflect.safeCast

/**
 * A [DecisionModel] over the System One API (`POST /v1/systemone`). TypeSafe's hosted models and a local
 * Ollama serving a decision model speak the same API, so [kev] and [jev] differ only in URL, model and key.
 *
 * The [http] client needs `ContentNegotiation` with JSON that ignores unknown keys. Ollama takes 1 to 64
 * questions per request. This class never retries; call it through `withRetry` for that.
 */
class SystemOneClient(
    baseUrl: String,
    private val http: HttpClient,
    private val model: String,
    private val apiKey: String? = null,
) : DecisionModel {

    private val url = "${baseUrl.trimEnd('/')}/v1/systemone"
    private val service = "Decision model '$model'"

    init {
        require(baseUrl.isNotBlank()) { "The decision model's base URL is missing" }
        require(model.isNotBlank()) { "The decision model's name is missing" }
        require(apiKey == null || apiKey.isNotBlank()) { "The decision model's API key is blank" }
    }

    override suspend fun <A : Answer> ask(state: JsonElement, questions: Map<String, Question<A>>): Either<ApiError, Map<String, A>> {
        if (questions.isEmpty()) return emptyMap<String, A>().right()

        val request = SystemOneRequest(model, state, questions.mapValues { (_, question) -> question.toDto() })

        return either {
            val response = postJson<SystemOneRequest, SystemOneResponse>(http, url, apiKey, request, service) {
                it.toApiError()
            }
            questions.mapValues { (name, question) -> read(name, question, response.answers[name]) }
        }
    }

    private suspend fun HttpResponse.toApiError(): ApiError {
        val details = errorDetails()

        return when (status.value) {
            // 404: Ollama doesn't have the model. The others: TypeSafe or Ollama rejected the request itself.
            400, 404, 413, 415, 422 -> ApiError.InvalidRequest("$service rejected the request $details")
            401 -> ApiError.Unauthorized("$service rejected the API key $details")
            429 -> ApiError.RateLimited("$service rate limit reached $details", retryAfter = retryAfter())
            // Includes TypeSafe's 529 Overloaded.
            in 500..599 -> ApiError.Unavailable("$service had a server error $details")
            else -> ApiError.Unexpected("$service request failed $details")
        }
    }

    companion object {
        const val OLLAMA_URL = "http://localhost:11434"
        const val KEV_MODEL = "hf.co/ggml-org/Kev-4B-GGUF:Q4_K_M"
        const val TYPESAFE_URL = "https://api.typesafe.ai"
        const val JEV_MODEL = "jev-latest"

        fun kev(http: HttpClient, baseUrl: String = OLLAMA_URL, model: String = KEV_MODEL) =
            SystemOneClient(baseUrl, http, model)

        fun jev(http: HttpClient, apiKey: String, model: String = JEV_MODEL) =
            SystemOneClient(TYPESAFE_URL, http, model, apiKey)
    }
}

private fun Question<*>.toDto(): QuestionDto = when (this) {
    is Noul -> NoulDto(instructions, NoulCriteria(yesMeans, noMeans).takeIf { yesMeans != null || noMeans != null })
    is Choice -> ChoiceDto(instructions, options)
    is Score -> ScoreDto(instructions, levels)
}

private fun <A : Answer> Raise<ApiError>.read(name: String, question: Question<A>, answer: AnswerDto?): A {
    ensureNotNull(answer) { ApiError.Unexpected("The decision model gave no answer to '$name'") }

    val read: Answer? = when (question) {
        is Noul -> (answer as? NoulAnswerDto)?.let { NoulAnswer(it.noul) }
        is Choice -> (answer as? ChoiceAnswerDto)?.let { ChoiceAnswer(it.choice, it.probabilities, it.confidence) }
        is Score -> (answer as? ScoreAnswerDto)?.let { score ->
            ScoreAnswer(score.score, question.levels.indices.map { score.probabilities[it] ?: 0.0 }, score.confidence)
        }
    }

    return ensureNotNull(question.answerType.safeCast(read)) {
        ApiError.Unexpected("The decision model answered '$name' as a different question type")
    }
}

// The API's JSON. Questions and answers carry their type in a "type" field, which kotlinx.serialization
// reads and writes from the subclasses' @SerialName.

@Serializable
private data class SystemOneRequest(val model: String, val state: JsonElement, val questions: Map<String, QuestionDto>)

@Serializable
private sealed interface QuestionDto

/** [criteria] is left out of the JSON when the question has none, and so is a meaning it doesn't give. */
@Serializable
@SerialName("noul")
private data class NoulDto(
    val instructions: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val criteria: NoulCriteria? = null,
) : QuestionDto

@Serializable
private data class NoulCriteria(
    @SerialName("true") @EncodeDefault(EncodeDefault.Mode.NEVER) val yesMeans: String? = null,
    @SerialName("false") @EncodeDefault(EncodeDefault.Mode.NEVER) val noMeans: String? = null,
)

@Serializable
@SerialName("choice")
private data class ChoiceDto(val instructions: String, val criteria: Map<String, String?>) : QuestionDto

@Serializable
@SerialName("score")
private data class ScoreDto(val instructions: String, val criteria: List<String>) : QuestionDto

@Serializable
private data class SystemOneResponse(val answers: Map<String, AnswerDto>)

@Serializable
private sealed interface AnswerDto

@Serializable
@SerialName("noul")
private data class NoulAnswerDto(val noul: Double) : AnswerDto

@Serializable
@SerialName("choice")
private data class ChoiceAnswerDto(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) : AnswerDto

/** [probabilities] is keyed by level index, which the JSON writes as a string ("0", "1", ...). */
@Serializable
@SerialName("score")
private data class ScoreAnswerDto(val score: Double, val probabilities: Map<Int, Double>, val confidence: Double) : AnswerDto
