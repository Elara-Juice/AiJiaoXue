package edu.neu.aijiaoxue.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** US43/44 仅增加实施历史，不改写 v5 的计划、建议与发布快照。 */
object Sprint3Migrations {
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `improvement_executions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `measureId` INTEGER NOT NULL, `implementationNote` TEXT NOT NULL,
                `actualAdjustment` TEXT NOT NULL, `implementedAt` INTEGER NOT NULL,
                `recordedBy` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `requestKey` TEXT NOT NULL,
                FOREIGN KEY(`measureId`) REFERENCES `improvement_measures`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(`recordedBy`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
            db.execSQL("CREATE UNIQUE INDEX `index_improvement_executions_measureId_requestKey` ON `improvement_executions` (`measureId`, `requestKey`)")
            db.execSQL("CREATE INDEX `index_improvement_executions_recordedBy` ON `improvement_executions` (`recordedBy`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `improvement_evidences` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `executionId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `description` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `requestKey` TEXT NOT NULL,
                FOREIGN KEY(`executionId`) REFERENCES `improvement_executions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
            db.execSQL("CREATE UNIQUE INDEX `index_improvement_evidences_executionId_requestKey` ON `improvement_evidences` (`executionId`, `requestKey`)")
        }
    }
}
