
package edu.neu.aijiaoxue.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import edu.neu.aijiaoxue.data.dao.ArrangementDao
import edu.neu.aijiaoxue.data.dao.CaptureDao
import edu.neu.aijiaoxue.data.dao.CourseDao
import edu.neu.aijiaoxue.data.dao.EvaluationDao
import edu.neu.aijiaoxue.data.dao.ImprovementDao
import edu.neu.aijiaoxue.data.dao.TaskDao
import edu.neu.aijiaoxue.data.dao.UserDao

import edu.neu.aijiaoxue.data.entity.Arrangement
import edu.neu.aijiaoxue.data.entity.Course
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.ImprovementItem
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.entity.ImprovementPlan
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.ReusableTeachingPractice
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.User

/**
 * 本地 Room 数据库。
 *
 * 版本 2：新增 US37 可复用教学亮点做法。
 */
@Database(
    entities = [
        User::class,
        Course::class,
        Arrangement::class,
        SupervisionTask::class,
        Material::class,
        InteractionEvent::class,
        EngagementRecord::class,
        Evaluation::class,
        EvaluationIssue::class,
        ImprovementPlan::class,
        ImprovementItem::class,
        ImprovementMeasure::class,
        ReusableTeachingPractice::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    abstract fun courseDao(): CourseDao

    abstract fun arrangementDao(): ArrangementDao

    abstract fun taskDao(): TaskDao

    abstract fun captureDao(): CaptureDao

    abstract fun evaluationDao(): EvaluationDao

    abstract fun improvementDao(): ImprovementDao

    companion object {

        const val NAME = "aijiaoxue.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 数据库版本 1 -> 2。
         * 新建 US37 的表，不删除原有评价及改进数据。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {

            override fun migrate(database: SupportSQLiteDatabase) {

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reusable_teaching_practices` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `evaluationId` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `note` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`evaluationId`)
                            REFERENCES `evaluations`(`id`)
                            ON UPDATE NO ACTION
                            ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_reusable_teaching_practices_evaluationId`
                    ON `reusable_teaching_practices` (`evaluationId`)
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
