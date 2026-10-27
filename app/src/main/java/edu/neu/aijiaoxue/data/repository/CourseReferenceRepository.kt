package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.CourseReferenceVersion
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.Role
import org.json.JSONArray
import org.json.JSONObject

data class CourseReferenceDraft(val chapterScope: String, val sourceDescription: String, val payloadJson: String)

class CourseReferenceRepository(private val db: AppDatabase) {
    suspend fun listVersions(actorId: Long, courseId: Long): List<CourseReferenceVersion> = db.withTransaction {
        authorizeCourse(actorId, courseId)
        db.analysisSourceDao().references(courseId)
    }

    suspend fun createVersion(actorId: Long, courseId: Long, draft: CourseReferenceDraft): Long = db.withTransaction {
        require(db.userDao().getById(actorId)?.role == Role.ADMIN) { "仅管理员可编辑课程要求" }
        requireNotNull(db.courseDao().getById(courseId)) { "课程不存在" }
        require(draft.chapterScope.isNotBlank() && draft.sourceDescription.isNotBlank()) { "请填写章节与录入依据" }
        val payload = validatePayload(draft.payloadJson)
        val revision = (db.analysisSourceDao().references(courseId).firstOrNull()?.revision ?: 0) + 1
        db.analysisSourceDao().insertReference(CourseReferenceVersion(courseId = courseId, revision = revision, chapterScope = draft.chapterScope.trim(), sourceDescription = draft.sourceDescription.trim(), payloadJson = payload, contentHash = InputSourceRules.hash(payload), createdBy = actorId, createdAt = System.currentTimeMillis()))
    }

    suspend fun bindToTask(actorId: Long, taskId: Long, versionId: Long, knowledgePointIds: List<String>, objectiveIds: List<String>) = db.withTransaction {
        val dao = db.analysisSourceDao()
        val task = requireNotNull(dao.ownedTask(actorId, taskId)) { "无权绑定此任务" }
        require(db.evaluationDao().getByTask(taskId)?.status != EvalStatus.SUBMITTED) { "已提交报告的教学要求不可变更" }
        val version = requireNotNull(dao.reference(versionId)) { "课程要求不存在" }
        require(version.courseId == task.courseId) { "课程要求不属于当前课程" }
        val json = JSONObject(version.payloadJson)
        fun ids(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }
        require(knowledgePointIds.isNotEmpty() || objectiveIds.isNotEmpty()) { "请选择本次课堂的教学范围" }
        require(knowledgePointIds.all { it in ids("knowledgePoints") } && objectiveIds.all { it in ids("objectives") }) { "所选教学范围已失效" }
        val selection = JSONObject().put("selectedKnowledgePointIds", JSONArray(knowledgePointIds.distinct())).put("selectedObjectiveIds", JSONArray(objectiveIds.distinct())).toString()
        dao.bindReference(taskId, versionId, selection)
    }

    private suspend fun authorizeCourse(actorId: Long, courseId: Long) {
        val actor = requireNotNull(db.userDao().getById(actorId)) { "请重新登录" }
        val course = requireNotNull(db.courseDao().getById(courseId)) { "课程不存在" }
        require(actor.role == Role.ADMIN || actor.role == Role.SUPERVISOR || (actor.role == Role.TEACHER && course.teacherId == actorId)) { "无权查看课程要求" }
    }

    companion object {
        fun validatePayload(text: String): String {
            val json = JSONObject(text)
            require(json.optInt("schemaVersion") == 1) { "课程要求版本格式无效" }
            val points = json.getJSONArray("knowledgePoints")
            val objectives = json.getJSONArray("objectives")
            require(points.length() + objectives.length() > 0) { "至少填写一条知识点或教学目标" }
            val allIds = mutableSetOf<String>()
            val pointIds = mutableSetOf<String>()
            for (array in listOf(points, objectives)) for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                require(item.getString("id").isNotBlank() && allIds.add(item.getString("id"))) { "知识点和目标编号不能为空或重复" }
                require(item.getString("text").isNotBlank()) { "知识点和目标正文不能为空" }
                if (array === points) pointIds.add(item.getString("id"))
            }
            for (i in 0 until objectives.length()) {
                val refs = objectives.getJSONObject(i).optJSONArray("knowledgePointIds") ?: JSONArray()
                for (j in 0 until refs.length()) require(refs.getString(j) in pointIds) { "教学目标引用了不存在的知识点" }
            }
            return json.toString()
        }
    }
}
