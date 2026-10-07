package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.EvalDimension

@Composable
fun HistoryDetailScreen(viewModel: HistoryDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    if (state.error != null) { ErrorPage("历史详情", state.error!!, onBack, viewModel::reload); return }
    ModulePage("历史详情 · 只读", onBack) { padding ->
        if (state.loading) { CircularProgressIndicator(Modifier.padding(padding).padding(24.dp)); return@ModulePage }
        val record = state.record ?: return@ModulePage
        val evaluation = record.draft.evaluation
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionCard(record.course.name) {
                ReadField("课程编号", record.course.code)
                ReadField("任务编号", record.task.code)
                Text("教师：${record.teacherName} · 督导：${record.supervisorName}")
                Text("${record.task.supervisionDate} · ${record.task.period}节 · ${record.task.type.label}")
                ReadField("班级", record.course.className); ReadField("课表", record.course.schedule)
                ReadField("地点", record.course.location); ReadField("章节", record.task.chapter)
                Text("课堂开始：${timeText(record.task.classStartAt)}")
                Text("课堂结束：${timeText(record.task.classEndAt)}")
                Text("评价提交：${timeText(evaluation.submittedAt)}")
                MessageCard("${evaluation.totalScore} 分 · ${evaluation.grade?.label.orEmpty()} · 已归档")
            } }
            item { ClassroomReference(record) }
            EvalDimension.entries.forEach { dim -> item { SectionCard("${dim.label} · ${dim.weight}%") {
                Text("评分：${evaluation.scoreOf(dim)} / 5")
                ReadField("评语", evaluation.commentOf(dim))
            } } }
            item { SectionCard("综合意见") {
                ReadField("主要优点", evaluation.strengths)
                record.draft.issues.forEachIndexed { index, issue ->
                    Text("问题 ${index + 1}：${issue.description}")
                    Text("维度：${issue.dimension?.label ?: "未指定"}", style = MaterialTheme.typography.bodySmall)
                }
                ReadField("改进建议", evaluation.suggestions); ReadField("总体意见", evaluation.overallOpinion)
            } }
        }
    }
}
