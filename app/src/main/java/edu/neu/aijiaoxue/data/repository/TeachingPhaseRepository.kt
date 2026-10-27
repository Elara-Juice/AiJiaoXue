package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.TeachingPhaseVersion
import edu.neu.aijiaoxue.data.model.*
import org.json.JSONArray
import org.json.JSONObject

class TeachingPhaseRepository(private val db: AppDatabase) {
    suspend fun appendVersion(actorId: Long, taskId: Long, segments: List<PhaseSegment>, confirm: Boolean = false): Long = db.withTransaction {
        val dao = db.analysisSourceDao()
        val task = requireNotNull(dao.ownedTask(actorId, taskId)) { "无权编辑课堂环节" }
        require(db.evaluationDao().getByTask(taskId)?.status != EvalStatus.SUBMITTED) { "已提交课堂环节只读" }
        val duration = task.classStartAt?.let { start -> ((task.classEndAt ?: System.currentTimeMillis()) - start).takeIf { it >= 0 } }
        InputSourceRules.validatePhases(segments, duration, confirm)
        val payload = encode(segments)
        val revision = (dao.latestPhases(taskId)?.revision ?: 0) + 1
        if (confirm) dao.deactivatePhases(taskId)
        val id = dao.insertPhases(TeachingPhaseVersion(taskId = taskId, revision = revision, payloadJson = payload, contentHash = InputSourceRules.hash(payload), status = if (confirm) PhaseStatus.CONFIRMED else PhaseStatus.DRAFT, confirmedBy = if (confirm) actorId else null, confirmedAt = if (confirm) System.currentTimeMillis() else null, isCurrent = confirm))
        if (confirm) dao.bumpInputRevision(taskId)
        id
    }

    companion object {
        fun encode(segments: List<PhaseSegment>): String = JSONObject().put("schemaVersion", 1).put("segments", JSONArray().apply {
            segments.sortedBy { it.startMs }.forEach { s -> put(JSONObject().put("id", s.id).put("kind", s.kind.name).put("label", s.label).put("startMs", s.startMs).put("endMs", s.endMs).put("note", s.note)) }
        }).toString()
        fun decode(json: String): List<PhaseSegment> = JSONObject(json).getJSONArray("segments").let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { s -> PhaseSegment(s.getString("id"), ActivityKind.valueOf(s.getString("kind")), s.getString("label"), s.getLong("startMs"), s.getLong("endMs"), s.optString("note")) } }
        }
    }
}
