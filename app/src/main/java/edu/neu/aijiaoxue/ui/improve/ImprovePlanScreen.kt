package edu.neu.aijiaoxue.ui.improve

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.neu.aijiaoxue.data.entity.ImprovementSuggestion
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.ui.evaluation.*

@Composable
fun ImprovePlanScreen(viewModel: ImprovePlanViewModel, onBack: () -> Unit, onFocus: () -> Unit, onTodos: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    var editingSuggestion by remember { mutableStateOf<ImprovementSuggestion?>(null) }
    var suggestionText by remember { mutableStateOf("") }
    val interactive = !state.busy && !state.leaving
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler { viewModel.flushAndNavigate(onBack) }
    if (!state.loading && state.record == null) {
        ErrorPage("改进计划", state.error ?: "加载失败", { viewModel.flushAndNavigate(onBack) }, viewModel::reload)
        return
    }
    ModulePage("个人改进计划", { viewModel.flushAndNavigate(onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("plan-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (state.loading) CircularProgressIndicator()
                else SectionCard(state.record?.course?.name.orEmpty()) {
                    Text("督导日期：${state.record?.task?.supervisionDate}")
                    MessageCard(if (state.readOnly) "计划已确认，内容和日期已锁定" else if (state.busy) "正在处理建议或保存计划…"
                        else if (state.saved) "计划草稿已保存" else "正在保存计划…")
                    ReadField("督导参考建议", state.record?.evaluation?.suggestions)
                    Text("参考建议由本次督导填写，各事项是否适用由你决定。")
                    if (state.record?.reviewPublished == true && !state.record?.reviewedAnalyses.isNullOrEmpty()) {
                        Text("下方候选建议依据随评价发布的审核快照提供，需你逐项判断并确认计划。当前版本的上游分析为规则模拟，候选建议也由规则生成，用于流程验证，尚未接入真实 AI 模型。")
                    } else {
                        Text("本次反馈暂无已发布的审核分析，可继续采用督导建议或人工新增措施。")
                    }
                    if (!state.readOnly) TextButton(onClick = viewModel::refreshSuggestions, enabled = interactive) {
                        Text("刷新建议")
                    }
                    state.error?.let { MessageCard(it, true) }
                }
            }
            state.record?.items?.forEach { item ->
                item(key = "item-${item.id}") { SectionCard("${item.priority.label}优先级 · ${item.source.label}") {
                    Text(item.description, style = MaterialTheme.typography.titleMedium)
                    if (state.measures.none { it.itemId == item.id }) Text("此事项暂无措施，请至少保留一条。")
                    if (state.record?.suggestions.orEmpty().none { it.itemId == item.id && it.problemSnapshot == item.description }) {
                        Text("此事项暂无匹配的候选建议，可人工新增措施。")
                    }
                    if (!state.readOnly) OutlinedButton(onClick = { viewModel.add(item.id) }, enabled = interactive) { Text("新增措施") }
                } }
                state.record?.suggestions?.filter { it.itemId == item.id }?.forEach { suggestion ->
                    item(key = "suggestion-${suggestion.id}") {
                        val linked = state.measures.firstOrNull { it.suggestionId == suggestion.id }
                        val snapshot = state.record?.reviewedAnalyses?.firstOrNull { it.id == suggestion.sourceSnapshotId }
                        val currentProblem = suggestion.problemSnapshot == item.description
                        val decision = when {
                            linked?.source == MeasureSource.MODIFIED -> "已修改采用"
                            linked != null -> "已采纳"
                            suggestion.dismissedAt != null -> "已放弃"
                            else -> "待选择"
                        }
                        SectionCard("${suggestion.generatorMode.label} · $decision") {
                            Column(Modifier.testTag("suggestion-${suggestion.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(suggestion.content, style = MaterialTheme.typography.bodyLarge)
                                ReadField("对应问题快照", suggestion.problemSnapshot)
                                ReadField("教学环节", suggestion.bopppsStage)
                                ReadField("建议理由", suggestion.rationale)
                                Text("来源版本：${suggestion.reviewVersionKey}")
                                Text("生成方式：${suggestion.generatorMode.label} · ${suggestion.generatorVersion}")
                                snapshot?.let {
                                    ReadField("已审核依据快照", it.effectiveContent)
                                    ReadField("课堂证据摘要", it.evidenceSummary)
                                    Text("审核时间：${timeText(it.reviewedAt)}")
                                }
                                Text("建议及来源在生成时留存，采用后仍保留该版本。")
                                if (!currentProblem) {
                                    MessageCard("此建议依据旧改进重点生成，现仅保留历史来源。请使用当前重点对应的建议或人工新增措施。")
                                } else if (state.readOnly) {
                                    Text("计划已确认，建议选择已锁定。")
                                } else if (linked != null) {
                                    Text("已关联下方措施；如需放弃，请先撤销采纳，再放弃此候选建议。")
                                    TextButton(onClick = { viewModel.remove(linked.id) }, enabled = interactive,
                                        modifier = Modifier.testTag("suggestion-unadopt-${suggestion.id}")) { Text("撤销采纳") }
                                } else if (suggestion.dismissedAt != null) {
                                    TextButton(onClick = { viewModel.dismissSuggestion(suggestion.id, false) }, enabled = interactive,
                                        modifier = Modifier.testTag("suggestion-restore-${suggestion.id}")) { Text("恢复建议") }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.adoptSuggestion(suggestion.id) }, enabled = interactive,
                                            modifier = Modifier.testTag("suggestion-adopt-${suggestion.id}")) { Text("采纳") }
                                        OutlinedButton(onClick = {
                                            editingSuggestion = suggestion
                                            suggestionText = suggestion.content
                                        }, enabled = interactive, modifier = Modifier.testTag("suggestion-edit-${suggestion.id}")) {
                                            Text("修改后采用")
                                        }
                                    }
                                    TextButton(onClick = { viewModel.dismissSuggestion(suggestion.id) }, enabled = interactive,
                                        modifier = Modifier.testTag("suggestion-dismiss-${suggestion.id}")) { Text("放弃") }
                                }
                            }
                        }
                    }
                }
                state.measures.filter { it.itemId == item.id }.forEach { measure ->
                    item(key = "measure-${measure.id}") {
                        val suggestion = state.record?.suggestions?.firstOrNull { it.id == measure.suggestionId }
                        SectionCard(suggestion?.let {
                            "${it.generatorMode.label} · ${if (measure.source == MeasureSource.MODIFIED) "修改后采用" else "已采纳"}"
                        } ?: measure.source.label) {
                            val enabled = !state.readOnly && interactive
                            val currentSuggestion = suggestion == null || suggestion.problemSnapshot == item.description
                            suggestion?.let {
                                ReadField("原建议快照", it.content)
                                Text("审核来源版本：${it.reviewVersionKey}")
                                if (!currentSuggestion) Text("来源对应旧改进重点，请核对本措施是否仍适用于当前事项。")
                            }
                            OutlinedTextField(value = measure.content, onValueChange = { viewModel.content(measure.id, it) },
                                readOnly = !enabled, label = { Text("措施内容（必填）") },
                                modifier = Modifier.fillMaxWidth().testTag("measure-content-${measure.id}"))
                            DateField("计划完成日期", measure.dueDate, { date -> viewModel.change(measure.id) { it.copy(dueDate = date) } },
                                enabled = enabled, clearable = enabled)
                            if (enabled) {
                                if (measure.source != MeasureSource.ADDED && currentSuggestion) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { viewModel.adopt(measure.id) }) { Text("采纳原建议") }
                                    if (suggestion == null) TextButton(onClick = { viewModel.change(measure.id) { it.copy(source = MeasureSource.MODIFIED) } }) { Text("修改建议") }
                                }
                                TextButton(onClick = { viewModel.remove(measure.id) }) {
                                    Text(when {
                                        suggestion != null -> "撤销采纳"
                                        measure.source == MeasureSource.ADDED -> "删除措施"
                                        else -> "放弃此建议"
                                    })
                                }
                            } else Text("执行状态：${measure.status.label}")
                        }
                    }
                }
            }
            if (!state.loading && state.record != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.error?.let { MessageCard(it, true) }
                    if (state.readOnly) Button(onClick = { viewModel.flushAndNavigate(onTodos) }, enabled = !state.leaving,
                        modifier = Modifier.fillMaxWidth()) { Text("查看改进待办") }
                    else {
                        OutlinedButton(onClick = { viewModel.flushAndNavigate(onFocus) }, enabled = !state.leaving,
                            modifier = Modifier.fillMaxWidth()) { Text("调整改进重点") }
                        OutlinedButton(onClick = { viewModel.persist() }, enabled = interactive, modifier = Modifier.fillMaxWidth()) { Text("保存计划草稿") }
                        Button(onClick = { if (viewModel.validate()) confirming = true }, enabled = interactive,
                            modifier = Modifier.fillMaxWidth()) { Text("确认计划") }
                    }
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("确认实施本计划？") },
        text = { Text("确认后生成改进待办，计划内容和日期不可修改；仍可在待办中更新执行状态。") },
        confirmButton = { TextButton(enabled = interactive, onClick = { confirming = false; viewModel.persist(confirm = true) }) { Text("确认并生成待办") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("继续编辑") } })
    editingSuggestion?.let { suggestion ->
        val currentProblem = state.record?.items.orEmpty().any { it.id == suggestion.itemId && it.description == suggestion.problemSnapshot }
        AlertDialog(onDismissRequest = { editingSuggestion = null }, title = { Text("修改后采用建议") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原建议及其审核来源会保留；下方内容将作为你的改进措施。")
                OutlinedTextField(value = suggestionText, onValueChange = { suggestionText = it },
                    label = { Text("修改后的建议") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("suggestion-edit-content"))
            } },
            confirmButton = { TextButton(enabled = currentProblem && suggestionText.isNotBlank() && interactive && !state.readOnly, onClick = {
                viewModel.adoptSuggestion(suggestion.id, suggestionText)
                editingSuggestion = null
            }) { Text("采用修改内容") } },
            dismissButton = { TextButton(onClick = { editingSuggestion = null }) { Text("取消") } })
    }
}
