package edu.neu.aijiaoxue.sprint3

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.MetricKey
import edu.neu.aijiaoxue.data.model.VerificationVerdict
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sprint 3 v6 → 当前 v8 累积迁移校验，保留原 v6 → v7 复评表约束验证：
 * 旧数据与来源关联在升级后仍可读，新表建立成功，指标快照在数据库层就是只追加的。
 */
@RunWith(AndroidJUnit4::class)
class Sprint3ReviewMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun version6KeepsOldDataAndCreatesReviewTables() = runBlocking {
        val name = "lyx-s3-migration-6-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = instrumentation.context.assets.open("migration-schemas/6.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
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
            seed(old, schema)
            old.rawQuery("PRAGMA foreign_key_check", null).use {
                assertFalse("旧 schema fixture 自身违反外键", it.moveToFirst())
            }
            old.version = 6
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            assertEquals(8, db.openHelper.writableDatabase.version)
            // 旧数据与来源关联仍然可读
            assertEquals("旧督导", db.userDao().getById(1)!!.name)
            assertEquals("旧课程", db.courseDao().getById(1)!!.name)
            assertEquals("旧正式评价", db.evaluationDao().getByTask(1)!!.overallOpinion)
            // 新表已建立且为空
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT description FROM evaluation_issues WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("旧问题1", it.getString(0))
            }
            for (table in listOf("metric_snapshots", "review_links", "issue_verifications")) {
                sql.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("升级后 $table 应为空", 0, it.getInt(0))
                }
            }
            // 指标快照在数据库层只追加：同一 (taskId, metricKey, methodVersion) 不能重复
            val inserted = sql.insert("metric_snapshots", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("taskId", 1L)
                put("metricKey", MetricKey.ENGAGEMENT_RATE.name)
                put("value", 60.0)
                put("unit", "%")
                put("denominator", "实到人数合计")
                put("sampling", "课堂全时段参与状态记录")
                put("methodVersion", "v1")
                put("sampleCount", 3)
                put("createdAt", 1000L)
            })
            assertTrue(inserted > 0)
            val duplicated = runCatching {
                sql.insert("metric_snapshots", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("taskId", 1L)
                    put("metricKey", MetricKey.ENGAGEMENT_RATE.name)
                    put("value", 99.0)
                    put("unit", "%")
                    put("denominator", "实到人数合计")
                    put("sampling", "课堂全时段参与状态记录")
                    put("methodVersion", "v1")
                    put("sampleCount", 3)
                    put("createdAt", 2000L)
                })
            }
            assertTrue("同一口径版本的快照不得重复写入", duplicated.isFailure)
            // 核验记录必须挂在真实关联上
            assertTrue(runCatching {
                sql.insert("issue_verifications", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("linkId", 999L)
                    put("verdict", VerificationVerdict.IMPROVED.name)
                    put("basis", "缺少关联的核验")
                    put("verifierId", 1L)
                    put("verifiedAt", 1000L)
                })
            }.isFailure)
            sql.query("PRAGMA foreign_key_check").use {
                assertFalse("迁移后出现悬空外键", it.moveToFirst())
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun seed(db: SQLiteDatabase, schema: JSONObject) {
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
        insert("users", "id" to 1L, "account" to "legacy-sup", "passwordHash" to "fixture-hash",
            "name" to "旧督导", "role" to "SUPERVISOR")
        insert("users", "id" to 2L, "account" to "legacy-tea", "passwordHash" to "fixture-hash",
            "name" to "旧教师", "role" to "TEACHER")
        insert("courses", "id" to 1L, "code" to "LEGACY-01", "name" to "旧课程", "teacherId" to 2L)
        insert("supervision_tasks", "id" to 1L, "code" to "LEGACY-1", "courseId" to 1L,
            "supervisorId" to 1L, "supervisionDate" to "2026-10-07", "period" to "1",
            "type" to "REGULAR", "status" to "COMPLETED")
        insert("evaluations", "id" to 1L, "taskId" to 1L, "contentScore" to 5, "designScore" to 4,
            "interactionScore" to 3, "engagementScore" to 4, "totalScore" to 81, "grade" to "GOOD",
            "suggestions" to "旧督导建议", "overallOpinion" to "旧正式评价", "status" to "SUBMITTED",
            "submittedAt" to 1000L)
        insert("evaluation_issues", "id" to 1L, "evaluationId" to 1L, "description" to "旧问题1",
            "dimension" to "INTERACTION")
        insert("engagement_records", "id" to 1L, "taskId" to 1L, "type" to "LISTENING",
            "presentCount" to 30, "engagedCount" to 18, "recordedAt" to 100L, "offsetMs" to 0L)
        insert("interaction_events", "id" to 1L, "taskId" to 1L, "type" to "TEACHER_QUESTION",
            "occurredAt" to 200L, "offsetMs" to 100L)
    }
}
