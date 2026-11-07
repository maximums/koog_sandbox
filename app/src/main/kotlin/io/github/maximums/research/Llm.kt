package io.github.maximums.research

import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.agent.context.DetachedPromptExecutorAPI
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel

// Every LLM step is a fresh one-shot prompt sent straight to the executor, so no step sees another
// step's history and page extraction can run in parallel. Trade-off: these calls bypass the agent
// pipeline, so Koog features (tracing, event handlers) don't see them.

@OptIn(DetachedPromptExecutorAPI::class)
internal suspend inline fun <reified T> AIAgentLLMContext.structured(
    model: LLModel,
    systemPrompt: String,
    userPrompt: String,
): T = promptExecutor.executeStructured<T>(prompt("research") { system(systemPrompt); user(userPrompt) }, model)
    .getOrThrow()
    .data

@OptIn(DetachedPromptExecutorAPI::class)
internal suspend fun AIAgentLLMContext.text(model: LLModel, systemPrompt: String, userPrompt: String): String =
    promptExecutor.execute(prompt("research") { system(systemPrompt); user(userPrompt) }, model).textContent()
