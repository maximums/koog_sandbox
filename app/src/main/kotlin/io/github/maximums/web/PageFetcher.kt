package io.github.maximums.web

/** [content] is the cleaned page text as Markdown. */
data class Page(val url: String, val title: String, val content: String)

fun interface PageFetcher {
    suspend fun fetch(url: String): Page
}
