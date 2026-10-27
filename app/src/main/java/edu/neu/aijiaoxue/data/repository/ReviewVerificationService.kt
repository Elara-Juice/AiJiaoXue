package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import org.json.JSONObject

/** One validation path for saving, reopening and governance. Sources are read again inside the transaction. */
internal class ReviewVerificationService(private val db: AppDatabase) {
    private val source = db.reviewSourceDao()
    private val dao = db.issueVerificationDao()

    private data class Context(val link: ReviewLink, val origin: SupervisionTask, val followUp: SupervisionTask,
        val beforeEvaluation: Evaluation, val afterEvaluation: Evaluation, val issue: EvaluationIssue,
        val afterIssues: List<EvaluationIssue>) {
        fun snapshot(): String = JSONObject().put("schemaVersion", 1).put("chronologyRuleVersion", 1)
            .put("originTaskId", origin.id).put("followUpTaskId", followUp.id)
            .put("originEvaluationId", beforeEvaluation.id).put("followUpEvaluationId", afterEvaluation.id)
            .put("originIssueId", issue.id).put("originalIssueText", issue.description)
            .put("originalDimension", issue.dimension?.name ?: JSONObject.NULL)
            .put("originDate", origin.supervisionDate).put("originPeriod", origin.period)
            .put("followUpDate", followUp.supervisionDate).put("followUpPeriod", followUp.period).toString()
    }

    private suspend fun context(actorId: Long, link: ReviewLink): Context {
        val origin = requireNotNull(db.taskDao().getById(link.originTaskId)) { "原课堂不存在" }
        val after = requireNotNull(db.taskDao().getById(link.followUpTaskId)) { "复评课堂不存在" }
        require(origin.courseId == after.courseId && source.teacherOfCourse(origin.courseId) == link.teacherId) { "关联的课程或任课教师已不匹配" }
        require(after.type == SupervisionType.FOLLOW_UP) { "后续课堂必须是跟踪复评" }
        require(origin.status == TaskStatus.COMPLETED && after.status == TaskStatus.COMPLETED) { "两次课堂均须完成正式评价" }
        val chronology = ReviewChronologyV1.validate(origin, after)
        require(chronology == ReviewChronologyStatus.VALID) { chronology.label }
        val beforeEval = requireNotNull(source.submittedEvaluationForActor(actorId, origin.id)) { "原课堂正式评价不存在或无权访问" }
        val afterEval = requireNotNull(source.submittedEvaluationForActor(actorId, after.id)) { "复评正式评价不存在或无权访问" }
        val issue = source.submittedIssuesForActor(actorId, origin.id).firstOrNull { it.id == link.originIssueId }
        requireNotNull(issue) { "逐问题核验需关联可核对的具体原问题" }
        require(issue.description == link.originIssueSnapshot &&
            (link.originDimensionKey == null || issue.dimension == link.originDimensionKey)) { "原问题来源与关联快照不一致，需重新核对" }
        return Context(link, origin, after, beforeEval, afterEval, issue, source.submittedIssuesForActor(actorId, after.id))
    }

    suspend fun save(actorId: Long, linkId: Long, draft: VerificationDraft): VerificationReceipt = db.withTransaction {
        val link = requireNotNull(db.reviewLinkDao().getForActor(actorId, linkId)) { "复评关联不存在或无权访问" }
        require(db.userDao().getById(actorId)?.role in setOf(Role.ADMIN, Role.SUPERVISOR)) { "仅有权处理此关联的督导或管理员可以核验" }
        val context = context(actorId, link)
        val now = System.currentTimeMillis()
        val evidence = draft.evidence.mapIndexed { index, request -> freeze(actorId, context, request, index, now) }
        validate(context, draft, evidence)
        // The old pair is only a compatibility projection, never a second evidence selection algorithm.
        val primaries = evidence.filter { it.role == VerificationEvidenceRole.PRIMARY }
        val singlePair = primaries.singleOrNull()?.takeIf { it.kind == VerificationEvidenceKind.METRIC_PAIR }
        val id = dao.insert(IssueVerification(linkId = linkId, verdict = draft.verdict, basis = draft.basis.trim(),
            verifierId = actorId, verifiedAt = now, beforeSnapshotId = singlePair?.beforeSnapshotId,
            afterSnapshotId = singlePair?.afterSnapshotId, contractVersion = 2, presence = draft.presence,
            extent = draft.extent, conflictExplanation = draft.conflictExplanation?.trim()?.takeIf { it.isNotEmpty() },
            contextSnapshotJson = context.snapshot()))
        dao.insertEvidence(evidence.map { it.copy(verificationId = id) })
        VerificationReceipt(id, draft.verdict, if (evidence.isEmpty()) "证据不足，继续观察" else evidence.joinToString("；") { it.kind.label })
    }

    /** Null means eligible; legacy/invalid records remain visible with this reason. */
    suspend fun ineligibility(actorId: Long, link: ReviewLink, verification: IssueVerification,
                             evidence: List<IssueVerificationEvidence>): String? {
        if (verification.contractVersion != 2) return "历史核验，待新版证据校验"
        return try {
            requireNotNull(db.reviewLinkDao().getForActor(actorId, link.id)) { "无权访问来源" }
            require(db.userDao().getById(verification.verifierId)?.role in setOf(Role.ADMIN, Role.SUPERVISOR)) { "核验人角色无效" }
            val context = context(actorId, link)
            require(verification.contextSnapshotJson == context.snapshot()) { "正式来源或授课日期与核验时不一致" }
            val requests = evidence.map(::requestOf)
            val draft = VerificationDraft(verification.verdict, verification.presence, verification.extent,
                verification.basis, requests, verification.conflictExplanation)
            val refreshed = requests.mapIndexed { index, request -> freeze(actorId, context, request, index, verification.verifiedAt) }
            require(refreshed.size == evidence.size && refreshed.zip(evidence).all { (a, b) -> a.snapshotJson == b.snapshotJson }) { "证据来源与冻结内容不一致" }
            validate(context, draft, refreshed)
            null
        } catch (e: IllegalArgumentException) { e.message ?: "来源失效，待重新核验" }
          catch (e: org.json.JSONException) { "冻结证据格式无效，待重新核验" }
    }

    private fun validate(context: Context, draft: VerificationDraft, evidence: List<IssueVerificationEvidence>) {
        val facts = evidence.map { row ->
            val json = JSONObject(row.snapshotJson)
            VerificationEvidenceFact(row.kind, row.role, row.metricKey,
                if (row.kind == VerificationEvidenceKind.METRIC_PAIR) json.getDouble("delta") else null)
        }
        val error = VerificationRulesV2.error(draft, context.issue.dimension, facts,
            context.afterIssues.any { VerificationRules.isSimilarIssue(context.issue.description, it.description) })
        require(error == null) { error.orEmpty() }
    }

    private suspend fun freeze(actorId: Long, context: Context, request: VerificationEvidenceRequest,
                               ordinal: Int, now: Long): IssueVerificationEvidence {
        val json = JSONObject().put("schemaVersion", 1).put("sourceTaskId", context.followUp.id)
            .put("sourceEvaluationId", context.afterEvaluation.id)
        val base = IssueVerificationEvidence(verificationId = 0, ordinal = ordinal,
            kind = VerificationEvidenceKind.FOLLOW_UP_OBSERVATION, role = request.role,
            sourceTaskId = context.followUp.id, sourceEvaluationId = context.afterEvaluation.id,
            relationExplanation = request.relationExplanation.trim(), snapshotJson = "", createdAt = now)
        return when (request) {
            is VerificationEvidenceRequest.MetricPair -> {
                val before = db.metricSnapshotDao().getForTask(actorId, context.origin.id).firstOrNull { it.id == request.beforeId }
                val after = db.metricSnapshotDao().getForTask(actorId, context.followUp.id).firstOrNull { it.id == request.afterId }
                requireNotNull(before) { "前次指标不存在、无权限或不属于原课堂" }
                requireNotNull(after) { "后次指标不存在、无权限或不属于复评课堂" }
                require(before.metricKey == after.metricKey) { "必须选择同一指标的前后快照" }
                val outcome = MetricRules.compare(before.view(), after.view())
                require(outcome is MetricRules.CompareOutcome.Comparable) { "所选指标不可比" }
                json.put("before", before.json()).put("after", after.json()).put("delta", outcome.delta)
                base.copy(kind = VerificationEvidenceKind.METRIC_PAIR, beforeSnapshotId = before.id,
                    afterSnapshotId = after.id, metricKey = before.metricKey,
                    factText = "${before.metricKey.label}：${before.value} → ${after.value}", snapshotJson = json.toString())
            }
            is VerificationEvidenceRequest.FollowUpIssue -> {
                val issue = requireNotNull(context.afterIssues.firstOrNull { it.id == request.issueId }) { "所选问题不属于后续正式评价" }
                json.put("sourceIssueId", issue.id).put("description", issue.description)
                    .put("dimension", issue.dimension?.name ?: JSONObject.NULL)
                base.copy(kind = VerificationEvidenceKind.FOLLOW_UP_ISSUE, sourceIssueId = issue.id,
                    factText = issue.description, snapshotJson = json.toString())
            }
            is VerificationEvidenceRequest.NoIssuesDeclaration -> {
                val evaluation = context.afterEvaluation
                require(request.evaluationId == evaluation.id && context.afterIssues.isEmpty() &&
                    evaluation.issueDeclaration == IssueDeclaration.NO_ISSUES_CONFIRMED &&
                    !evaluation.issueDeclarationNote.isNullOrBlank() && evaluation.issueDeclaredBy != null && evaluation.issueDeclaredAt != null) {
                    "后续评价未正式确认零问题，空白列表不能作为问题消失的证据"
                }
                json.put("declaration", evaluation.issueDeclaration.name).put("note", evaluation.issueDeclarationNote)
                    .put("declaredBy", evaluation.issueDeclaredBy).put("declaredAt", evaluation.issueDeclaredAt)
                base.copy(kind = VerificationEvidenceKind.NO_ISSUES_DECLARATION,
                    factText = evaluation.issueDeclarationNote, snapshotJson = json.toString())
            }
            is VerificationEvidenceRequest.FollowUpObservation -> {
                require(request.evaluationId == context.afterEvaluation.id) { "补充观察必须关联本次正式复评评价" }
                require(request.text.isNotBlank() && request.text.length <= 2000) { "请填写2000字以内的实际观察事实" }
                require((request.startAt == null) == (request.endAt == null)) { "观察区间起止必须同时填写；留空表示本次整堂课" }
                if (request.startAt != null && request.endAt != null) {
                    val start = requireNotNull(context.followUp.classStartAt) { "未记录课堂采集时间，无法绑定观察区间" }
                    val end = requireNotNull(context.followUp.classEndAt) { "未记录课堂采集时间，无法绑定观察区间" }
                    require(request.startAt >= start && request.endAt <= end && request.endAt > request.startAt) { "观察区间必须位于复评课堂记录范围内" }
                }
                json.put("text", request.text.trim()).put("scopeStartAt", request.startAt ?: JSONObject.NULL)
                    .put("scopeEndAt", request.endAt ?: JSONObject.NULL)
                base.copy(factText = request.text.trim(), scopeStartAt = request.startAt, scopeEndAt = request.endAt,
                    snapshotJson = json.toString())
            }
        }
    }

    private fun requestOf(row: IssueVerificationEvidence): VerificationEvidenceRequest = when (row.kind) {
        VerificationEvidenceKind.METRIC_PAIR -> VerificationEvidenceRequest.MetricPair(
            requireNotNull(row.beforeSnapshotId) { "前次指标来源已失效" }, requireNotNull(row.afterSnapshotId) { "后次指标来源已失效" }, row.role, row.relationExplanation)
        VerificationEvidenceKind.FOLLOW_UP_ISSUE -> VerificationEvidenceRequest.FollowUpIssue(
            requireNotNull(row.sourceIssueId) { "复评问题来源已失效" }, row.role, row.relationExplanation)
        VerificationEvidenceKind.NO_ISSUES_DECLARATION -> VerificationEvidenceRequest.NoIssuesDeclaration(
            requireNotNull(row.sourceEvaluationId) { "零问题声明来源已失效" }, row.role, row.relationExplanation)
        VerificationEvidenceKind.FOLLOW_UP_OBSERVATION -> VerificationEvidenceRequest.FollowUpObservation(
            requireNotNull(row.sourceEvaluationId) { "观察所关联的正式评价已失效" }, requireNotNull(row.factText), row.scopeStartAt, row.scopeEndAt, row.role, row.relationExplanation)
    }

    private fun MetricSnapshot.view() = MetricSnapshotView(metricKey, value, unit, denominator, sampling, methodVersion, scopeStartAt, scopeEndAt)
    private fun MetricSnapshot.json() = JSONObject().put("id", id).put("taskId", taskId)
        .put("metricKey", metricKey.name).put("value", value ?: JSONObject.NULL).put("unit", unit)
        .put("denominator", denominator).put("denominatorValue", denominatorValue ?: JSONObject.NULL)
        .put("sampling", sampling).put("methodVersion", methodVersion).put("sampleCount", sampleCount)
        .put("scopeStartAt", scopeStartAt ?: JSONObject.NULL).put("scopeEndAt", scopeEndAt ?: JSONObject.NULL)
}
