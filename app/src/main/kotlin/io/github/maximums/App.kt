@file:JvmName("AppEntry")

package io.github.maximums

import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels
import io.github.maximums.research.ResearchModels
import io.github.maximums.research.ResearchReport
import io.github.maximums.research.researchAgent
import io.github.maximums.web.DuckDuckGoSearchProvider
import io.github.maximums.web.JinaReaderFetcher
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(args: Array<out String>) {
    val question = args.joinToString(" ").trim()
    if (question.isEmpty()) {
        System.err.println("Usage: kresearch <question>")
        exitProcess(1)
    }

    val agent = researchAgent(
        executor = simpleOllamaAIExecutor(),
        search = DuckDuckGoSearchProvider(),
        fetcher = JinaReaderFetcher(),
        models = ResearchModels(main = OllamaModels.Alibaba.QWEN_3_5_9B, small = OllamaModels.Alibaba.QWEN_3_5_9B),
    )
    val report = runBlocking { agent.run(question) }

    val output = Path("report.md")
    output.writeText(report.toMarkdown())
    println("Report written to ${output.toAbsolutePath()}")
}

private fun ResearchReport.toMarkdown(): String = buildString {
    appendLine(markdown.trim())
    if (sources.isNotEmpty()) {
        appendLine()
        appendLine("## Sources")
        appendLine()
        sources.forEach { appendLine("- [${it.id}] [${it.title}](${it.url})") }
    }
}
