package edu.neu.aijiaoxue.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object TeachingSupportMigrations {
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS teaching_highlights (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId INTEGER NOT NULL,
                teacherId INTEGER NOT NULL, sourceText TEXT NOT NULL, practice TEXT NOT NULL, updatedAt INTEGER NOT NULL,
                FOREIGN KEY(taskId) REFERENCES supervision_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_teaching_highlights_taskId ON teaching_highlights(taskId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_teaching_highlights_teacherId ON teaching_highlights(teacherId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS teaching_adoptions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, measureId INTEGER NOT NULL, itemId INTEGER NOT NULL,
                sourceKey TEXT NOT NULL, sourceTitle TEXT NOT NULL, sourceReference TEXT NOT NULL,
                problemSnapshot TEXT NOT NULL, originalContent TEXT NOT NULL, adoptedAt INTEGER NOT NULL,
                FOREIGN KEY(measureId) REFERENCES improvement_measures(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_teaching_adoptions_measureId ON teaching_adoptions(measureId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_teaching_adoptions_itemId_sourceKey ON teaching_adoptions(itemId,sourceKey)")
        }
    }
}
