package edu.neu.aijiaoxue.ui.supervisor

import edu.neu.aijiaoxue.ui.theme.AppBackAction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.dao.SupervisorTaskRow
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.TrackedIssue
import edu.neu.aijiaoxue.navigation.Routes
import edu.neu.aijiaoxue.ui.components.StatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorPortalScreen(model: SupervisorPortalViewModel, tab: SupervisorTab, onNavigate: (String) -> Unit, taskId: Long? = null, onBack: (() -> Unit)? = null) {
    val state by model.uiState.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var taskFilter by rememberSaveable { mutableStateOf<TaskStatus?>(null) }
    var issueFilter by rememberSaveable { mutableStateOf<TrackingStage?>(null) }
    val filteredTasks = state.tasks.filter { (taskFilter == null || it.status == taskFilter) && (it.courseName + it.teacherName + it.code).contains(query.trim(), true) }
    val filteredIssues = state.issues.filter { (taskId == null || it.row.taskId == taskId || it.row.id in state.relatedIssueIds) && (issueFilter == null || it.stage == issueFilter) && (it.row.courseName + it.row.teacherName + it.row.description).contains(query.trim(), true) }
    Scaffold(topBar = { TopAppBar(title = { Text(if (taskId != null) "本课堂关联改进" else tab.label) },
        navigationIcon = { onBack?.let { AppBackAction(it) } },
        actions = { if (tab == SupervisorTab.TASKS) TextButton(onClick = { onNavigate(Routes.COURSE_SEARCH) }) { Text("新建督导") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("supervisor-${tab.name}"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::reload) { Text("重试") } } }
            if (tab == SupervisorTab.WORKBENCH) {
                item { Text(Session.currentUser?.name?.takeIf { it.isNotBlank() }?.let { "$it，你好" } ?: "你好，教学督导", style = MaterialTheme.typography.headlineSmall); Text(DateFormats.today() + " · 从当前待办继续", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val current = state.tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
                    ?: state.tasks.firstOrNull { it.status == TaskStatus.PENDING_EVALUATION }
                    ?: state.tasks.firstOrNull { it.status == TaskStatus.NOT_STARTED && it.supervisionDate == DateFormats.today() }
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("继续当前督导", style = MaterialTheme.typography.labelLarge)
                            Text(current?.courseName ?: "从一堂新课开始", style = MaterialTheme.typography.headlineSmall)
                            Text(current?.let { "${it.teacherName} · ${it.supervisionDate} 第 ${it.period} 节\n${it.progressText()}" } ?: "查看课程目标，明确本次听课关注点。")
                            Button(onClick = { onNavigate(current?.nextRoute() ?: Routes.COURSE_SEARCH) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(current?.nextLabel() ?: "查找课程") }
                        }
                    }
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onNavigate(Routes.COURSE_SEARCH) }, modifier = Modifier.weight(1f)) { Text("新建督导") }
                    OutlinedButton(onClick = { onNavigate(Routes.MY_TASKS) }, modifier = Modifier.weight(1f)) { Text("全部任务") }
                } }
                val today = state.tasks.filter { it.supervisionDate == DateFormats.today() && it.status == TaskStatus.NOT_STARTED }
                if (today.isNotEmpty()) item { Text("今日听课", style = MaterialTheme.typography.titleMedium) }
                items(today, key = { "today-${it.id}" }) { PortalTaskCard(it, onNavigate) }
                item { Text("我的待办", style = MaterialTheme.typography.titleMedium) }
                val pending = state.tasks.filter { it.status == TaskStatus.PENDING_EVALUATION }
                items(pending, key = { "pending-${it.id}" }) { PortalTaskCard(it, onNavigate) }
                val open = state.issues.filter { it.stage != TrackingStage.CLOSED }
                items(open.take(8), key = { "issue-${it.row.id}" }) { PortalIssueCard(it, onNavigate) }
                if (!state.loading && pending.isEmpty() && open.isEmpty()) item { PortalEmpty("暂无待处理事项", "新的分析、评价和改进记录会在这里提示。") }
                if (open.size > 8) item { TextButton(onClick = { onNavigate(Routes.SUPERVISOR_TRACKING) }) { Text("查看全部改进事项（${open.size}）") } }
                val recent = state.tasks.filter { it.status == TaskStatus.COMPLETED }.take(3)
                if (recent.isNotEmpty()) item { Text("最近发布", style = MaterialTheme.typography.titleMedium) }
                items(recent, key = { "recent-${it.id}" }) { PortalTaskCard(it, onNavigate) }
            } else {
                item { OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(if (tab == SupervisorTab.TASKS) "搜索课程、教师或任务编号" else "搜索课程、教师或问题") }, modifier = Modifier.fillMaxWidth().testTag("portal-search")) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == SupervisorTab.TASKS) {
                        FilterChip(taskFilter == null, { taskFilter = null }, label = { Text("全部") })
                        TaskStatus.entries.forEach { status -> FilterChip(taskFilter == status, { taskFilter = status }, label = { Text(taskLabel(status)) }) }
                    } else {
                        FilterChip(issueFilter == null, { issueFilter = null }, label = { Text("全部") })
                        TrackingStage.entries.forEach { stage -> FilterChip(issueFilter == stage, { issueFilter = stage }, label = { Text(stage.label) }) }
                    }
                } }
                if (tab == SupervisorTab.TASKS) {
                    items(filteredTasks, key = { it.id }) { PortalTaskCard(it, onNavigate) }
                    if (!state.loading && filteredTasks.isEmpty()) item { PortalEmpty("没有符合条件的任务", "可调整筛选，或从右上角创建督导任务。") }
                } else {
                    item { Text("从正式报告的问题出发，跟踪教师行动，再以复评证据判断改善。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(filteredIssues, key = { it.row.id }) { PortalIssueCard(it, onNavigate) }
                    if (!state.loading && filteredIssues.isEmpty()) item { PortalEmpty("暂无匹配的改进问题", "报告发布后的问题会自动出现在这里；无主要问题的报告无需创建整改事项。") }
                    item { TextButton(onClick = { onNavigate(Routes.REVIEW_LIST) }) { Text("查看全部复评关联") } }
                }
            }
        }
    }
}

internal fun taskLabel(status: TaskStatus) = when (status) { TaskStatus.IN_PROGRESS -> "采集中"; TaskStatus.COMPLETED -> "已发布"; else -> status.label }

@Composable
internal fun PortalCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
internal fun PortalEmpty(title: String, text: String) { Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
internal fun PortalTaskCard(row: SupervisorTaskRow, navigate: (String) -> Unit) {
    PortalCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.courseName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            StatusBadge(taskLabel(row.status))
        }
        Text("${row.teacherName} · ${row.supervisionDate} 第 ${row.period} 节", style = MaterialTheme.typography.bodyMedium)
        row.chapter?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(row.progressText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isTaskOverdue(row.status, row.supervisionDate)) Text("已超过计划听课日期", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { navigate(row.nextRoute()) }, modifier = Modifier.weight(1f).testTag("task-next-${row.id}")) { Text(row.nextLabel()) }
            TextButton(onClick = { navigate(Routes.taskDetail(row.id)) }) { Text("课堂档案") }
        }
    }
}

@Composable
internal fun PortalIssueCard(issue: TrackedIssue, navigate: (String) -> Unit) {
    val row = issue.row
    Surface(Modifier.fillMaxWidth().clickable { navigate(Routes.issueDetail(row.id)) }.testTag("tracked-issue-${row.id}"), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(issue.stage.label)
            Text(row.description, style = MaterialTheme.typography.titleMedium)
            Text("${row.courseName} · ${row.teacherName}", style = MaterialTheme.typography.bodyMedium)
            Text("来源 ${row.supervisionDate} · 问题 #${row.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${row.doneCount}/${row.measureCount} 项措施完成 · ${row.executionCount} 条实施记录", style = MaterialTheme.typography.bodySmall)
            row.dueDate?.let { Text("最近期限 $it" + if (isMeasureOverdue(MeasureStatus.IN_PROGRESS, it)) " · 已逾期" else "", style = MaterialTheme.typography.bodySmall) }
            Text("查看问题与改进 →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
