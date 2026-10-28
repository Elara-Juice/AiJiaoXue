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
import edu.neu.aijiaoxue.data.model.IssueDeclaration
import edu.neu.aijiaoxue.ui.theme.AppGlyph
import edu.neu.aijiaoxue.ui.theme.AppIcon

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
                if (!state.readOnly) OutlinedButton(onClick = onAnalysis, enabled = !state.busy,
                    modifier = Modifier.testTag("evaluation-open-analysis")) {
                    AppIcon(AppGlyph.ANALYTICS, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("查看与审核分析")
                }
                ReviewedAnalysisSection(record.reviewedAnalyses, state.readOnly, record.publication,
                    record.publishedFacts, record.publishedEvidence)
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
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次问题确认", style = MaterialTheme.typography.titleMedium)
                if (state.readOnly) {
                    Text(if (evaluation.issueDeclaration == IssueDeclaration.UNASSESSED)
                        "历史正式评价，未记录显式零问题声明" else evaluation.issueDeclaration.label)
                    evaluation.issueDeclarationNote?.let { Text(it) }
                } else {
                    listOf(IssueDeclaration.HAS_ISSUES, IssueDeclaration.NO_ISSUES_CONFIRMED).forEach { declaration ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = evaluation.issueDeclaration == declaration,
                                onClick = { viewModel.declareIssues(declaration) }, enabled = enabled)
                            Text(declaration.label)
                        }
                    }
                    if (evaluation.issueDeclaration == IssueDeclaration.NO_ISSUES_CONFIRMED) {
                        Text("请说明本次观察范围与确认依据；已有问题须自行核对后删除。")
                        OutlinedTextField(value = evaluation.issueDeclarationNote.orEmpty(),
                            onValueChange = viewModel::declarationNote, enabled = enabled,
                            label = { Text("无主要问题确认说明（必填）") }, modifier = Modifier.fillMaxWidth())
                    }
                }
                IssueEditor(state.draft.issues, enabled, viewModel::addIssue, viewModel::changeIssue, viewModel::removeIssue)
            } }
            item { OutlinedTextField(value = evaluation.suggestions.orEmpty(),
                onValueChange = { text -> viewModel.opinion { it.copy(suggestions = text) } }, readOnly = !enabled,
                label = { Text("改进建议（选填）") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(value = evaluation.overallOpinion.orEmpty(),
                onValueChange = { text -> viewModel.opinion { it.copy(overallOpinion = text) } }, readOnly = !enabled,
                label = { Text("总体意见（必填）") }, modifier = Modifier.fillMaxWidth()) }
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { MessageCard(it, true) }
                if (state.readOnly) Button(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(AppGlyph.ARCHIVE, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("查看历史归档")
                }
                else {
                    OutlinedButton(onClick = { viewModel.persist() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        AppIcon(AppGlyph.SAVE, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("保存草稿")
                    }
                    Button(onClick = { if (viewModel.validate()) confirming = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        AppIcon(AppGlyph.SEND, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
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
