package edu.neu.aijiaoxue.sprint2

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.improve.ImprovementRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实旧schema建库再由Room升级；不清空演示数据库，不靠重装证明迁移成功。 */
@RunWith(AndroidJUnit4::class)
class Sprint2MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun version1KeepsManualDraftConfirmedPlanAndTodo() = migrate(1)
    @Test fun version2KeepsManualDataAndLegacyAi() = migrate(2)
    @Test fun version3KeepsManualDataAndReviewedAi() = migrate(3)

    private fun migrate(version: Int) = runBlocking {
        val name = "yzj-s2-migration-$version-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = instrumentation.context.assets.open("migration-schemas/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) {
                    requireNotNull(indices)
                    old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            seed(old, schema, version)
            old.version = version
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            assertEquals(4, db.openHelper.writableDatabase.version)
            assertEquals("旧教师", db.userDao().getById(2)!!.name)
            assertEquals("旧课程", db.courseDao().getById(1)!!.name)
            assertEquals("tasks/1/photo/legacy.jpg", db.captureDao().getMaterial(1)!!.filePath)
            assertEquals("旧人工草稿", db.evaluationDao().getByTask(3)!!.overallOpinion)
            assertEquals(EvalStatus.DRAFT, db.evaluationDao().getByTask(3)!!.status)
            val repo = ImprovementRepository(db)
            val confirmed = repo.load(2, 1)
            assertEquals(PlanStatus.CONFIRMED, confirmed.plan!!.status)
            assertEquals("旧已确认措施", confirmed.measures.single().content)
            assertEquals(MeasureStatus.IN_PROGRESS, confirmed.measures.single().status)
            assertNull(confirmed.measures.single().suggestionId)
            assertEquals("旧重点1", confirmed.items.single().description)
            val draft = repo.load(2, 2)
            assertEquals(PlanStatus.DRAFT, draft.plan!!.status)
            assertEquals("旧草稿措施", draft.measures.single().content)
            assertEquals(1, repo.todos(2, false, null).first().size)
            assertEquals(1L, repo.todos(2, false, null).first().single().measureId)
            // 旧库没有评价发布快照。升级不把历史AI默默发布给教师。
            assertFalse(confirmed.reviewPublished)
            assertTrue(confirmed.reviewedAnalyses.isEmpty())
            assertTrue(confirmed.suggestions.isEmpty())
            if (version >= 2) {
                assertEquals("旧AI分析", db.aiAnalysisDao().getAnalysisById(1)!!.content)
                val correction = db.aiCorrectionDao().observeCorrections(1).first().single()
                assertEquals("旧人工修正", correction.correctedContent)
                assertEquals("旧课堂证据摘要", db.aiEvidenceDao().getByAnalysis(1).single().description)
                if (version == 3) assertEquals(1L, correction.reviewerId)
                else assertNull(correction.reviewerId)
            }
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse("迁移后出现悬空外键", it.moveToFirst())
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun seed(db: SQLiteDatabase, schema: JSONObject, version: Int) {
        val entities = schema.getJSONArray("entities")
        fun insert(table: String, vararg values: Pair<String, Any?>) {
            val supplied = values.toMap()
            val entity = (0 until entities.length()).map { entities.getJSONObject(it) }
                .single { it.getString("tableName") == table }
            val fields = entity.getJSONArray("fields")
            val row = ContentValues()
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                val key = field.getString("columnName")
                val value = if (supplied.containsKey(key)) supplied[key]
                    else if (!field.optBoolean("notNull", false)) null
                    else if (field.getString("affinity") == "TEXT") "" else 0L
                when (value) {
                    null -> row.putNull(key)
                    is String -> row.put(key, value)
                    is Int -> row.put(key, value)
                    is Long -> row.put(key, value)
                    is Float -> row.put(key, value)
                    else -> error("未支持的fixture值：$key=$value")
                }
            }
            assertTrue(db.insertOrThrow(table, null, row) > 0)
        }
        insert("users", "id" to 1L, "account" to "legacy-sup", "passwordHash" to "fixture-hash", "name" to "旧督导", "role" to "SUPERVISOR")
        insert("users", "id" to 2L, "account" to "legacy-tea", "passwordHash" to "fixture-hash", "name" to "旧教师", "role" to "TEACHER")
        insert("courses", "id" to 1L, "code" to "LEGACY-01", "name" to "旧课程", "teacherId" to 2L)
        for (id in 1L..3L) {
            insert("supervision_tasks", "id" to id, "code" to "LEGACY-$id", "courseId" to 1L,
                "supervisorId" to 1L, "supervisionDate" to "2026-10-07", "period" to id.toString(),
                "type" to "REGULAR", "status" to if (id == 3L) "PENDING_EVALUATION" else "COMPLETED")
            insert("evaluations", "id" to id, "taskId" to id, "contentScore" to 5, "designScore" to 4,
                "interactionScore" to 3, "engagementScore" to 4, "totalScore" to 81, "grade" to "GOOD",
                "suggestions" to "旧督导建议", "overallOpinion" to if (id == 3L) "旧人工草稿" else "旧正式评价",
                "status" to if (id == 3L) "DRAFT" else "SUBMITTED", "submittedAt" to if (id == 3L) null else 1000L)
            insert("evaluation_issues", "id" to id, "evaluationId" to id, "description" to "旧问题$id", "dimension" to "INTERACTION")
        }
        for (id in 1L..2L) {
            insert("improvement_plans", "id" to id, "teacherId" to 2L, "taskId" to id,
                "status" to if (id == 1L) "CONFIRMED" else "DRAFT", "confirmedAt" to if (id == 1L) 2000L else null)
            insert("improvement_items", "id" to id, "planId" to id, "issueId" to id,
                "source" to "SUPERVISOR", "description" to "旧重点$id", "priority" to "HIGH")
            insert("improvement_measures", "id" to id, "planId" to id, "itemId" to id,
                "content" to if (id == 1L) "旧已确认措施" else "旧草稿措施", "source" to "MODIFIED",
                "dueDate" to "2026-11-01", "status" to if (id == 1L) "IN_PROGRESS" else "NOT_STARTED")
        }
        insert("materials", "id" to 1L, "taskId" to 1L, "type" to "PPT",
            "filePath" to "tasks/1/photo/legacy.jpg", "capturedAt" to 100L, "offsetMs" to 0L)
        if (version >= 2) {
            insert("ai_analysis_results", "id" to 1L, "taskId" to 1L, "type" to "BOPPPS",
                "content" to "旧AI分析", "confidence" to 0.8f, "verified" to 1, "createdAt" to 3000L)
            insert("ai_evidences", "id" to 1L, "analysisId" to 1L, "taskId" to 1L,
                "materialId" to 1L, "description" to "旧课堂证据摘要")
            insert("ai_corrections", "id" to 1L, "analysisId" to 1L, "reviewerId" to 1L,
                "originalContent" to "旧AI分析", "correctedContent" to "旧人工修正", "accepted" to 0, "createdAt" to 4000L)
        }
    }
}
