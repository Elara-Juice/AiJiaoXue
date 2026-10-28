package edu.neu.aijiaoxue.ui.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import edu.neu.aijiaoxue.data.ai.CloudAnalysisPipeline
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.components.TaskContextHeader
import edu.neu.aijiaoxue.ui.components.StatusBadge
import edu.neu.aijiaoxue.ui.evaluation.*
import edu.neu.aijiaoxue.ui.input.EvidenceSourceDialog
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAnalysisScreen(vm: CloudAnalysisViewModel, onBack: () -> Unit, onReview: (Long) -> Unit,
                        onEvaluation: () -> Unit, onInputs: () -> Unit, onParticipation: () -> Unit) {
    val state by vm.uiState.collectAsState()
    var section by rememberSaveable { mutableStateOf(AnalysisViewSection.PREPARATION) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showCapabilities by rememberSaveable { mutableStateOf(false) }
    var retryConfirmation by remember { mutableStateOf<AnalysisJob?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, _ -> vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        lifecycle.addObserver(observer)
        vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); vm.setForeground(false) }
    }
    LaunchedEffect(vm) { while (true) { delay(3_000); vm.poll() } }
    if (!state.loading && state.task == null) { ErrorPage("课堂分析", state.error ?: "无权访问", onBack, vm::reload); return }
    state.source?.let { EvidenceSourceDialog(it, vm::closeSource) }
    if (showSettings) AnalysisSettingsDialog(vm, state) { showSettings = false }
    retryConfirmation?.let { job ->
        AlertDialog(onDismissRequest = { retryConfirmation = null }, title = { Text("重新提交分析") },
            text = { Text("上次云端提交的结果尚未确认。重新提交可能重复处理。请先尝试刷新已有任务；确认需要重新分析时再继续。") },
            confirmButton = { TextButton(onClick = { retryConfirmation = null; vm.retry(job) }) { Text("确认重新提交") } },
            dismissButton = { TextButton(onClick = { retryConfirmation = null }) { Text("返回") } })
    }
    val canAnalyze = state.task?.status in setOf(TaskStatus.PENDING_EVALUATION, TaskStatus.COMPLETED)
    val currentResults = state.results.filter { it.isCurrent }
    val pendingReview = currentResults.firstOrNull { !it.verified }
    val listState = rememberLazyListState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("课堂分析") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            actions = { TextButton(onClick = { showSettings = true }) { Text("服务设置") } }) },
        bottomBar = {
            if (canAnalyze) Surface(shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (section) {
                        AnalysisViewSection.PREPARATION -> Button(
                            onClick = { section = AnalysisViewSection.PROGRESS; vm.startRecommended() },
                            enabled = !state.busy && state.materials.isNotEmpty() && state.configured,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("analysis-start-recommended")) {
                            Text(if (state.busy) "正在准备…" else "开始推荐分析")
                        }
                        AnalysisViewSection.PROGRESS -> OutlinedButton(onClick = { section = AnalysisViewSection.RESULTS }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("查看已完成结果（${currentResults.size}）") }
                        AnalysisViewSection.RESULTS -> if (pendingReview != null) Button(onClick = { onReview(pendingReview.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("审核分析结果") }
                            else Button(onClick = onEvaluation, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("继续人工评价") }
                    }
                    TextButton(onClick = onEvaluation, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("analysis-enter-evaluation")) { Text("进入人工评价") }
                }
            }
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).testTag(ANALYSIS_PAGE_TAG),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "context") {
                TaskContextHeader(title = state.courseName,
                    subtitle = listOfNotNull(state.task?.chapter, state.task?.code).filter(String::isNotBlank).joinToString(" · "))
            }
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = section.ordinal) {
                    AnalysisViewSection.entries.forEach { value ->
                        Tab(selected = section == value, onClick = { section = value }, text = { Text(when (value) {
                            AnalysisViewSection.PREPARATION -> "准备"; AnalysisViewSection.PROGRESS -> "进度"; AnalysisViewSection.RESULTS -> "结果"
                        }) }, modifier = Modifier.testTag("analysis-section-${value.name}"))
                    }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item(key = "error") { Notice(error, true) { vm.clearNotice() } } }
            state.message?.takeIf { state.busy }?.let { item(key = "message") { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
            if (!canAnalyze && !state.loading) item { Notice("结束课堂采集后，即可整理材料并开始分析。") }
            when (section) {
                AnalysisViewSection.PREPARATION -> {
                    item(key = "summary") {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("先核对材料，再开始分析", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("音频转写和图片识别完成后，将继续分析课堂内容与教学结构。", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    SummaryNumber("材料", state.materials.size, Modifier.weight(1f))
                                    SummaryNumber("有效转写", state.transcriptCount, Modifier.weight(1f))
                                    SummaryNumber("图片识别", state.imageTextCount, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    item(key = "prepare") {
                        AnalysisSurface {
                            Text("分析前检查", style = MaterialTheme.typography.titleMedium)
                            PreparationLine("课堂材料", if (state.materials.isEmpty()) "尚未添加录音或图片" else "已保存 ${state.materials.size} 份材料", state.materials.isNotEmpty())
                            PreparationLine("转写与课堂范围", if (state.transcriptCount > 0) "已有有效转写，可核对课程目标和环节" else "录音将先转写；课程目标可在资料中补充", state.transcriptCount > 0)
                            OutlinedButton(onClick = onInputs, modifier = Modifier.fillMaxWidth()) { Text("整理课程要求、转写与补充事实") }
                            HorizontalDivider()
                            PreparationLine("分析服务", if (state.configured) "已配置，建议检查连接" else "请先连接团队分析服务", state.configured)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showSettings = true }) { Text("服务设置") }
                                TextButton(onClick = vm::checkConnection, enabled = state.configured && !state.busy) { Text("检查连接") }
                            }
                            state.connectionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (state.materials.isNotEmpty()) item { Text("本次课堂材料", style = MaterialTheme.typography.titleMedium) }
                    items(state.materials, key = { "material-${it.id}" }) { material ->
                        val audio = material.type == MaterialType.AUDIO
                        val ready = material.id in if (audio) state.transcribedMaterialIds else state.recognizedMaterialIds
                        AnalysisSurface {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(material.note?.takeIf(String::isNotBlank)?.take(50) ?: "${if (audio) "课堂音频" else material.type.label} #${material.id}", style = MaterialTheme.typography.titleSmall)
                                    Text(if (audio) "${material.durationMs?.let(::durationLabel) ?: "时长待确认"} · ${if (material.isImported) "导入 · 课堂时间未知" else "现场录制"}" else "${material.type.label} · ${if (material.isImported) "导入图片" else "课堂拍摄"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusBadge(if (ready) "已有结果" else "待处理")
                            }
                            TextButton(onClick = { section = AnalysisViewSection.PROGRESS; vm.start(if (audio) AnalysisOperation.TRANSCRIBE else AnalysisOperation.IMAGE_TEXT, material.id) }, enabled = canAnalyze && !state.busy) { Text(if (audio) "音频转写" else "图片文字识别") }
                        }
                    }
                    item(key = "capabilities-toggle") {
                        TextButton(onClick = { showCapabilities = !showCapabilities }, modifier = Modifier.fillMaxWidth()) { Text(if (showCapabilities) "收起分析能力" else "查看全部分析能力") }
                    }
                    if (showCapabilities) {
                        items(state.plan?.items.orEmpty(), key = { "op-${it.operation}" }) { item ->
                            AnalysisSurface {
                                Text(item.operation.label + if (item.local) " · 本地统计" else "", style = MaterialTheme.typography.titleSmall)
                                item.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                OutlinedButton(onClick = { section = AnalysisViewSection.PROGRESS; vm.start(item.operation) }, enabled = canAnalyze && item.available && !state.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("analysis-run-${item.operation.name}")) { Text(if (item.available) "运行${item.operation.label}" else "需要补充资料") }
                            }
                        }
                        item { OutlinedButton(onClick = onParticipation, enabled = canAnalyze, modifier = Modifier.fillMaxWidth()) { Text("多源参与分析与环节定位") } }
                    }
                }
                AnalysisViewSection.PROGRESS -> {
                    item(key = "run") {
                        AnalysisSurface {
                            Text("本轮分析", style = MaterialTheme.typography.titleLarge)
                            if (state.run == null) Text("分析任务创建后，会在这里显示真实处理进度。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            state.run?.let { snapshot ->
                                Text(runLabel(snapshot.run.state), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                val complete = snapshot.steps.count { it.state in setOf(AnalysisStepState.SUCCEEDED, AnalysisStepState.REUSED, AnalysisStepState.PARTIAL) }
                                Text("已完成 $complete 项 · 共 ${snapshot.steps.size} 项")
                                if (snapshot.run.state == AnalysisRunState.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                                snapshot.steps.forEach { step ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(step.operation.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text(stepLabel(step.state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    step.blockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                when (snapshot.run.state) {
                                    AnalysisRunState.PAUSED, AnalysisRunState.PREPARED, AnalysisRunState.WAITING_REVIEW -> Button(onClick = vm::resumeRun, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("继续本轮分析") }
                                    AnalysisRunState.WAITING_DECISION -> {
                                        OutlinedButton(onClick = vm::continueAvailable, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("仅基于成功材料继续") }
                                        TextButton(onClick = vm::reconfirmRun, enabled = !state.busy) { Text("重新确认最新输入") }
                                    }
                                    else -> Unit
                                }
                                if (snapshot.run.state !in setOf(AnalysisRunState.COMPLETED, AnalysisRunState.PARTIAL, AnalysisRunState.CANCELLED)) TextButton(onClick = vm::cancelRun) { Text("停止本轮后续处理") }
                            }
                        }
                    }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("任务记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::refresh, enabled = !state.busy) { Text(if (state.refreshing) "更新中" else "刷新") }
                    } }
                    if (state.jobs.isEmpty()) item { EmptyAnalysis("还没有分析任务", "从准备页添加材料并开始推荐分析。") }
                    items(state.jobs.take(30), key = { "job-${it.id}" }) { job ->
                        AnalysisSurface {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(job.operation.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                StatusBadge(jobStateLabel(job.state))
                            }
                            Text(jobStageLabel(job.stage), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            job.errorCode?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (job.state in CloudAnalysisPipeline.ACTIVE) TextButton(onClick = { vm.cancel(job.id) }) { Text("取消任务") }
                            else if (job.state !in setOf(JobState.SUCCEEDED, JobState.PARTIAL)) TextButton(onClick = {
                                if (job.errorDetailsJson?.contains("SUBMISSION_UNKNOWN") == true || job.errorCode?.contains("SUBMISSION_UNKNOWN") == true) retryConfirmation = job else vm.retry(job)
                            }, enabled = !state.busy) { Text("使用当前输入重试") }
                        }
                    }
                }
                AnalysisViewSection.RESULTS -> {
                    item(key = "result-summary") {
                        Text("分析结果与依据", style = MaterialTheme.typography.headlineSmall)
                        Text("${currentResults.size} 项当前结果 · ${currentResults.count { !it.verified }} 项待审核", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (currentResults.isEmpty()) item { EmptyAnalysis("结果将在这里汇总", "完成分析后，可逐项查看结论、原始证据并人工审核。") }
                    if (state.results.any { !it.isCurrent }) item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(showHistory, { showHistory = it }); Text("查看历史分析", Modifier.padding(start = 12.dp))
                    } }
                    items(state.results.filter { it.isCurrent || showHistory }, key = { "result-${it.id}" }) { result ->
                        AnalysisResultBlock(result, state.presentations[result.id], state.evidence[result.id].orEmpty(), vm::openEvidence) { onReview(result.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable
private fun SummaryNumber(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier) { Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
}
@Composable
private fun PreparationLine(title: String, detail: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        StatusBadge(if (ready) "已准备" else "待处理")
    }
}
@Composable
private fun EmptyAnalysis(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun Notice(text: String, error: Boolean = false, dismiss: (() -> Unit)? = null) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            dismiss?.let { TextButton(onClick = it) { Text("收起提示") } }
        }
    }
}

@Composable
private fun AnalysisResultBlock(result: AiAnalysisResult, presentation: AnalysisPresentation?, evidence: List<AiEvidence>, open: (Long) -> Unit, review: () -> Unit) {
    var expanded by rememberSaveable(result.id) { mutableStateOf(false) }
    AnalysisSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.type.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StatusBadge(if (result.verified) "已审核" else "待审核")
        }
        Text(when {
            result.schemaVersion == 0 -> "历史模拟结果，不代表真实模型分析"
            result.sourceMode == SourceMode.DETERMINISTIC_RULE -> "本地规则统计 · ${resultStateLabel(result.resultState)}"
            else -> "AI 候选结论 · ${resultStateLabel(result.resultState)}"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!result.isCurrent) Notice("历史批次，只供回看，不重新进入正式报告。")
        Text(presentation?.summary ?: result.content, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
        presentation?.coverage?.forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.stage.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(item.status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded && item.explanation.isNotBlank()) Text(item.explanation, style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            presentation?.findings?.forEach { finding ->
                Text(finding.title, style = MaterialTheme.typography.titleSmall)
                if (finding.detail.isNotBlank()) Text(finding.detail, style = MaterialTheme.typography.bodyMedium)
            }
            if (presentation?.findings?.isNotEmpty() == true || presentation?.coverage?.isNotEmpty() == true) Text(result.content, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "展开完整分析") }
        HorizontalDivider()
        Text("分析依据 · ${evidence.size} 条", style = MaterialTheme.typography.titleSmall)
        evidence.take(if (expanded) evidence.size else 3).forEach { source ->
            TextButton(onClick = { open(source.id) }, modifier = Modifier.fillMaxWidth()) {
                Text("查看来源：${source.description.take(100)}", modifier = Modifier.fillMaxWidth(), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        OutlinedButton(onClick = review, modifier = Modifier.fillMaxWidth().testTag("analysis-review-${result.type.name}")) { Text(if (result.verified) "查看或修订审核" else "审核分析结果") }
    }
}

@Composable
private fun AnalysisSettingsDialog(vm: CloudAnalysisViewModel, state: CloudAnalysisState, dismiss: () -> Unit) {
    val initial = remember { vm.settings.get() }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("连接分析服务") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("填写团队分析代理地址与设备令牌。云服务密钥由服务器管理。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(url, { url = it }, label = { Text("服务地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("设备令牌") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.connectionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { error = vm.saveSettings(url, token); if (error == null) vm.checkConnection() }, enabled = !state.busy) { Text("保存并检查连接") }
        }
    }, confirmButton = { TextButton(onClick = { error = vm.saveSettings(url, token); if (error == null) dismiss() }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("返回") } })
}

internal fun jobStateLabel(state: JobState) = when (state) {
    JobState.QUEUED -> "等待处理"; JobState.RUNNING -> "处理中"; JobState.SUCCEEDED -> "已完成"; JobState.PARTIAL -> "部分完成"
    JobState.FAILED -> "失败"; JobState.CANCEL_REQUESTED -> "取消待确认"; JobState.CANCELLED -> "已取消"; JobState.STALE_INPUT -> "输入已更新"; JobState.EXPIRED -> "已过期"
}
private fun resultStateLabel(state: ResultState) = when (state) {
    ResultState.SUPPORTED -> "有证据支持"; ResultState.PARTIAL -> "仅部分输入可分析"; ResultState.INSUFFICIENT_INPUT -> "输入不足，暂不能判断"
    ResultState.UNREADABLE -> "材料无法辨读"; ResultState.FAILED -> "分析失败"
}
private fun jobStageLabel(stage: String?) = when (stage) {
    null, "" -> "等待状态更新"
    "QUEUED" -> "等待处理"; "RUNNING" -> "正在处理"; "UPLOADING", "UPLOAD" -> "正在上传材料"
    "TRANSCRIBING", "ASR" -> "正在转写录音"; "ANALYZING", "MODEL" -> "正在分析课堂内容"; "VALIDATING" -> "正在校验结果与证据"
    else -> if (stage.any { it.code in 0x4E00..0x9FFF }) stage else "正在处理，等待服务更新"
}
private fun runLabel(state: AnalysisRunState) = when (state) {
    AnalysisRunState.PREPARED -> "准备就绪"; AnalysisRunState.RUNNING -> "正在处理课堂材料"; AnalysisRunState.PAUSED -> "已暂停，可继续原任务"
    AnalysisRunState.WAITING_REVIEW -> "等待人工核对"; AnalysisRunState.WAITING_DECISION -> "需要确认材料或输入变化"; AnalysisRunState.COMPLETED -> "本轮分析已完成"
    AnalysisRunState.PARTIAL -> "部分完成，已有结果可查看"; AnalysisRunState.CANCEL_REQUESTED -> "已停止后续步骤，云端取消待确认"; AnalysisRunState.CANCELLED -> "本轮已停止"
}
private fun stepLabel(state: AnalysisStepState) = when (state) {
    AnalysisStepState.PLANNED -> "待处理"; AnalysisStepState.BLOCKED -> "待补充"; AnalysisStepState.RUNNING -> "处理中"; AnalysisStepState.SUCCEEDED -> "已完成"; AnalysisStepState.PARTIAL -> "部分完成"
    AnalysisStepState.FAILED -> "失败"; AnalysisStepState.SKIPPED -> "已跳过"; AnalysisStepState.REUSED -> "复用已有结果"; AnalysisStepState.CANCEL_REQUESTED -> "取消待确认"; AnalysisStepState.CANCELLED -> "已取消"; AnalysisStepState.STALE_INPUT -> "输入已变化"
}
private fun durationLabel(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
