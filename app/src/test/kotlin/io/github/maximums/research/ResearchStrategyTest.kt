package io.github.maximums.research

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.web.Page
import io.github.maximums.web.PageFetcher
import io.github.maximums.web.SearchProvider
import io.github.maximums.web.SearchResult
import kotlin.test.assertEquals

private const val PLAN = """{"subQuestions":[{"id":"sq1","question":"What is Koog?"}],"successCriteria":["Defines Koog"]}"""
private const val QUERIES = """{"queries":["koog framework"]}"""
private const val FINDINGS =
    """{"findings":[{"subQuestionId":"sq1","claim":"Koog is an agent framework.","quote":"Koog is a Kotlin framework for AI agents."}]}"""
private const val COVERED = """{"gaps":[],"conflicts":[]}"""
private const val NOT_COVERED = """{"gaps":[{"subQuestionId":"sq1","missing":"History"}],"conflicts":[]}"""

val ResearchStrategyTest by testSuite {
    val models = ResearchModels(main = OllamaModels.Alibaba.QWEN_3_5_9B, small = OllamaModels.Alibaba.QWEN_3_5_9B)
    val fetcher = PageFetcher { url -> Page(url, "Koog docs", "Koog is a Kotlin framework for AI agents.") }

    class RecordingSearch : SearchProvider {
        val queries = mutableListOf<String>()

        override suspend fun search(query: String, limit: Int): List<SearchResult> {
            queries += query
            return listOf(SearchResult("https://koog.ai/docs", "Koog docs", "Koog is...", rank = 0))
        }
    }

    fun executor(assessment: String, report: String): PromptExecutor = getMockExecutor {
        mockLLMAnswer(PLAN) onRequestContains "Question to plan"
        mockLLMAnswer(QUERIES) onRequestContains "Open gaps"
        mockLLMAnswer(FINDINGS) onRequestContains "Page content"
        mockLLMAnswer(assessment) onRequestContains "Evidence collected"
        mockLLMAnswer(report) onRequestContains "Write the report"
    }

    test("answers in one round when the first assessment finds no gaps") {
        val search = RecordingSearch()
        val report = researchAgent(executor(COVERED, "Koog is an agent framework [1]."), search, fetcher, models)
            .run("What is Koog?")

        assertEquals(listOf("koog framework"), search.queries)
        assertEquals("Koog is an agent framework [1].", report.markdown)
        assertEquals(listOf(Source(1, "https://koog.ai/docs", "Koog docs")), report.sources)
    }

    test("keeps searching until the round limit while gaps remain") {
        val search = RecordingSearch()
        val report = researchAgent(executor(NOT_COVERED, "Partial answer [1]."), search, fetcher, models)
            .run("What is Koog?")

        assertEquals(ResearchLimits().maxRounds, search.queries.size)
        assertEquals("Partial answer [1].", report.markdown)
    }

    test("rewrites a report that cites unknown sources") {
        val executor = getMockExecutor {
            mockLLMAnswer(PLAN) onRequestContains "Question to plan"
            mockLLMAnswer(QUERIES) onRequestContains "Open gaps"
            mockLLMAnswer(FINDINGS) onRequestContains "Page content"
            mockLLMAnswer(COVERED) onRequestContains "Evidence collected"
            mockLLMAnswer("Made up [7].") onCondition { "Write the report" in it && "previous draft" !in it }
            mockLLMAnswer("Fixed [1].") onCondition { "previous draft" in it }
        }
        val report = researchAgent(executor, RecordingSearch(), fetcher, models).run("What is Koog?")

        assertEquals("Fixed [1].", report.markdown)
    }
}
