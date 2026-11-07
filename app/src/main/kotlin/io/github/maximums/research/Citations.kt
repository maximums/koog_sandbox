package io.github.maximums.research

// Matches [3] and [1, 2].
private val citationPattern = Regex("""\[(\d{1,4}(?:\s*,\s*\d{1,4})*)]""")

private fun MatchResult.sourceIds(): List<Int> = groupValues[1].split(',').map { it.trim().toInt() }

fun citedSourceIds(markdown: String): Set<Int> =
    citationPattern.findAll(markdown).flatMap { it.sourceIds() }.toSet()

fun hasValidCitations(markdown: String, sources: List<Source>): Boolean {
    val cited = citedSourceIds(markdown)
    return cited.isNotEmpty() && sources.map { it.id }.containsAll(cited)
}

fun ResearchState.needsRewrite(limits: ResearchLimits): Boolean =
    sources.isNotEmpty() && !hasValidCitations(draft, sources) && reportAttempts <= limits.maxReportRewrites

/** Drops citations of unknown sources and keeps only the sources the report cites. */
fun ResearchState.toReport(): ResearchReport {
    val known = sources.map { it.id }.toSet()
    val markdown = citationPattern.replace(draft) { match ->
        val kept = match.sourceIds().filter { it in known }
        if (kept.isEmpty()) "" else kept.joinToString(", ", prefix = "[", postfix = "]")
    }
    val cited = citedSourceIds(markdown)
    return ResearchReport(markdown, sources.filter { it.id in cited })
}
