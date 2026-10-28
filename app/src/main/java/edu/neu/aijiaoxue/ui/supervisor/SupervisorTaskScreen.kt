package edu.neu.aijiaoxue.ui.supervisor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.repository.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.navigation.Routes
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject

data class SupervisorTaskState(val detail: SupervisorTaskDetail? = null, val loading: Boolean = true, val error: String? = null)
class SupervisorTaskViewModel(private val repo: SupervisorPortalRepository, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val state = MutableStateFlow(SupervisorTaskState())
    val uiState = state.asStateFlow()
    init { reload() }
    fun reload() { viewModelScope.launch { try { state.value = SupervisorTaskState(repo.task(actorId,taskId), false) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { state.value = SupervisorTaskState(loading = false,error = e.message) } } }
}

@Composable
fun SupervisorTaskScreen(vm: SupervisorTaskViewModel, onBack: () -> Unit, navigate: (String) -> Unit) {
    val state by vm.uiState.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }
    ModulePage("课堂档案", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("task-overview"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::reload) { Text("重新加载") } } }
            state.detail?.let { detail ->
                val task = detail.row
                item { Text(task.courseName, style = MaterialTheme.typography.headlineSmall); Text("${task.teacherName} · ${task.supervisionDate} 第 ${task.period} 节"); Text(task.code, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (detail.origins.isNotEmpty()) item { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("本次复评关注", style = MaterialTheme.typography.titleMedium)
                        detail.origins.forEach { origin -> Text(origin.originIssueSnapshot); TextButton(onClick = { navigate(Routes.reviewDetail(origin.id)) }) { Text("查看原问题与对比") } }
                    }
                } }
                item { PortalCard {
                    Text("本次课堂", style = MaterialTheme.typography.titleMedium)
                    Text("${task.type.label} · ${taskLabel(task.status)}")
                    Text("授课章节：${task.chapter ?: "未填写"}")
                    Text(task.progressText())
                    Button(onClick = { navigate(if (task.status == TaskStatus.NOT_STARTED) Routes.capture(task.id) else task.nextRoute()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (task.status == TaskStatus.NOT_STARTED) "开始课堂采集" else task.nextLabel()) }
                } }
                item { PortalCard {
                    Text("教学目标与课程范围", style = MaterialTheme.typography.titleMedium)
                    detail.reference?.let { reference ->
                        Text("${reference.chapterScope} · v${reference.revision}")
                        Text(reference.sourceDescription, style = MaterialTheme.typography.bodySmall)
                        val json = runCatching { JSONObject(reference.payloadJson) }.getOrNull()
                        val selected = runCatching { JSONObject(detail.referenceSelectionJson.orEmpty()) }.getOrNull()
                        for ((key, selectionKey, label) in listOf(Triple("objectives", "selectedObjectiveIds", "本节课目标"), Triple("knowledgePoints", "selectedKnowledgePointIds", "本节课知识点"))) {
                            val ids = selected?.optJSONArray(selectionKey)?.let { values -> (0 until values.length()).map { values.optString(it) }.toSet() }.orEmpty()
                            val rows = json?.optJSONArray(key)
                            val texts = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.takeIf { it.optString("id") in ids }?.optString("text") }
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(texts.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" } ?: "尚未选择，请在课程范围中确认。")
                        }
                    }
                        ?: Text("尚未绑定本节课教学目标。可先查看课程要求，再选择本次授课范围。")
                    OutlinedButton(onClick = { navigate(Routes.inputWorkspace(task.id)) }) { Text("查看与选择课程范围") }
                } }
                item { PortalCard {
                    Text("同课程历史评价", style = MaterialTheme.typography.titleMedium)
                    if (detail.history.isEmpty()) Text("暂无本人已发布的历史督导报告")
                    detail.history.take(5).forEach { history -> TextButton(onClick = { navigate(Routes.historyDetail(history.id)) }) { Text("${history.supervisionDate} · ${history.chapter ?: history.code}") } }
                } }
                item { PortalCard {
                    Text("报告与持续改进", style = MaterialTheme.typography.titleMedium)
                    Text(if (task.status == TaskStatus.COMPLETED) "报告已发布，正式问题已进入改进跟踪。" else "完成评价并发布后，可在此追踪教师行动与复评结论。")
                    OutlinedButton(onClick = { navigate(Routes.taskTracking(task.id)) }) { Text("查看关联改进") }
                } }
            }
        }
    }
}
