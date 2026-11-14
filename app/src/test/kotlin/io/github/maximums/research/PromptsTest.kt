package io.github.maximums.research

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val PromptsTest by testSuite {

    test("loads the researcher's system prompt from the resources") {
        val prompt = loadPrompt("researcher.md")

        assertTrue(prompt.startsWith("You are a web research agent."))
    }

    test("fails loudly, naming the path, when a prompt is missing") {
        val error = assertFailsWith<IllegalStateException> { loadPrompt("missing.md") }

        assertContains(error.message.orEmpty(), "/prompts/missing.md")
    }
}
