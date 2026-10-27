package edu.neu.aijiaoxue.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import edu.neu.aijiaoxue.data.model.ReviewChronologyV1

/** v9 only changes review integrity; historical verdicts and selected snapshot IDs are untouched. */
object RemediationV9Migration {
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE evaluations ADD COLUMN issueDeclaration TEXT NOT NULL DEFAULT 'UNASSESSED'")
            db.execSQL("ALTER TABLE evaluations ADD COLUMN issueDeclarationNote TEXT")
            db.execSQL("ALTER TABLE evaluations ADD COLUMN issueDeclaredBy INTEGER")
            db.execSQL("ALTER TABLE evaluations ADD COLUMN issueDeclaredAt INTEGER")
            db.execSQL("ALTER TABLE review_links ADD COLUMN originDimensionKey TEXT")
            db.execSQL("ALTER TABLE review_links ADD COLUMN chronologyStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE review_links ADD COLUMN chronologyRuleVersion INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE issue_verifications ADD COLUMN contractVersion INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE issue_verifications ADD COLUMN presence TEXT NOT NULL DEFAULT 'UNDETERMINED'")
            db.execSQL("ALTER TABLE issue_verifications ADD COLUMN extent TEXT NOT NULL DEFAULT 'UNDETERMINED'")
            db.execSQL("ALTER TABLE issue_verifications ADD COLUMN conflictExplanation TEXT")
            db.execSQL("ALTER TABLE issue_verifications ADD COLUMN contextSnapshotJson TEXT")
            db.execSQL("""CREATE TABLE IF NOT EXISTS issue_verification_evidence (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                verificationId INTEGER NOT NULL, ordinal INTEGER NOT NULL,
                kind TEXT NOT NULL, role TEXT NOT NULL,
                sourceTaskId INTEGER, sourceEvaluationId INTEGER, sourceIssueId INTEGER,
                beforeSnapshotId INTEGER, afterSnapshotId INTEGER, metricKey TEXT,
                relationExplanation TEXT NOT NULL, factText TEXT, scopeStartAt INTEGER, scopeEndAt INTEGER,
                snapshotJson TEXT NOT NULL, createdAt INTEGER NOT NULL,
                FOREIGN KEY(verificationId) REFERENCES issue_verifications(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(sourceTaskId) REFERENCES supervision_tasks(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(sourceEvaluationId) REFERENCES evaluations(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(sourceIssueId) REFERENCES evaluation_issues(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(beforeSnapshotId) REFERENCES metric_snapshots(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(afterSnapshotId) REFERENCES metric_snapshots(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
            db.execSQL("CREATE UNIQUE INDEX index_issue_verification_evidence_verificationId_ordinal ON issue_verification_evidence(verificationId,ordinal)")
            listOf("sourceTaskId", "sourceEvaluationId", "sourceIssueId", "beforeSnapshotId", "afterSnapshotId").forEach {
                db.execSQL("CREATE INDEX index_issue_verification_evidence_$it ON issue_verification_evidence($it)")
            }
            // Keep the exact v9 vocabulary in migration code; future enum labels may change.
            db.execSQL("""UPDATE review_links SET originDimensionKey = CASE originDimension
                WHEN 'CONTENT' THEN 'CONTENT' WHEN '教学内容' THEN 'CONTENT'
                WHEN 'DESIGN' THEN 'DESIGN' WHEN '教学设计' THEN 'DESIGN'
                WHEN 'INTERACTION' THEN 'INTERACTION' WHEN '课堂互动' THEN 'INTERACTION'
                WHEN 'ENGAGEMENT' THEN 'ENGAGEMENT' WHEN '学生参与' THEN 'ENGAGEMENT' ELSE NULL END""")
            db.query("""SELECT l.id,l.originTaskId,ot.supervisionDate,ot.period,
                l.followUpTaskId,ft.supervisionDate,ft.period FROM review_links l
                JOIN supervision_tasks ot ON ot.id=l.originTaskId
                JOIN supervision_tasks ft ON ft.id=l.followUpTaskId""").use { rows ->
                while (rows.moveToNext()) {
                    val status = ReviewChronologyV1.validate(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getLong(4), rows.getString(5), rows.getString(6))
                    db.execSQL("UPDATE review_links SET chronologyStatus=?,chronologyRuleVersion=1 WHERE id=?",
                        arrayOf(status.name, rows.getLong(0)))
                }
            }
            // Reconstruct only this new metric from immutable formal scores, never re-create old metrics.
            db.execSQL("""INSERT OR IGNORE INTO metric_snapshots
                (taskId,metricKey,value,unit,denominator,denominatorValue,sampling,methodVersion,
                 sampleCount,scopeStartAt,scopeEndAt,createdAt)
                SELECT e.taskId,'EVALUATION_DESIGN',e.designScore,'score','single_evaluation',1.0,
                    'submitted_design_dimension','manual_design_score_v1',
                    CASE WHEN e.designScore IS NULL THEN 0 ELSE 1 END,t.classStartAt,t.classEndAt,e.submittedAt
                FROM evaluations e JOIN supervision_tasks t ON t.id=e.taskId
                WHERE e.status='SUBMITTED' AND t.status='COMPLETED' AND e.submittedAt IS NOT NULL""")
        }
    }
}
