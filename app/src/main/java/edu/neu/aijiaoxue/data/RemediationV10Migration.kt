package edu.neu.aijiaoxue.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v10追加真实来源与发布结构，保留v8/v9用户记录。 */
object RemediationV10Migration {
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            AnalysisSourceMigration.createTables(db)
            db.execSQL("ALTER TABLE supervision_tasks ADD COLUMN courseReferenceVersionId INTEGER REFERENCES course_reference_versions(id) ON DELETE RESTRICT")
            db.execSQL("ALTER TABLE supervision_tasks ADD COLUMN referenceSelectionJson TEXT")
            db.execSQL("ALTER TABLE supervision_tasks ADD COLUMN inputRevision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE supervision_tasks ADD COLUMN analysisCaseId TEXT")
            db.execSQL("CREATE INDEX index_supervision_tasks_courseReferenceVersionId ON supervision_tasks(courseReferenceVersionId)")
            db.execSQL("CREATE UNIQUE INDEX index_supervision_tasks_analysisCaseId ON supervision_tasks(analysisCaseId)")
            val analysis = listOf("operation TEXT NOT NULL DEFAULT 'LEGACY'", "schemaVersion INTEGER NOT NULL DEFAULT 0", "payloadJson TEXT", "inputFingerprint TEXT", "runId TEXT", "resultState TEXT NOT NULL DEFAULT 'SUPPORTED'", "sourceMode TEXT NOT NULL DEFAULT 'LEGACY'", "isCurrent INTEGER NOT NULL DEFAULT 1", "producerJson TEXT")
            analysis.forEach { db.execSQL("ALTER TABLE ai_analysis_results ADD COLUMN $it") }
            db.execSQL("UPDATE ai_analysis_results SET operation=CASE type WHEN 'BOPPPS' THEN 'BOPPPS' WHEN 'INTERACTION' THEN 'INTERACTION_STATS' WHEN 'ENGAGEMENT' THEN 'PARTICIPATION' WHEN 'VERBAL_REPETITION' THEN 'EXPRESSION_STATS' WHEN 'TEACHING_TRANSITION' THEN 'TRANSITION' ELSE 'LEGACY' END")
            db.execSQL("ALTER TABLE ai_evidences ADD COLUMN sourceRefJson TEXT")
            db.execSQL("ALTER TABLE ai_corrections ADD COLUMN originalPayloadHash TEXT")
            db.execSQL("ALTER TABLE ai_corrections ADD COLUMN correctedPayloadJson TEXT")
            db.execSQL("ALTER TABLE review_publications ADD COLUMN contextSnapshotJson TEXT")
            listOf("schemaVersion INTEGER NOT NULL DEFAULT 0", "originalPayloadJson TEXT", "effectivePayloadJson TEXT", "inputFingerprint TEXT", "sourceMode TEXT NOT NULL DEFAULT 'LEGACY'", "producerJson TEXT", "resultState TEXT NOT NULL DEFAULT 'SUPPORTED'").forEach {
                db.execSQL("ALTER TABLE reviewed_analysis_snapshots ADD COLUMN $it")
            }
        }
    }
}
