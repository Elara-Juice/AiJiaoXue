package edu.neu.aijiaoxue.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputWorkspaceScreen(vm: InputWorkspaceViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val tabs = if (state.task == null) listOf(InputWorkspaceTab.COURSE) else InputWorkspaceTab.entries
    val selected = state.editor.tab.takeIf { it in tabs } ?: InputWorkspaceTab.COURSE
    val material = state.transcripts.firstOrNull { it.materialId == state.editor.selectedMaterialId } ?: state.transcripts.firstOrNull()
    val dirty = material?.let { state.editor.transcripts[it.materialId]?.changes?.size } ?: 0
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (state.task == null) "课程要求" else "课堂分析资料") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }, actions = { TextButton(onClick = vm::refresh, enabled = !state.busy) { Text("刷新") } }) },
        bottomBar = {
            if (state.task != null) Surface(shadowElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (selected) {
                        InputWorkspaceTab.TRANSCRIPT -> {
                            Text("未保存 $dirty 句 · 保留原音频范围", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { material?.let { vm.saveTranscriptDraft(it.materialId) } }, enabled = !state.busy && dirty > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("input-save-transcript")) { Text(if (state.busy) "正在保存…" else "保存本次修改") }
                        }
                        InputWorkspaceTab.FACTS -> if (state.editable) Button(onClick = vm::saveFactDraft, enabled = !state.busy && state.editor.fact.description.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("input-save-fact")) { Text(if (state.busy) "正在保存…" else "保存补充事实") }
                        InputWorkspaceTab.COURSE -> Text("选择版本后，勾选本节课涉及的知识点与目标。", style = MaterialTheme.typography.bodySmall)
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                state.course?.let { Text(it.name, style = MaterialTheme.typography.titleLarge) }
                state.task?.let { Text("${it.code} · ${it.chapter.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                if (state.task != null && !state.editable) Text("正式报告已冻结；原环节与补录事实只读。转写校订不改变已发布内容。", style = MaterialTheme.typography.bodySmall)
                state.draftWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.task == null) { state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; state.message?.let { Text(it) } }
            }
            if (tabs.size > 1) TabRow(selectedTabIndex = tabs.indexOf(selected)) {
                tabs.forEach { tab -> Tab(selected = tab == selected, onClick = { vm.selectTab(tab) }, text = { Text(when (tab) { InputWorkspaceTab.COURSE -> "课程范围"; InputWorkspaceTab.TRANSCRIPT -> "转写校订"; InputWorkspaceTab.FACTS -> "环节与事实" }) }, modifier = Modifier.testTag("input-tab-${tab.name}")) }
            }
            if (!state.loading) key(selected) {
                val scroll = rememberScrollState(state.editor.scrollOffsets[selected] ?: 0)
                LaunchedEffect(scroll, selected) {
                    snapshotFlow { scroll.value to scroll.isScrollInProgress }.filter { !it.second }.distinctUntilChanged().collect { (offset, _) -> vm.setScroll(selected, offset) }
                }
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (selected) {
                        InputWorkspaceTab.COURSE -> {
                            if (state.role == Role.ADMIN) ReferenceEditor(state.busy, vm::createReference)
                            InputBlock("本节课教学范围") {
                                if (state.references.isEmpty()) Text("暂无课程要求，请由管理员录入大纲知识点和教学目标。")
                                state.references.forEach { version -> ReferenceVersionCard(version, state.task?.courseReferenceVersionId == version.id, state.task?.referenceSelectionJson, state.editable && !state.busy) { points, objectives -> vm.bindReference(version.id, points, objectives) } }
                            }
                        }
                        InputWorkspaceTab.TRANSCRIPT -> {
                            Text("逐句核对文字和说话角色，完成后一次保存。修改会保存在本机草稿中。")
                            if (material == null) Text("尚无转写。返回分析准备，对录音执行转写后再来校订。")
                            else {
                                var expanded by remember { mutableStateOf(false) }
                                Box { OutlinedButton(onClick = { expanded = true }) { Text("音频 #${material.materialId} · v${material.revision} · 切换材料") }; DropdownMenu(expanded, { expanded = false }) { state.transcripts.forEach { version -> DropdownMenuItem(text = { Text("音频 #${version.materialId} · ${if (InputWorkspaceViewModel.firstClassMs(version) == null) "课堂时点未知" else "已映射课堂时间"}") }, onClick = { vm.selectMaterial(version.materialId); expanded = false }) } } }
                                TranscriptEditor(material, state, vm)
                            }
                            state.editor.transcripts.filter { (id, draft) -> draft.changes.isNotEmpty() && state.transcripts.none { it.materialId == id } }.values.forEach { draft -> InputBlock("音频 #${draft.materialId} 的来源已失效") { Text("草稿仍保留，不能保存到其他材料。可复制文字后核对原材料。"); draft.changes.values.forEach { Text(it.text) } } }
                            var advanced by rememberSaveable { mutableStateOf(false) }
                            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置：术语与忽略表达") }
                            if (advanced) TermEditor(state, vm)
                        }
                        InputWorkspaceTab.FACTS -> { PhaseEditor(state, vm); FactEditor(state, vm) }
                    }
                    if (state.task != null) {
                        var originals by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { originals = !originals }) { Text(if (originals) "收起原始材料" else "查看原始材料") }
                        if (originals) state.sources.filter { it.key.startsWith("m") }.forEach { source -> TextButton(onClick = { vm.openSource(source.sourceRefJson) }, enabled = !state.busy) { Text(source.label) } }
                    }
                }
            }
        }
    }
    if (state.editor.phase.visible && state.editable) PhaseEditorSheet(state, vm)
    state.resolvedSource?.let { EvidenceSourceDialog(it, vm::closeSource) }
}

@Composable
internal fun InputBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
}

@Composable
internal fun MutationMessage(state: MutationState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("input-mutation-error")) }
    if (state.status == UiMutationStatus.SAVED) Text("保存成功", color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TermEditor(state: InputWorkspaceUiState, vm: InputWorkspaceViewModel) {
    var terms by rememberSaveable(state.professionalTerms) { mutableStateOf(state.professionalTerms) }
    var ignored by rememberSaveable(state.ignoredExpressions) { mutableStateOf(state.ignoredExpressions) }
    OutlinedTextField(terms, { terms = it }, label = { Text("专业术语，每行一条") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(ignored, { ignored = it }, label = { Text("人工忽略表达，每行一条") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { vm.saveTerms(terms, ignored) }, enabled = !state.busy) { Text("保存排除版本") }
}

@Composable
private fun ReferenceEditor(busy: Boolean, save: (String, String, String, String) -> Unit) {
    var chapter by rememberSaveable { mutableStateOf("") }; var source by rememberSaveable { mutableStateOf("") }
    var points by rememberSaveable { mutableStateOf("") }; var objective by rememberSaveable { mutableStateOf("") }
    var linked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var goals by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    InputBlock("录入课程要求") {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起录入表单" else "新增课程要求版本") }
        if (expanded) {
            OutlinedTextField(chapter, { chapter = it }, label = { Text("章节范围") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(source, { source = it }, label = { Text("录入依据，例如大纲第3章") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(points, { points = it; linked = emptyList() }, label = { Text("知识点，每行一条") }, modifier = Modifier.fillMaxWidth())
            goals.forEachIndexed { index, goal -> Row { Text(goal.substringBefore('|'), Modifier.weight(1f)); TextButton(onClick = { goals = goals.filterIndexed { i, _ -> i != index } }) { Text("删除") } } }
            OutlinedTextField(objective, { objective = it }, label = { Text("添加一个教学目标") }, modifier = Modifier.fillMaxWidth())
            points.lines().filter(String::isNotBlank).forEachIndexed { index, text -> val id = "KP${index + 1}"; Row { Checkbox(id in linked, { checked -> linked = if (checked) linked + id else linked - id }); Text(text, Modifier.weight(1f).padding(top = 12.dp)) } }
            TextButton(onClick = { goals = goals + (objective.trim() + " | " + linked.joinToString(",")); objective = ""; linked = emptyList() }, enabled = objective.isNotBlank()) { Text("加入目标列表") }
            Button(onClick = { save(chapter, source, points, goals.joinToString("\n")) }, enabled = !busy && goals.isNotEmpty()) { Text("保存新版本") }
        }
    }
}
