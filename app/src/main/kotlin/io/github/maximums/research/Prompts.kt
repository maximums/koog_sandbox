package io.github.maximums.research

import io.github.maximums.web.Page

internal object Prompts {
    val plan = """
        You plan web research. Split the question into 2 to 5 focused sub-questions that together answer it.
        Give each sub-question a short id: sq1, sq2, and so on.
        Also list the success criteria a complete answer must meet.
    """.trimIndent()

    fun planInput(question: String) = "Question to plan:\n$question"

    fun queries(max: Int) = """
        You write web search queries. Write at most $max short, specific queries that target the open gaps.
        Do not repeat queries that were already searched.
    """.trimIndent()

    fun queriesInput(state: ResearchState) = buildString {
        appendLine("Question: ${state.question}")
        appendLine()
        appendLine("Open gaps:")
        state.gaps.forEach { appendLine("- ${it.subQuestionId}: ${it.missing}") }
        if (state.pastQueries.isNotEmpty()) {
            appendLine()
            appendLine("Already searched:")
            state.pastQueries.forEach { appendLine("- $it") }
        }
    }

    val extract = """
        You extract evidence from one web page. For each sub-question the page helps answer, add findings:
        a short claim and a verbatim quote from the page that supports it.
        Use only the page. Return no findings if the page is not relevant.
    """.trimIndent()

    fun extractInput(plan: ResearchPlan, page: Page, maxChars: Int) = buildString {
        appendSubQuestions(plan)
        appendLine()
        appendLine("Page title: ${page.title}")
        appendLine("Page content:")
        append(page.content.take(maxChars))
    }

    val assess = """
        You review research progress. Decide for each sub-question whether the evidence answers it.
        Return a gap for every sub-question that is not fully answered, saying what is still missing.
        Also list conflicts where sources disagree.
    """.trimIndent()

    fun assessInput(state: ResearchState) = buildString {
        appendSubQuestions(state.plan)
        appendLine()
        appendLine("Success criteria:")
        state.plan.successCriteria.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Evidence collected:")
        appendEvidence(state.evidence)
    }

    val write = """
        You write a research report in Markdown that answers the question using only the given evidence.
        Cite sources inline by number, like [1] or [1, 2]. Cite only the numbered sources listed.
        Point out conflicting evidence. If the evidence is not enough to answer, say so.
    """.trimIndent()

    fun writeInput(state: ResearchState) = buildString {
        appendLine("Question: ${state.question}")
        appendLine()
        appendLine("Sources:")
        state.sources.forEach { appendLine("[${it.id}] ${it.title} - ${it.url}") }
        appendLine()
        appendLine("Evidence:")
        appendEvidence(state.evidence)
        if (state.conflicts.isNotEmpty()) {
            appendLine()
            appendLine("Conflicts:")
            state.conflicts.forEach { appendLine("- $it") }
        }
        if (state.draft.isNotBlank()) {
            appendLine()
            appendLine("Your previous draft cited sources that are not listed. Cite only: ${state.sources.joinToString { "[${it.id}]" }}.")
        }
        appendLine()
        append("Write the report.")
    }

    private fun StringBuilder.appendSubQuestions(plan: ResearchPlan) {
        appendLine("Sub-questions:")
        plan.subQuestions.forEach { appendLine("- ${it.id}: ${it.question}") }
    }

    private fun StringBuilder.appendEvidence(evidence: List<EvidenceNote>) {
        if (evidence.isEmpty()) appendLine("(none)")
        evidence.forEach { appendLine("- [${it.sourceId}] (${it.subQuestionId}) ${it.claim} Quote: \"${it.quote}\"") }
    }
}
