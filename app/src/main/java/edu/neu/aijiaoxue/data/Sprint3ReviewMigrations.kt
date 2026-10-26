package edu.neu.aijiaoxue.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sprint 3（李奕萱）：v6 → v7。
 *
 * 只新增三张表，不改动 v6 及更早的任何列，保证旧数据与来源关联在升级后仍然可读：
 * - metric_snapshots：US45 正式指标快照，同一 (taskId, metricKey, methodVersion) 唯一，
 *   口径变化只能新增版本，不能原地覆盖历史快照。
 * - review_links：原课堂（原问题）与后续复评任务的稳定关联，保存原问题快照文本。
 * - issue_verifications：US46 逐问题核验记录，保存判断、依据、核验人与核验时间。
 */
object Sprint3ReviewMigrations {
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `metric_snapshots` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `taskId` INTEGER NOT NULL, `metricKey` TEXT NOT NULL, `value` REAL,
                `unit` TEXT NOT NULL, `denominator` TEXT NOT NULL, `denominatorValue` REAL,
                `sampling` TEXT NOT NULL, `methodVersion` TEXT NOT NULL, `sampleCount` INTEGER NOT NULL,
                `scopeStartAt` INTEGER, `scopeEndAt` INTEGER, `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`taskId`) REFERENCES `supervision_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE INDEX `index_metric_snapshots_taskId` ON `metric_snapshots` (`taskId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX `index_metric_snapshots_taskId_metricKey_methodVersion` " +
                    "ON `metric_snapshots` (`taskId`, `metricKey`, `methodVersion`)"
            )

            db.execSQL("""CREATE TABLE IF NOT EXISTS `review_links` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `originTaskId` INTEGER NOT NULL, `originIssueId` INTEGER,
                `followUpTaskId` INTEGER NOT NULL, `teacherId` INTEGER NOT NULL,
                `originIssueSnapshot` TEXT NOT NULL, `originDimension` TEXT,
                `createdBy` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`originTaskId`) REFERENCES `supervision_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`followUpTaskId`) REFERENCES `supervision_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE INDEX `index_review_links_originTaskId` ON `review_links` (`originTaskId`)")
            db.execSQL("CREATE INDEX `index_review_links_followUpTaskId` ON `review_links` (`followUpTaskId`)")
            db.execSQL("CREATE INDEX `index_review_links_teacherId` ON `review_links` (`teacherId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX `index_review_links_originTaskId_originIssueId_followUpTaskId` " +
                    "ON `review_links` (`originTaskId`, `originIssueId`, `followUpTaskId`)"
            )

            db.execSQL("""CREATE TABLE IF NOT EXISTS `issue_verifications` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `linkId` INTEGER NOT NULL, `verdict` TEXT NOT NULL, `basis` TEXT NOT NULL,
                `verifierId` INTEGER NOT NULL, `verifiedAt` INTEGER NOT NULL,
                `beforeSnapshotId` INTEGER, `afterSnapshotId` INTEGER,
                FOREIGN KEY(`linkId`) REFERENCES `review_links`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`verifierId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(`beforeSnapshotId`) REFERENCES `metric_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`afterSnapshotId`) REFERENCES `metric_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
            db.execSQL("CREATE INDEX `index_issue_verifications_linkId` ON `issue_verifications` (`linkId`)")
            db.execSQL("CREATE INDEX `index_issue_verifications_verifierId` ON `issue_verifications` (`verifierId`)")
        }
    }
}
