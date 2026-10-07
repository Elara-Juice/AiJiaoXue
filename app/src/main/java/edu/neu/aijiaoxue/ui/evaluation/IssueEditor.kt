package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.model.EvalDimension

@Composable
fun IssueEditor(issues: List<EvaluationIssue>, enabled: Boolean, onAdd: () -> Unit,
    onChange: (Long, (EvaluationIssue) -> EvaluationIssue) -> Unit, onRemove: (Long) -> Unit) {
    SectionCard("主要问题（至少一条）") {
        if (issues.isEmpty()) Text("尚未添加问题")
        issues.forEachIndexed { index, issue ->
            OutlinedTextField(value = issue.description, onValueChange = { text -> onChange(issue.id) { it.copy(description = text) } },
                readOnly = !enabled, label = { Text("问题 ${index + 1}") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = issue.dimension == null, enabled = enabled,
                    onClick = { onChange(issue.id) { it.copy(dimension = null) } }, label = { Text("未指定维度") })
                EvalDimension.entries.forEach { dimension ->
                    FilterChip(selected = issue.dimension == dimension, enabled = enabled,
                        onClick = { onChange(issue.id) { it.copy(dimension = dimension) } }, label = { Text(dimension.label) })
                }
            }
            if (enabled) TextButton(onClick = { onRemove(issue.id) }) { Text("删除问题 ${index + 1}") }
        }
        if (enabled) OutlinedButton(onClick = onAdd) { Text("添加问题") }
    }
}
