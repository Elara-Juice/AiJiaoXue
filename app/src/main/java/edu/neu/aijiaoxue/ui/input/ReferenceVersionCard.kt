package edu.neu.aijiaoxue.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.CourseReferenceVersion
import org.json.JSONObject

@Composable
internal fun ReferenceVersionCard(version: CourseReferenceVersion, bound: Boolean, selectionJson: String?, editable: Boolean, bind: (List<String>, List<String>) -> Unit) {
    val json = remember(version.payloadJson) { JSONObject(version.payloadJson) }
    fun selected(key: String): Set<String> = if (!bound || selectionJson == null) emptySet() else runCatching { JSONObject(selectionJson).getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    var points by remember(version.id, bound, selectionJson) { mutableStateOf(selected("selectedKnowledgePointIds")) }
    var objectives by remember(version.id, bound, selectionJson) { mutableStateOf(selected("selectedObjectiveIds")) }
    Text("v${version.revision} · ${version.chapterScope}${if (bound) " · 当前绑定" else ""}", style = MaterialTheme.typography.titleSmall)
    Text(version.sourceDescription)
    for (key in listOf("knowledgePoints", "objectives")) {
        val a = json.getJSONArray(key)
        for (i in 0 until a.length()) {
            val row = a.getJSONObject(i); val id = row.getString("id")
            Row { if (editable) Checkbox(checked = id in if (key == "knowledgePoints") points else objectives, onCheckedChange = { checked -> if (key == "knowledgePoints") points = if (checked) points + id else points - id else objectives = if (checked) objectives + id else objectives - id })
                Text("$id ${row.getString("text")}", Modifier.weight(1f).padding(top = 12.dp)) }
        }
    }
    if (editable) Button(onClick = { bind(points.toList(), objectives.toList()) }) { Text("绑定所选课堂范围") }
    HorizontalDivider()
}
