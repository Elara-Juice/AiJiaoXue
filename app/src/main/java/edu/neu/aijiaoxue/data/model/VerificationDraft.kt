package edu.neu.aijiaoxue.data.model

data class VerificationDraft(
    val verdict: VerificationVerdict,
    val presence: IssuePresence,
    val extent: ImprovementExtent,
    val basis: String,
    val evidence: List<VerificationEvidenceRequest> = emptyList(),
    val conflictExplanation: String? = null,
)

sealed interface VerificationEvidenceRequest {
    val role: VerificationEvidenceRole
    val relationExplanation: String

    data class MetricPair(val beforeId: Long, val afterId: Long,
        override val role: VerificationEvidenceRole,
        override val relationExplanation: String) : VerificationEvidenceRequest
    data class FollowUpIssue(val issueId: Long,
        override val role: VerificationEvidenceRole,
        override val relationExplanation: String) : VerificationEvidenceRequest
    data class NoIssuesDeclaration(val evaluationId: Long,
        override val role: VerificationEvidenceRole,
        override val relationExplanation: String) : VerificationEvidenceRequest
    /** Null times explicitly mean the whole dated lesson; non-null times are epoch milliseconds. */
    data class FollowUpObservation(val evaluationId: Long, val text: String,
        val startAt: Long? = null, val endAt: Long? = null,
        override val role: VerificationEvidenceRole,
        override val relationExplanation: String) : VerificationEvidenceRequest
}

data class VerificationEvidenceFact(
    val kind: VerificationEvidenceKind,
    val role: VerificationEvidenceRole,
    val metricKey: MetricKey? = null,
    val delta: Double? = null,
)

/** Business validation used both before insertion and when determining governance eligibility. */
object VerificationRulesV2 {
    fun primaryMetric(dimension: EvalDimension?): MetricKey? = when (dimension) {
        EvalDimension.INTERACTION -> MetricKey.INTERACTION_DENSITY
        EvalDimension.ENGAGEMENT -> MetricKey.ENGAGEMENT_RATE
        EvalDimension.DESIGN -> MetricKey.EVALUATION_DESIGN
        else -> null
    }

    fun error(draft: VerificationDraft, dimension: EvalDimension?, facts: List<VerificationEvidenceFact>,
              similarIssuePresent: Boolean = false): String? {
        if (draft.basis.isBlank()) return "请填写核验依据"
        if (draft.basis.length > VerificationRules.MAX_BASIS_LENGTH) return "核验依据不能超过${VerificationRules.MAX_BASIS_LENGTH}字"
        if (draft.evidence.size > 30) return "一次核验最多选择30项证据"
        if (draft.evidence.any { it.relationExplanation.isBlank() || it.relationExplanation.length > 500 }) {
            return "每项证据需填写500字以内的原问题关联说明"
        }
        if (facts.any { it.role == VerificationEvidenceRole.PRIMARY && it.kind == VerificationEvidenceKind.METRIC_PAIR &&
                    (it.metricKey == MetricKey.EVALUATION_TOTAL || it.metricKey != primaryMetric(dimension)) }) {
            return "总分和无关维度指标只能作为辅助依据"
        }
        if (draft.verdict == VerificationVerdict.CONTINUE_OBSERVING) {
            return if (draft.extent != ImprovementExtent.UNDETERMINED) "继续观察的改善程度应为尚不能确定" else null
        }
        val primary = facts.filter { it.role == VerificationEvidenceRole.PRIMARY }
        if (primary.isEmpty()) return "证据不足：请选择与原问题相关的主要依据，或保留继续观察"
        val observation = primary.any { it.kind == VerificationEvidenceKind.FOLLOW_UP_OBSERVATION }
        val noIssues = primary.any { it.kind == VerificationEvidenceKind.NO_ISSUES_DECLARATION }
        val issueFact = primary.any { it.kind == VerificationEvidenceKind.FOLLOW_UP_ISSUE }
        val contradictoryMetric = facts.any { it.role == VerificationEvidenceRole.PRIMARY && it.delta != null &&
            if (draft.verdict == VerificationVerdict.IMPROVED) it.delta <= 0 else it.delta > 0 }
        when (draft.verdict) {
            VerificationVerdict.IMPROVED -> {
                if (draft.presence == IssuePresence.UNDETERMINED || draft.extent !in setOf(ImprovementExtent.PARTIAL, ImprovementExtent.RESOLVED)) {
                    return "改善结论需明确原问题现状及部分改善/本次已解决"
                }
                if (draft.extent == ImprovementExtent.RESOLVED &&
                    (draft.presence != IssuePresence.NOT_OBSERVED || (!observation && !noIssues))) {
                    return "本次已解决需逐问题确认未再观察到，并提供观察事实或正式零问题声明"
                }
                // Similar wording is a UI hint only; it must not force two different issues to be identical.
                val conflict = draft.presence == IssuePresence.PRESENT || issueFact || contradictoryMetric
                if (conflict && (!observation || draft.conflictExplanation.isNullOrBlank())) {
                    return "原问题仍存在或指标与结论冲突：请补充观察事实和冲突说明"
                }
                if (conflict && draft.extent != ImprovementExtent.PARTIAL) return "存在未解释消除的冲突时只能记录部分改善或继续观察"
            }
            VerificationVerdict.NOT_IMPROVED -> {
                if (draft.presence != IssuePresence.PRESENT || draft.extent != ImprovementExtent.NONE) return "未改善需确认原问题仍存在、改善程度为未改善"
                if ((contradictoryMetric || noIssues) && (!observation || draft.conflictExplanation.isNullOrBlank())) {
                    return "所选依据与未改善结论冲突，请补充观察事实和冲突说明"
                }
            }
            else -> Unit
        }
        return null
    }
}
