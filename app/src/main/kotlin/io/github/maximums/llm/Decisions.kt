package io.github.maximums.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import io.github.maximums.llm.decisions.DecisionsLLMClient

/**
 * A prompt executor for Google's Gemini decision models ([io.github.maximums.llm.decisions.DecisionModels]),
 * authenticated with [apiKey] (a Gemini API key) and calling it with [httpClient].
 */
fun simpleDecisionsExecutor(apiKey: String, httpClient: KoogHttpClient.Factory = KtorKoogHttpClient.Factory()): MultiLLMPromptExecutor {
    require(apiKey.isNotBlank()) { "The Gemini API key is blank" }

    return MultiLLMPromptExecutor(DecisionsLLMClient(apiKey, httpClientFactory = httpClient))
}
