package edu.neu.aijiaoxue.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import edu.neu.aijiaoxue.data.dao.ImprovementSuggestionDao
import edu.neu.aijiaoxue.data.entity.ImprovementSuggestion
import edu.neu.aijiaoxue.data.entity.ReviewPublication
import edu.neu.aijiaoxue.data.entity.ReviewedAnalysisSnapshot
import edu.neu.aijiaoxue.data.dao.AiAnalysisDao
import edu.neu.aijiaoxue.data.dao.AiCorrectionDao
import edu.neu.aijiaoxue.data.dao.AiEvidenceDao
import edu.neu.aijiaoxue.data.dao.ArrangementDao
import edu.neu.aijiaoxue.data.dao.CaptureDao
import edu.neu.aijiaoxue.data.dao.CourseDao
import edu.neu.aijiaoxue.data.dao.EvaluationDao
import edu.neu.aijiaoxue.data.dao.ImprovementDao
import edu.neu.aijiaoxue.data.dao.TaskDao
import edu.neu.aijiaoxue.data.dao.UserDao
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.entity.AiEvidence
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
 * Sprint 2 保留旧评价、计划及待办。v3 已被团队其他分支使用，本分支统一升级 v4。
 */
@Database(
    entities = [
        User::class, Course::class, Arrangement::class, SupervisionTask::class,
        Material::class, InteractionEvent::class, EngagementRecord::class,
        Evaluation::class, EvaluationIssue::class,
        ImprovementPlan::class, ImprovementItem::class, ImprovementMeasure::class,
        // Sprint 2 AI 分析、证据追溯、人工审核
        AiAnalysisResult::class, AiEvidence::class, AiCorrection::class,
        ReviewPublication::class, ReviewedAnalysisSnapshot::class, ImprovementSuggestion::class,
    ],
    version = 4,
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
    abstract fun aiAnalysisDao(): AiAnalysisDao
    abstract fun aiEvidenceDao(): AiEvidenceDao
    abstract fun aiCorrectionDao(): AiCorrectionDao
    abstract fun improvementSuggestionDao(): ImprovementSuggestionDao

    companion object {
        const val NAME = "aijiaoxue.db"
        val MIGRATIONS: Array<Migration> = Sprint2Migrations.ALL

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
