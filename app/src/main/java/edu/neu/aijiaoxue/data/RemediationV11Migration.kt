package edu.neu.aijiaoxue.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object RemediationV11Migration {
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE analysis_jobs ADD COLUMN errorDetailsJson TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS analysis_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, requestKey TEXT NOT NULL, taskId INTEGER NOT NULL, createdBy INTEGER NOT NULL, state TEXT NOT NULL, policyVersion TEXT NOT NULL, planRevision INTEGER NOT NULL, selectedOperationsJson TEXT NOT NULL, materialSelectionJson TEXT NOT NULL, acceptedInputsJson TEXT NOT NULL, proxyBaseUrl TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(taskId) REFERENCES supervision_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_analysis_runs_taskId ON analysis_runs(taskId)")
            db.execSQL("CREATE INDEX index_analysis_runs_createdBy ON analysis_runs(createdBy)")
            db.execSQL("CREATE UNIQUE INDEX index_analysis_runs_requestKey ON analysis_runs(requestKey)")
            db.execSQL("CREATE TABLE IF NOT EXISTS analysis_run_steps (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, runId INTEGER NOT NULL, stepKey TEXT NOT NULL, operation TEXT NOT NULL, materialId INTEGER, dependsOnJson TEXT NOT NULL, state TEXT NOT NULL, jobId INTEGER, ownsJob INTEGER NOT NULL, inputFingerprint TEXT, outputRefsJson TEXT, blockReason TEXT, errorDetailsJson TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(runId) REFERENCES analysis_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(jobId) REFERENCES analysis_jobs(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            db.execSQL("CREATE UNIQUE INDEX index_analysis_run_steps_runId_stepKey ON analysis_run_steps(runId,stepKey)")
            db.execSQL("CREATE INDEX index_analysis_run_steps_jobId ON analysis_run_steps(jobId)")
        }
    }
}
