package edu.neu.aijiaoxue.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.SupervisorTaskRow
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.capture.AudioRecorder
import kotlinx.coroutines.flow.collectLatest

fun SupervisorTab.route() = when (this) { SupervisorTab.WORKBENCH -> Routes.HOME_SUPERVISOR; SupervisorTab.TASKS -> Routes.MY_TASKS; SupervisorTab.TRACKING -> Routes.SUPERVISOR_TRACKING; SupervisorTab.PROFILE -> Routes.SUPERVISOR_ME }

@Composable
fun SupervisorNavigationFrame(nav: NavHostController, actorId: Long?, database: AppDatabase? = null, content: @Composable () -> Unit) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val selected = SupervisorTab.entries.firstOrNull { it.route() == route }
    val workspaceTab = when (route) {
        Routes.TASK_DETAIL -> TaskWorkspaceTab.OVERVIEW
        Routes.CAPTURE -> TaskWorkspaceTab.CAPTURE
        Routes.ANALYSIS, Routes.INPUT_WORKSPACE -> TaskWorkspaceTab.ANALYSIS
        Routes.EVALUATION, Routes.HISTORY_DETAIL -> TaskWorkspaceTab.REPORT
        Routes.TASK_TRACKING -> TaskWorkspaceTab.IMPROVEMENT
        else -> null
    }
    val taskId = entry?.arguments?.getLong(Routes.ARG_TASK_ID)?.takeIf { it > 0 }
    val recorder by AudioRecorder.state.collectAsState()
    val recordingHere = recorder.taskId == taskId && recorder.status != AudioRecorder.Status.IDLE
    val db = database ?: AppDatabase.get(LocalContext.current)
    val task by produceState<SupervisorTaskRow?>(null, entry, actorId) {
        value = null
        if (actorId != null && workspaceTab != null && taskId != null)
            db.supervisorPortalDao().observeTasks(actorId).collectLatest { value = it.firstOrNull { row -> row.id == taskId } }
    }
    if (actorId == null) { content(); return }
    Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = {
        if (selected != null) NavigationBar {
            SupervisorTab.entries.forEach { tab -> NavigationBarItem(selected = selected == tab,
                onClick = { nav.navigate(tab.route()) { popUpTo(Routes.HOME_SUPERVISOR) { saveState = true }; launchSingleTop = true; restoreState = true } },
                icon = { PortalNavigationIcon(tab) }, label = { Text(tab.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.testTag("nav-${tab.name}")) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (task != null && workspaceTab != null) Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskWorkspaceTab.entries.forEach { tab ->
                        val row = requireNotNull(task)
                        val ready = row.status in setOf(TaskStatus.PENDING_EVALUATION, TaskStatus.COMPLETED)
                        val destination = when (tab) {
                            TaskWorkspaceTab.OVERVIEW -> Routes.taskDetail(row.id)
                            TaskWorkspaceTab.CAPTURE -> Routes.capture(row.id)
                            TaskWorkspaceTab.ANALYSIS -> Routes.analysis(row.id)
                            TaskWorkspaceTab.REPORT -> if (row.status == TaskStatus.COMPLETED) Routes.historyDetail(row.id) else Routes.evaluation(row.id)
                            TaskWorkspaceTab.IMPROVEMENT -> Routes.taskTracking(row.id)
                        }
                        FilterChip(selected = tab == workspaceTab,
                            onClick = { if (tab != workspaceTab && !nav.popBackStack(destination, false)) nav.navigate(destination) { launchSingleTop = true } },
                            enabled = (!recordingHere || tab == workspaceTab) && (tab !in setOf(TaskWorkspaceTab.ANALYSIS, TaskWorkspaceTab.REPORT) || ready),
                            label = { Text(tab.label) }, modifier = Modifier.testTag("task-tab-${tab.name}"))
                    }
                }
                if (recordingHere) Text("录音尚未结束，请先停止并保存，再切换步骤。", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun PortalNavigationIcon(tab: SupervisorTab) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.8.dp.toPx())
        when (tab) {
            SupervisorTab.WORKBENCH -> {
                listOf(.12f to .12f, .56f to .12f, .12f to .56f, .56f to .56f).forEach { (x,y) -> drawRoundRect(color, Offset(w*x,h*y), Size(w*.32f,h*.32f), style = stroke) }
            }
            SupervisorTab.TASKS -> {
                drawRoundRect(color, Offset(w*.2f,h*.1f), Size(w*.6f,h*.8f), style = stroke)
                listOf(.35f,.55f,.75f).forEach { y -> drawLine(color, Offset(w*.32f,h*y), Offset(w*.68f,h*y), stroke.width) }
            }
            SupervisorTab.TRACKING -> {
                drawCircle(color, w*.35f, style = stroke)
                drawLine(color, Offset(w*.3f,h*.5f), Offset(w*.45f,h*.65f), stroke.width)
                drawLine(color, Offset(w*.45f,h*.65f), Offset(w*.72f,h*.35f), stroke.width)
            }
            SupervisorTab.PROFILE -> {
                drawCircle(color, w*.17f, Offset(w*.5f,h*.3f), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(w*.18f,h*.58f), Size(w*.64f,h*.55f), style = stroke)
            }
        }
    }
}
