package io.github.maximums.llm.zai.dto

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Converts a Prompt to a list of chat messages for the Z.ai API. Text, reasoning and tools for now.
 */
internal fun Prompt.toZaiChatMessages(): List<ZaiChatMessageDTO> {
    val messages = this.messages
    return buildList {
        for (message in messages) {
            when (message) {
                is Message.System -> {
                    message.parts.forEach { part ->
                        add(ZaiChatMessageDTO(role = "system", content = part.text))
                    }
                }

                is Message.User -> {
                    // First add tool results
                    message.parts.filterIsInstance<MessagePart.Tool.Result>().forEach { part ->
                        add(part.toZaiToolChatMessage())
                    }

                    // Only after add user texts if not empty
                    message.toZaiTextChatMessageOrNull()?.let { add(it) }
                }

                is Message.Assistant -> {
                    add(
                        ZaiChatMessageDTO(
                            role = "assistant",
                            // If no text, then it should be null, e.g. for a reasoning-only message.
                            content = message.textContent().ifEmpty { null },
                            reasoningContent = message.toZaiReasoningContent(),
                            toolCalls = message.toZaiToolCalls(),
                        )
                    )
                }
            }
        }
    }
}

private fun Message.User.toZaiTextChatMessageOrNull(): ZaiChatMessageDTO? {
    val contentParts = parts.filterIsInstance<MessagePart.ContentPart>().ifEmpty { return null }

    val text = StringBuilder()
    contentParts.forEach { part ->
        when (part) {
            is MessagePart.Text -> text.append(part.text)
            // TODO(vision): GLM-5.3-Flash/FlashX take images, video and files. Send the user's content as an array
            //  of text/image_url/video_url/file items (the reference's vision request; 5 MB per image, 200 MB per
            //  video, 50 MB per file), then give those models LLMCapability.Vision.Image.
            else -> throw IllegalArgumentException("Unsupported message part: $part")
        }
    }

    return ZaiChatMessageDTO(role = "user", content = text.toString())
}

@OptIn(ExperimentalUuidApi::class)
private fun MessagePart.Tool.Result.toZaiToolChatMessage(): ZaiChatMessageDTO {
    require(parts.all { it is MessagePart.Text }) { "Unsupported non-text content in the result of tool '$tool'" }

    return ZaiChatMessageDTO(
        role = "tool",
        content = parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text },
        toolCallId = id ?: Uuid.random().toString(),
    )
}

/**
 * The message's reasoning as Z.ai's single `reasoning_content` string. Z.ai wants it back unmodified, so the
 * pieces are joined with nothing in between, the way its streaming chunks add up.
 */
private fun Message.Assistant.toZaiReasoningContent(): String? =
    parts.filterIsInstance<MessagePart.Reasoning>()
        .flatMap { it.content }
        .joinToString("")
        .ifEmpty { null }

@OptIn(ExperimentalUuidApi::class)
private fun Message.Assistant.toZaiToolCalls(): List<ZaiToolCallDTO>? =
    parts.filterIsInstance<MessagePart.Tool.Call>()
        .map { part ->
            ZaiToolCallDTO(
                id = part.id ?: Uuid.random().toString(),
                type = "function",
                // Requests take the arguments as a JSON string, which `args` already is.
                function = ZaiToolCallDTO.Call(name = part.tool, arguments = JsonPrimitive(part.args)),
            )
        }
        .ifEmpty { null }
