package io.github.maximums.llm

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.llm.zai.ZaiModels
import io.github.maximums.web.RecordingServer
import io.github.maximums.web.Reply
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** A minimal Chat Completions reply, in the OpenAI format Z.ai answers with. */
private const val REPLY = """
{
  "id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "glm-5.3-flash",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello!"}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}
}
"""

val ZaiTest by testSuite {

    test("sends GLM-5.3-Flash requests to Z.ai's Chat Completions endpoint with the API key") {
        val server = RecordingServer(Reply(body = REPLY))
        val executor = simpleZaiExecutor("zai-test", KtorKoogHttpClient.Factory(server.http))

        val answer = executor.execute(prompt("hello") { user("Say hello.") }, ZaiModels.GLM_5_3_FLASH)

        assertEquals("Hello!", answer.textContent())
        val request = server.requests.single()
        assertEquals("https://api.z.ai/api/paas/v4/chat/completions", request.url.toString())
        assertEquals("Bearer zai-test", request.headers[HttpHeaders.Authorization])
        val body = server.sentJson()
        assertEquals("glm-5.3-flash", body.getValue("model").jsonPrimitive.content)
        assertFalse("response_format" in body, "Z.ai documents no JSON schemas, so none should be sent")
    }

    test("rejects a blank API key up front") {
        assertFailsWith<IllegalArgumentException> { simpleZaiExecutor(" ") }
    }
}
