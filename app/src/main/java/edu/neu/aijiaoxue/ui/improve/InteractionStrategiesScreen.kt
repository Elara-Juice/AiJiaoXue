
package edu.neu.aijiaoxue.ui.improve

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InteractionStrategiesScreen(
    onBack: () -> Unit
) {
    var problem by remember {
        mutableStateOf("互动不足")
    }

    val defaults = when (problem) {
        "互动不足" -> listOf(
            "每讲解一个核心知识点后，提出一个开放式问题，邀请学生说明自己的理解。",
            "安排 3—5 分钟小组讨论，请每组选一名代表分享结论。",
            "设计一道与本节内容相关的实际问题，让学生现场分析并回答。"
        )

        else -> listOf(
            "在课堂中段设置一次简短提问，检查学生对当前知识点的理解程度。",
            "安排学生两人互相讲解刚学的知识点，并请部分学生分享。",
            "设计一道难度适中的课堂练习，根据学生回答及时调整讲解节奏。"
        )
    }

    var strategy1 by remember(problem) {
        mutableStateOf(defaults[0])
    }
    var strategy2 by remember(problem) {
        mutableStateOf(defaults[1])
    }
    var strategy3 by remember(problem) {
        mutableStateOf(defaults[2])
    }

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
                text = "课堂互动改进策略",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text("选择课堂问题，查看并调整相应的改进建议。")
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "选择需要改进的问题",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Button(
                        onClick = { problem = "互动不足" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (problem == "互动不足") {
                                "✓ 互动不足"
                            } else {
                                "互动不足"
                            }
                        )
                    }

                    Button(
                        onClick = { problem = "参与度下降" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (problem == "参与度下降") {
                                "✓ 学生参与度下降"
                            } else {
                                "学生参与度下降"
                            }
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "改进建议（可直接修改）",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            OutlinedTextField(
                value = strategy1,
                onValueChange = { strategy1 = it },
                label = { Text("建议一") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = strategy2,
                onValueChange = { strategy2 = it },
                label = { Text("建议二") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = strategy3,
                onValueChange = { strategy3 = it },
                label = { Text("建议三") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = {
                    strategy1 = defaults[0]
                    strategy2 = defaults[1]
                    strategy3 = defaults[2]
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复默认建议")
            }

            Text(
                text = "当前版本为基础版：可以选择问题和编辑建议；修改内容暂存在当前页面，离开页面后不会保留。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
