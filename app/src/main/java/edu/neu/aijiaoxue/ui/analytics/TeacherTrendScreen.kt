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

/**
 * US47：教师教学趋势页面。
 *
 * teacherId 必须由登录身份或经过权限验证的调用方传入，
 * 不要直接使用固定的教师 ID。
 */
@Composable
fun TeacherTrendScreen(
    teacherId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // 获取数据库中已提交的真实课堂评价记录
    val trendFlow = remember(teacherId) {
        AppDatabase.get(context)
            .evaluationDao()
            .observeTeacherTrend(teacherId)
    }

    val rows by trendFlow.collectAsState(
        initial = emptyList()
    )

    // DAO 返回结果已经按课堂日期升序排列
    val firstScore = rows.firstOrNull()?.totalScore
    val lastScore = rows.lastOrNull()?.totalScore

    val scoreChange = if (
        rows.size >= 2 &&
        firstScore != null &&
        lastScore != null
    ) {
        lastScore - firstScore
    } else {
        null
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onBack) {
                    Text("返回")
                }

                Text(
                    text = "教师教学趋势",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = "统计范围：该教师已完成且已提交评价的课堂"
                )

                Text(
                    text = "有效评价课堂数：${rows.size}"
                )

                if (scoreChange != null) {
                    val sign = if (scoreChange > 0) "+" else ""

                    Text(
                        text = "总分首末变化：$sign$scoreChange 分"
                    )
                } else if (rows.size < 2) {
                    Text(
                        text = "至少需要两条有效课堂记录才能比较首末变化。"
                    )
                } else {
                    Text(
                        text = "部分课堂总分缺失，暂时无法计算首末变化。"
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "暂无正式评价记录。请确认该教师已有已提交的课堂评价。",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(
            items = rows,
            key = { it.taskId }
        ) { row ->
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = row.courseName,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text("课堂日期：${row.supervisionDate}")
                    Text("教师：${row.teacherName}")

                    Text(
                        text = "总分：${row.totalScore ?: "暂无"}"
                    )

                    Text(
                        text = "教学内容：${row.contentScore ?: "暂无"}"
                    )

                    Text(
                        text = "教学设计：${row.designScore ?: "暂无"}"
                    )

                    Text(
                        text = "课堂互动：${row.interactionScore ?: "暂无"}"
                    )

                    Text(
                        text = "学生参与：${row.engagementScore ?: "暂无"}"
                    )
                }
            }
        }
    }
}