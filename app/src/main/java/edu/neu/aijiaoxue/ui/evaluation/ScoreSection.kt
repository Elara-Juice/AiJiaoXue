package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.EvalRules

@Composable
fun ScoreSection(dimension: EvalDimension, evaluation: Evaluation, enabled: Boolean, onScore: (Int) -> Unit, onComment: (String) -> Unit) {
    SectionCard("${dimension.label} · ${dimension.weight}%") {
        Text("1分 差 — 5分 优；评分必填", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (score in EvalRules.MIN_SCORE..EvalRules.MAX_SCORE) {
                FilterChip(selected = evaluation.scoreOf(dimension) == score, onClick = { onScore(score) },
                    enabled = enabled, label = { Text(score.toString()) },
                    modifier = Modifier.testTag("score-${dimension.name}-$score"))
            }
        }
        OutlinedTextField(value = evaluation.commentOf(dimension), onValueChange = onComment,
            readOnly = !enabled, label = { Text("${dimension.label}评语（选填）") }, modifier = Modifier.fillMaxWidth())
    }
}
