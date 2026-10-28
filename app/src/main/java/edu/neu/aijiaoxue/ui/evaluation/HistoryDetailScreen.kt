package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.IssueDeclaration
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import org.json.JSONObject
import edu.neu.aijiaoxue.ui.theme.AppGlyph
import edu.neu.aijiaoxue.ui.theme.AppIcon

@Composable
fun HistoryDetailScreen(viewModel: HistoryDetailViewModel, onBack: () -> Unit, onEvidence: ((Long) -> Unit)? = null, onParticipation: (() -> Unit)? = null, onImprovement: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsState()
    if (state.error != null) { ErrorPage("完整督导报告", state.error!!, onBack, viewModel::reload); return }
    ModulePage("完整督导报告 · 只读", onBack) { padding ->
        if (state.loading) { CircularProgressIndicator(Modifier.padding(padding).padding(24.dp)); return@ModulePage }
        val record = state.record ?: return@ModulePage
        val evaluation = record.draft.evaluation
        val context = record.publication?.contextSnapshotJson?.let(::JSONObject)
        fun frozen(key: String, fallback: String?): String? = if (context == null) fallback else if (context.isNull(key)) null else context.optString(key)
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onImprovement != null) item { SectionCard("报告已发布") {
                Text("教师可查看本次反馈。正式问题已进入改进跟踪，后续措施和复评会与本报告关联。")
                Button(onClick = onImprovement, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(AppGlyph.TREND, tint = MaterialTheme.colorScheme.onPrimary)
                    Text("查看教师改进与复评")
                }
            } }
            item { SectionCard(frozen("courseName", record.course.name).orEmpty()) {
                ReadField("课程编号", frozen("courseCode", record.course.code))
                ReadField("任务编号", frozen("taskCode", record.task.code))
                Text("教师：${frozen("teacherName", record.teacherName)} · 督导：${frozen("supervisorName", record.supervisorName)}")
                Text("${frozen("supervisionDate", record.task.supervisionDate)} · ${frozen("period", record.task.period)}节")
                ReadField("班级", frozen("className", record.course.className)); ReadField("课表", frozen("schedule", record.course.schedule))
                ReadField("地点", frozen("location", record.course.location)); ReadField("章节", frozen("chapter", record.task.chapter))
                Text("课堂开始：${timeText(record.task.classStartAt)}")
                Text("课堂结束：${timeText(record.task.classEndAt)}")
                Text("评价提交：${timeText(evaluation.submittedAt)}")
                MessageCard("${evaluation.totalScore} 分 · ${evaluation.grade?.label.orEmpty()} · 已归档")
            } }
            if (context != null) item {
                SectionCard("发布时教学要求与课堂材料摘要") {
                    ReadField("课程要求依据", frozen("courseReferenceSource", null))
                    context.optJSONObject("courseReference")?.let { Text(AnalysisJson.summary(it)) } ?: Text("发布时未绑定课程要求")
                    AnalysisJson.objects(context.optJSONArray("materialSummaries")).forEach { material -> Text("${material.optString("type")} · 课堂 ${material.optLong("offsetMs") / 1000} 秒 · ${if (material.isNull("note")) "未填写备注" else material.optString("note")}") }
                }
            }
            // 正式报告保留冻结摘要，同时保留 US32 的原始记录查询与文件可用性提示。
            // 本页仓储已限定管理员/原课堂督导；教师反馈不会进入此原始材料组件。
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (context != null) Text("课堂原始记录与当前文件状态（只读）；不改写上方发布时摘要。", style = MaterialTheme.typography.bodySmall)
                ClassroomReference(record)
            } }
            item { ReviewedAnalysisSection(record.reviewedAnalyses, true, record.publication, record.publishedFacts, record.publishedEvidence, onEvidence) }
            if (onParticipation != null) item { OutlinedButton(onClick = onParticipation) {
                AppIcon(AppGlyph.COMPARE, tint = MaterialTheme.colorScheme.primary)
                Text("课堂参与分析")
            } }
            EvalDimension.entries.forEach { dim -> item { SectionCard("${dim.label} · ${dim.weight}%") {
                Text("评分：${evaluation.scoreOf(dim)} / 5")
                ReadField("评语", evaluation.commentOf(dim))
            } } }
            item { SectionCard("综合意见") {
                ReadField("主要优点", evaluation.strengths)
                if (evaluation.issueDeclaration == IssueDeclaration.NO_ISSUES_CONFIRMED) { Text("督导确认本次无主要问题"); ReadField("确认依据", evaluation.issueDeclarationNote) }
                record.draft.issues.forEachIndexed { index, issue ->
                    Text("问题 ${index + 1}：${issue.description}")
                    Text("维度：${issue.dimension?.label ?: "未指定"}", style = MaterialTheme.typography.bodySmall)
                }
                ReadField("改进建议", evaluation.suggestions); ReadField("总体意见", evaluation.overallOpinion)
            } }
        }
    }
}
