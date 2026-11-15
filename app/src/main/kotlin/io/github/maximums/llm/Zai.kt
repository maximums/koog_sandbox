package io.github.maximums.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

// Z.ai (GLM models) speaks OpenAI's Chat Completions API at its own address, so Koog's OpenAI client runs it.

/** Z.ai's models, as Koog [LLModel]s for [simpleZaiExecutor]. */
object ZaiModels {
    /**
     * GLM-5.3-Flash: 1M-token context, up to 128K tokens out, with tool calling. It always thinks; Z.ai has no
     * way to turn that off.
     *
     * It has no `Schema` capability on purpose. Z.ai documents only JSON mode, not JSON schemas, so Koog asks
     * for structured output by putting the schema in the prompt instead.
     *
     * No sampling is set: Z.ai's defaults for it (temperature 1, top_p 0.95) are the ones it recommends.
     */
    val GLM_5_3_FLASH = LLModel(
        // Koog's OpenAI client only serves OpenAI models, and Z.ai's API is OpenAI's.
        provider = LLMProvider.OpenAI,
        id = "glm-5.3-flash",
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            // Z.ai only has OpenAI's Chat Completions API, not the newer Responses API.
            LLMCapability.OpenAIEndpoint.Completions,
        ),
        contextLength = 1_000_000,
        maxOutputTokens = 128_000,
    )
}

/** Z.ai's API; OpenAI's would be `https://api.openai.com` with `v1/chat/completions`. */
private val ZAI_SETTINGS = OpenAIClientSettings(baseUrl = "https://api.z.ai/api/paas/v4", chatCompletionsPath = "chat/completions")

/** A prompt executor for Z.ai's models ([ZaiModels]), authenticated with [apiKey] and calling it with [httpClient]. */
fun simpleZaiExecutor(apiKey: String, httpClient: KoogHttpClient.Factory = KtorKoogHttpClient.Factory()): MultiLLMPromptExecutor {
    require(apiKey.isNotBlank()) { "The Z.ai API key is blank" }

    return MultiLLMPromptExecutor(OpenAILLMClient(apiKey, ZAI_SETTINGS, httpClient))
}
