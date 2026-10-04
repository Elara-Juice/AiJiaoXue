package edu.neu.aijiaoxue.navigation

import androidx.compose.runtime.Composable

/** F0 角色首页：教学管理人员 —— 督导安排、任务进度。 */
@Composable
fun AdminHomeScreen(onNavigate: (String) -> Unit) {
    HomeScaffold(
        entries = listOf(
            HomeEntry("督导安排", "查看本学期未督导课程，发布督导安排", Routes.ARRANGEMENT),
            HomeEntry("任务进度", "按课程、督导、状态跟踪督导任务", Routes.TASK_PROGRESS),
            HomeEntry("历史记录", "查看全部已完成督导的评价", Routes.HISTORY),
        ),
        onNavigate = onNavigate,
    )
}
