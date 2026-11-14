package io.github.maximums.web

import arrow.core.Either
import java.time.LocalDate

/** Pages to read in one batch. [focus], when set, asks for only the parts of each page most relevant to it. */
data class PageRequest(val urls: List<String>, val focus: String? = null)

/**
 * [content] is the page text as Markdown, or only its most relevant parts when the request had a focus.
 * [publishedDate] is when the page was published or last updated, as far as the provider can tell, or null.
 */
data class Page(val url: String, val content: String, val publishedDate: LocalDate? = null)

/** Why reading a page failed: the whole request ([ApiError]) or just that page ([Unreadable]). */
sealed interface FetchError {
    val message: String
}

/** A page the provider couldn't read, while the rest of the request went through. */
data class Unreadable(val url: String, override val message: String) : FetchError

/**
 * Reads web pages in one batch. The request fails as a whole with an [ApiError], or every requested URL gets
 * its own outcome: its [Page], or [Unreadable] when that page couldn't be read.
 */
fun interface PageFetcher {
    suspend fun fetch(request: PageRequest): Either<ApiError, Map<String, Either<Unreadable, Page>>>
}
