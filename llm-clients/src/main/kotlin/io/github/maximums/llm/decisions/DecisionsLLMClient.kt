package io.github.maximums.llm.decisions

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.post
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import io.github.maximums.llm.decisions.dto.DecisionsErrorResponseDTO
import io.github.maximums.llm.decisions.dto.DecisionsRequestDTO
import io.github.maximums.llm.decisions.dto.DecisionsRequestDTOSerializer
import io.github.maximums.llm.decisions.dto.DecisionsResponseDTO
import io.github.maximums.llm.decisions.dto.toDecisionsQuestions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Google's Gemini Decisions API as a Koog provider of its own. Koog's executors pick a client by provider, so
 * this lets [DecisionsLLMClient] sit next to Koog's Gemini client, whose provider is [LLMProvider.Google].
 */
object DecisionsLLMProvider : LLMProvider("google-decisions", "Google Decisions")

/**
 * A Koog [LLMClient] for Google's Gemini Decisions API (https://aistudio.google.com/api-reference/decisions). It
 * answers typed questions (yes/no, choice, score) about a shared input with calibrated probabilities instead of
 * text.
 *
 * The questions come in the prompt's [DecisionParams], its last user message's text is the shared input, and the
 * answers come back as JSON in the reply's text. There's no streaming: the API has none, so Koog's default
 * [executeStreaming] (not implemented) applies.
 *
 * @param httpClient A Koog HTTP client with the Gemini API's base URL and `x-goog-api-key` header already set. To
 *   build one from an API key, use the secondary constructor.
 * @param clock Clock for the responses' metadata timestamps.
 */
class DecisionsLLMClient(
    private val httpClient: KoogHttpClient,
    private val clock: KoogClock = KoogClock.System,
) : LLMClient() {

    companion object {
        /** The Gemini API. */
        const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com"

        private const val CLIENT_NAME = "DecisionsLLMClient"

        /** The Decisions endpoint, under `v1beta` while the API is in beta. */
        private const val DECISIONS_PATH = "v1beta/decisions"

        private val jsonContentHeaders = mapOf("Content-Type" to "application/json")

        private val decisionsJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    /**
     * Builds the underlying [KoogHttpClient] for the Gemini API, authenticated with [apiKey] (a Gemini API key).
     *
     * [httpClientFactory] defaults to the one Koog finds on the classpath, such as Ktor's.
     */
    constructor(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
        clock: KoogClock = KoogClock.System,
        httpClientFactory: KoogHttpClient.Factory = HttpClientFactoryResolver.resolve(),
    ) : this(
        httpClient = httpClientFactory.create(
            clientName = CLIENT_NAME,
            baseUrl = baseUrl,
            headers = mapOf("x-goog-api-key" to apiKey),
            queryParameters = emptyMap(),
            requestTimeoutMillis = timeoutConfig.requestTimeoutMillis,
            connectTimeoutMillis = timeoutConfig.connectTimeoutMillis,
            socketTimeoutMillis = timeoutConfig.socketTimeoutMillis,
            json = decisionsJson,
        ),
        clock = clock,
    )

    override fun llmProvider(): LLMProvider = DecisionsLLMProvider

    /**
     * Answers [prompt]'s [DecisionParams.questions] about the text of its last user message. Decision models don't
     * chat, so the rest of the prompt isn't sent, and [tools] are ignored: the API takes none.
     *
     * The reply's text is the answers' JSON as the API wrote it, keyed by question ID; when the input was blocked,
     * it's `{}` and the finish reason is the block reason (e.g. `safety`).
     */
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        require(model.provider == DecisionsLLMProvider) { "Model not supported by the Gemini Decisions API" }
        val params = requireNotNull(prompt.params as? DecisionParams) {
            "The Gemini Decisions API needs DecisionParams with the questions to answer"
        }
        val input = prompt.messages.filterIsInstance<Message.User>().lastOrNull()?.textContent()?.ifBlank { null }
        requireNotNull(input) { "The Gemini Decisions API needs a user message with text as its input" }

        val request = decisionsJson.encodeToString(
            DecisionsRequestDTOSerializer,
            DecisionsRequestDTO(
                model = model.id,
                input = input,
                questions = params.questions.toDecisionsQuestions(),
                additionalProperties = params.additionalProperties,
            )
        )

        val responseBody = try {
            httpClient.post<String, String>(
                path = DECISIONS_PATH,
                requestBody = request,
                headers = jsonContentHeaders,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.toLLMClientException()
        }

        return parseResponse(decisionsJson.decodeFromString<DecisionsResponseDTO>(responseBody))
    }

    private fun parseResponse(response: DecisionsResponseDTO): Message.Assistant = Message.Assistant(
        parts = listOf(MessagePart.Text(response.answers.toString())),
        metaInfo = ResponseMetaInfo.create(
            clock,
            totalTokensCount = response.usage?.totalTokens,
            inputTokensCount = response.usage?.totalInputTokens,
            outputTokensCount = response.usage?.totalOutputTokens,
        ),
        finishReason = response.promptFeedback?.blockReason,
    )

    private fun Exception.toLLMClientException(): LLMClientException = LLMClientException(
        clientName = clientName,
        message = if (this is KoogHttpClientException) toDecisionsErrorMessage() else message,
        cause = this,
    )

    /** Google's error status or code and message when the body is its error JSON, otherwise the raw body. */
    private fun KoogHttpClientException.toDecisionsErrorMessage(): String {
        val error = errorBody?.let { body ->
            runCatching { decisionsJson.decodeFromString<DecisionsErrorResponseDTO>(body).error }.getOrNull()
        }
        return if (error != null) {
            "Gemini Decisions error ${error.status ?: error.code?.content} (HTTP $statusCode): ${error.message}"
        } else {
            "Gemini Decisions request failed (HTTP $statusCode): $errorBody"
        }
    }

    /** The Decisions API has no moderation. */
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("Moderation is not supported by the Gemini Decisions API.")

    override fun close() {
        httpClient.close()
    }
}
