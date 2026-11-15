package io.github.maximums.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.maximums.retry.RetryPolicy
import io.github.maximums.retry.withRetry
import io.github.maximums.web.ApiError
import io.github.maximums.web.Page
import io.github.maximums.web.PageFetcher
import io.github.maximums.web.PageRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * The LLM-facing tool for reading pages, usually ones found with [WebSearchTool]. Like that tool, it owns only
 * the contract the model sees; the reading itself is done by a [PageFetcher].
 *
 * One call reads at most [maxPagesPerCall] pages in a single batch, and returns at most [maxCharsPerCall]
 * characters of page text in all, so a call can't fill the context. The pages share that budget: a page
 * shorter than an equal share keeps all its text, and the longer ones split the rest equally and are cut.
 * With a focus, pages come back as their most relevant excerpts instead, which is how long pages such as
 * papers are meant to be read.
 *
 * As in [WebSearchTool], failures come back as data written for the model: [Result.error] when the whole read
 * failed (after retrying temporary failures with [retryPolicy]), and [Result.unreadable] for single pages.
 */
class ReadPagesTool(
    private val fetcher: PageFetcher,
    private val maxPagesPerCall: Int,
    private val maxCharsPerCall: Int,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : Tool<ReadPagesTool.Args, ReadPagesTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = "readPages",
    description = "Reads web pages and returns their text as Markdown.",
) {

    init {
        require(maxPagesPerCall >= 1) { "maxPagesPerCall must be at least 1, was $maxPagesPerCall" }
        require(maxCharsPerCall >= 1) { "maxCharsPerCall must be at least 1, was $maxCharsPerCall" }
    }

    @Serializable
    data class Args(
        @property:LLMDescription("URLs to read.")
        val urls: List<String>,

        @property:LLMDescription("What you're looking for. If set, returns only the relevant excerpts; use it for long pages.")
        val focus: String? = null,
    )

    @Serializable
    data class Result(
        val pages: List<PageText> = emptyList(),
        /** Pages that weren't read, each with the reason. */
        val unreadable: List<UnreadablePage> = emptyList(),
        /** Why the whole read failed, written for the model; null when it went through. */
        val error: String? = null,
    )

    @Serializable
    data class PageText(
        val url: String,
        val content: String,
        /** ISO date (`2025-03-11`) the page was published or last updated, when known. */
        val publishedDate: String? = null,
        /** True when [content] was cut off at the character limit. */
        val truncated: Boolean = false,
    )

    @Serializable
    data class UnreadablePage(val url: String, val reason: String)

    override suspend fun execute(args: Args): Result {
        val urls = args.urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (urls.isEmpty()) return Result(error = "No URLs given.")

        val toRead = urls.take(maxPagesPerCall)
        val skipped = urls.drop(maxPagesPerCall).map { UnreadablePage(it, "Skipped: at most $maxPagesPerCall pages per call.") }

        return withRetry(retryPolicy) { fetcher.fetch(PageRequest(toRead, args.focus)) }.fold(
            ifLeft = { error ->
                logger.warn { "readPages failed for $toRead: $error" }
                Result(unreadable = skipped, error = error.forModel())
            },
            ifRight = { outcomes ->
                val read = outcomes.values.mapNotNull { outcome -> outcome.getOrNull() }
                val shares = shareChars(read.map { it.content.length }, maxCharsPerCall)
                Result(
                    pages = read.zip(shares) { page, maxChars -> page.toPageText(maxChars) },
                    unreadable = outcomes.values.mapNotNull { outcome ->
                        outcome.leftOrNull()?.let { UnreadablePage(it.url, it.message) }
                    } + skipped,
                )
            },
        )
    }

    private fun Page.toPageText(maxChars: Int): PageText {
        val truncated = content.length > maxChars
        return PageText(
            url = url,
            content = if (truncated) content.take(maxChars) else content,
            publishedDate = publishedDate?.toString(),
            truncated = truncated,
        )
    }
}

/**
 * Splits [budget] characters between texts of the given [lengths], returning each text's share in the same
 * order. Going from the shortest text up, each gets an equal share of what's left, or its whole length when
 * that's less, so what short texts don't use goes to the longer ones.
 */
private fun shareChars(lengths: List<Int>, budget: Int): List<Int> {
    val shares = IntArray(lengths.size)
    var left = budget
    for ((done, i) in lengths.indices.sortedBy { lengths[it] }.withIndex()) {
        shares[i] = minOf(lengths[i], left / (lengths.size - done))
        left -= shares[i]
    }
    return shares.asList()
}

private fun ApiError.forModel(): String = when (this) {
    is ApiError.Unauthorized, is ApiError.QuotaExceeded ->
        "Reading pages is unavailable for this task. Don't call readPages again."

    is ApiError.RateLimited, is ApiError.Unavailable ->
        "Reading pages is temporarily unavailable."

    is ApiError.InvalidRequest, is ApiError.Unexpected ->
        "These pages couldn't be read."
}
