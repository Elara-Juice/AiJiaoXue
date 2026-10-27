package edu.neu.aijiaoxue.ui.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.ai.CloudAnalysisPipeline
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.*
import edu.neu.aijiaoxue.ui.input.EvidenceSourceDialog
import edu.neu.aijiaoxue.ui.governance.ReloadWhenResumed
import kotlinx.coroutines.delay

/** 生产入口始终使用真实代理或明确标识的确定性统计，Mock仅留给旧回归测试。 */
@Composable
fun CloudAnalysisScreen(vm: CloudAnalysisViewModel, onBack: () -> Unit, onReview: (Long) -> Unit,
                        onEvaluation: () -> Unit, onInputs: () -> Unit, onParticipation: () -> Unit) {
    val state by vm.uiState.collectAsState()
    ReloadWhenResumed { vm.reload() }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showAllJobs by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { while (true) { delay(5_000); vm.poll() } }
    if (!state.loading && state.task == null) { ErrorPage("课堂分析", state.error ?: "无权访问", onBack, vm::reload); return }
    state.source?.let { EvidenceSourceDialog(it, vm::closeSource) }
    if (showSettings) {
        val initial = remember { vm.settings.get() }
        var url by remember { mutableStateOf(initial.baseUrl) }
        var token by remember { mutableStateOf(initial.token) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("云端分析连接") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("填写团队部署的分析服务地址和设备令牌。DeepSeek、百炼密钥仅配置在服务器。")
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS 服务地址") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("设备令牌") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = { error = vm.saveSettings(url, token); if (error == null) showSettings = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } })
    }
    ModulePage("课堂分析", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(ANALYSIS_PAGE_TAG), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { item { MessageCard(it, error = true) } }
            item {
                SectionCard("分析准备") {
                    Text("转写 ${state.transcriptCount} 份 · 图片识别 ${state.imageTextCount} 份")
                    Text("先核对转写、课程要求和课堂环节，再运行对应分析。所有模型结论均须督导审核。")
                    OutlinedButton(onClick = onInputs, modifier = Modifier.fillMaxWidth()) { Text("课程要求、转写与补充事实") }
                    TextButton(onClick = { showSettings = true }) { Text("配置云端分析服务") }
                }
            }
            val canAnalyze = state.task?.status in setOf(TaskStatus.PENDING_EVALUATION, TaskStatus.COMPLETED)
            if (!canAnalyze) item { MessageCard("课堂采集结束后才能分析") }
            items(state.materials, key = { "material-${it.id}" }) { material ->
                val operation = if (material.type == MaterialType.AUDIO) AnalysisOperation.TRANSCRIBE else AnalysisOperation.IMAGE_TEXT
                OutlinedButton(onClick = { vm.start(operation, material.id) }, enabled = canAnalyze && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("${operation.label} · ${material.type.label} #${material.id}")
                }
            }
            item {
                SectionCard("分析能力") {
                    listOf(AnalysisOperation.CONTENT, AnalysisOperation.SYLLABUS_ALIGNMENT, AnalysisOperation.BOPPPS,
                        AnalysisOperation.GOAL_ACTIVITY, AnalysisOperation.TRANSITION, AnalysisOperation.MATERIAL_SUPPORT,
                        AnalysisOperation.EXPRESSION_STATS, AnalysisOperation.INTERACTION_STATS).forEach { op ->
                        OutlinedButton(onClick = { vm.start(op) }, enabled = canAnalyze && !state.busy, modifier = Modifier.fillMaxWidth().testTag("analysis-run-${op.name}")) {
                            Text(op.label + if (op in CloudAnalysisPipeline.LOCAL_OPERATIONS) "（本地统计）" else "")
                        }
                    }
                    OutlinedButton(onClick = onParticipation, enabled = canAnalyze, modifier = Modifier.fillMaxWidth()) { Text("多源参与分析与环节定位") }
                }
            }
            item { Row { Text("分析任务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton(onClick = vm::refresh, enabled = !state.busy) { Text("刷新") } } }
            if (state.jobs.size > 30) item { TextButton(onClick = { showAllJobs = !showAllJobs }) { Text(if (showAllJobs) "只显示最近30个任务" else "显示全部${state.jobs.size}个任务") } }
            items(if (showAllJobs) state.jobs else state.jobs.take(30), key = { "job-${it.id}" }) { job ->
                SectionCard(job.operation.label) {
                    Text(jobStateLabel(job.state)); job.stage?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    job.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (job.state in CloudAnalysisPipeline.ACTIVE) TextButton(onClick = { vm.cancel(job.id) }) { Text("取消任务") }
                    else if (job.state !in setOf(JobState.SUCCEEDED, JobState.PARTIAL)) TextButton(onClick = { vm.retry(job) }, enabled = !state.busy) { Text("使用当前输入重试") }
                }
            }
            if (state.results.any { !it.isCurrent }) item { Row { Switch(showHistory, { showHistory = it }); Text("显示已替代的历史分析（只读）", Modifier.padding(12.dp)) } }
            items(state.results.filter { it.isCurrent || showHistory }, key = { "result-${it.id}" }) { result ->
                SectionCard(result.type.label) {
                    Text(if (result.schemaVersion == 0) "历史模拟结果（不代表真实模型分析）" else resultStateLabel(result.resultState))
                    if (!result.isCurrent) Text("历史批次，只能查看；不会重新进入正式报告。")
                    Text(result.content)
                    Text(if (result.verified) "已审核" else "待人工审核", style = MaterialTheme.typography.labelLarge)
                    state.evidence[result.id].orEmpty().forEach { evidence ->
                        TextButton(onClick = { vm.openEvidence(evidence.id) }, modifier = Modifier.fillMaxWidth()) { Text("查看来源：${evidence.description.take(100)}") }
                    }
                    OutlinedButton(onClick = { onReview(result.id) }, modifier = Modifier.fillMaxWidth().testTag("analysis-review-${result.type.name}")) { Text(if (result.verified) "查看或修订审核" else "审核分析结果") }
                }
            }
            if (canAnalyze) item { Button(onClick = onEvaluation, modifier = Modifier.fillMaxWidth().testTag("analysis-enter-evaluation")) { Text("进入人工评价") } }
        }
    }
}

private fun jobStateLabel(state: JobState) = when (state) {
    JobState.QUEUED -> "等待处理"; JobState.RUNNING -> "处理中"; JobState.SUCCEEDED -> "已完成"; JobState.PARTIAL -> "部分完成"
    JobState.FAILED -> "失败"; JobState.CANCEL_REQUESTED -> "正在取消"; JobState.CANCELLED -> "已取消"; JobState.STALE_INPUT -> "输入已更新"; JobState.EXPIRED -> "已过期"
}
private fun resultStateLabel(state: ResultState) = when (state) {
    ResultState.SUPPORTED -> "有证据支持的候选结果"; ResultState.PARTIAL -> "仅部分输入可分析"; ResultState.INSUFFICIENT_INPUT -> "输入不足，不能据此断言缺失或异常"
    ResultState.UNREADABLE -> "材料无法辨读"; ResultState.FAILED -> "分析失败"
}
