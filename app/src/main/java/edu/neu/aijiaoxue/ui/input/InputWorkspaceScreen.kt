package edu.neu.aijiaoxue.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.CourseReferenceVersion
import edu.neu.aijiaoxue.data.entity.TranscriptVersion
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.PhaseSegment
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.ui.evaluation.timeText
import org.json.JSONObject
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputWorkspaceScreen(vm: InputWorkspaceViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text(if (state.task == null) "课程要求" else "课堂分析资料") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }, actions = { TextButton(onClick = vm::refresh) { Text("刷新") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.course?.let { Text(it.name, style = MaterialTheme.typography.titleLarge) }
            state.task?.let { Text("${it.code} · ${it.chapter.orEmpty()}\n${if (state.editable) "资料修改后，相关旧分析需重新校验" else "正式报告已冻结，原课堂环节和补录事实只读"}") }
            if (state.role == Role.ADMIN) ReferenceEditor(state.busy, vm::createReference)
            InputBlock("课程要求版本与本次范围") {
                if (state.references.isEmpty()) Text("暂无课程要求，请由管理员在课程要求页面录入。")
                state.references.forEach { version -> ReferenceVersionCard(version, state.task?.courseReferenceVersionId == version.id, state.task?.referenceSelectionJson, state.editable && !state.busy) { points, objectives -> vm.bindReference(version.id, points, objectives) } }
            }
            if (state.task != null) {
                PhaseEditor(state.phases, state.phaseStatus, state.editable && !state.busy, vm::savePhases)
                FactEditor(state, vm)
                InputBlock("真实转写与说话角色") {
                    Text("转写由分析页的真实录音转写任务生成。校订追加新版本；原句音频范围保留。")
                    if (state.transcripts.isEmpty()) Text("尚无转写，请先在分析页对真实录音执行转写。")
                    state.transcripts.filter { InputWorkspaceViewModel.firstClassMs(it) != null }.forEach { version -> TranscriptEditor(version, state.busy, vm) }
                    val unknown = state.transcripts.filter { InputWorkspaceViewModel.firstClassMs(it) == null }
                    if (unknown.isNotEmpty()) {
                        Text("课堂时点未知的录音", style = MaterialTheme.typography.titleMedium)
                        Text("以下录音缺少可靠课堂映射，单独列出；列表位置不代表授课先后，仍可按文件内时间播放。")
                        unknown.forEach { version -> TranscriptEditor(version, state.busy, vm) }
                    }
                }
                InputBlock("表达统计排除项") {
                    var terms by remember(state.professionalTerms) { mutableStateOf(state.professionalTerms) }
                    var ignored by remember(state.ignoredExpressions) { mutableStateOf(state.ignoredExpressions) }
                    OutlinedTextField(terms, { terms = it }, label = { Text("专业术语，每行一条") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ignored, { ignored = it }, label = { Text("人工忽略表达，每行一条") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.saveTerms(terms, ignored) }, enabled = !state.busy) { Text("保存排除版本") }
                }
                InputBlock("原始材料") {
                    state.sources.filter { it.key.startsWith("m") }.forEach { source -> TextButton(onClick = { vm.openSource(source.sourceRefJson) }, enabled = !state.busy) { Text(source.label) } }
                    if (state.sources.isEmpty()) Text("尚未采集材料")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    state.resolvedSource?.let { EvidenceSourceDialog(it, vm::closeSource) }
}

@Composable
private fun InputBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } }
}

@Composable
private fun ReferenceEditor(busy: Boolean, save: (String, String, String, String) -> Unit) {
    var chapter by remember { mutableStateOf("") }; var source by remember { mutableStateOf("") }
    var points by remember { mutableStateOf("") }; var objectives by remember { mutableStateOf("") }
    InputBlock("录入新课程要求版本") {
        OutlinedTextField(chapter, { chapter = it }, label = { Text("章节范围") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(source, { source = it }, label = { Text("录入依据，如大纲2026版第3章") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(points, { points = it }, label = { Text("知识点，每行一条，编号 KP1、KP2…") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(objectives, { objectives = it }, label = { Text("目标，每行一条；可写：目标正文 | KP1,KP2") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { save(chapter, source, points, objectives) }, enabled = !busy) { Text("保存新版本") }
    }
}

@Composable
private fun ReferenceVersionCard(version: CourseReferenceVersion, bound: Boolean, selectionJson: String?, editable: Boolean, bind: (List<String>, List<String>) -> Unit) {
    val json = remember(version.payloadJson) { JSONObject(version.payloadJson) }
    fun selected(key: String): Set<String> = if (!bound || selectionJson == null) emptySet() else runCatching { JSONObject(selectionJson).getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    var points by remember(version.id, bound, selectionJson) { mutableStateOf(selected("selectedKnowledgePointIds")) }
    var objectives by remember(version.id, bound, selectionJson) { mutableStateOf(selected("selectedObjectiveIds")) }
    Text("v${version.revision} · ${version.chapterScope}${if (bound) " · 当前绑定" else ""}", style = MaterialTheme.typography.titleSmall)
    Text(version.sourceDescription)
    for (key in listOf("knowledgePoints", "objectives")) {
        val a = json.getJSONArray(key)
        for (i in 0 until a.length()) {
            val row = a.getJSONObject(i); val id = row.getString("id")
            Row { if (editable) Checkbox(checked = id in if (key == "knowledgePoints") points else objectives, onCheckedChange = { checked -> if (key == "knowledgePoints") points = if (checked) points + id else points - id else objectives = if (checked) objectives + id else objectives - id })
                Text("$id ${row.getString("text")}", Modifier.weight(1f).padding(top = 12.dp)) }
        }
    }
    if (editable) Button(onClick = { bind(points.toList(), objectives.toList()) }) { Text("绑定所选课堂范围") }
    HorizontalDivider()
}

@Composable
private fun PhaseEditor(phases: List<PhaseSegment>, status: PhaseStatus?, editable: Boolean, save: (List<PhaseSegment>, Boolean) -> Unit) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }; var start by remember { mutableStateOf("") }; var end by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ActivityKind.LECTURE) }; var note by remember { mutableStateOf("") }; var formError by remember { mutableStateOf<String?>(null) }
    InputBlock("人工教学环节") {
        Text(if (status == PhaseStatus.DRAFT) "当前显示未确认草稿，下游仍使用上一个确认版本。" else "按课堂秒数标记；区间不可重叠，允许未标记空档。")
        phases.forEach { p ->
            Text("${p.label} · ${activityLabel(p.kind)} · ${p.startMs / 1000}–${p.endMs / 1000} 秒")
            if (editable) Row { TextButton(onClick = { editingId = p.id; label = p.label; start = (p.startMs / 1000).toString(); end = (p.endMs / 1000).toString(); kind = p.kind; note = p.note }) { Text("编辑") }; TextButton(onClick = { save(phases.filterNot { it.id == p.id }, false) }, enabled = phases.size > 1) { Text("删除") } }
        }
        if (editable) {
            OutlinedTextField(label, { label = it }, label = { Text("环节名称") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(start, { start = it }, label = { Text("开始秒") }, modifier = Modifier.weight(1f)); OutlinedTextField(end, { end = it }, label = { Text("结束秒") }, modifier = Modifier.weight(1f)) }
            var expanded by remember { mutableStateOf(false) }
            Box { TextButton(onClick = { expanded = true }) { Text("活动类型：${activityLabel(kind)}") }; DropdownMenu(expanded, { expanded = false }) { ActivityKind.entries.forEach { k -> DropdownMenuItem(text = { Text(activityLabel(k)) }, onClick = { kind = k; expanded = false }) } } }
            OutlinedTextField(note, { note = it }, label = { Text("事实备注") }, modifier = Modifier.fillMaxWidth())
            formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val from = start.toLongOrNull(); val to = end.toLongOrNull()
                if (from == null || to == null || from < 0 || to <= from || from > Long.MAX_VALUE / 1000 || to > Long.MAX_VALUE / 1000) formError = "请输入合法整数秒"
                else { formError = null; val segment = PhaseSegment(editingId ?: "phase-${UUID.randomUUID()}", kind, label, from * 1000, to * 1000, note); save((phases.filterNot { it.id == editingId } + segment).sortedBy { it.startMs }, false); editingId = null; label = ""; start = ""; end = ""; note = "" }
            }) { Text(if (editingId == null) "添加并保存草稿" else "保存环节修改") }
            Button(onClick = { save(phases, true) }, enabled = phases.isNotEmpty()) { Text("确认当前环节版本") }
        }
    }
}

@Composable
private fun FactEditor(state: InputWorkspaceUiState, vm: InputWorkspaceViewModel) {
    var text by remember { mutableStateOf("") }; var at by remember { mutableStateOf("") }; var start by remember { mutableStateOf("") }; var end by remember { mutableStateOf("") }
    var sourceJson by remember { mutableStateOf<String?>(null) }; var supersedes by remember { mutableStateOf<Long?>(null) }
    InputBlock("人工补充事实") {
        Text("填写课堂实际发生的事实，选择来源或填写发生时间。补录时间由系统另行记录。")
        state.facts.forEach { fact ->
            Text(fact.description)
            Text("发生：${fact.classAtMs?.let { "${it / 1000} 秒" } ?: fact.classStartMs?.let { "${it / 1000}–${fact.classEndMs!! / 1000} 秒" } ?: "见关联来源"} · 补录：${timeText(fact.recordedAt)}", style = MaterialTheme.typography.bodySmall)
            Row {
                fact.sourceRefJson?.let { json -> TextButton(onClick = { vm.openSource(json) }) { Text("打开来源") } }
                if (state.editable) TextButton(onClick = { supersedes = fact.id; text = fact.description; sourceJson = fact.sourceRefJson; at = fact.classAtMs?.div(1000)?.toString().orEmpty(); start = fact.classStartMs?.div(1000)?.toString().orEmpty(); end = fact.classEndMs?.div(1000)?.toString().orEmpty() }) { Text("追加更正") }
            }
        }
        if (state.editable && state.task?.classEndAt != null) {
            OutlinedTextField(text, { text = it }, label = { Text(if (supersedes == null) "事实描述" else "更正后的事实描述") }, modifier = Modifier.fillMaxWidth())
            var expanded by remember { mutableStateOf(false) }
            Box { TextButton(onClick = { expanded = true }) { Text(state.sources.firstOrNull { it.sourceRefJson == sourceJson }?.label ?: "选择原课堂来源（可选）") }; DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text("不选择来源，填写发生时间") }, onClick = { sourceJson = null; expanded = false })
                state.sources.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sourceJson = s.sourceRefJson; expanded = false }) }
            } }
            OutlinedTextField(at, { at = it }, label = { Text("发生时点（秒，与区间二选一）") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(start, { start = it }, label = { Text("区间开始秒") }, modifier = Modifier.weight(1f)); OutlinedTextField(end, { end = it }, label = { Text("区间结束秒") }, modifier = Modifier.weight(1f)) }
            Button(onClick = { vm.addFact(text, sourceJson, at, start, end, supersedes) }, enabled = !state.busy) { Text(if (supersedes == null) "保存独立事实" else "追加更正版本") }
            if (supersedes != null) TextButton(onClick = { supersedes = null; text = ""; sourceJson = null; at = ""; start = ""; end = "" }) { Text("取消更正") }
        }
    }
}

@Composable
private fun TranscriptEditor(version: TranscriptVersion, busy: Boolean, vm: InputWorkspaceViewModel) {
    Text("音频 #${version.materialId} · 转写 v${version.revision} · ${AnalysisJson.summary(version.state.name)}", style = MaterialTheme.typography.titleSmall)
    InputWorkspaceViewModel.firstClassMs(version)?.let { Text("已知课堂起点：${it / 1000} 秒", style = MaterialTheme.typography.bodySmall) }
    val segments = remember(version.id) { AnalysisJson.objects(JSONObject(version.payloadJson).getJSONArray("segments")).sortedBy { it.getLong("mediaStartMs") } }
    var page by remember(version.id) { mutableIntStateOf(0) }
    val pageCount = maxOf(1, (segments.size + 49) / 50)
    if (segments.isEmpty()) Text("未识别到语音")
    TranscriptPages(segments.size, page, pageCount) { page = it }
    for (i in page * 50 until minOf(segments.size, (page + 1) * 50)) {
        val segment = segments[i]; val id = segment.getString("segmentId")
        var text by remember(version.id, id) { mutableStateOf(segment.getString("text")) }
        var role by remember(version.id, id) { mutableStateOf(runCatching { SpeakerRole.valueOf(segment.optString("speakerRole", "UNKNOWN")) }.getOrDefault(SpeakerRole.UNKNOWN)) }
        OutlinedTextField(text, { text = it }, label = { Text("${segment.getLong("mediaStartMs") / 1000}–${segment.getLong("mediaEndMs") / 1000} 秒 · 文件时间") }, modifier = Modifier.fillMaxWidth())
        Row { SpeakerRole.entries.forEach { option -> TextButton(onClick = { role = option }) { Text((if (role == option) "✓ " else "") + when (option) { SpeakerRole.TEACHER -> "教师"; SpeakerRole.STUDENT -> "学生"; SpeakerRole.UNKNOWN -> "未知" }) } } }
        Row { TextButton(onClick = { vm.correctTranscript(version.id, id, text, role) }, enabled = !busy) { Text("保存校订") }; TextButton(onClick = { vm.openSource(InputWorkspaceViewModel.ref("TRANSCRIPT_SEGMENT", version.taskId, version.id, version.revision, version.contentHash, id, segment.getString("text")).toString()) }, enabled = !busy) { Text("定位原音频") } }
        HorizontalDivider()
    }
    if (pageCount > 1) TranscriptPages(segments.size, page, pageCount) { page = it }
}

@Composable
private fun TranscriptPages(total: Int, page: Int, pageCount: Int, onPage: (Int) -> Unit) {
    Text("共 $total 句 · 第 ${page + 1}/$pageCount 页 · 每页最多 50 句", style = MaterialTheme.typography.bodySmall)
    if (pageCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("上一页") }
        OutlinedButton(onClick = { onPage(page + 1) }, enabled = page + 1 < pageCount) { Text("下一页") }
    }
}

private fun activityLabel(kind: ActivityKind): String = when (kind) {
    ActivityKind.INTRODUCTION -> "导入"
    ActivityKind.LECTURE -> "讲授"
    ActivityKind.DISCUSSION -> "讨论"
    ActivityKind.PRACTICE -> "练习"
    ActivityKind.SUMMARY -> "总结"
    ActivityKind.OTHER -> "其他"
}
