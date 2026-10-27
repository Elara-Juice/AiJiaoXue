package edu.neu.aijiaoxue.ui.governance

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
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import edu.neu.aijiaoxue.ui.evaluation.*
import java.util.Locale

@Composable
fun GovernanceScreen(model: GovernanceViewModel, onBack: () -> Unit, onTask: (Long) -> Unit) {
    val state by model.uiState.collectAsState()
    ReloadWhenResumed { model.reload() }
    ModulePage("教学趋势与质量治理", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("governanceList"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                MessageCard("数据范围：本机已完成且已提交评价的课堂。按原课堂日期筛选；其后续正式复评可跨出该日期范围。缺测不补零，指标变化不自动等于教学改善。")
            }
            item {
                SectionCard("查询条件") {
                    val dashboard = state.dashboard
                    val filter = state.filter
                    if (dashboard?.role == Role.ADMIN) {
                        IdFilter("教师", filter.teacherId, dashboard.courseOptions.distinctBy { it.teacherId }
                            .map { it.teacherId to it.teacherName }, !state.loading) {
                            model.setFilter(filter.copy(teacherId = it, courseId = null))
                        }
                    }
                    IdFilter("课程", filter.courseId, dashboard?.courseOptions.orEmpty()
                        .filter { filter.teacherId == null || it.teacherId == filter.teacherId }
                        .map { it.courseId to "${it.courseName}（${it.teacherName}）" }, !state.loading) {
                        model.setFilter(filter.copy(courseId = it))
                    }
                    DateField("开始日期", filter.fromDate, { model.setFilter(filter.copy(fromDate = it)) }, enabled = !state.loading)
                    DateField("结束日期", filter.toDate, { model.setFilter(filter.copy(toDate = it)) }, enabled = !state.loading)
                    Button(onClick = model::reload, enabled = !state.loading) { Text("应用筛选 / 刷新") }
                }
            }
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { message -> item { MessageCard(message, error = true) } }
            if (!state.loading && state.dashboard != null && state.filter != state.dashboard!!.filter) item {
                MessageCard("筛选条件已修改，请点击应用；当前结果仍按上次已应用的条件显示。")
            }
            if (!state.loading) state.dashboard?.let { dashboard ->
                dashboard.summary?.let { summary -> item {
                    SectionCard("整体教学质量态势") {
                        Text("${summary.teacherCount} 位教师 · ${summary.courseCount} 门课程 · ${summary.classroomCount} 次正式督导")
                        Text("主要问题按正式问题条目统计：${summary.issuesByDimension.values.sum()} 条")
                        summary.issuesByDimension.entries.sortedByDescending { it.value }.forEach {
                            Text("${it.key?.label ?: "未指定维度"}：${it.value} 条")
                        }
                        Text("已确认计划的措施：${summary.measuresByStatus.values.sum()} 条")
                        MeasureStatus.entries.forEach { Text("${it.label}：${summary.measuresByStatus[it] ?: 0} 条") }
                        Text("有实际实施记录的措施：${summary.executedMeasureCount} 条；实施记录：${summary.executionRecordCount} 条")
                        Text("复评核验：${summary.latestVerifications.size} 组原问题与后续课堂关系，各取最新一次；整堂复评不当作单个问题。")
                        VerificationVerdict.entries.forEach { Text("${it.label}：${summary.verificationCounts[it] ?: 0} 组") }
                        Text("待重新核验：${summary.pendingVerifications.size} 组（不计入有效结论与改善率）")
                        Text(summary.improvementRate?.let { "有效改善率：${number(it * 100)}%" } ?: "有效改善率：暂无可计算结果")
                        Text("口径：有效已改善 ÷（有效已改善 + 有效未改善），继续观察单列。", style = MaterialTheme.typography.bodySmall)
                        if (summary.latestVerifications.isEmpty()) Text("暂无正式复评核验，未由措施完成状态推断成效。")
                    }
                } }
                if (dashboard.trends.isEmpty()) item { MessageCard("当前范围没有已提交的有效督导记录。") }
                dashboard.trends.forEach { trend -> item(key = "course-${trend.courseId}") {
                    SectionCard("${trend.teacherName} · ${trend.courseName}") {
                        if (trend.classrooms.size < 2) Text("仅有 ${trend.classrooms.size} 次有效督导，至少两次才能形成趋势。")
                        else if (!trend.hasComparableTrend) Text("已有 ${trend.classrooms.size} 次正式督导，但缺少可比指标；尚不能形成指标趋势。")
                        else Text("${trend.classrooms.size} 次正式督导，逐次比较同口径指标。")
                        trend.classrooms.forEach { task ->
                            TextButton(onClick = { onTask(task.taskId) }) {
                                Text("${task.supervisionDate} ${task.period}节 · ${task.taskCode} · 查看课堂来源")
                            }
                        }
                        trend.comparisons.forEach { pair ->
                            val before = trend.classrooms.first { it.taskId == pair.beforeTaskId }
                            val after = trend.classrooms.first { it.taskId == pair.afterTaskId }
                            Text("${before.supervisionDate} ${before.period}节 → ${after.supervisionDate} ${after.period}节：${pair.metricKey.label}",
                                style = MaterialTheme.typography.labelLarge)
                            Text(trendText(pair))
                            pair.after?.let { Text("口径：${it.unit} / ${it.denominator} / ${it.methodVersion}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } }
                dashboard.summary?.latestVerifications?.forEach { verification -> item(key = "verification-${verification.verificationId}") {
                    SectionCard("最新核验 · ${verification.verdict.label}") {
                        verification.ineligibilityReason?.let { MessageCard("待重新核验：$it", true) }
                        Text(verification.originIssueSnapshot)
                        Text("${verification.verifierName} · ${timeText(verification.verifiedAt)}")
                        Text(verification.basis)
                        TextButton(onClick = { onTask(verification.originTaskId) }) { Text("查看原课堂与落实记录") }
                        TextButton(onClick = { onTask(verification.followUpTaskId) }) { Text("查看复评课堂 ${verification.followUpCode}") }
                    }
                } }
            }
        }
    }
}

@Composable
private fun IdFilter(label: String, selected: Long?, options: List<Pair<Long, String>>, enabled: Boolean, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("$label：${options.firstOrNull { it.first == selected }?.second ?: "全部"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("全部") }, onClick = { open = false; onSelect(null) })
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(id) }) }
        }
    }
}

internal fun number(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "缺测"

internal fun trendText(pair: MetricTrendPair): String {
    val comparison = pair.outcome as? MetricRules.CompareOutcome.Comparable
    if (comparison == null) return "${number(pair.before?.value)} → ${number(pair.after?.value)}；${pair.note ?: "无法比较"}"
    val direction = when { comparison.delta > 0 -> "上升"; comparison.delta < 0 -> "下降"; else -> "持平" }
    return "${number(comparison.before)} → ${number(comparison.after)}；$direction ${number(kotlin.math.abs(comparison.delta))} ${pair.after?.unit.orEmpty()}"
}

@Composable
internal fun ReloadWhenResumed(reload: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(reload)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
