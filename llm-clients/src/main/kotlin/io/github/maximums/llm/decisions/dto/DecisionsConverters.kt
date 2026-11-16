package io.github.maximums.llm.decisions.dto

import io.github.maximums.llm.decisions.DecisionQuestion

/**
 * Converts questions to the Decisions API's, keeping their IDs.
 */
internal fun Map<String, DecisionQuestion>.toDecisionsQuestions(): Map<String, DecisionsQuestionDTO> =
    mapValues { (_, question) -> question.toDecisionsQuestion() }

private fun DecisionQuestion.toDecisionsQuestion(): DecisionsQuestionDTO = when (this) {
    is DecisionQuestion.Noul -> DecisionsQuestionDTO.Noul(
        instructions = instructions,
        criteria = DecisionsQuestionDTO.Noul.Criteria(trueDescription, falseDescription)
            .takeIf { it != DecisionsQuestionDTO.Noul.Criteria() },
    )

    is DecisionQuestion.Choice -> DecisionsQuestionDTO.Choice(instructions = instructions, criteria = options)

    is DecisionQuestion.Score -> DecisionsQuestionDTO.Score(instructions = instructions, criteria = levels)
}
