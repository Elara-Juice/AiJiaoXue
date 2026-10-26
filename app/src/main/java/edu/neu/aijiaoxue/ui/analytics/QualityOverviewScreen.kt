
package edu.neu.aijiaoxue.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.QualityOverviewRow
import java.util.Locale

@Composable
fun QualityOverviewScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val overviewFlow = remember {
        AppDatabase.get(context)
            .evaluationDao()
            .observeQualityOverview()
    }

    val rows by overviewFlow.collectAsState(initial = emptyList())

    val teacherCount = rows
        .map { it.teacherId }
        .distinct()
        .size

    val totalClasses = rows.sumOf { it.evaluatedClassCount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(onClick = onBack) {
                Text("返回")
            }

            Text(
                text = "教学质量总览",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            Text("统计教师数：$teacherCount")
            Text("已评价课堂总数：$totalClasses")
            Text("以下数据按照教师和课程分别统计。")
        }

        if (rows.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "暂无教学质量数据",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("完成课堂督导并提交正式评价后，这里才会显示统计结果。")
                    }
                }
            }
        }

        items(
            items = rows,
            key = { "${it.teacherId}-${it.courseId}" }
        ) { row ->
            QualityOverviewCard(row = row)
        }
    }
}

@Composable
private fun QualityOverviewCard(
    row: QualityOverviewRow
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = row.courseName,
                style = MaterialTheme.typography.titleLarge
            )

            Text("任课教师：${row.teacherName}")
            Text("已评价课堂：${row.evaluatedClassCount} 节")

            Text(
                "平均总分：${formatScore(row.averageTotalScore)}"
            )
            Text(
                "教学内容：${formatScore(row.averageContentScore)}"
            )
            Text(
                "教学设计：${formatScore(row.averageDesignScore)}"
            )
            Text(
                "课堂互动：${formatScore(row.averageInteractionScore)}"
            )
            Text(
                "学生参与：${formatScore(row.averageEngagementScore)}"
            )

            Text(
                "首次评价日期：${row.firstEvaluationDate ?: "暂无"}"
            )
            Text(
                "最近评价日期：${row.lastEvaluationDate ?: "暂无"}"
            )
        }
    }
}

private fun formatScore(score: Double?): String {
    return score?.let {
        String.format(Locale.getDefault(), "%.1f", it)
    } ?: "暂无"
}
