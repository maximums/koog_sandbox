package io.github.maximums.llm.decisions

import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.llm.LLModel

/**
 * Google's Gemini decision models, for [DecisionsLLMClient].
 *
 * | Name                | Context | Output | Questions per request |
 * |---------------------|---------|--------|-----------------------|
 * | [GEMINI_DECISION_1] | 262,144 | 0      | 1 to 1,000            |
 */
object DecisionModels : LLModelDefinitions {

    /**
     * Gemini Decision 1: calibrated yes/no, choice and score decisions, generating no text. Its 262,144-token context
     * holds the input and all the questions.
     *
     * It has none of Koog's capabilities: it generates no text (`Completion`), takes no tools, has no structured
     * output or thinking, and its image, audio, video and PDF input goes unused, as [DecisionsLLMClient] sends text
     * only.
     *
     * @see <a href="https://aistudio.google.com/docs/models/gemini-decision-1">Gemini Decision 1</a>
     */
    @JvmField
    val GEMINI_DECISION_1: LLModel = LLModel(
        provider = DecisionsLLMProvider,
        id = "gemini-decision-1",
        capabilities = emptyList(),
        contextLength = 262_144,
        maxOutputTokens = 0,
    )

    private val supportedModels: List<LLModel> = listOf(
        GEMINI_DECISION_1,
    )

    private val customModels: MutableList<LLModel> = mutableListOf()

    override val models: List<LLModel>
        get() = supportedModels + customModels

    override fun addCustomModel(model: LLModel) {
        require(model.provider == DecisionsLLMProvider) { "Model provider must be DecisionsLLMProvider" }
        customModels.add(model)
    }
}
