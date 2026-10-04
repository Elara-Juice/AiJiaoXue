package edu.neu.aijiaoxue.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.User

/**
 * 修改任何实体后 version + 1。Sprint 1 期间允许破坏性迁移（清空重建），
 * 改表结构后各自卸载重装或清除应用数据即可。
 */
@Database(
    entities = [
        User::class, Course::class, Arrangement::class, SupervisionTask::class,
        Material::class, InteractionEvent::class, EngagementRecord::class,
        Evaluation::class, EvaluationIssue::class,
        ImprovementPlan::class, ImprovementItem::class, ImprovementMeasure::class,
    ],
    version = 1,
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

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
