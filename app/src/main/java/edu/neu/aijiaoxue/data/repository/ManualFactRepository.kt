package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.ManualClassroomFact
import edu.neu.aijiaoxue.data.model.EvalStatus
import org.json.JSONObject

data class ManualFactDraft(val description: String, val sourceRefJson: String? = null, val classAtMs: Long? = null, val classStartMs: Long? = null, val classEndMs: Long? = null, val supersedesFactId: Long? = null)

class ManualFactRepository(private val db: AppDatabase) {
    suspend fun add(actorId: Long, taskId: Long, draft: ManualFactDraft, requestKey: String): Long = db.withTransaction {
        val dao = db.analysisSourceDao()
        val task = requireNotNull(dao.ownedTask(actorId, taskId)) { "无权补录课堂事实" }
        require(task.classEndAt != null && db.evaluationDao().getByTask(taskId)?.status != EvalStatus.SUBMITTED) { "仅已结束且尚未提交评价的课堂可补录事实" }
        require(draft.description.isNotBlank() && requestKey.isNotBlank()) { "事实描述不能为空" }
        dao.factByRequest(taskId, requestKey)?.let { old ->
            require(old.description == draft.description.trim() && old.sourceRefJson == draft.sourceRefJson && old.classAtMs == draft.classAtMs && old.classStartMs == draft.classStartMs && old.classEndMs == draft.classEndMs && old.supersedesFactId == draft.supersedesFactId) { "重试标识已用于另一份事实" }
            return@withTransaction old.id
        }
        val duration = task.classStartAt?.let { task.classEndAt - it }
        InputSourceRules.validateFactAnchor(draft.sourceRefJson != null, draft.classAtMs, draft.classStartMs, draft.classEndMs, duration)
        draft.sourceRefJson?.let { validateSourceOwnership(db, taskId, JSONObject(it)) }
        draft.supersedesFactId?.let { id ->
            val old = requireNotNull(dao.fact(id)) { "被更正事实不存在" }
            require(old.taskId == taskId && old.isCurrent) { "只能更正本课堂当前事实，请刷新重试" }
            check(dao.deactivateFact(id) == 1) { "事实已被其他操作更正" }
        }
        val id = dao.insertFact(ManualClassroomFact(taskId = taskId, description = draft.description.trim(), sourceRefJson = draft.sourceRefJson, classAtMs = draft.classAtMs, classStartMs = draft.classStartMs, classEndMs = draft.classEndMs, recordedBy = actorId, recordedAt = System.currentTimeMillis(), requestKey = requestKey, supersedesFactId = draft.supersedesFactId))
        dao.bumpInputRevision(taskId)
        id
    }
}

/** 不把任意跨任务 ID 变成可信来源。文件和 hash 在解析器打开时另行核验。 */
internal suspend fun validateSourceOwnership(db: AppDatabase, taskId: Long, ref: JSONObject) {
    require(ref.optLong("taskId", -1) == taskId) { "来源不属于当前课堂" }
    val id = ref.getLong("rowId")
    val dao = db.analysisSourceDao()
    val matches = when (ref.getString("kind")) {
        "MATERIAL" -> db.captureDao().getMaterial(id)?.taskId == taskId
        "INTERACTION_EVENT" -> dao.event(taskId, id) != null
        "ENGAGEMENT_RECORD" -> dao.engagement(taskId, id) != null
        "TRANSCRIPT_SEGMENT" -> dao.transcript(id)?.let { version ->
            val segments = JSONObject(version.payloadJson).getJSONArray("segments")
            version.taskId == taskId && (0 until segments.length()).any { segments.getJSONObject(it).getString("segmentId") == ref.optString("itemKey") }
        } == true
        "MANUAL_PHASE" -> dao.phases(id)?.let { version -> version.taskId == taskId && TeachingPhaseRepository.decode(version.payloadJson).any { it.id == ref.optString("itemKey") } } == true
        "IMAGE_TEXT" -> dao.imageText(id)?.taskId == taskId
        "MANUAL_FACT" -> dao.fact(id)?.taskId == taskId
        "REVIEWED_ANALYSIS" -> db.aiAnalysisDao().getAnalysisById(id)?.taskId == taskId
        else -> false
    }
    require(matches) { "来源不存在或不属于当前课堂" }
}
