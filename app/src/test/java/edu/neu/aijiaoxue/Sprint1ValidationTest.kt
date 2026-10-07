package edu.neu.aijiaoxue

import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.*
import edu.neu.aijiaoxue.ui.improve.planError
import org.junit.Assert.*
import org.junit.Test

class Sprint1ValidationTest {
    private val evaluation = Evaluation(taskId = 1, contentScore = 5, designScore = 4,
        interactionScore = 3, engagementScore = 4, overallOpinion = "总体良好")
    private val draft = EvaluationDraft(evaluation, listOf(EvaluationIssue(evaluationId = 0, description = "互动较少")))
    private val item = ImprovementItem(id = 1, planId = 1, source = ItemSource.TEACHER, description = "增加互动", priority = Priority.HIGH)
    private val measure = ImprovementMeasure(planId = 1, itemId = 1, content = "每节课增加一次讨论", source = MeasureSource.ADDED, dueDate = "2026-10-15")

    @Test fun missingScoreNamesTheDimension() {
        assertEquals("请完成评分：教学设计", evaluationError(draft.copy(evaluation = evaluation.copy(designScore = null))))
    }
    @Test fun validEvaluationHasNoValidationError() { assertNull(evaluationError(draft)) }
    @Test fun blankAndMissingIssuesCannotSubmit() {
        assertNotNull(evaluationError(draft.copy(issues = emptyList())))
        assertNotNull(evaluationError(draft.copy(issues = draft.issues + EvaluationIssue(evaluationId = 0, description = "  "))))
    }
    @Test fun whitespaceOpinionCannotSubmit() {
        assertNotNull(evaluationError(draft.copy(evaluation = evaluation.copy(overallOpinion = " \n "))))
    }
    @Test fun dateMustBeRealAndCanonical() {
        assertTrue(validDate("2028-02-29"))
        listOf("2026-02-29", "2026-04-31", "2026-1-2", "", "tomorrow").forEach { assertFalse(it, validDate(it)) }
    }
    @Test fun eachFocusNeedsAMeasure() {
        assertNotNull(planError(listOf(item, item.copy(id = 2)), listOf(measure)))
    }
    @Test fun planRejectsBlankContentAndMissingOrInvalidDate() {
        assertNotNull(planError(listOf(item), listOf(measure.copy(content = " "))))
        assertNotNull(planError(listOf(item), listOf(measure.copy(dueDate = ""))))
        assertNotNull(planError(listOf(item), listOf(measure.copy(dueDate = "2026-02-30"))))
    }
    @Test fun teacherAddedMeasureCanConfirmWithoutReferenceSuggestion() {
        assertNull(planError(listOf(item), listOf(measure)))
    }
}
