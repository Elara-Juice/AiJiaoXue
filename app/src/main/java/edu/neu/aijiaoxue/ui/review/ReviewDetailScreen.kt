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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import edu.neu.aijiaoxue.data.model.MetricRules
import edu.neu.aijiaoxue.data.model.VerificationVerdict
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
                    OutlinedButton(onClick = { onOpenParticipation(detail.link.originTaskId) }) { Text("原课堂参与分析") }
                    OutlinedButton(onClick = { onOpenParticipation(detail.link.followUpTaskId) }) { Text("复评课堂参与分析") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.captureSnapshots(detail.link.originTaskId) },
                        enabled = !state.capturing) { Text("保存原课堂指标快照") }
                    OutlinedButton(onClick = { model.captureSnapshots(detail.link.followUpTaskId) },
                        enabled = !state.capturing) { Text("保存复评课堂指标快照") }
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
                    Text("复评课堂还没有已提交的问题条目")
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
                    }
                }
            }

            SectionCard("保存核验结论") {
                Text("「措施已完成」不自动等于「问题已改善」，需要人工判断、依据、核验人和时间。")
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
