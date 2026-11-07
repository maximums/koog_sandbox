package io.github.maximums.research

import kotlinx.serialization.Serializable

// LLM outputs (structured)

@Serializable
data class SubQuestion(val id: String, val question: String)

@Serializable
data class ResearchPlan(val subQuestions: List<SubQuestion>, val successCriteria: List<String>)

@Serializable
data class SearchQueries(val queries: List<String>)

@Serializable
data class Finding(val subQuestionId: String, val claim: String, val quote: String)

@Serializable
data class PageFindings(val findings: List<Finding>)

@Serializable
data class Gap(val subQuestionId: String, val missing: String)

@Serializable
data class Assessment(val gaps: List<Gap>, val conflicts: List<String>)

// Research results

data class Source(val id: Int, val url: String, val title: String)

data class EvidenceNote(val sourceId: Int, val subQuestionId: String, val claim: String, val quote: String)

data class ResearchReport(val markdown: String, val sources: List<Source>)
