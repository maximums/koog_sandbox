package io.github.maximums.llm.zai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.serialization.ToolDescriptorSchemaGenerator
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.post
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.utils.time.KoogClock
import io.github.maximums.llm.zai.dto.ZaiChatRequestDTO
import io.github.maximums.llm.zai.dto.ZaiChatRequestDTOSerializer
import io.github.maximums.llm.zai.dto.ZaiChatResponseDTO
import io.github.maximums.llm.zai.dto.ZaiChatStreamResponseDTO
import io.github.maximums.llm.zai.dto.ZaiErrorResponseDTO
import io.github.maximums.llm.zai.dto.ZaiToolDTO
import io.github.maximums.llm.zai.dto.toZaiChatMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Koog [LLMClient] for Z.ai's GLM models, over Z.ai's Chat Completions API
 * (https://docs.z.ai/api-reference/llm/chat-completion).
 *
 * The prompt's tool choice is ignored: Z.ai only has `auto`. Koog copes when the model lacks
 * [ai.koog.prompt.llm.LLMCapability.ToolChoice].
 *
 * @param httpClient A Koog HTTP client with Z.ai's base URL and `Authorization: Bearer <apiKey>` header already
 *   set. To build one from an API key, use the secondary constructor.
 * @param clock Clock for the responses' metadata timestamps.
 * @param toolDescriptorConverter Generates the JSON schemas of the tools sent to the model.
 */
class ZaiLLMClient(
    private val httpClient: KoogHttpClient,
    private val clock: KoogClock = KoogClock.System,
    private val toolDescriptorConverter: ToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
) : LLMClient() {

    companion object {
        /** Z.ai's general API endpoint (https://docs.z.ai/api-reference/introduction). */
        const val DEFAULT_BASE_URL: String = "https://api.z.ai/api/paas/v4"

        private const val CLIENT_NAME = "ZaiLLMClient"

        private const val CHAT_COMPLETIONS_PATH = "chat/completions"

        private val jsonContentHeaders = mapOf("Content-Type" to "application/json")

        private val zaiJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    /**
     * Builds the underlying [KoogHttpClient] for Z.ai, authenticated with [apiKey].
     *
     * [httpClientFactory] defaults to the one Koog finds on the classpath, such as Ktor's.
     */
    constructor(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
        clock: KoogClock = KoogClock.System,
        toolDescriptorConverter: ToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
        httpClientFactory: KoogHttpClient.Factory = HttpClientFactoryResolver.resolve(),
    ) : this(
        httpClient = httpClientFactory.create(
            clientName = CLIENT_NAME,
            baseUrl = baseUrl,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            queryParameters = emptyMap(),
            requestTimeoutMillis = timeoutConfig.requestTimeoutMillis,
            connectTimeoutMillis = timeoutConfig.connectTimeoutMillis,
            socketTimeoutMillis = timeoutConfig.socketTimeoutMillis,
            json = zaiJson,
        ),
        clock = clock,
        toolDescriptorConverter = toolDescriptorConverter,
    )

    // Z.ai is Zhipu AI's international platform, and Koog already has a provider for it.
    override fun llmProvider(): LLMProvider = LLMProvider.ZhipuAI

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val request = chatRequest(prompt, model, tools, stream = false)

        val responseBody = try {
            httpClient.post<String, String>(
                path = CHAT_COMPLETIONS_PATH,
                requestBody = request,
                headers = jsonContentHeaders,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.toLLMClientException()
        }

        return parseResponse(zaiJson.decodeFromString<ZaiChatResponseDTO>(responseBody))
    }

    /**
     * Streams the reply over Server-Sent Events (https://docs.z.ai/guides/capabilities/streaming). Tool calls may
     * come whole or, with `tool_stream`, in fragments; Koog's [buildStreamFrameFlow] joins them by index.
     */
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
        val request = chatRequest(prompt, model, tools, stream = true)

        return buildStreamFrameFlow {
            var finishReason: String? = null
            var metaInfo: ResponseMetaInfo? = null

            httpClient.sse(
                path = CHAT_COMPLETIONS_PATH,
                requestBody = request,
                requestBodyType = String::class,
                dataFilter = { it != "[DONE]" },
                decodeStreamingResponse = { zaiJson.decodeFromString<ZaiChatStreamResponseDTO>(it) },
                processStreamingChunk = { it },
                headers = jsonContentHeaders,
            ).collect { chunk ->
                chunk.choices.firstOrNull()?.let { choice ->
                    choice.delta.reasoningContent?.ifEmpty { null }?.let { emitReasoningDelta(text = it, index = choice.index) }
                    choice.delta.content?.ifEmpty { null }?.let { emitTextDelta(it) }
                    choice.delta.toolCalls?.forEach { toolCall ->
                        emitToolCallDelta(
                            id = toolCall.id,
                            name = toolCall.function?.name,
                            args = toolCall.function?.arguments?.toArgumentsJson(),
                            index = toolCall.index,
                        )
                    }
                    choice.finishReason?.let { finishReason = it }
                }
                chunk.usage?.let { metaInfo = it.toMetaInfo() }
            }

            emitEnd(finishReason, metaInfo)
        }
            .catch { e -> throw if (e is Exception && e !is CancellationException) e.toLLMClientException() else e }
            .requireEndFrame()
    }

    /** The Chat Completions request for [prompt] as JSON, for both [execute] and [executeStreaming]. */
    private fun chatRequest(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>, stream: Boolean): String {
        require(model.provider == LLMProvider.ZhipuAI) { "Model not supported by Z.ai" }

        val params = prompt.params.toZaiParams()
        // Koog sends a schema only as a parameter when the model has a Schema capability, and Z.ai can't take one.
        require(params.schema == null) {
            "Z.ai has no JSON schemas: give ${model.id} no Schema capability, so Koog puts the schema in the prompt"
        }

        return zaiJson.encodeToString(
            ZaiChatRequestDTOSerializer,
            ZaiChatRequestDTO(
                model = model.id,
                messages = prompt.toZaiChatMessages(),
                tools = tools.map { it.toZaiTool() }.ifEmpty { null },
                stream = stream,
                temperature = params.temperature,
                topP = params.topP,
                doSample = params.doSample,
                maxTokens = params.maxTokens,
                stop = params.stop,
                thinking = params.toZaiThinking(),
                reasoningEffort = params.reasoningEffort?.value,
                userId = params.user,
                additionalProperties = params.additionalProperties,
            )
        )
    }

    private fun ToolDescriptor.toZaiTool(): ZaiToolDTO = ZaiToolDTO(
        type = "function",
        function = ZaiToolDTO.Definition(
            name = name,
            description = description,
            parameters = toolDescriptorConverter.generate(this),
        )
    )

    private fun parseResponse(response: ZaiChatResponseDTO): Message.Assistant {
        val choice = response.choices.firstOrNull()
            ?: throw LLMClientException(clientName = clientName, message = "Missing choices in Z.ai response")

        return Message.Assistant(
            parts = buildList {
                choice.message.content?.ifEmpty { null }?.let { add(MessagePart.Text(it)) }
                choice.message.reasoningContent?.ifEmpty { null }?.let { add(MessagePart.Reasoning(content = it)) }
                choice.message.toolCalls?.forEach { toolCall ->
                    add(
                        MessagePart.Tool.Call(
                            id = toolCall.id,
                            tool = toolCall.function.name,
                            args = toolCall.function.arguments.toArgumentsJson(),
                        )
                    )
                }
            },
            metaInfo = response.usage.toMetaInfo(),
            finishReason = choice.finishReason,
        )
    }

    private fun ZaiChatResponseDTO.Usage?.toMetaInfo(): ResponseMetaInfo = ResponseMetaInfo.create(
        clock,
        totalTokensCount = this?.totalTokens,
        inputTokensCount = this?.promptTokens,
        outputTokensCount = this?.completionTokens,
    )

    /** Z.ai's `thinking` object, or null when neither of its options is set. */
    private fun ZaiParams.toZaiThinking(): ZaiChatRequestDTO.Thinking? =
        ZaiChatRequestDTO.Thinking(
            type = thinking?.let { if (it) "enabled" else "disabled" },
            clearThinking = clearThinking,
        ).takeIf { it != ZaiChatRequestDTO.Thinking() }

    /** Tool call arguments as a JSON string, whether Z.ai sent that string or the object itself. */
    private fun JsonElement.toArgumentsJson(): String = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }

    private fun Exception.toLLMClientException(): LLMClientException = LLMClientException(
        clientName = clientName,
        message = if (this is KoogHttpClientException) toZaiErrorMessage() else message,
        cause = this,
    )

    /** Z.ai's own error code and message when the body is its error JSON, otherwise the raw body. */
    private fun KoogHttpClientException.toZaiErrorMessage(): String {
        val error = errorBody?.let { body ->
            runCatching { zaiJson.decodeFromString<ZaiErrorResponseDTO>(body).error }.getOrNull()
        }
        return if (error != null) {
            "Z.ai error ${error.code} (HTTP $statusCode): ${error.message}"
        } else {
            "Z.ai request failed (HTTP $statusCode): $errorBody"
        }
    }

    /**
     * Z.ai has no moderation API. It flags unsafe content in chat completions instead: a `sensitive` finish
     * reason or error 1301 (https://docs.z.ai/api-reference/api-code).
     */
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("Moderation is not supported by Z.ai API.")

    override fun close() {
        httpClient.close()
    }
}
