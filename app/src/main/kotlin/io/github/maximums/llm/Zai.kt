package io.github.maximums.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import io.github.maximums.llm.zai.ZaiLLMClient

/**
 * A prompt executor for Z.ai's models ([io.github.maximums.llm.zai.ZaiModels]), authenticated with [apiKey] and
 * calling it with [httpClient].
 */
fun simpleZaiExecutor(apiKey: String, httpClient: KoogHttpClient.Factory = KtorKoogHttpClient.Factory()): MultiLLMPromptExecutor {
    require(apiKey.isNotBlank()) { "The Z.ai API key is blank" }

    return MultiLLMPromptExecutor(ZaiLLMClient(apiKey, httpClientFactory = httpClient))
}
