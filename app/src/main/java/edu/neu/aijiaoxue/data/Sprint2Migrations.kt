package edu.neu.aijiaoxue.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1=S1，v2=main AI基础版，v3=李奕萱分支；迁移只保留事实，不推测历史审核人。 */
object Sprint2Migrations {
    val ALL: Array<Migration> = (1..3).map { from -> object : Migration(from, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            when (from) {
                1 -> createAiTables(db)
                2 -> db.execSQL("ALTER TABLE ai_corrections ADD COLUMN reviewerId INTEGER")
                3 -> rebuildTeamAiTables(db)
            }
            db.execSQL("ALTER TABLE improvement_measures ADD COLUMN suggestionId INTEGER")
            createSuggestionTables(db)
        }
    } }.toTypedArray()

    private fun createAiTables(db: SupportSQLiteDatabase, suffix: String = "") {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_analysis_results$suffix` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `taskId` INTEGER NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, `confidence` REAL NOT NULL,
            `startMs` INTEGER, `endMs` INTEGER, `verified` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_evidences$suffix` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `analysisId` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, `materialId` INTEGER, `eventId` INTEGER,
            `startMs` INTEGER, `endMs` INTEGER, `description` TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_corrections$suffix` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `analysisId` INTEGER NOT NULL, `originalContent` TEXT NOT NULL, `correctedContent` TEXT NOT NULL,
            `accepted` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `reviewerId` INTEGER)""")
    }

    private fun rebuildTeamAiTables(db: SupportSQLiteDatabase) {
        // v3 为审核人增加非空外键；v4 接受 v2 的未知审核人，统一为可空字段。
        // 先复制三张表，再按子表到父表删除，避免旧级联外键吞掉待迁移数据。
        createAiTables(db, "_v4")
        db.execSQL("""INSERT INTO ai_analysis_results_v4 (id,taskId,type,content,confidence,startMs,endMs,verified,createdAt)
            SELECT id,taskId,type,content,confidence,startMs,endMs,verified,createdAt FROM ai_analysis_results""")
        db.execSQL("""INSERT INTO ai_evidences_v4 (id,analysisId,taskId,materialId,eventId,startMs,endMs,description)
            SELECT id,analysisId,taskId,materialId,eventId,startMs,endMs,description FROM ai_evidences""")
        db.execSQL("""INSERT INTO ai_corrections_v4 (id,analysisId,originalContent,correctedContent,accepted,createdAt,reviewerId)
            SELECT id,analysisId,originalContent,correctedContent,accepted,createdAt,reviewerId FROM ai_corrections""")
        listOf("ai_evidences", "ai_corrections", "ai_analysis_results").forEach { table ->
            db.execSQL("DROP TABLE `$table`")
            db.execSQL("ALTER TABLE `${table}_v4` RENAME TO `$table`")
        }
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
