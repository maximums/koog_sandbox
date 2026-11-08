package io.github.maximums.research

import io.github.maximums.research.model.Source

// Matches [3] and [1, 2].
private val citationPattern = Regex("""\[(\d{1,4}(?:\s*,\s*\d{1,4})*)]""")

private fun MatchResult.sourceIds(): List<Int> = groupValues[1].split(',').map { it.trim().toInt() }

fun citedSourceIds(markdown: String): Set<Int> =
    citationPattern.findAll(markdown).flatMap { it.sourceIds() }.toSet()

fun hasValidCitations(markdown: String, sources: List<Source>): Boolean {
    val cited = citedSourceIds(markdown)
    return cited.isNotEmpty() && sources.map { it.id }.containsAll(cited)
}
