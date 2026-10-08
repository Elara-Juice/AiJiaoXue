package edu.neu.aijiaoxue.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.model.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    courseId: Long,
    onBack: () -> Unit,
    onCreateTask: (Long) -> Unit,
) {
    val viewModel: CourseDetailViewModel = viewModel(
        key = "course-detail-$courseId",
        factory = viewModelFactory { initializer { CourseDetailViewModel(this[ androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, courseId) } },
    )
    val row by viewModel.course.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("课程详情") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (row == null) {
                Text("未找到课程信息", color = MaterialTheme.colorScheme.error)
            } else {
                val course = row!!
                Text(course.name, style = MaterialTheme.typography.headlineSmall)
                Text("课程编号：${course.code}")
                Text("任课教师：${course.teacherName}")
                Text("上课班级：${course.className ?: "未填写"}")
                Text("上课时间：${course.schedule ?: "未填写"}")
                Text("上课地点：${course.location ?: "未填写"}")
                Text("历史督导次数：${course.completedCount}")
                Text("最近督导日期：${course.lastSupervisionDate ?: "未填写"}")
                if (course.hasOpenArrangement) Text("已纳入待督导安排", color = MaterialTheme.colorScheme.primary)
                if (Session.requireUser().role == Role.SUPERVISOR) {
                    Button(onClick = { onCreateTask(course.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("创建督导任务")
                    }
                }
            }
        }
    }
}