package io.github.maximums.research.model

data class ResearchLimits(
    val maxRounds: Int = 3,
    val maxQueriesPerRound: Int = 5,
    val maxPagesPerRound: Int = 8,
    val maxPagesTotal: Int = 30,
    val maxReportRewrites: Int = 2,
    val resultsPerQuery: Int = 10,
    val maxPageChars: Int = 12_000,
    val searchConcurrency: Int = 2,
    val fetchConcurrency: Int = 4,
    val extractionConcurrency: Int = 1,
)
