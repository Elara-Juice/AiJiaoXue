package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.analysis.AnomalyInterval
import edu.neu.aijiaoxue.data.analysis.ParticipationAnalysis
import edu.neu.aijiaoxue.data.analysis.ParticipationInsight
import edu.neu.aijiaoxue.data.analysis.ParticipationInput
import edu.neu.aijiaoxue.data.analysis.PhaseSegment
import edu.neu.aijiaoxue.data.dao.ReviewTaskCandidateRow
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.ImprovementExecution
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.entity.IssueVerification
import edu.neu.aijiaoxue.data.entity.MetricSnapshot
import edu.neu.aijiaoxue.data.entity.ReviewLink
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.IssueVerificationEvidence
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import edu.neu.aijiaoxue.data.model.MetricKey
import edu.neu.aijiaoxue.data.model.MetricRules
import edu.neu.aijiaoxue.data.model.MetricSnapshotView
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.VerificationVerdict
import edu.neu.aijiaoxue.data.model.VerificationRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Sprint 3（李奕萱）：US45 历次指标变化、US46 重点问题改善核验、US24/25 参与分析的数据入口。
 *
 * 业务约束（来自 Sprint 3 每日任务表“业务正确性要点”）：
 * - 指标快照只追加不覆盖：同一 (taskId, metricKey, methodVersion) 已有记录时忽略新值；
 * - 缺测不补零：value 为 null 表示缺测，不参与差值计算；
 * - 不可比不强行计算：单位、分母、采样方式、方法版本、课堂时长不一致时只给原因；
 * - 「措施已完成」不等于「问题已改善」：改善结论必须有人工判断、依据、核验人和时间；
 * - 证据不足时保留「继续观察」；
 * - 未发布的复评结果不进入正式比较。
 */

/** 一次指标比较的结果。 */
data class MetricComparison(
    val metricKey: MetricKey,
    val before: MetricSnapshot?,
    val after: MetricSnapshot?,
    val outcome: MetricRules.CompareOutcome?,
    /** 不可比较或缺数据时的说明。 */
    val note: String?,
)

/** 两次课堂的比较结果。 */
data class ComparisonResult(
    val beforeTaskId: Long,
    val afterTaskId: Long,
    val comparisons: List<MetricComparison>,
    val note: String?,
) {
    val comparableCount: Int get() = comparisons.count { it.outcome is MetricRules.CompareOutcome.Comparable }
}

/** 复评关联的完整详情。 */
data class ReviewLinkDetail(
    val link: ReviewLink,
    val originTask: SupervisionTask?,
    val followUpTask: SupervisionTask?,
    val originIssues: List<EvaluationIssue>,
    val followUpIssues: List<EvaluationIssue>,
    val measures: List<ImprovementMeasure>,
    val executions: List<ImprovementExecution>,
    val comparison: ComparisonResult?,
    val verifications: List<IssueVerification>,
    val followUpEvaluation: Evaluation? = null,
    val evidence: List<IssueVerificationEvidence> = emptyList(),
    val verificationWarnings: Map<Long, String> = emptyMap(),
    val canVerify: Boolean = false,
    val canOpenOriginParticipation: Boolean = false,
    val canOpenFollowUpParticipation: Boolean = false,
)

/** 保存核验后的回执。 */
data class VerificationReceipt(
    val id: Long,
    val verdict: VerificationVerdict,
    /** 本次判断所依据的证据说明。 */
    val evidence: String,
)

class ReviewRepository(private val db: AppDatabase) {

    private val linkDao = db.reviewLinkDao()
    private val snapshotDao = db.metricSnapshotDao()
    private val verificationDao = db.issueVerificationDao()
    private val sourceDao = db.reviewSourceDao()

    /**
     * 建立复评关联：把原课堂（原问题）与后续复评任务绑定。
     * 只有后续复评课堂的督导本人（或教学管理人员）可以建立；原课堂必须已完成且已提交评价。
     */
    suspend fun createLink(
        actorId: Long,
        followUpTaskId: Long,
        originTaskId: Long,
        originIssueId: Long? = null,
    ): Long = withContext(Dispatchers.IO) { db.withTransaction {
        if (originTaskId == followUpTaskId) error("原课堂与复评课堂不能是同一次课堂")
        val followUp = sourceDao.followUpTaskForActor(actorId, followUpTaskId)
            ?: error("只能为本人督导的课堂建立复评关联")
        if (followUp.type != SupervisionType.FOLLOW_UP) error("复评课堂的督导类型应为“跟踪复评”")
        val origin = sourceDao.originTaskForActor(actorId, originTaskId)
            ?: error("无权读取该原课堂的正式结果")
        if (origin.courseId != followUp.courseId) error("原课堂与复评课堂必须属于同一门课程")
        require(origin.status == TaskStatus.COMPLETED) { "原课堂尚未完成" }
        val chronology = ReviewChronologyV1.validate(origin, followUp)
        require(chronology == ReviewChronologyStatus.VALID) { chronology.label }
        val originEvaluation = sourceDao.submittedEvaluationForActor(actorId, originTaskId)
            ?: error("原课堂还没有已提交的正式评价，不能建立复评关联")
        val issues = sourceDao.submittedIssuesForActor(actorId, originTaskId)
        val issue = originIssueId?.let { id -> issues.firstOrNull { it.id == id } }
        if (originIssueId != null && issue == null) error("原问题条目不存在或不在已提交评价中")
        val snapshotText = issue?.description ?: "整堂课堂复评（原评价共 ${issues.size} 条问题）"
        val teacherId = sourceDao.teacherOfCourse(origin.courseId) ?: error("课程没有任课教师")
        val link = ReviewLink(
            originTaskId = originTaskId,
            originIssueId = issue?.id,
            followUpTaskId = followUpTaskId,
            teacherId = teacherId,
            originIssueSnapshot = snapshotText,
            originDimension = issue?.dimension?.label,
            originDimensionKey = issue?.dimension,
            chronologyStatus = chronology,
            chronologyRuleVersion = ReviewChronologyV1.VERSION,
            createdBy = actorId,
        )
        val id = linkDao.insert(link)
        if (id == -1L) error("该原问题与复评课堂的关联已存在")
        id
    } }

    /** 读取复评关联详情（含原问题、实施记录、正式结果、比较与核验历史）。 */
    suspend fun linkDetail(actorId: Long, linkId: Long): ReviewLinkDetail? = withContext(Dispatchers.IO) {
        val link = linkDao.getForActor(actorId, linkId) ?: return@withContext null
        val originTask = db.taskDao().getById(link.originTaskId)
        val followUpTask = db.taskDao().getById(link.followUpTaskId)
        val originIssues = sourceDao.submittedIssuesForActor(actorId, link.originTaskId)
        val followUpIssues = sourceDao.submittedIssuesForActor(actorId, link.followUpTaskId)
        val measures = sourceDao.originMeasuresForLink(actorId, linkId)
        val executions = sourceDao.originExecutionsForLink(actorId, linkId)
        val comparison = compare(actorId, link.originTaskId, link.followUpTaskId)
        val verifications = verificationDao.getForLink(actorId, linkId)
        val evidence = verificationDao.evidenceForLink(actorId, linkId)
        val warnings = mutableMapOf<Long, String>()
        val service = ReviewVerificationService(db)
        for (verification in verifications) {
            service.ineligibility(actorId, link, verification, evidence.filter { it.verificationId == verification.id })
                ?.let { warnings[verification.id] = it }
        }
        ReviewLinkDetail(
            link = link,
            originTask = originTask,
            followUpTask = followUpTask,
            originIssues = originIssues,
            followUpIssues = followUpIssues,
            measures = measures,
            executions = executions,
            comparison = comparison,
            verifications = verifications,
            followUpEvaluation = sourceDao.submittedEvaluationForActor(actorId, link.followUpTaskId),
            evidence = evidence,
            verificationWarnings = warnings,
            canVerify = db.userDao().getById(actorId)?.role in setOf(Role.ADMIN, Role.SUPERVISOR),
            canOpenOriginParticipation = sourceDao.followUpTaskForActor(actorId, link.originTaskId) != null,
            canOpenFollowUpParticipation = sourceDao.followUpTaskForActor(actorId, link.followUpTaskId) != null,
        )
    }

    /**
     * US45：两次课堂的指标比较。
     * 复评正式结果未发布时不进入正式比较；缺测与口径不一致只给原因，不给差值。
     */
    suspend fun compare(actorId: Long, beforeTaskId: Long, afterTaskId: Long): ComparisonResult =
        withContext(Dispatchers.IO) {
            val origin = sourceDao.originTaskForActor(actorId, beforeTaskId)
            val followUp = sourceDao.originTaskForActor(actorId, afterTaskId)
            val originEvaluation = sourceDao.submittedEvaluationForActor(actorId, beforeTaskId)
            val followUpEvaluation = sourceDao.submittedEvaluationForActor(actorId, afterTaskId)
            if (origin == null || followUp == null || originEvaluation == null || followUpEvaluation == null ||
                origin.status != TaskStatus.COMPLETED || followUp.status != TaskStatus.COMPLETED) {
                return@withContext ComparisonResult(
                    beforeTaskId, afterTaskId, emptyList(),
                    "复评课堂的正式结果尚未发布，暂不进入正式比较",
                )
            }
            val chronology = ReviewChronologyV1.validate(origin, followUp)
            if (origin.courseId != followUp.courseId || chronology != ReviewChronologyStatus.VALID) {
                return@withContext ComparisonResult(beforeTaskId, afterTaskId, emptyList(),
                    if (origin.courseId != followUp.courseId) "不同课程不能比较" else chronology.label)
            }
            val before = snapshotDao.getForTask(actorId, beforeTaskId)
            val after = snapshotDao.getForTask(actorId, afterTaskId)
            val comparisons = MetricKey.entries.map { key ->
                val beforeList = before.filter { it.metricKey == key }
                val afterList = after.filter { it.metricKey == key }
                val beforeSnapshot = beforeList.maxByOrNull { it.methodVersion }
                val afterSnapshot = afterList.maxByOrNull { it.methodVersion }
                when {
                    beforeSnapshot == null && afterSnapshot == null ->
                        MetricComparison(key, null, null, null, "两次课堂都没有该指标的正式快照")
                    beforeSnapshot == null ->
                        MetricComparison(key, null, afterSnapshot, null, "缺少前次课堂的正式指标快照")
                    afterSnapshot == null ->
                        MetricComparison(key, beforeSnapshot, null, null, "缺少第二次有效课堂的正式指标快照")
                    else -> {
                        val common = beforeList.map { it.methodVersion }
                            .intersect(afterList.map { it.methodVersion }.toSet())
                            .maxOrNull()
                        val (b, a) = if (common != null) {
                            beforeList.first { it.methodVersion == common } to
                                afterList.first { it.methodVersion == common }
                        } else {
                            beforeSnapshot to afterSnapshot
                        }
                        val outcome = MetricRules.compare(b.toView(), a.toView())
                        MetricComparison(
                            key, b, a, outcome,
                            if (outcome is MetricRules.CompareOutcome.NotComparable) outcome.reason.message else null,
                        )
                    }
                }
            }
            ComparisonResult(beforeTaskId, afterTaskId, comparisons, null)
        }

    /**
     * 保存正式指标快照。只追加不覆盖：已存在的 (taskId, metricKey, methodVersion) 保留原值，
     * 后续重分析不会改写历史正式快照。
     */
    suspend fun captureSnapshots(actorId: Long, taskId: Long): Int = withContext(Dispatchers.IO) {
        val task = sourceDao.followUpTaskForActor(actorId, taskId) ?: return@withContext 0
        if (task.status != TaskStatus.COMPLETED) return@withContext 0
        // 正式评价提交前不能占用不可覆盖的快照唯一键，否则缺测值会阻塞正式值。
        if (sourceDao.submittedEvaluationForActor(actorId, taskId) == null) return@withContext 0
        val snapshots = buildSnapshots(actorId, task)
        var inserted = 0
        snapshots.forEach { snapshot ->
            val id = snapshotDao.insert(snapshot)
            if (id != -1L) inserted++
        }
        inserted
    }

    /**
     * US46：保存逐问题核验。
     * 「措施已完成」不自动等于「问题已改善」：改善/未改善必须有可检查的证据，
     * 证据不足时保留「继续观察」。
     */
    suspend fun saveVerification(
        actorId: Long, linkId: Long, verdict: VerificationVerdict, basis: String,
    ): VerificationReceipt {
        require(verdict == VerificationVerdict.CONTINUE_OBSERVING) { "请选择原问题状态及证据" }
        return saveVerification(actorId, linkId, VerificationDraft(verdict, IssuePresence.UNDETERMINED,
            ImprovementExtent.UNDETERMINED, basis))
    }

    suspend fun saveVerification(actorId: Long, linkId: Long, draft: VerificationDraft): VerificationReceipt =
        withContext(Dispatchers.IO) { ReviewVerificationService(db).save(actorId, linkId, draft) }

    /** 复评关联列表（权限在 SQL 层判断）。 */
    fun observeLinks(actorId: Long) = sourceDao.observeLinkRows(actorId)

    /** 可建立复评关联的跟踪复评课堂。 */
    fun observeFollowUpTasks(actorId: Long) = sourceDao.observeFollowUpTasks(actorId)

    /** 原课堂候选：同一门课程的已完成且已提交评价的课堂。 */
    suspend fun originCandidates(actorId: Long, followUpTaskId: Long): List<ReviewTaskCandidateRow> =
        withContext(Dispatchers.IO) {
            val followUp = sourceDao.followUpTaskForActor(actorId, followUpTaskId) ?: return@withContext emptyList()
            sourceDao.originCandidates(actorId, followUp.courseId, followUpTaskId).filter { candidate ->
                ReviewChronologyV1.validate(candidate.id, candidate.supervisionDate, candidate.period,
                    followUp.id, followUp.supervisionDate, followUp.period) == ReviewChronologyStatus.VALID
            }
        }

    /** 原课堂正式评价中的问题条目。 */
    suspend fun originIssues(actorId: Long, originTaskId: Long): List<EvaluationIssue> =
        withContext(Dispatchers.IO) { sourceDao.submittedIssuesForActor(actorId, originTaskId) }

    /**
     * US24/US25：多源参与分析与参与异常区间定位。
     * 只有该课堂的督导本人或教学管理人员可以读取课堂原始采集数据。
     */
    suspend fun participationInsight(actorId: Long, taskId: Long): ParticipationInsight =
        withContext(Dispatchers.IO) {
            val task = sourceDao.followUpTaskForActor(actorId, taskId)
                ?: error("只有课堂督导本人或教学管理人员可以查看课堂采集数据")
            val engagements = sourceDao.engagementsForActor(actorId, taskId)
            val events = sourceDao.eventsForActor(actorId, taskId)
            val materials = sourceDao.materialsForActor(actorId, taskId)
            val evaluation = sourceDao.submittedEvaluationForActor(actorId, taskId)
            val issues = sourceDao.submittedIssuesForActor(actorId, taskId)
            val phases = EffectivePhaseProvider(db).load(actorId, taskId)
            val insight = ParticipationAnalysis.analyze(
                ParticipationInput(
                    classStartAt = task.classStartAt,
                    classEndAt = task.classEndAt,
                    engagements = engagements,
                    events = events,
                    materials = materials,
                    evaluation = evaluation,
                    evaluationIssues = issues,
                    phases = phases,
                )
            )
            insight
        }

    /** 计算一次课堂的正式指标快照（口径、单位、分母、采样方式、方法版本一并写入）。 */
    private suspend fun buildSnapshots(actorId: Long, task: SupervisionTask): List<MetricSnapshot> {
        val engagements = sourceDao.engagementsForActor(actorId, task.id)
        val events = sourceDao.eventsForActor(actorId, task.id)
        val evaluation = sourceDao.submittedEvaluationForActor(actorId, task.id)
        val now = System.currentTimeMillis()
        val scopeStart = task.classStartAt
        val scopeEnd = task.classEndAt
        val result = mutableListOf<MetricSnapshot>()

        // US45：学生参与率。缺测时 value 为 null，不按 0 处理。
        val engagementSpec = MetricRules.spec(MetricKey.ENGAGEMENT_RATE)
        val counted = engagements.filter { it.engagedCount != null && (it.presentCount ?: 0) > 0 }
        val percentOnly = engagements.filter { it.engagedPercent != null && it.engagedCount == null }
        val engagementValue: Double?
        val engagementDenominator: String
        val engagementSample: Int
        if (counted.isNotEmpty()) {
            val engagedSum = counted.sumOf { it.engagedCount!!.toDouble() }
            val presentSum = counted.sumOf { it.presentCount!!.toDouble() }
            engagementValue = if (presentSum > 0) engagedSum * 100.0 / presentSum else null
            engagementDenominator = MetricRules.DENOMINATOR_PRESENT
            engagementSample = counted.size
        } else if (percentOnly.isNotEmpty()) {
            engagementValue = percentOnly.mapNotNull { it.engagedPercent }.average()
            engagementDenominator = MetricRules.DENOMINATOR_PERCENT_RECORDS
            engagementSample = percentOnly.size
        } else {
            engagementValue = null
            engagementDenominator = engagementSpec.denominatorLabel
            engagementSample = 0
        }
        result += MetricSnapshot(
            taskId = task.id,
            metricKey = MetricKey.ENGAGEMENT_RATE,
            value = engagementValue,
            unit = engagementSpec.unit,
            denominator = engagementDenominator,
            denominatorValue = counted.sumOf { it.presentCount ?: 0 }.toDouble(),
            sampling = engagementSpec.sampling,
            methodVersion = engagementSpec.methodVersion,
            sampleCount = engagementSample,
            scopeStartAt = scopeStart,
            scopeEndAt = scopeEnd,
            createdAt = now,
        )

        // US45：师生互动密度（每 10 分钟互动次数）。没有互动记录或没有课堂时长都按缺测处理。
        val interactionSpec = MetricRules.spec(MetricKey.INTERACTION_DENSITY)
        val durationMs = if (scopeStart != null && scopeEnd != null && scopeEnd > scopeStart) scopeEnd - scopeStart else null
        val interactionValue = if (events.isEmpty() || durationMs == null) null
        else events.size * 10.0 / (durationMs / 60_000.0)
        result += MetricSnapshot(
            taskId = task.id,
            metricKey = MetricKey.INTERACTION_DENSITY,
            value = interactionValue,
            unit = interactionSpec.unit,
            denominator = interactionSpec.denominatorLabel,
            denominatorValue = durationMs?.let { it / 60_000.0 },
            sampling = interactionSpec.sampling,
            methodVersion = interactionSpec.methodVersion,
            sampleCount = events.size,
            scopeStartAt = scopeStart,
            scopeEndAt = scopeEnd,
            createdAt = now,
        )

        // US45：督导评价总分。只有已提交的正式评价才作为正式结果。
        val totalSpec = MetricRules.spec(MetricKey.EVALUATION_TOTAL)
        result += MetricSnapshot(
            taskId = task.id,
            metricKey = MetricKey.EVALUATION_TOTAL,
            value = evaluation?.totalScore?.toDouble(),
            unit = totalSpec.unit,
            denominator = totalSpec.denominatorLabel,
            denominatorValue = 100.0,
            sampling = totalSpec.sampling,
            methodVersion = totalSpec.methodVersion,
            sampleCount = if (evaluation == null) 0 else 1,
            scopeStartAt = scopeStart,
            scopeEndAt = scopeEnd,
            createdAt = now,
        )
        val designSpec = MetricRules.spec(MetricKey.EVALUATION_DESIGN)
        evaluation?.submittedAt?.let { submittedAt ->
            result += MetricSnapshot(taskId = task.id, metricKey = MetricKey.EVALUATION_DESIGN,
                value = evaluation.designScore?.toDouble(), unit = designSpec.unit,
                denominator = designSpec.denominatorLabel, denominatorValue = 1.0, sampling = designSpec.sampling,
                methodVersion = designSpec.methodVersion, sampleCount = if (evaluation.designScore == null) 0 else 1,
                scopeStartAt = scopeStart, scopeEndAt = scopeEnd, createdAt = submittedAt)
        }
        return result
    }

    private fun MetricSnapshot.toView() = MetricSnapshotView(
        metricKey = metricKey,
        value = value,
        unit = unit,
        denominator = denominator,
        sampling = sampling,
        methodVersion = methodVersion,
        scopeStartAt = scopeStartAt,
        scopeEndAt = scopeEndAt,
    )
}
