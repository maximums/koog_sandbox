package io.github.maximums.research

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.maximums.web.PageFetcher
import io.github.maximums.web.SearchProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/** [main] plans, assesses and writes; [small] writes queries and extracts evidence. */
data class ResearchModels(val main: LLModel, val small: LLModel)

fun researchAgent(
    executor: PromptExecutor,
    search: SearchProvider,
    fetcher: PageFetcher,
    models: ResearchModels,
    limits: ResearchLimits = ResearchLimits(),
): GraphAIAgent<String, ResearchReport> = AIAgent(
    promptExecutor = executor,
    llmModel = models.main,
    strategy = researchStrategy(search, fetcher, models, limits),
    maxIterations = limits.maxRounds * 10 + limits.maxReportRewrites + 10,
)

fun researchStrategy(
    search: SearchProvider,
    fetcher: PageFetcher,
    models: ResearchModels,
    limits: ResearchLimits,
): AIAgentGraphStrategy<String, ResearchReport> = strategy("web-research") {
    val planResearch by node<String, ResearchState> { question ->
        val plan = llm.structured<ResearchPlan>(models.main, Prompts.plan, Prompts.planInput(question))
        ResearchState(question, plan, gaps = plan.subQuestions.map { Gap(it.id, it.question) })
    }

    val generateQueries by node<ResearchState, ResearchState> { state ->
        val queries = llm.structured<SearchQueries>(models.small, Prompts.queries(limits.maxQueriesPerRound), Prompts.queriesInput(state))
            .queries.map(String::trim).filter(String::isNotEmpty).distinct()
            .take(limits.maxQueriesPerRound)
        state.copy(round = state.round + 1, queries = queries)
    }

    val searchWeb by node<ResearchState, ResearchState> { state ->
        val results = state.queries.mapConcurrently(limits.searchConcurrency) { query ->
            orNull { search.search(query, limits.resultsPerQuery) }.orEmpty()
        }
        state.copy(pastQueries = state.pastQueries + state.queries, queries = emptyList(), searchResults = results.flatten())
    }

    val choosePages by node<ResearchState, ResearchState> { state ->
        val selected = selectPages(state.searchResults, state.seenUrls, state.pageBudget(limits))
        state.copy(
            seenUrls = state.seenUrls + selected.map { normalizeUrl(it.url) },
            searchResults = emptyList(),
            selected = selected,
        )
    }

    val fetchPages by node<ResearchState, ResearchState> { state ->
        val pages = state.selected.mapConcurrently(limits.fetchConcurrency) { orNull { fetcher.fetch(it.url) } }
        state.copy(pagesFetched = state.pagesFetched + state.selected.size, selected = emptyList(), pages = pages.filterNotNull())
    }

    val extractEvidence by node<ResearchState, ResearchState> { state ->
        val findings = state.pages.mapConcurrently(limits.extractionConcurrency) { page ->
            orNull {
                llm.structured<PageFindings>(
                    models.small, Prompts.extract, Prompts.extractInput(state.plan, page, limits.maxPageChars),
                ).findings
            }.orEmpty()
        }
        val useful = state.pages.zip(findings).filter { (_, found) -> found.isNotEmpty() }
        val sources = useful.mapIndexed { i, (page, _) -> Source(state.sources.size + i + 1, page.url, page.title) }
        val notes = sources.zip(useful).flatMap { (source, pageFindings) ->
            pageFindings.second.map { EvidenceNote(source.id, it.subQuestionId, it.claim, it.quote) }
        }
        state.copy(sources = state.sources + sources, evidence = state.evidence + notes, pages = emptyList())
    }

    val assessCoverage by node<ResearchState, ResearchState> { state ->
        val assessment = llm.structured<Assessment>(models.main, Prompts.assess, Prompts.assessInput(state))
        state.copy(gaps = assessment.gaps, conflicts = assessment.conflicts)
    }

    val writeReport by node<ResearchState, ResearchState> { state ->
        val draft = llm.text(models.main, Prompts.write, Prompts.writeInput(state))
        state.copy(draft = draft, reportAttempts = state.reportAttempts + 1)
    }

    nodeStart then planResearch then generateQueries then searchWeb then choosePages then
        fetchPages then extractEvidence then assessCoverage

    edge(assessCoverage forwardTo generateQueries onCondition { !it.isDone(limits) })
    edge(assessCoverage forwardTo writeReport onCondition { it.isDone(limits) })
    edge(writeReport forwardTo writeReport onCondition { it.needsRewrite(limits) })
    edge(writeReport forwardTo nodeFinish onCondition { !it.needsRewrite(limits) } transformed { it.toReport() })
}

private suspend fun <T, R> List<T>.mapConcurrently(concurrency: Int, transform: suspend (T) -> R): List<R> {
    val permits = Semaphore(concurrency)
    return coroutineScope { map { async { permits.withPermit { transform(it) } } }.awaitAll() }
}

/** A failed search, fetch or extraction skips that item instead of failing the run. */
private inline fun <T> orNull(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
