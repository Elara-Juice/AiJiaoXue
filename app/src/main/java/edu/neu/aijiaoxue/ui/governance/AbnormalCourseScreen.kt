package edu.neu.aijiaoxue.ui.governance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.CourseAttentionState
import edu.neu.aijiaoxue.data.repository.CourseAttentionRecord
import edu.neu.aijiaoxue.ui.evaluation.*

@Composable
fun AbnormalCourseScreen(model: AbnormalCourseViewModel, onBack: () -> Unit, onTask: (Long) -> Unit) {
    val state by model.uiState.collectAsState()
    ReloadWhenResumed { model.reload() }
    ModulePage("正式评价关注课程", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("abnormal-course-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { MessageCard("关注规则：已完成课堂的已提交正式评价总分低于60分。规则只触发人工关注；缺分不补0，草稿分数不参与筛选。") }
            item { SectionCard("查询条件") {
                val filter = state.filter
                val options = state.result?.options.orEmpty()
                AttentionFilter("教师", filter.teacherId, options.distinctBy { it.teacherId }.map { it.teacherId to it.teacherName }) {
                    model.setFilter(filter.copy(teacherId = it, courseId = null))
                }
                AttentionFilter("课程", filter.courseId, options.filter { filter.teacherId == null || it.teacherId == filter.teacherId }
                    .map { it.courseId to it.courseName }) { model.setFilter(filter.copy(courseId = it)) }
                DateField("开始日期", filter.fromDate, { model.setFilter(filter.copy(fromDate = it)) }, enabled = !state.loading)
                DateField("结束日期", filter.toDate, { model.setFilter(filter.copy(toDate = it)) }, enabled = !state.loading)
                Button(model::reload, enabled = !state.loading) { Text("应用筛选 / 刷新") }
            } }
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { item { MessageCard(it, true) } }
            state.result?.let { result ->
                if (state.filter != result.filter) item { MessageCard("条件已修改，当前结果仍按上次应用条件显示。") }
                item { Text("关注课程 ${result.groups.size} 门 · 触发课堂 ${result.groups.sumOf { it.classrooms.size }} 次 · 未触发阈值 ${result.normalCount} 次") }
                result.groups.forEach { group -> item(key = "attention-${group.courseId}") {
                    SectionCard("${group.courseName} · ${group.teacherName} · ${group.classrooms.size} 次触发") {
                        group.classrooms.forEach { AttentionClassroom(it, onTask) }
                    }
                } }
                item { SectionCard("正式分数无法判定（${result.undetermined.size}）") {
                    if (result.undetermined.isEmpty()) Text("没有缺失或无效正式总分。")
                    result.undetermined.forEach { AttentionClassroom(it, onTask) }
                } }
                item { SectionCard("尚无正式评价（${result.unpublished.size}）") {
                    if (result.unpublished.isEmpty()) Text("当前范围均已有正式评价。")
                    result.unpublished.forEach { AttentionClassroom(it, onTask) }
                } }
            }
        }
    }
}

@Composable
private fun AttentionClassroom(record: CourseAttentionRecord, onTask: (Long) -> Unit) {
    val row = record.classroom
    Text("${row.courseName} · ${row.teacherName} · ${row.supervisionDate} ${row.period}节")
    Text(record.reason)
    record.issues.forEach { Text("主要问题：${it.description}") }
    if (record.state != CourseAttentionState.UNPUBLISHED) {
        TextButton(onClick = { onTask(row.taskId) }, modifier = Modifier.testTag("abnormal-report-${row.taskId}")) { Text("查看本次正式报告") }
    }
    HorizontalDivider()
}

@Composable
private fun AttentionFilter(label: String, selected: Long?, options: List<Pair<Long, String>>, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label：${options.firstOrNull { it.first == selected }?.second ?: "全部"}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部") }, onClick = { expanded = false; onSelect(null) })
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
