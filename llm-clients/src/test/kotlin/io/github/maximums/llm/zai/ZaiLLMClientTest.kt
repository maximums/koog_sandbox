package io.github.maximums.llm.zai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.toMessageResponse
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val GLM = LLModel(
    provider = LLMProvider.ZhipuAI,
    id = "glm-5.3-flash",
    capabilities = listOf(LLMCapability.Completion),
    contextLength = 1_000_000,
)

/** A Chat Completions reply in the shape of Z.ai's response schema. */
private const val REPLY = """
{
  "id": "task-1", "request_id": "req-1", "created": 0, "model": "glm-5.3-flash",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "Goodbye!", "reasoning_content": "The user is leaving."},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}
}
"""

private val WEATHER = ToolDescriptor(
    name = "get_weather",
    description = "Gets the weather.",
    requiredParameters = listOf(ToolParameterDescriptor("city", "City name.", ToolParameterType.String)),
)

/**
 * A streamed reply in the shape of Z.ai's streaming guide: reasoning, then text, then a tool call whose arguments
 * come in two fragments, as with `tool_stream`; the last chunk has the finish reason and usage.
 */
private val STREAM = listOf(
    """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"The user wants"}}]}""",
    """{"choices":[{"index":0,"delta":{"reasoning_content":" the weather."}}]}""",
    """{"choices":[{"index":0,"delta":{"content":"Let me"}}]}""",
    """{"choices":[{"index":0,"delta":{"content":" check."}}]}""",
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":"}}]}}]}""",
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Rome\"}"}}]}}]}""",
    """{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
    "[DONE]",
).joinToString("") { "data: $it\n\n" }

private fun client(server: RecordingServer) =
    ZaiLLMClient("zai-test", httpClientFactory = KtorKoogHttpClient.Factory(server.http))

val ZaiLLMClientTest by testSuite {

    test("sends the prompt to Z.ai's Chat Completions endpoint and returns its reply") {
        val server = RecordingServer(REPLY)
        val prompt = prompt("chat", LLMParams(temperature = 0.2, maxTokens = 100)) {
            system("Be brief.")
            user("Hi")
            assistant(listOf(MessagePart.Text("Hello!"), MessagePart.Reasoning("The user greets me.")))
            reasoning("A reasoning-only message.")
            user("Bye")
        }

        val answer = client(server).execute(prompt, GLM)

        assertEquals(listOf(MessagePart.Text("Goodbye!"), MessagePart.Reasoning("The user is leaving.")), answer.parts)
        assertEquals("stop", answer.finishReason)
        assertEquals(listOf(3, 2, 5), answer.metaInfo.run { listOf(inputTokensCount, outputTokensCount, totalTokensCount) })
        val request = server.requests.single()
        assertEquals("https://api.z.ai/api/paas/v4/chat/completions", request.url.toString())
        assertEquals("Bearer zai-test", request.headers[HttpHeaders.Authorization])
        val expected = """
            {
              "model": "glm-5.3-flash",
              "messages": [
                {"role": "system", "content": "Be brief."},
                {"role": "user", "content": "Hi"},
                {"role": "assistant", "content": "Hello!", "reasoning_content": "The user greets me."},
                {"role": "assistant", "reasoning_content": "A reasoning-only message."},
                {"role": "user", "content": "Bye"}
              ],
              "stream": false,
              "temperature": 0.2,
              "max_tokens": 100
            }
        """
        assertEquals(Json.parseToJsonElement(expected), server.sentJson())
    }

    test("sends tools, tool calls and their results, and returns the model's tool calls") {
        // Z.ai's guide sends arguments as a JSON string, its reply schema as an object: one call of each.
        val server = RecordingServer(
            """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "tool_calls": [
                    {"id": "call-2", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"Rome\"}"}},
                    {"id": "call-3", "type": "function", "function": {"name": "get_weather", "arguments": {"city": "Oslo"}}}
                  ]
                },
                "finish_reason": "tool_calls"
              }]
            }
            """
        )
        val prompt = prompt("tools") {
            user("Weather in Paris?")
            assistant(listOf(MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = """{"city":"Paris"}""")))
            user(listOf(MessagePart.Tool.Result(id = "call-1", tool = "get_weather", output = "Sunny")))
        }

        val answer = client(server).execute(prompt, GLM, listOf(WEATHER))

        val calls = listOf(
            MessagePart.Tool.Call(id = "call-2", tool = "get_weather", args = """{"city":"Rome"}"""),
            MessagePart.Tool.Call(id = "call-3", tool = "get_weather", args = """{"city":"Oslo"}"""),
        )
        assertEquals(calls, answer.parts)
        assertEquals("tool_calls", answer.finishReason)
        val expected = """
            {
              "model": "glm-5.3-flash",
              "messages": [
                {"role": "user", "content": "Weather in Paris?"},
                {"role": "assistant", "tool_calls": [
                  {"id": "call-1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"Paris\"}"}}
                ]},
                {"role": "tool", "content": "Sunny", "tool_call_id": "call-1"}
              ],
              "tools": [{
                "type": "function",
                "function": {
                  "name": "get_weather",
                  "description": "Gets the weather.",
                  "parameters": {
                    "type": "object",
                    "properties": {"city": {"description": "City name.", "type": "string"}},
                    "required": ["city"]
                  }
                }
              }],
              "stream": false
            }
        """
        assertEquals(Json.parseToJsonElement(expected), server.sentJson())
    }

    test("streams reasoning, text and tool calls as Koog frames") {
        val server = RecordingServer(STREAM, contentType = "text/event-stream")

        val frames = client(server).executeStreaming(prompt("stream") { user("Weather in Rome?") }, GLM, listOf(WEATHER)).toList()

        val answer = frames.toMessageResponse()
        val parts = listOf(
            MessagePart.Reasoning("The user wants the weather."),
            MessagePart.Text("Let me check."),
            MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = """{"city":"Rome"}"""),
        )
        assertEquals(parts, answer.parts)
        assertEquals("tool_calls", answer.finishReason)
        assertEquals(listOf(3, 2, 5), answer.metaInfo.run { listOf(inputTokensCount, outputTokensCount, totalTokensCount) })
        assertEquals(JsonPrimitive(true), server.sentJson()["stream"])
    }

    test("reports Z.ai's error code and message when a stream fails") {
        val server = RecordingServer(
            """{"error":{"code":"1302","message":"Rate limit reached"}}""",
            HttpStatusCode.TooManyRequests,
        )

        val error = assertFailsWith<LLMClientException> {
            client(server).executeStreaming(prompt("stream") { user("Hi") }, GLM).toList()
        }

        assertContains(error.message.orEmpty(), "Z.ai error 1302 (HTTP 429): Rate limit reached")
    }

    test("sends ZaiParams as Z.ai's request fields") {
        val server = RecordingServer(REPLY)
        val params = ZaiParams(
            user = "user-42",
            additionalProperties = mapOf("request_id" to JsonPrimitive("req-123456")),
            topP = 0.9,
            doSample = true,
            stop = listOf("END"),
            thinking = true,
            clearThinking = false,
            reasoningEffort = ZaiReasoningEffort.LOW,
        )

        client(server).execute(prompt("params", params) { user("Hi") }, GLM)

        val expected = """
            {
              "model": "glm-5.3-flash",
              "messages": [{"role": "user", "content": "Hi"}],
              "stream": false,
              "top_p": 0.9,
              "do_sample": true,
              "stop": ["END"],
              "thinking": {"type": "enabled", "clear_thinking": false},
              "reasoning_effort": "low",
              "user_id": "user-42",
              "request_id": "req-123456"
            }
        """
        assertEquals(Json.parseToJsonElement(expected), server.sentJson())
    }

    test("rejects parameters outside Z.ai's ranges") {
        assertFailsWith<IllegalArgumentException> { ZaiParams(temperature = 1.5) }
        assertFailsWith<IllegalArgumentException> { ZaiParams(user = "short") }
        assertFailsWith<IllegalArgumentException> { ZaiParams(stop = listOf("END", "STOP")) }
    }

    test("keeps its Z.ai fields when Koog merges in default params") {
        val merged = ZaiParams(topP = 0.9).default(LLMParams(temperature = 0.3))

        assertEquals(ZaiParams(temperature = 0.3, topP = 0.9), merged)
    }

    test("rejects a JSON schema, since Z.ai has none, before sending anything") {
        val server = RecordingServer(REPLY)
        val schema = LLMParams.Schema.JSON.Basic("Answer", JsonObject(emptyMap()))

        assertFailsWith<IllegalArgumentException> {
            client(server).execute(prompt("schema", LLMParams(schema = schema)) { user("Hi") }, GLM)
        }
        assertEquals(emptyList(), server.requests)
    }

    test("has no moderation, since Z.ai has no moderation API") {
        assertFailsWith<UnsupportedOperationException> {
            client(RecordingServer(REPLY)).moderate(prompt("moderate") { user("Hi") }, GLM)
        }
    }

    test("reports Z.ai's error code and message") {
        val server = RecordingServer(
            """{"error":{"code":"1211","message":"Unknown model"}}""",
            HttpStatusCode.BadRequest,
        )

        val error = assertFailsWith<LLMClientException> {
            client(server).execute(prompt("chat") { user("Hi") }, GLM)
        }

        assertContains(error.message.orEmpty(), "Z.ai error 1211 (HTTP 400): Unknown model")
    }
}
