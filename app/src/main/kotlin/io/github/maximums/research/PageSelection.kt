package io.github.maximums.research

import io.github.maximums.web.SearchResult
import java.net.URI

private val trackingParams = listOf("utm_", "gclid=", "fbclid=")

/** Dedupe key: ignores scheme, `www.`, fragment, trailing slash and tracking parameters. */
fun normalizeUrl(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull()
    val host = uri?.host?.lowercase()?.removePrefix("www.") ?: return url.trim()
    val port = if (uri.port != -1) ":${uri.port}" else ""
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val query = uri.rawQuery
        ?.split('&')
        ?.filterNot { param -> trackingParams.any(param::startsWith) }
        ?.joinToString("&")
        ?.takeIf { it.isNotEmpty() }
        ?.let { "?$it" }
        .orEmpty()
    return "$host$port$path$query"
}

/** Picks top results across all queries (best rank first), skipping duplicates and already-seen URLs. */
fun selectPages(results: List<SearchResult>, seenUrls: Set<String>, budget: Int): List<SearchResult> =
    results
        .sortedBy { it.rank }
        .distinctBy { normalizeUrl(it.url) }
        .filterNot { normalizeUrl(it.url) in seenUrls }
        .take(budget)
