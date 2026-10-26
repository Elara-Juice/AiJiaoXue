package edu.neu.aijiaoxue.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session

/**
 * F0 角色首页：任课教师 —— 督导反馈、改进计划、改进待办。
 * 改进计划从某条反馈进入（需要 taskId），所以首页入口是反馈列表。
 */
@Composable
fun TeacherHomeScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val teacherId = Session.requireUser().id
    // US33：未读反馈红点，按本人 id 查询
    val unread by remember(teacherId) {
        AppDatabase.get(context).evaluationDao().observeUnreadCount(teacherId)
    }.collectAsState(initial = 0)

    HomeScaffold(
        entries = listOf(
            HomeEntry("督导反馈", "查看本人课程的督导评价，制定改进计划", Routes.FEEDBACK_LIST, badge = unread),
            HomeEntry("改进待办", "跟踪改进措施的完成情况", Routes.IMPROVE_TODO),
            HomeEntry("指标比较", "查看复评关联与前后指标变化", Routes.REVIEW_LIST),
            HomeEntry("教学亮点与互动策略", "沉淀做法、参考资源并加入改进计划", Routes.TEACHING_SUPPORT),
            HomeEntry("教学改进趋势", "查看本人课程的正式结果与改进轨迹", Routes.GOVERNANCE),
        ),
        onNavigate = onNavigate,
    )
}
