package edu.neu.aijiaoxue.sprint2

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.improve.ImprovementRepository
import edu.neu.aijiaoxue.ui.improve.ImprovementExecutionRepository
import edu.neu.aijiaoxue.ui.improve.ExecutionInput
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
    @Test fun version4KeepsPublishedSourcesAdoptionDismissalAndConfirmedTodo() = migrate(4)
    @Test fun version5KeepsFrozenSourcesAndCanRecordActualImplementation() = migrate(5)
    @Test fun version4ArchivesInvalidAiWithoutLosingPersonalData() = migrate(4, invalidAi = true)

    private fun migrate(version: Int, invalidAi: Boolean = false) = runBlocking {
        val name = "yzj-s2-migration-$version-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = instrumentation.context.assets.open("migration-schemas/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val originalAiRows = mutableMapOf<Pair<String, Long>, JSONObject>()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
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
            seed(old, schema, version, invalidAi)
            if (invalidAi) {
                listOf("ai_analysis_results", "ai_evidences", "ai_corrections").forEach { table ->
                    old.rawQuery("SELECT * FROM $table", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val json = rowJson(cursor)
                            originalAiRows[table to json.getLong("id")] = json
                        }
                    }
                }
            }
            old.rawQuery("PRAGMA foreign_key_check", null).use {
                assertFalse("旧 schema fixture 自身违反外键", it.moveToFirst())
            }
            old.version = version
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            assertEquals(7, db.openHelper.writableDatabase.version)
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
            if (version >= 4) assertEquals(1L, confirmed.measures.single().suggestionId)
            else assertNull(confirmed.measures.single().suggestionId)
            assertEquals("旧重点1", confirmed.items.single().description)
            val draft = repo.load(2, 2)
            assertEquals(PlanStatus.DRAFT, draft.plan!!.status)
            assertEquals("旧草稿措施", draft.measures.single().content)
            assertEquals(1, repo.todos(2, false, null).first().size)
            assertEquals(1L, repo.todos(2, false, null).first().single().measureId)
            if (version >= 4) {
                assertTrue(confirmed.reviewPublished)
                val snapshot = confirmed.reviewedAnalyses.single()
                assertEquals("v4-frozen-review", snapshot.versionKey)
                assertEquals("已发布人工结论", snapshot.effectiveContent)
                assertEquals("发布时的证据摘要", snapshot.evidenceSummary)
                assertEquals(1L, snapshot.reviewerId)
                assertEquals(4000L, snapshot.reviewedAt)
                assertEquals(2, confirmed.suggestions.size)
                val adopted = confirmed.suggestions.single { it.id == 1L }
                assertEquals("采用前候选原文", adopted.content)
                assertEquals("旧重点1", adopted.problemSnapshot)
                assertEquals(SuggestionOrigin.RULE_BASED, adopted.generatorMode)
                assertEquals("rule-v4", adopted.generatorVersion)
                assertEquals("v4-frozen-review", adopted.reviewVersionKey)
                assertEquals(1L, adopted.sourceSnapshotId)
                assertNull(adopted.dismissedAt)
                assertEquals(6500L, confirmed.suggestions.single { it.id == 2L }.dismissedAt)
                val todo = repo.todos(2, false, null).first().single()
                assertEquals(1L, todo.suggestionId)
                assertEquals("采用前候选原文", todo.suggestionContent)
                assertEquals("v4-frozen-review", todo.reviewVersionKey)
            } else {
                // 没有历史发布快照时，不把迁移的 AI 默默发布给教师。
                assertFalse(confirmed.reviewPublished)
                assertTrue(confirmed.reviewedAnalyses.isEmpty())
                assertTrue(confirmed.suggestions.isEmpty())
            }
            if (version >= 2) {
                assertEquals("旧AI分析", db.aiAnalysisDao().getAnalysisById(1)!!.content)
                val correction = db.aiCorrectionDao().observeCorrections(1).first().single { it.id == 1L }
                assertEquals("旧人工修正", correction.correctedContent)
                assertEquals("旧课堂证据摘要", db.aiEvidenceDao().getByAnalysis(1).single { it.id == 1L }.description)
                if (version >= 3) assertEquals(1L, correction.reviewerId)
                else assertNull(correction.reviewerId)
            }
            val executions = ImprovementExecutionRepository(db)
            assertTrue("旧计划不能被迁移成虚构的实际做法", executions.load(2, 1).executions.isEmpty())
            val executionId = executions.append(2, 1, 1, ExecutionInput(
                implementationNote = "升级后实施记录", actualAdjustment = "增加分组讨论并记录过程",
                implementedAt = System.currentTimeMillis() - 1000, requestKey = "after-migration"))
            assertEquals(executionId, executions.load(2, 1).executions.single().execution.id)
            verifyArchive(db, originalAiRows, invalidAi)
            verifyConstraints(db, version)
            if (invalidAi) {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("""INSERT INTO ai_analysis_results(taskId,type,content,confidence,verified,createdAt)
                    VALUES(1,'BOPPPS','迁移后新分析',0.8,0,9000)""")
                val analysisId = sql.query("SELECT id FROM ai_analysis_results WHERE content='迁移后新分析'").use {
                    assertTrue(it.moveToFirst())
                    assertTrue("已删除高位分析 ID 不得被重用", it.getLong(0) > 100L)
                    it.getLong(0)
                }
                sql.execSQL("""INSERT INTO ai_evidences(analysisId,taskId,description)
                    VALUES(?,1,'迁移后新证据')""", arrayOf(analysisId))
                sql.query("SELECT id FROM ai_evidences WHERE description='迁移后新证据'").use {
                    assertTrue(it.moveToFirst())
                    assertTrue("已删除高位证据 ID 不得被重用", it.getLong(0) > 200L)
                }
                sql.execSQL("""INSERT INTO ai_corrections(analysisId,reviewerId,originalContent,correctedContent,accepted,createdAt)
                    VALUES(?,1,'迁移后新分析','迁移后新修正',0,9100)""", arrayOf(analysisId))
                sql.query("SELECT id FROM ai_corrections WHERE correctedContent='迁移后新修正'").use {
                    assertTrue(it.moveToFirst())
                    assertTrue("已删除高位审核 ID 不得被重用", it.getLong(0) > 300L)
                }
            }
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse("迁移后出现悬空外键", it.moveToFirst())
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun seed(db: SQLiteDatabase, schema: JSONObject, version: Int, invalidAi: Boolean) {
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
                "dueDate" to "2026-11-01", "status" to if (id == 1L) "IN_PROGRESS" else "NOT_STARTED",
                "suggestionId" to if (version >= 4 && id == 1L) 1L else null)
        }
        insert("materials", "id" to 1L, "taskId" to 1L, "type" to "PPT",
            "filePath" to "tasks/1/photo/legacy.jpg", "capturedAt" to 100L, "offsetMs" to 0L)
        insert("interaction_events", "id" to 1L, "taskId" to 1L, "type" to "GROUP_DISCUSSION",
            "occurredAt" to 200L, "offsetMs" to 100L)
        if (version >= 2) {
            insert("ai_analysis_results", "id" to 1L, "taskId" to 1L, "type" to "BOPPPS",
                "content" to "旧AI分析", "confidence" to 0.8f, "verified" to 1, "createdAt" to 3000L)
            insert("ai_evidences", "id" to 1L, "analysisId" to 1L, "taskId" to 1L,
                "materialId" to 1L, "eventId" to 1L, "description" to "旧课堂证据摘要")
            insert("ai_corrections", "id" to 1L, "analysisId" to 1L, "reviewerId" to 1L,
                "originalContent" to "旧AI分析", "correctedContent" to "旧人工修正", "accepted" to 0, "createdAt" to 4000L)
        }
        if (version >= 4) {
            insert("review_publications", "id" to 1L, "evaluationId" to 1L, "taskId" to 1L,
                "supervisorId" to 1L, "publishedAt" to 5000L)
            insert("reviewed_analysis_snapshots", "id" to 1L, "publicationId" to 1L, "analysisId" to 1L,
                "correctionId" to 1L, "reviewerId" to 1L, "type" to "BOPPPS", "originalContent" to "旧AI分析",
                "effectiveContent" to "已发布人工结论", "evidenceSummary" to "发布时的证据摘要",
                "reviewedAt" to 4000L, "versionKey" to "v4-frozen-review")
            for (id in 1L..2L) {
                insert("improvement_suggestions", "id" to id, "planId" to 1L, "itemId" to 1L,
                    "sourceSnapshotId" to 1L, "content" to if (id == 1L) "采用前候选原文" else "已放弃候选",
                    "problemSnapshot" to "旧重点1", "bopppsStage" to "参与式学习", "rationale" to "发布时的建议依据",
                    "generatorMode" to "RULE_BASED", "generatorVersion" to if (id == 1L) "rule-v4" else "rule-v4-second",
                    "reviewVersionKey" to "v4-frozen-review", "createdAt" to 6000L,
                    "dismissedAt" to if (id == 2L) 6500L else null)
            }
        }
        if (invalidAi) {
            check(version == 4)
            insert("materials", "id" to 2L, "taskId" to 2L, "type" to "PPT", "filePath" to "tasks/2/other.jpg")
            insert("interaction_events", "id" to 2L, "taskId" to 2L, "type" to "GROUP_DISCUSSION")
            insert("ai_analysis_results", "id" to 2L, "taskId" to 999L, "type" to "BOPPPS",
                "content" to "孤立任务分析原文", "confidence" to 0.75f, "startMs" to 12L, "endMs" to 99L,
                "verified" to 1, "createdAt" to 7100L)
            insert("ai_analysis_results", "id" to 3L, "taskId" to 1L, "type" to "UNKNOWN_V4",
                "content" to "无法映射类型原文", "confidence" to 0.5f, "createdAt" to 7200L)
            for (id in 2L..6L) {
                insert("ai_evidences", "id" to id,
                    "analysisId" to when (id) { 2L -> 999L; 5L -> 2L; else -> 1L },
                    "taskId" to when (id) { 5L -> 999L; 6L -> 2L; else -> 1L },
                    "materialId" to when (id) { 3L -> 999L; 4L -> 2L; else -> 1L },
                    "eventId" to when (id) { 3L -> 999L; 4L -> 2L; else -> null },
                    "startMs" to 20L, "endMs" to 80L, "description" to "异常引用证据原文$id")
            }
            for (id in 2L..4L) {
                insert("ai_corrections", "id" to id,
                    "analysisId" to when (id) { 2L -> 999L; 3L -> 2L; else -> 1L },
                    "reviewerId" to if (id == 4L) 999L else 1L, "originalContent" to "异常审核原分析$id",
                    "correctedContent" to "异常审核修正原文$id", "accepted" to 0, "createdAt" to 7300L + id)
            }
            // 模拟曾真实存在、随后删除的高位记录，保证旧序列大于当前 MAX(id)。
            insert("ai_analysis_results", "id" to 100L, "taskId" to 1L, "type" to "BOPPPS",
                "content" to "稍后删除的高位分析", "confidence" to 0.8f)
            insert("ai_evidences", "id" to 200L, "analysisId" to 100L, "taskId" to 1L,
                "description" to "稍后删除的高位证据")
            insert("ai_corrections", "id" to 300L, "analysisId" to 100L, "reviewerId" to 1L,
                "originalContent" to "稍后删除的高位分析", "correctedContent" to "稍后删除的高位审核", "accepted" to 1)
            db.execSQL("DELETE FROM ai_evidences WHERE id=200")
            db.execSQL("DELETE FROM ai_corrections WHERE id=300")
            db.execSQL("DELETE FROM ai_analysis_results WHERE id=100")
        }
    }

    private fun verifyArchive(db: AppDatabase, originalRows: Map<Pair<String, Long>, JSONObject>, invalidAi: Boolean) {
        val sql = db.openHelper.readableDatabase
        sql.query("SELECT * FROM legacy_ai_migration_archives ORDER BY id").use { cursor ->
            assertEquals(if (invalidAi) 10 else 0, cursor.count)
            while (cursor.moveToNext()) {
                assertEquals(4, cursor.getInt(cursor.getColumnIndexOrThrow("sourceVersion")))
                val key = cursor.getString(cursor.getColumnIndexOrThrow("sourceTable")) to
                    cursor.getLong(cursor.getColumnIndexOrThrow("sourceId"))
                val expected = requireNotNull(originalRows[key])
                val actual = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("rawRowJson")))
                assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
                expected.keys().forEach { field -> assertEquals("归档字段 $key.$field", expected.get(field).toString(), actual.get(field).toString()) }
                assertTrue(cursor.getString(cursor.getColumnIndexOrThrow("reason")).isNotBlank())
                assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("archivedAt")) > 0L)
            }
        }
        if (invalidAi) {
            sql.query("SELECT reviewerId,correctedContent FROM ai_corrections WHERE id=4").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
                assertEquals("异常审核修正原文4", it.getString(1))
            }
            sql.query("SELECT id,materialId,eventId,description FROM ai_evidences WHERE id IN (3,4)").use {
                assertEquals(2, it.count)
                while (it.moveToNext()) {
                    assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
                    assertEquals("异常引用证据原文${it.getLong(0)}", it.getString(3))
                }
            }
            sql.query("SELECT COUNT(*) FROM ai_analysis_results").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    private suspend fun verifyConstraints(db: AppDatabase, version: Int) {
        val sql = db.openHelper.writableDatabase
        for ((table, columns) in mapOf(
            "ai_analysis_results" to listOf("taskId"),
            "ai_evidences" to listOf("analysisId", "taskId", "materialId", "eventId"),
            "ai_corrections" to listOf("analysisId", "reviewerId"),
        )) {
            val names = sql.query("PRAGMA index_list(`$table`)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            columns.forEach { assertTrue("缺少 $table.$it 索引", "index_${table}_$it" in names) }
        }
        val materialForeignKey = sql.query("PRAGMA foreign_key_list(ai_evidences)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(cursor.getColumnIndexOrThrow("from")),
                    cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
        }
        assertEquals("SET NULL", materialForeignKey["materialId"])
        assertEquals("SET NULL", materialForeignKey["eventId"])
        assertEquals("CASCADE", materialForeignKey["analysisId"])
        assertTrue("迁移后必须拒绝不存在的父任务", runCatching {
            sql.execSQL("""INSERT INTO ai_analysis_results(taskId,type,content,confidence,verified,createdAt)
                VALUES(99999,'BOPPPS','不可写入',0.8,0,1)""")
        }.isFailure)
        if (version >= 2) {
            sql.execSQL("DELETE FROM materials WHERE id=1")
            sql.execSQL("DELETE FROM interaction_events WHERE id=1")
            val evidence = db.aiEvidenceDao().getByAnalysis(1).single { it.id == 1L }
            assertNull(evidence.materialId)
            assertNull(evidence.eventId)
            assertEquals("旧课堂证据摘要", evidence.description)
            sql.execSQL("DELETE FROM ai_analysis_results WHERE id=1")
            assertTrue(db.aiEvidenceDao().getByAnalysis(1).isEmpty())
            assertTrue(db.aiCorrectionDao().observeCorrections(1).first().isEmpty())
            if (version >= 4) {
                val after = ImprovementRepository(db).load(2, 1)
                assertEquals("已发布人工结论", after.reviewedAnalyses.single().effectiveContent)
                assertEquals(2, after.suggestions.size)
                assertEquals("旧已确认措施", after.measures.single().content)
                assertEquals(1L, after.measures.single().suggestionId)
            }
        }
    }

    private fun rowJson(cursor: Cursor): JSONObject = JSONObject().apply {
        cursor.columnNames.forEachIndexed { index, column ->
            put(column, when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                else -> cursor.getString(index)
            })
        }
    }
}
