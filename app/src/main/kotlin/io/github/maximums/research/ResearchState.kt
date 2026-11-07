package io.github.maximums.research

import io.github.maximums.web.Page
import io.github.maximums.web.SearchResult

data class ResearchState(
    val question: String,
    val plan: ResearchPlan = ResearchPlan(emptyList(), emptyList()),
    val round: Int = 0,
    val gaps: List<Gap> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val pastQueries: List<String> = emptyList(),
    val seenUrls: Set<String> = emptySet(),
    val pagesFetched: Int = 0,
    val sources: List<Source> = emptyList(),
    val evidence: List<EvidenceNote> = emptyList(),
    val draft: String = "",
    val reportAttempts: Int = 0,
    // Per-round scratch data, cleared by the step that consumes it.
    val queries: List<String> = emptyList(),
    val searchResults: List<SearchResult> = emptyList(),
    val selected: List<SearchResult> = emptyList(),
    val pages: List<Page> = emptyList(),
)

fun ResearchState.isDone(limits: ResearchLimits): Boolean =
    gaps.isEmpty() || round >= limits.maxRounds || pagesFetched >= limits.maxPagesTotal

fun ResearchState.pageBudget(limits: ResearchLimits): Int =
    minOf(limits.maxPagesPerRound, limits.maxPagesTotal - pagesFetched).coerceAtLeast(0)
