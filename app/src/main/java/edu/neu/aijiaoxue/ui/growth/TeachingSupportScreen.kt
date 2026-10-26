package edu.neu.aijiaoxue.ui.growth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.neu.aijiaoxue.data.model.PlanStatus
import edu.neu.aijiaoxue.ui.evaluation.*

@Composable
fun TeachingSupportScreen(model: TeachingSupportViewModel, onBack: () -> Unit, onFocus: (Long) -> Unit,
    onPlan: (Long) -> Unit, onFeedback: (Long) -> Unit) {
    val state by model.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    // 写入未结束时不打开读取计划的另一个 ViewModel，避免旧列表覆盖刚采用的措施。
    BackHandler(enabled = state.busy) { }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.reload()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    ModulePage("教学亮点与互动策略", { if (!model.state.value.busy) onBack() }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("从已发布反馈沉淀亮点；策略与资源按问题匹配，由您调整后采用。规则建议不代表已证明教学有效。")
            state.error?.let { MessageCard(it, error = true) }
            state.message?.let { MessageCard(it) }
            linkError?.let { MessageCard(it, error = true) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = model::reload, enabled = !state.busy) { Text("刷新") }
            val record = state.record
            if (record != null) {
                SectionCard("选择课堂反馈") {
                    if (record.feedbacks.isEmpty()) Text("暂无本人已发布的课堂反馈")
                    record.feedbacks.forEach { feedback ->
                        OutlinedButton(onClick = { model.select(feedback.taskId) }, enabled = !state.busy) {
                            Text("${feedback.supervisionDate} ${feedback.courseName} · 任务${feedback.taskId}")
                        }
                    }
                }
                record.selected?.let { selected ->
                    val taskId = selected.task.id
                    val highlight = record.highlights.find { it.taskId == taskId }
                    var practice by remember(taskId, highlight?.updatedAt) { mutableStateOf(highlight?.practice.orEmpty()) }
                    SectionCard("本次反馈的教学亮点") {
                        Text(selected.evaluation.strengths?.takeIf { it.isNotBlank() } ?: "本次正式反馈未填写亮点")
                        OutlinedTextField(practice, { practice = it }, label = { Text("可复用做法说明") },
                            modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                        Button(onClick = { model.saveHighlight(practice) }, enabled = !state.busy && !selected.evaluation.strengths.isNullOrBlank()) {
                            Text("标记并保存亮点")
                        }
                        TextButton(onClick = { if (!model.state.value.busy) onFeedback(taskId) }, enabled = !state.busy) { Text("查看正式反馈来源") }
                    }
                    if (selected.items.isEmpty()) {
                        MessageCard("请先从本次反馈确定改进重点，再选择策略或复用做法。")
                        Button(onClick = { if (!model.state.value.busy) onFocus(taskId) }, enabled = !state.busy) { Text("确定改进重点") }
                    } else {
                        TextButton(onClick = { if (!model.state.value.busy) onPlan(taskId) }, enabled = !state.busy) { Text("打开改进计划") }
                        val editable = selected.plan?.status == PlanStatus.DRAFT
                        if (!editable) MessageCard("计划已确认，保留原文与来源；本页仅供查阅。")
                        selected.items.forEach { item ->
                            SectionCard("改进重点：${item.description}") {
                                val choices = model.choices(item.id)
                                if (choices.isEmpty()) Text("没有适用的互动策略或本人亮点，请自行制定措施。")
                                choices.forEach { choice ->
                                    var edited by remember(item.id, choice.key, choice.content) { mutableStateOf(choice.content) }
                                    Text(choice.title, style = MaterialTheme.typography.titleSmall)
                                    Text(choice.reason)
                                    Text("出处：${choice.reference}", style = MaterialTheme.typography.bodySmall)
                                    TeachingSupportCatalog.resources.find { "resource:${it.key}" == choice.key }?.let { resource ->
                                        TextButton(onClick = {
                                            linkError = runCatching { uriHandler.openUri(resource.sourceUrl) }
                                                .exceptionOrNull()?.let { "无法打开浏览器，请按上方网址查阅来源。" }
                                        }) { Text("查阅资源原始出处") }
                                    }
                                    OutlinedTextField(edited, { edited = it }, label = { Text("调整后加入计划") },
                                        enabled = editable && !state.busy, modifier = Modifier.fillMaxWidth())
                                    val adopted = record.adoptions.any { it.itemId == item.id && it.sourceKey == choice.key }
                                    Button(onClick = { model.adopt(item.id, choice.key, edited) }, enabled = editable && !state.busy && !adopted) {
                                        Text(if (adopted) "已加入计划" else "采用此做法")
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    if (record.adoptions.isNotEmpty()) SectionCard("已采用做法的来源") {
                        record.adoptions.forEach { adoption ->
                            Text(adoption.sourceTitle)
                            Text("对应问题：${adoption.problemSnapshot}")
                            Text("候选原文：${adoption.originalContent}")
                            Text("当前措施：${selected.measures.find { it.id == adoption.measureId }?.content.orEmpty()}")
                            Text("出处：${adoption.sourceReference}")
                        }
                    }
                }
                SectionCard("我的可复用亮点库") {
                    if (record.highlights.isEmpty()) Text("尚未保存亮点做法")
                    record.highlights.forEach { highlight ->
                        Text(highlight.practice)
                        Text("正式亮点原文：${highlight.sourceText}")
                        TextButton(onClick = { if (!model.state.value.busy) onFeedback(highlight.taskId) }, enabled = !state.busy) { Text("查看来源课堂 ${highlight.taskId}") }
                    }
                }
            }
        }
    }
}
