package io.github.maximums.research

data class ResearchLimits(
    val maxRounds: Int = 3,
    val maxQueriesPerRound: Int = 5,
    val maxPagesPerRound: Int = 8,
    val maxPagesTotal: Int = 30,
    val maxReportRewrites: Int = 2,
    val resultsPerQuery: Int = 10,
    /** Page text one readPages call returns, shared by its pages: about 5k tokens of the model's 32k context. */
    val maxCharsPerRead: Int = 20_000,
    val searchConcurrency: Int = 2,
    val fetchConcurrency: Int = 4,
    val extractionConcurrency: Int = 1,
)
