package edu.neu.aijiaoxue.navigation

import androidx.compose.runtime.Composable

/** F0 角色首页：教学督导 —— 课程检索、我的督导任务、历史记录。 */
@Composable
fun SupervisorHomeScreen(onNavigate: (String) -> Unit) {
    HomeScaffold(
        entries = listOf(
            HomeEntry("课程检索", "按课程名称、教师姓名查找课程", Routes.COURSE_SEARCH),
            HomeEntry("我的督导任务", "继续未完成的采集或评价", Routes.MY_TASKS),
            HomeEntry("历史记录", "查看本人已完成的督导评价", Routes.HISTORY),
        ),
        onNavigate = onNavigate,
    )
}
