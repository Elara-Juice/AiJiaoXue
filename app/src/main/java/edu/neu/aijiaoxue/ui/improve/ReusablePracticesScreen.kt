
package edu.neu.aijiaoxue.ui.improve

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.ReusableTeachingPractice
import kotlinx.coroutines.launch

@Composable
fun ReusablePracticesScreen(
    teacherId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember(context) {
        AppDatabase.get(context).improvementDao()
    }
    val scope = rememberCoroutineScope()

    val candidates by remember(teacherId, dao) {
        dao.observeReusablePracticeCandidates(teacherId)
    }.collectAsState(initial = emptyList())

    val savedPractices by remember(teacherId, dao) {
        dao.observeReusablePractices(teacherId)
    }.collectAsState(initial = emptyList())

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
                text = "教学亮点复用",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text("从以往督导评价中保存值得继续使用的教学做法。")
        }

        item {
            Text(
                text = "已保存的做法（${savedPractices.size}）",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (savedPractices.isEmpty()) {
            item {
                Text("暂时还没有保存教学亮点。")
            }
        }

        items(
            items = savedPractices,
            key = { "saved-${it.id}" }
        ) { practice ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = practice.courseName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("评价日期：${practice.supervisionDate}")
                    Text(practice.content)

                    if (!practice.note.isNullOrBlank()) {
                        Text("复用说明：${practice.note}")
                    }
                }
            }
        }

        item {
            Text(
                text = "可保存的教学亮点",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (candidates.isEmpty()) {
            item {
                Text(
                    "暂无可保存的亮点。请确认本人有已提交、且填写了教学亮点的督导评价。"
                )
            }
        }

        items(
            items = candidates,
            key = { "candidate-${it.evaluationId}" }
        ) { candidate ->
            val note = remember(candidate.evaluationId) {
                mutableStateOf("")
            }
            val saving = remember(candidate.evaluationId) {
                mutableStateOf(false)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = candidate.courseName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("评价日期：${candidate.supervisionDate}")
                    Text(candidate.strengths.orEmpty())

                    OutlinedTextField(
                        value = note.value,
                        onValueChange = { note.value = it },
                        label = { Text("复用说明（选填）") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        enabled = !saving.value &&
                                !candidate.strengths.isNullOrBlank(),
                        onClick = {
                            scope.launch {
                                saving.value = true
                                try {
                                    dao.insertReusableTeachingPractice(
                                        ReusableTeachingPractice(
                                            evaluationId = candidate.evaluationId,
                                            content = candidate.strengths
                                                .orEmpty()
                                                .trim(),
                                            note = note.value
                                                .trim()
                                                .takeIf { it.isNotEmpty() }
                                        )
                                    )
                                } finally {
                                    saving.value = false
                                }
                            }
                        }
                    ) {
                        Text(
                            if (saving.value) "正在保存…" else "保存为可复用做法"
                        )
                    }
                }
            }
        }
    }
}
