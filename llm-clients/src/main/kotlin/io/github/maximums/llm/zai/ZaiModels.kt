package io.github.maximums.llm.zai

import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Z.ai's GLM models with a model page in Z.ai's docs, for [ZaiLLMClient]. Others from the Chat Completions
 * reference (`glm-5.1`, `glm-5`, `glm-4.7`, `glm-4.6`, `glm-5.3-flashx-legal`) can be added with [addCustomModel].
 *
 * | Name             | Context | Output | Input            | Thinking                |
 * |------------------|---------|--------|------------------|-------------------------|
 * | [GLM_5_3]        | 1M      | 128K   | Text             | Always                  |
 * | [GLM_5_2]        | 1M      | 128K   | Text             | On by default, optional |
 * | [GLM_5_3_FLASH]  | 1M      | 128K   | Text, image, ... | Always                  |
 * | [GLM_5_3_FLASHX] | 1M      | 128K   | Text, image, ... | Always                  |
 *
 * Their capabilities are what Z.ai offers and [ZaiLLMClient] sends, so none has:
 * - `ToolChoice`: Z.ai only has `auto`.
 * - `Schema`: Z.ai has no JSON schemas, and its JSON mode (`response_format`) is for text models only. Koog then
 *   puts the schema in the prompt; with a `Schema` capability it would only send it as a parameter, which
 *   [ZaiLLMClient] rejects.
 * - Vision: Flash and FlashX take images, video and files, but the client only sends text yet.
 *
 * No sampling is set: Z.ai's defaults (temperature 1, top_p 0.95) are the ones it recommends.
 */
object ZaiModels : LLModelDefinitions {

    private val capabilities: List<LLMCapability> = listOf(
        LLMCapability.Completion,
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.Thinking,
    )

    /**
     * GLM-5.3, Z.ai's flagship text model. It always thinks; Z.ai has no way to turn that off.
     *
     * @see <a href="https://docs.z.ai/guides/llm/glm-5.3">GLM-5.3</a>
     */
    @JvmField
    val GLM_5_3: LLModel = LLModel(
        provider = LLMProvider.ZhipuAI,
        id = "glm-5.3",
        capabilities = capabilities,
        contextLength = 1_000_000,
        maxOutputTokens = 128_000,
    )

    /**
     * GLM-5.2, a text model whose thinking is on by default and can be turned off with [ZaiParams.thinking].
     *
     * @see <a href="https://docs.z.ai/guides/llm/glm-5.2">GLM-5.2</a>
     */
    @JvmField
    val GLM_5_2: LLModel = LLModel(
        provider = LLMProvider.ZhipuAI,
        id = "glm-5.2",
        capabilities = capabilities,
        contextLength = 1_000_000,
        maxOutputTokens = 128_000,
    )

    /**
     * GLM-5.3-Flash, Z.ai's natively multimodal GLM-5 model. It always thinks; Z.ai has no way to turn that off.
     *
     * @see <a href="https://docs.z.ai/guides/vlm/glm-5.3-flash">GLM-5.3-Flash/FlashX</a>
     */
    @JvmField
    val GLM_5_3_FLASH: LLModel = LLModel(
        provider = LLMProvider.ZhipuAI,
        id = "glm-5.3-flash",
        capabilities = capabilities,
        contextLength = 1_000_000,
        maxOutputTokens = 128_000,
    )

    /**
     * GLM-5.3-FlashX, a faster GLM-5.3-Flash (200 tokens/s). It always thinks.
     *
     * @see <a href="https://docs.z.ai/guides/vlm/glm-5.3-flash">GLM-5.3-Flash/FlashX</a>
     */
    @JvmField
    val GLM_5_3_FLASHX: LLModel = LLModel(
        provider = LLMProvider.ZhipuAI,
        id = "glm-5.3-flashx",
        capabilities = capabilities,
        contextLength = 1_000_000,
        maxOutputTokens = 128_000,
    )

    private val supportedModels: List<LLModel> = listOf(
        GLM_5_3,
        GLM_5_2,
        GLM_5_3_FLASH,
        GLM_5_3_FLASHX,
    )

    private val customModels: MutableList<LLModel> = mutableListOf()

    override val models: List<LLModel>
        get() = supportedModels + customModels

    override fun addCustomModel(model: LLModel) {
        require(model.provider == LLMProvider.ZhipuAI) { "Model provider must be ZhipuAI" }
        customModels.add(model)
    }
}
