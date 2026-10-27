package edu.neu.aijiaoxue.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import edu.neu.aijiaoxue.data.model.MetricRules
import edu.neu.aijiaoxue.data.model.VerificationVerdict
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.MetricComparison
import edu.neu.aijiaoxue.ui.evaluation.MessageCard
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard
import edu.neu.aijiaoxue.ui.evaluation.timeText

/**
 * Sprint 3（李奕萱）US45/US46：复评关联详情。
 * 页面只做展示与录入，判定规则在 Rules.kt，权限在数据查询层。
 */
@Composable
fun ReviewDetailScreen(
    model: ReviewDetailViewModel,
    onBack: () -> Unit,
    onOpenParticipation: (Long) -> Unit,
) {
    val state by model.uiState.collectAsState()
    val detail = state.detail

    ModulePage("复评关联详情", onBack) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { MessageCard(it) }
            state.error?.let { MessageCard(it, error = true) }
            if (state.loading) {
                Text("加载中…")
                return@Column
            }
            if (detail == null) {
                Text("复评关联不存在或无权访问")
                return@Column
            }

            SectionCard("原课堂与复评课堂") {
                Text("原问题：${detail.link.originIssueSnapshot}")
                detail.link.originDimension?.let { Text("原问题维度：$it") }
                Text("原课堂：${detail.originTask?.supervisionDate ?: "未记录"} 第${detail.originTask?.period ?: "-"}节")
                Text("复评课堂：${detail.followUpTask?.supervisionDate ?: "未记录"} 第${detail.followUpTask?.period ?: "-"}节")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.canOpenOriginParticipation) OutlinedButton(onClick = { onOpenParticipation(detail.link.originTaskId) }) { Text("原课堂参与分析") }
                    if (detail.canOpenFollowUpParticipation) OutlinedButton(onClick = { onOpenParticipation(detail.link.followUpTaskId) }) { Text("复评课堂参与分析") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.captureSnapshots(detail.link.originTaskId) },
                        enabled = !state.capturing && detail.canOpenOriginParticipation) { Text("保存原课堂指标快照") }
                    OutlinedButton(onClick = { model.captureSnapshots(detail.link.followUpTaskId) },
                        enabled = !state.capturing && detail.canOpenFollowUpParticipation) { Text("保存复评课堂指标快照") }
                }
            }

            SectionCard("原课堂改进措施与实施记录") {
                if (detail.measures.isEmpty()) {
                    Text("原课堂没有确认的改进措施")
                } else {
                    detail.measures.forEach { measure ->
                        Text("措施：${measure.content}（状态：${measure.status.label}）",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (detail.executions.isEmpty()) {
                    Text("尚无实施记录（措施勾选完成不等于问题已改善）")
                } else {
                    detail.executions.forEach { execution ->
                        Text("实施：${execution.actualAdjustment}", style = MaterialTheme.typography.bodySmall)
                        Text("时间：${timeText(execution.implementedAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionCard("复评正式结果") {
                if (detail.followUpIssues.isEmpty()) {
                    Text(if (detail.followUpEvaluation?.issueDeclaration == IssueDeclaration.NO_ISSUES_CONFIRMED)
                        "本次正式评价已明确确认无主要问题：${detail.followUpEvaluation.issueDeclarationNote}"
                    else "没有问题条目不代表问题已消失；尚无显式零问题声明。")
                } else {
                    detail.followUpIssues.forEach { issue ->
                        Text("· ${issue.description}" + (issue.dimension?.let { "（$it）" } ?: ""))
                    }
                }
            }

            SectionCard("前后指标比较（US45）") {
                detail.comparison?.note?.let { MessageCard(it) }
                val comparisons = detail.comparison?.comparisons.orEmpty()
                if (comparisons.isEmpty()) {
                    Text("暂无可比较的指标")
                } else {
                    comparisons.forEach { item -> ComparisonRow(item) }
                }
            }

            SectionCard("核验记录（US46）") {
                if (detail.verifications.isEmpty()) {
                    Text("还没有核验记录")
                } else {
                    detail.verifications.forEach { verification ->
                        Text("判断：${verification.verdict.label}", style = MaterialTheme.typography.titleSmall)
                        Text("依据：${verification.basis}", style = MaterialTheme.typography.bodySmall)
                        Text("核验人：${verification.verifierId}　核验时间：${timeText(verification.verifiedAt)}",
                            style = MaterialTheme.typography.bodySmall)
                        detail.verificationWarnings[verification.id]?.let { MessageCard("待重新核验：$it", true) }
                        if (verification.contractVersion == 2) {
                            Text("现状：${verification.presence.label}；程度：${verification.extent.label}")
                            verification.conflictExplanation?.let { Text("冲突说明：$it") }
                            detail.evidence.filter { it.verificationId == verification.id }.forEach { evidence ->
                                Text("${evidence.role.label} · ${evidence.kind.label}：${evidence.factText.orEmpty()}")
                                Text("与原问题的关联：${evidence.relationExplanation}", style = MaterialTheme.typography.bodySmall)
                                Text("来源：课堂${evidence.sourceTaskId ?: "已失效"} / 评价${evidence.sourceEvaluationId ?: "已失效"}" +
                                    (evidence.beforeSnapshotId?.let { " / 指标快照 $it → ${evidence.afterSnapshotId}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            if (detail.canVerify && detail.link.originIssueId != null) SectionCard("保存核验结论") {
                Text("「措施已完成」不自动等于「问题已改善」，需要人工判断、依据、核验人和时间。")
                Text("原问题当前状态")
                IssuePresence.entries.forEach { value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.presence == value, onClick = { model.setPresence(value) })
                        Text(value.label)
                    }
                }
                Text("改善程度")
                ImprovementExtent.entries.forEach { value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.extent == value, onClick = { model.setExtent(value) })
                        Text(value.label)
                    }
                }
                Text("请选择本次实际采用的证据；总分只能辅助。")
                detail.comparison?.comparisons.orEmpty().filter { it.outcome is MetricRules.CompareOutcome.Comparable }.forEach { pair ->
                    val selected = state.metricRoles[pair.metricKey]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected != null, onCheckedChange = { checked ->
                            model.setMetric(pair.metricKey, if (!checked) null else VerificationEvidenceRole.AUXILIARY)
                        })
                        Text(pair.metricKey.label)
                    }
                    if (selected != null) {
                        val canPrimary = pair.metricKey == VerificationRulesV2.primaryMetric(detail.link.originDimensionKey)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == VerificationEvidenceRole.PRIMARY,
                                onClick = { model.setMetric(pair.metricKey, VerificationEvidenceRole.PRIMARY) }, enabled = canPrimary)
                            Text("主要依据")
                            RadioButton(selected == VerificationEvidenceRole.AUXILIARY,
                                onClick = { model.setMetric(pair.metricKey, VerificationEvidenceRole.AUXILIARY) })
                            Text("辅助依据")
                        }
                    }
                }
                detail.followUpIssues.forEach { issue ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(issue.id in state.selectedIssueIds, onCheckedChange = { model.toggleIssue(issue.id, it) })
                        Text("采用复评问题：${issue.description}")
                    }
                }
                if (detail.followUpEvaluation?.issueDeclaration == IssueDeclaration.NO_ISSUES_CONFIRMED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.useNoIssuesDeclaration, onCheckedChange = model::setNoIssues)
                        Text("采用正式零问题声明，并逐问题确认本次未再观察到原问题")
                    }
                }
                OutlinedTextField(state.observation, model::setObservation,
                    label = { Text("核验人补充观察（选填；填写后作为主要依据）") }, modifier = Modifier.fillMaxWidth())
                Text("补充观察范围：本次整堂复评课。请写实际观察到的改善点和仍存在的问题。")
                OutlinedTextField(state.evidenceRelation, model::setEvidenceRelation,
                    label = { Text("所选证据与这个原问题的关联说明") }, modifier = Modifier.fillMaxWidth())
                if (detail.followUpIssues.any { VerificationRules.isSimilarIssue(detail.link.originIssueSnapshot, it.description) }) {
                    MessageCard("复评列表含相似问题，请核对是否同一问题；文字相似本身不代替人工确认。")
                }
                OutlinedTextField(state.conflictExplanation, model::setConflict,
                    label = { Text("冲突说明（问题仍存在或指标与判断不一致时必填）") }, modifier = Modifier.fillMaxWidth())
                VerificationVerdict.entries.forEach { verdict ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = state.verdict == verdict, onClick = { model.setVerdict(verdict) })
                        Text(verdict.label)
                    }
                }
                OutlinedTextField(
                    value = state.basis,
                    onValueChange = { model.setBasis(it) },
                    label = { Text("核验依据") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.save() }, enabled = !state.saving) { Text("保存核验结论") }
                    TextButton(onClick = { model.load() }) { Text("刷新") }
                }
            }
        }
    }
}

/** 单项指标的比较结果：可比给差值，不可比给原因，缺测不补零。 */
@Composable
private fun ComparisonRow(item: MetricComparison) {
    val outcome = item.outcome
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.metricKey.label, style = MaterialTheme.typography.titleSmall)
        val beforeText = item.before?.let { snapshot ->
            "${snapshot.value ?: "缺测"}${snapshot.unit}（口径 ${snapshot.denominator}，采样 ${snapshot.sampling}，版本 ${snapshot.methodVersion}）"
        } ?: "无快照"
        val afterText = item.after?.let { snapshot ->
            "${snapshot.value ?: "缺测"}${snapshot.unit}（口径 ${snapshot.denominator}，采样 ${snapshot.sampling}，版本 ${snapshot.methodVersion}）"
        } ?: "无快照"
        Text("前次：$beforeText", style = MaterialTheme.typography.bodySmall)
        Text("后次：$afterText", style = MaterialTheme.typography.bodySmall)
        when (outcome) {
            is MetricRules.CompareOutcome.Comparable ->
                Text("变化：${outcome.after - outcome.before}（前 ${outcome.before} → 后 ${outcome.after}），只反映观察结果",
                    style = MaterialTheme.typography.bodySmall)
            is MetricRules.CompareOutcome.NotComparable ->
                Text("暂不能比较：${outcome.reason.message}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            null -> item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
