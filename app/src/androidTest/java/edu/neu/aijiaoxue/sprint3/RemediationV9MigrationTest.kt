package edu.neu.aijiaoxue.sprint3

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.RemediationV9Migration
import edu.neu.aijiaoxue.data.model.IssueDeclaration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemediationV9MigrationTest {
    @Test fun v8ToV9PreservesWrongHistoricalReferencesAndMissingDeclarationsThenUpgradesToCurrent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "remediation-v9-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = instrumentation.context.assets.open("migration-schemas/8.json").bufferedReader()
            .use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            fun insert(table: String, vararg supplied: Pair<String, Any?>) {
                val values = supplied.toMap()
                val fields = (0 until entities.length()).map { entities.getJSONObject(it) }
                    .single { it.getString("tableName") == table }.getJSONArray("fields")
                val row = ContentValues()
                for (i in 0 until fields.length()) {
                    val field = fields.getJSONObject(i)
                    val key = field.getString("columnName")
                    val value = if (key in values) values[key] else if (!field.optBoolean("notNull", false)) null
                        else if (field.getString("affinity") == "TEXT") "" else 0L
                    when (value) {
                        null -> row.putNull(key)
                        is String -> row.put(key, value)
                        is Int -> row.put(key, value)
                        is Long -> row.put(key, value)
                        is Double -> row.put(key, value)
                        else -> error("Unsupported test value")
                    }
                }
                old.insertOrThrow(table, null, row)
            }
            insert("users", "id" to 1L, "account" to "v9-sup", "name" to "历史督导", "role" to "SUPERVISOR", "passwordHash" to "test")
            insert("users", "id" to 2L, "account" to "v9-tea", "name" to "历史教师", "role" to "TEACHER", "passwordHash" to "test")
            insert("courses", "id" to 1L, "code" to "V9", "name" to "历史课程", "teacherId" to 2L)
            for (id in 1L..2L) {
                insert("supervision_tasks", "id" to id, "code" to "V9-$id", "courseId" to 1L,
                    "supervisorId" to 1L, "supervisionDate" to "2026-10-20", "period" to if (id == 1L) "3-4" else "1-2",
                    "type" to if (id == 1L) "REGULAR" else "FOLLOW_UP", "status" to "COMPLETED")
                insert("evaluations", "id" to id, "taskId" to id, "contentScore" to 4, "designScore" to 4,
                    "interactionScore" to 4, "engagementScore" to 4, "totalScore" to 80, "grade" to "GOOD",
                    "overallOpinion" to "历史评价$id", "status" to "SUBMITTED", "submittedAt" to if (id == 1L) 1000L else null)
                insert("metric_snapshots", "id" to id + 50, "taskId" to id, "metricKey" to "EVALUATION_TOTAL", "value" to 80.0,
                    "unit" to "分", "denominator" to "百分制总分", "sampling" to "已提交正式评价", "methodVersion" to "v1", "sampleCount" to 1, "createdAt" to 1000L)
            }
            insert("evaluation_issues", "id" to 1L, "evaluationId" to 1L, "description" to "原互动问题", "dimension" to "INTERACTION")
            insert("review_links", "id" to 1L, "originTaskId" to 1L, "originIssueId" to 1L, "followUpTaskId" to 2L,
                "teacherId" to 2L, "originIssueSnapshot" to "原互动问题", "originDimension" to "课堂互动", "createdBy" to 1L)
            insert("issue_verifications", "id" to 1L, "linkId" to 1L, "verdict" to "IMPROVED", "basis" to "旧版错误总分依据",
                "verifierId" to 1L, "verifiedAt" to 2000L, "beforeSnapshotId" to 52L, "afterSnapshotId" to 51L)
            old.version = 8
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Expected v8 fixture")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(8, oldVersion); assertEquals(9, newVersion)
                    RemediationV9Migration.MIGRATION_8_9.migrate(db)
                }
            }).build())
        try {
            val sql = helper.writableDatabase
            assertEquals(9, sql.version)
            sql.query("SELECT chronologyStatus,originDimensionKey FROM review_links WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals("REVERSED", it.getString(0)); assertEquals("INTERACTION", it.getString(1))
            }
            sql.query("SELECT verdict,basis,beforeSnapshotId,afterSnapshotId,contractVersion FROM issue_verifications WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals("IMPROVED", it.getString(0)); assertEquals("旧版错误总分依据", it.getString(1))
                assertEquals(52L, it.getLong(2)); assertEquals(51L, it.getLong(3)); assertEquals(1, it.getInt(4))
            }
            sql.query("SELECT taskId,value,createdAt FROM metric_snapshots WHERE metricKey='EVALUATION_DESIGN'").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertEquals(4.0, it.getDouble(1), 0.0)
                assertEquals(1000L, it.getLong(2)); assertFalse(it.moveToNext())
            }
            sql.query("SELECT COUNT(*) FROM issue_verification_evidence").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { helper.close() }
        val current = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            assertEquals(10, current.openHelper.writableDatabase.version)
            assertEquals(IssueDeclaration.UNASSESSED, current.evaluationDao().getByTask(2)!!.issueDeclaration)
            assertNull(current.evaluationDao().getByTask(2)!!.issueDeclaredAt)
            assertEquals("历史评价2", current.evaluationDao().getByTask(2)!!.overallOpinion)
        } finally { current.close(); context.deleteDatabase(name) }
    }
}
