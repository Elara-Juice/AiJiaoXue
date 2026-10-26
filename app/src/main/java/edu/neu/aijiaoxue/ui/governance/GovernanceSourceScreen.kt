package edu.neu.aijiaoxue.ui.governance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evaluation.*

@Composable
fun GovernanceSourceScreen(model: GovernanceSourceViewModel, onBack: () -> Unit, onTask: (Long) -> Unit) {
    val state by model.uiState.collectAsState()
    ReloadWhenResumed { model.reload() }
    ModulePage("治理数据来源", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("governanceSourceList"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { item { MessageCard(it, error = true); TextButton(onClick = model::reload) { Text("重试") } } }
            state.detail?.let { detail ->
                item { SectionCard("正式课堂") {
                    val task = detail.classroom
                    Text("${task.teacherName} · ${task.courseName} · ${task.taskCode}")
                    Text("${task.supervisionDate} ${task.period}节 · 督导：${task.supervisorName}")
                    ReadField("主题", task.chapter)
                    Text("评价总分：${task.totalScore?.toString() ?: "缺测"}；提交时间：${timeText(task.submittedAt)}")
                    ReadField("亮点", task.strengths)
                    ReadField("正式意见", task.overallOpinion)
                } }
                item { SectionCard("正式指标及口径") {
                    if (detail.snapshots.isEmpty()) Text("没有正式指标快照，未根据当前材料补算历史数据。")
                    detail.snapshots.forEach {
                        Text("${it.metricKey.label}：${number(it.value)} ${it.unit}")
                        Text("${it.methodVersion} · ${it.denominator} · 样本 ${it.sampleCount} 条")
                        Text(it.sampling)
                        Text("统计区间：${timeText(it.scopeStartAt)} 至 ${timeText(it.scopeEndAt)}")
                    }
                } }
                item { SectionCard("正式问题") {
                    if (detail.issues.isEmpty()) Text("没有问题条目。")
                    detail.issues.forEach { Text("${it.dimension?.label ?: "未指定维度"}：${it.description}") }
                } }
                item { SectionCard("改进落实") {
                    if (detail.measures.isEmpty()) Text("暂无已确认计划的措施。")
                    detail.measures.forEach { measure ->
                        Text("${measure.itemDescription} · ${measure.status.label}", style = MaterialTheme.typography.titleSmall)
                        Text(measure.content)
                        val records = detail.executions.filter { it.measureId == measure.measureId }
                        if (records.isEmpty()) Text("尚无实际实施记录；待办状态不作为问题改善证明。")
                        records.forEach {
                            Text("${timeText(it.implementedAt)} · ${it.implementationNote}")
                            Text("实际调整：${it.actualAdjustment}")
                        }
                    }
                } }
                detail.verifications.forEach { verification -> item {
                    SectionCard("后续核验 · ${verification.verdict.label}") {
                        Text(verification.originIssueSnapshot)
                        Text(verification.basis)
                        Text("${verification.verifierName} · ${timeText(verification.verifiedAt)}")
                        TextButton(onClick = { onTask(verification.followUpTaskId) }) { Text("查看后续课堂 ${verification.followUpCode}") }
                    }
                } }
            }
        }
    }
}
