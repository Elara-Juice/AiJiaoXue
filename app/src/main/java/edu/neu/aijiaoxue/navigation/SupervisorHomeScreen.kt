package edu.neu.aijiaoxue.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.ui.course.MyTasksViewModel
import edu.neu.aijiaoxue.ui.components.StatusBadge
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.ui.theme.AppGlyph
import edu.neu.aijiaoxue.ui.theme.AppIcon

/** F0 角色首页：教学督导 —— 课程检索、我的督导任务、历史记录。 */
@Composable
fun SupervisorHomeScreen(onNavigate: (String) -> Unit, viewModel: MyTasksViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val current = state.unfinished.firstOrNull()
    HomeScaffold(
        entries = listOf(
            HomeEntry("我的督导任务", "继续未完成的采集或评价", Routes.MY_TASKS),
            HomeEntry("课程检索", "按课程名称、教师姓名查找课程", Routes.COURSE_SEARCH),
            HomeEntry("历史记录", "查看本人已完成的督导评价", Routes.HISTORY),
            HomeEntry("复评与核验", "关联原课堂与复评课堂，比较前后指标并核验问题改善", Routes.REVIEW_LIST),
        ),
        onNavigate = onNavigate,
        featuredContent = {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("继续当前督导", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    when {
                        state.isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        current == null -> {
                            Text("准备好开始新一堂课了吗？", style = MaterialTheme.typography.titleMedium)
                            Text("从课程检索开始，创建属于本次课堂的督导任务。", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { onNavigate(Routes.COURSE_SEARCH) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                AppIcon(AppGlyph.SEARCH, tint = MaterialTheme.colorScheme.onPrimary)
                                Text("查找课程")
                            }
                        }
                        else -> {
                            val row = current.row
                            Text(row.courseName, style = MaterialTheme.typography.headlineSmall)
                            Text("${row.teacherName} · ${row.supervisionDate} 第 ${row.period} 节", style = MaterialTheme.typography.bodyMedium)
                            StatusBadge("${row.status.label} · ${current.materialCount} 份材料")
                            val (label, route) = when (row.status) {
                                TaskStatus.NOT_STARTED -> "开始采集" to Routes.capture(row.id)
                                TaskStatus.IN_PROGRESS -> "继续采集" to Routes.capture(row.id)
                                TaskStatus.PENDING_EVALUATION -> "查看分析" to Routes.analysis(row.id)
                                TaskStatus.COMPLETED -> "查看报告" to Routes.historyDetail(row.id)
                            }
                            Button(onClick = { onNavigate(route) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                AppIcon(
                                    when (row.status) {
                                        TaskStatus.NOT_STARTED, TaskStatus.IN_PROGRESS -> AppGlyph.PLAY
                                        TaskStatus.PENDING_EVALUATION -> AppGlyph.ANALYTICS
                                        TaskStatus.COMPLETED -> AppGlyph.REPORT
                                    },
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                                Text(label)
                            }
                            Text("待处理 ${state.unfinished.size} 项 · 已完成 ${state.completed.size} 项", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        },
    )
}
