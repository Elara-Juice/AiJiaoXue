package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.EvalRules

@Composable
fun EvaluationScreen(viewModel: EvaluationViewModel, onBack: () -> Unit, onHistory: () -> Unit, onAnalysis: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshReviewedReference()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.validationAttempt) { state.invalidIndex?.let { list.animateScrollToItem(it) } }
    if (!state.loading && state.record == null) {
        ErrorPage("督导评价", state.error ?: "加载失败", onBack, viewModel::reload)
        return
    }
    ModulePage("督导评价", onBack) { padding ->
        if (state.loading) { CircularProgressIndicator(Modifier.padding(padding).padding(24.dp)); return@ModulePage }
        val record = state.record ?: return@ModulePage
        val evaluation = state.draft.evaluation
        val enabled = !state.readOnly && !state.busy
        LazyColumn(state = list, modifier = Modifier.padding(padding).fillMaxSize().testTag("evaluation-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionCard(record.course.name) {
                Text("${record.teacherName} · ${record.task.supervisionDate} · ${record.task.period}节")
                Text(record.task.code)
                MessageCard(if (state.readOnly) "评价已提交，内容已锁定" else if (state.saved) "草稿已保存" else "正在保存草稿…")
                state.error?.let { MessageCard(it, true) }
            } }
            // Keep this in the existing reference item: validation scroll positions below
            // intentionally remain 3 (scores), 8 (issues), and 10 (overall opinion).
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClassroomReference(record)
                SectionCard("已审核分析参考") {
                    Text("分析仅供督导参考，不自动评分、创建问题或替代人工评价。")
                    Text("当前版本展示规则模拟分析，用于流程验证，尚未接入真实 AI 模型。")
                    if (!state.readOnly) OutlinedButton(onClick = onAnalysis, enabled = !state.busy,
                        modifier = Modifier.testTag("evaluation-open-analysis")) { Text("查看与审核分析") }
                    if (record.reviewedAnalyses.isEmpty()) {
                        Text("暂无可用的已审核分析，可继续完成人工评价。尚未完成审核或缺少有效审核记录的分析不在此展示。")
                    } else {
                        Text(if (state.readOnly) "以下为随本次评价发布的审核快照。" else "以下为当前已审核分析；提交评价时将留存本次参考快照。")
                        record.reviewedAnalyses.forEach { analysis ->
                            Column(Modifier.fillMaxWidth().testTag("evaluation-reviewed-${analysis.analysisId}"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider()
                                Text(when (analysis.type.uppercase()) {
                                    "BOPPPS" -> "BOPPPS 教学结构"
                                    "INTERACTION" -> "课堂互动"
                                    "ENGAGEMENT" -> "课堂参与"
                                    "VERBAL_REPETITION" -> "口头表达"
                                    "TEACHING_TRANSITION" -> "教学环节衔接"
                                    "CONTENT" -> "教学内容"
                                    else -> analysis.type
                                }, style = MaterialTheme.typography.titleSmall)
                                ReadField("分析原文", analysis.originalContent)
                                ReadField("审核后有效内容", analysis.effectiveContent)
                                ReadField("证据摘要", analysis.evidenceSummary)
                                Text("审核时间：${timeText(analysis.reviewedAt)}")
                                Text("审核来源版本：${analysis.versionKey}")
                            }
                        }
                    }
                }
            } }
            item {
                val total = EvalRules.totalScore(evaluation.scores())
                MessageCard(total?.let { "总分 $it 分 · ${EvalRules.gradeOf(it).label}" } ?: "完成四维评分后显示总分与等级")
            }
            EvalDimension.entries.forEach { dimension -> item {
                ScoreSection(dimension, evaluation, enabled, { viewModel.score(dimension, it) }, { viewModel.comment(dimension, it) })
            } }
            item { OutlinedTextField(value = evaluation.strengths.orEmpty(),
                onValueChange = { text -> viewModel.opinion { it.copy(strengths = text) } }, readOnly = !enabled,
                label = { Text("主要优点（选填）") }, modifier = Modifier.fillMaxWidth()) }
            item { IssueEditor(state.draft.issues, enabled, viewModel::addIssue, viewModel::changeIssue, viewModel::removeIssue) }
            item { OutlinedTextField(value = evaluation.suggestions.orEmpty(),
                onValueChange = { text -> viewModel.opinion { it.copy(suggestions = text) } }, readOnly = !enabled,
                label = { Text("改进建议（选填）") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(value = evaluation.overallOpinion.orEmpty(),
                onValueChange = { text -> viewModel.opinion { it.copy(overallOpinion = text) } }, readOnly = !enabled,
                label = { Text("总体意见（必填）") }, modifier = Modifier.fillMaxWidth()) }
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { MessageCard(it, true) }
                if (state.readOnly) Button(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("查看历史归档") }
                else {
                    OutlinedButton(onClick = { viewModel.persist() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("保存草稿") }
                    Button(onClick = { if (viewModel.validate()) confirming = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.busy) "正在提交…" else "提交评价")
                    }
                }
            } }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("确认提交评价？") },
        text = { Text("提交后评价将锁定，任务标记为已完成，任课教师可以查看反馈。") },
        confirmButton = { TextButton(onClick = { confirming = false; viewModel.persist(submit = true) }) { Text("确认提交") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("继续编辑") } })
}
