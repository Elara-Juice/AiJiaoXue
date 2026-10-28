package edu.neu.aijiaoxue.ui.analysis

import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.model.AnalysisOperation
import edu.neu.aijiaoxue.data.model.BopppsStage
import org.json.JSONArray
import org.json.JSONObject

data class AnalysisFinding(val title: String, val detail: String)
data class BopppsCoverageItem(val stage: BopppsStage, val status: String, val explanation: String)
data class AnalysisPresentation(
    val summary: String,
    val findings: List<AnalysisFinding> = emptyList(),
    val coverage: List<BopppsCoverageItem> = emptyList(),
)

/** Only formats validated stored payloads. No invented score, confidence percentage or time axis. */
object AnalysisPresentationMapper {
    fun map(result: AiAnalysisResult): AnalysisPresentation {
        val json = result.payloadJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return AnalysisPresentation(result.content)
        val findings = when (result.operation) {
            AnalysisOperation.CONTENT -> rows(json, "topics") { row ->
                AnalysisFinding(row.optString("name", "教学主题"),
                    strings(row.optJSONArray("keywords")).joinToString(" · "))
            }
            AnalysisOperation.SYLLABUS_ALIGNMENT -> rows(json, "alignments") { row ->
                AnalysisFinding(row.optString("knowledgePointId", "知识点"),
                    listOf(label(row.optString("coverage")), row.optString("reason")).filter(String::isNotBlank).joinToString(" · "))
            }
            AnalysisOperation.GOAL_ACTIVITY -> rows(json, "activities") { row ->
                AnalysisFinding(row.optString("label", "课堂活动"), row.optString("description", row.optString("reason")))
            }
            AnalysisOperation.TRANSITION -> rows(json, "transitions") { row ->
                AnalysisFinding(label(row.optString("type", "衔接候选")), row.optString("reason", row.optString("statement")))
            }
            AnalysisOperation.MATERIAL_SUPPORT -> rows(json, "supports") { row ->
                AnalysisFinding(label(row.optString("relation", "材料与教学内容")), row.optString("reason", row.optString("statement")))
            }
            AnalysisOperation.EXPRESSION_STATS -> rows(json, "expressions") { row ->
                AnalysisFinding(row.optString("expression", "表达"), "出现 ${row.optInt("count")} 次")
            }
            else -> emptyList()
        }
        val coverage = if (result.operation == AnalysisOperation.BOPPPS) {
            val rows = objects(json.optJSONArray("coverage"))
            BopppsStage.entries.map { stage ->
                val row = rows.firstOrNull { it.optString("stage") == stage.name }
                BopppsCoverageItem(stage, label(row?.optString("status").orEmpty().ifBlank { "INSUFFICIENT_INPUT" }), row?.optString("reason").orEmpty())
            }
        } else emptyList()
        val summary = when {
            coverage.isNotEmpty() -> "按本次课堂材料核对六个教学环节，选择下方证据查看分析依据。"
            findings.isNotEmpty() -> findings.first().let { listOf(it.title, it.detail).filter(String::isNotBlank).joinToString(" · ") }
            else -> result.content
        }
        return AnalysisPresentation(summary, findings, coverage)
    }

    private fun rows(json: JSONObject, key: String, convert: (JSONObject) -> AnalysisFinding) =
        objects(json.optJSONArray(key)).map(convert)
    private fun objects(array: JSONArray?) = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    private fun strings(array: JSONArray?) = if (array == null) emptyList() else
        (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
    private fun label(value: String) = AnalysisJson.summary(value)
}
