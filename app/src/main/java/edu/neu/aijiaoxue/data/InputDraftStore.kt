package edu.neu.aijiaoxue.data

import android.content.Context
import android.util.AtomicFile
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.TranscriptEdit
import edu.neu.aijiaoxue.ui.input.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 私有编辑补丁不是业务输入。恢复后仍须查询层授权和版本核对，绝不自动发布。 */
class InputDraftStore(context: Context, actorId: Long, taskId: Long, courseId: Long?) {
    private val file = AtomicFile(File(context.filesDir, "input-drafts/$actorId-$taskId-${courseId ?: 0}.json"))
    private val mutex = Mutex()

    suspend fun read(): InputEditorState? = mutex.withLock { withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        require(file.baseFile.length() <= 8 * 1024 * 1024) { "本地编辑草稿过大，请联系项目成员保留文件后处理" }
        decode(file.openRead().bufferedReader().use { it.readText() })
    } }

    suspend fun write(state: InputEditorState) = mutex.withLock { withContext(Dispatchers.IO) {
        check(file.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "无法创建私有草稿目录" }
        val stream = file.startWrite()
        try { stream.write(encode(state).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    } }

    companion object {
        fun encode(state: InputEditorState): String = JSONObject().apply {
            put("schemaVersion", 1); put("tab", state.tab.name); put("selectedMaterialId", state.selectedMaterialId)
            put("pages", JSONObject().apply { state.pages.forEach { (id, page) -> put(id.toString(), page) } })
            put("scroll", JSONObject().apply { state.scrollOffsets.forEach { (tab, offset) -> put(tab.name, offset) } })
            put("phase", JSONObject().apply { val p = state.phase; put("visible", p.visible); put("editingId", p.editingId); put("label", p.label); put("start", p.start); put("end", p.end); put("kind", p.kind.name); put("note", p.note); put("baseVersionId", p.baseVersionId); put("editRevision", p.editRevision) })
            put("fact", JSONObject().apply { val f = state.fact; put("description", f.description); put("sourceJson", f.sourceJson); put("at", f.at); put("start", f.start); put("end", f.end); put("supersedesId", f.supersedesId); put("editRevision", f.editRevision); put("requestKey", f.requestKey) })
            put("transcripts", JSONArray().apply { state.transcripts.values.filter { it.changes.isNotEmpty() }.forEach { d -> put(JSONObject().put("materialId", d.materialId).put("baseVersionId", d.baseVersionId).put("editRevision", d.editRevision).put("changes", JSONArray().apply { d.changes.values.forEach { put(JSONObject().put("segmentId", it.segmentId).put("text", it.text).put("role", it.speakerRole.name)) } })) } })
        }.toString()

        fun decode(text: String): InputEditorState {
            val root = JSONObject(text); require(root.getInt("schemaVersion") == 1) { "本地草稿格式不支持，原文件已保留" }
            fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
            fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
            val p = root.getJSONObject("phase"); val f = root.getJSONObject("fact")
            val pages = root.getJSONObject("pages"); val scroll = root.getJSONObject("scroll")
            val drafts = root.getJSONArray("transcripts")
            return InputEditorState(
                tab = InputWorkspaceTab.valueOf(root.getString("tab")), selectedMaterialId = root.longOrNull("selectedMaterialId"),
                pages = pages.keys().asSequence().associate { it.toLong() to pages.getInt(it).coerceAtLeast(0) },
                scrollOffsets = scroll.keys().asSequence().associate { InputWorkspaceTab.valueOf(it) to scroll.getInt(it).coerceAtLeast(0) },
                phase = PhaseEditorDraft(p.getBoolean("visible"), p.stringOrNull("editingId"), p.getString("label"), p.getString("start"), p.getString("end"), ActivityKind.valueOf(p.getString("kind")), p.getString("note"), p.longOrNull("baseVersionId"), p.getLong("editRevision")),
                fact = FactEditorDraft(f.getString("description"), f.stringOrNull("sourceJson"), f.getString("at"), f.getString("start"), f.getString("end"), f.longOrNull("supersedesId"), f.getLong("editRevision"), f.getString("requestKey")),
                transcripts = (0 until drafts.length()).map { drafts.getJSONObject(it) }.associate { d ->
                    val changes = d.getJSONArray("changes"); val id = d.getLong("materialId")
                    id to TranscriptEditorDraft(id, d.getLong("baseVersionId"), (0 until changes.length()).map { changes.getJSONObject(it) }.associate { c -> c.getString("segmentId") to TranscriptEdit(c.getString("segmentId"), c.getString("text"), SpeakerRole.valueOf(c.getString("role"))) }, d.getLong("editRevision"))
                },
            )
        }
    }
}
