package edu.neu.aijiaoxue.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** v1=S1，v2=早期 AI，v3=团队整合版，v4=个人已交付版；各版本直接升级，不经过拆约束的旧路径。 */
object Sprint2Migrations {
    val ALL: Array<Migration> = (1..4).map { from -> object : Migration(from, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createArchiveTable(db)
            if (from == 1) createAiTables(db) else rebuildAiTables(db, from)
            if (from < 4) {
                db.execSQL("ALTER TABLE improvement_measures ADD COLUMN suggestionId INTEGER")
                createSuggestionTables(db)
            }
            // v4 的发布快照、建议、采用关联不重建，也不重新推断发布身份与时间。
        }
    } }.toTypedArray()

    private fun createAiTables(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_analysis_results` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `taskId` INTEGER NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, `confidence` REAL NOT NULL,
            `startMs` INTEGER, `endMs` INTEGER, `verified` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
            FOREIGN KEY(`taskId`) REFERENCES `supervision_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX `index_ai_analysis_results_taskId` ON `ai_analysis_results` (`taskId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_evidences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `analysisId` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, `materialId` INTEGER, `eventId` INTEGER,
            `startMs` INTEGER, `endMs` INTEGER, `description` TEXT NOT NULL,
            FOREIGN KEY(`analysisId`) REFERENCES `ai_analysis_results`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`taskId`) REFERENCES `supervision_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`materialId`) REFERENCES `materials`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
            FOREIGN KEY(`eventId`) REFERENCES `interaction_events`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        listOf("analysisId", "taskId", "materialId", "eventId").forEach { column ->
            db.execSQL("CREATE INDEX `index_ai_evidences_$column` ON `ai_evidences` (`$column`)")
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_corrections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `analysisId` INTEGER NOT NULL, `originalContent` TEXT NOT NULL, `correctedContent` TEXT NOT NULL,
            `accepted` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `reviewerId` INTEGER,
            FOREIGN KEY(`analysisId`) REFERENCES `ai_analysis_results`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`reviewerId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        listOf("analysisId", "reviewerId").forEach { column ->
            db.execSQL("CREATE INDEX `index_ai_corrections_$column` ON `ai_corrections` (`$column`)")
        }
    }

    private fun rebuildAiTables(db: SupportSQLiteDatabase, sourceVersion: Int) {
        val tables = listOf("ai_analysis_results", "ai_evidences", "ai_corrections")
        val sequences = tables.associateWith { table ->
            db.query("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(table)).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        }
        // 无外键的临时副本先保全原始行，删除子表后才删除父表，不能触发级联吞数据。
        tables.forEach { db.execSQL("CREATE TABLE `${it}_legacy_v5` AS SELECT * FROM `$it`") }
        listOf("ai_evidences", "ai_corrections", "ai_analysis_results").forEach {
            db.execSQL("DROP TABLE `$it`")
        }
        createAiTables(db)

        forEachOldRow(db, "ai_analysis_results") { row ->
            val reasons = mutableListOf<String>()
            if (!exists(db, "supervision_tasks", row.getAsLong("taskId"))) reasons += "父任务缺失"
            // 历史自由文本类型不能强行转换为某个业务枚举，也不能令 Room 读库崩溃。
            if (row.getAsString("type") !in setOf("BOPPPS", "INTERACTION", "ENGAGEMENT")) {
                reasons += "分析类型无法映射为已知枚举"
            }
            if (reasons.isEmpty()) insert(db, "ai_analysis_results", row)
            else archive(db, sourceVersion, "ai_analysis_results", row, reasons.joinToString("；"))
        }

        forEachOldRow(db, "ai_evidences") { original ->
            val analysisTask = db.query("SELECT taskId FROM ai_analysis_results WHERE id = ?",
                arrayOf(original.getAsLong("analysisId"))).use { if (it.moveToFirst()) it.getLong(0) else null }
            val taskId = original.getAsLong("taskId")
            if (analysisTask == null || analysisTask != taskId) {
                archive(db, sourceVersion, "ai_evidences", original,
                    if (analysisTask == null) "父分析缺失或已归档" else "证据任务与父分析不一致")
            } else {
                val row = ContentValues(original)
                val reasons = mutableListOf<String>()
                for ((column, table) in listOf("materialId" to "materials", "eventId" to "interaction_events")) {
                    val id = row.getAsLong(column) ?: continue
                    val sameTask = db.query("SELECT 1 FROM `$table` WHERE id = ? AND taskId = ?",
                        arrayOf(id, taskId)).use { it.moveToFirst() }
                    if (!sameTask) {
                        row.putNull(column)
                        reasons += "$column 失效或属于其他任务，断开引用并保留说明"
                    }
                }
                if (reasons.isNotEmpty()) archive(db, sourceVersion, "ai_evidences", original, reasons.joinToString("；"))
                insert(db, "ai_evidences", row)
            }
        }

        forEachOldRow(db, "ai_corrections") { original ->
            if (!exists(db, "ai_analysis_results", original.getAsLong("analysisId"))) {
                archive(db, sourceVersion, "ai_corrections", original, "父分析缺失或已归档")
            } else {
                val row = ContentValues(original)
                val reviewer = row.getAsLong("reviewerId")
                if (reviewer == null) row.putNull("reviewerId")
                else if (!exists(db, "users", reviewer)) {
                    archive(db, sourceVersion, "ai_corrections", original, "审核人引用失效，保留未知身份")
                    row.putNull("reviewerId")
                }
                insert(db, "ai_corrections", row)
            }
        }

        tables.forEach { table ->
            // 即使最大 ID 的异常行被归档，也不能把其 ID 重用成另一条分析/修正。
            val lastId = db.query("SELECT COALESCE(MAX(id), 0) FROM `${table}_legacy_v5`").use {
                it.moveToFirst(); it.getLong(0)
            }
            val sequence = maxOf(sequences.getValue(table), lastId)
            db.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf(table))
            db.execSQL("INSERT INTO sqlite_sequence(name, seq) VALUES(?, ?)", arrayOf<Any>(table, sequence))
            db.execSQL("DROP TABLE `${table}_legacy_v5`")
        }
    }

    private fun createArchiveTable(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `legacy_ai_migration_archives` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceVersion` INTEGER NOT NULL,
            `sourceTable` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `reason` TEXT NOT NULL,
            `rawRowJson` TEXT NOT NULL, `archivedAt` INTEGER NOT NULL)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_legacy_ai_migration_archives_sourceVersion_sourceTable_sourceId`
            ON `legacy_ai_migration_archives` (`sourceVersion`, `sourceTable`, `sourceId`)""")
    }

    private fun archive(db: SupportSQLiteDatabase, version: Int, table: String, row: ContentValues, reason: String) {
        val json = JSONObject()
        row.valueSet().forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        insert(db, "legacy_ai_migration_archives", ContentValues().apply {
            put("sourceVersion", version)
            put("sourceTable", table)
            put("sourceId", row.getAsLong("id"))
            put("reason", reason)
            put("rawRowJson", json.toString())
            put("archivedAt", System.currentTimeMillis())
        })
    }

    private fun forEachOldRow(db: SupportSQLiteDatabase, table: String, action: (ContentValues) -> Unit) {
        db.query("SELECT * FROM `${table}_legacy_v5` ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val row = ContentValues()
                cursor.columnNames.forEachIndexed { i, column ->
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> row.putNull(column)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(column, cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(column, cursor.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> row.put(column, cursor.getString(i))
                        else -> error("AI 历史字段类型不受支持：$table.$column")
                    }
                }
                action(row)
            }
        }
    }

    private fun exists(db: SupportSQLiteDatabase, table: String, id: Long): Boolean =
        db.query("SELECT 1 FROM `$table` WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun insert(db: SupportSQLiteDatabase, table: String, row: ContentValues) {
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, row)
    }

    private fun createSuggestionTables(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `review_publications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `evaluationId` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, `supervisorId` INTEGER NOT NULL,
            `publishedAt` INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_review_publications_evaluationId` ON `review_publications` (`evaluationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_publications_taskId` ON `review_publications` (`taskId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `reviewed_analysis_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `publicationId` INTEGER NOT NULL, `analysisId` INTEGER NOT NULL, `correctionId` INTEGER NOT NULL,
            `reviewerId` INTEGER NOT NULL, `type` TEXT NOT NULL, `originalContent` TEXT NOT NULL,
            `effectiveContent` TEXT NOT NULL, `evidenceSummary` TEXT NOT NULL, `reviewedAt` INTEGER NOT NULL,
            `versionKey` TEXT NOT NULL, FOREIGN KEY(`publicationId`) REFERENCES `review_publications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reviewed_analysis_snapshots_publicationId` ON `reviewed_analysis_snapshots` (`publicationId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `improvement_suggestions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `planId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `sourceSnapshotId` INTEGER NOT NULL,
            `content` TEXT NOT NULL, `problemSnapshot` TEXT NOT NULL, `bopppsStage` TEXT NOT NULL, `rationale` TEXT NOT NULL,
            `generatorMode` TEXT NOT NULL, `generatorVersion` TEXT NOT NULL, `reviewVersionKey` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL, `dismissedAt` INTEGER,
            FOREIGN KEY(`itemId`) REFERENCES `improvement_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_improvement_suggestions_planId` ON `improvement_suggestions` (`planId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_improvement_suggestions_itemId` ON `improvement_suggestions` (`itemId`)")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_improvement_suggestions_itemId_reviewVersionKey_generatorVersion_problemSnapshot`
            ON `improvement_suggestions` (`itemId`, `reviewVersionKey`, `generatorVersion`, `problemSnapshot`)""")
    }
}
