package edu.neu.aijiaoxue.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import edu.neu.aijiaoxue.data.dao.ImprovementSuggestionDao
import edu.neu.aijiaoxue.data.dao.ImprovementExecutionDao
import edu.neu.aijiaoxue.data.entity.ImprovementExecution
import edu.neu.aijiaoxue.data.entity.ImprovementEvidence
import edu.neu.aijiaoxue.data.entity.IssueVerification
import edu.neu.aijiaoxue.data.entity.MetricSnapshot
import edu.neu.aijiaoxue.data.entity.ReviewLink
import edu.neu.aijiaoxue.data.dao.IssueVerificationDao
import edu.neu.aijiaoxue.data.dao.MetricSnapshotDao
import edu.neu.aijiaoxue.data.dao.ReviewLinkDao
import edu.neu.aijiaoxue.data.dao.ReviewSourceDao
import edu.neu.aijiaoxue.data.entity.ImprovementSuggestion
import edu.neu.aijiaoxue.data.entity.ReviewPublication
import edu.neu.aijiaoxue.data.entity.ReviewedAnalysisSnapshot
import edu.neu.aijiaoxue.data.entity.LegacyAiMigrationArchive
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
import edu.neu.aijiaoxue.data.entity.TeachingHighlight
import edu.neu.aijiaoxue.data.entity.TeachingAdoption
import edu.neu.aijiaoxue.data.dao.TeachingSupportDao
import edu.neu.aijiaoxue.data.dao.GovernanceDao
import edu.neu.aijiaoxue.data.dao.AnalysisSourceDao
import edu.neu.aijiaoxue.data.entity.CourseReferenceVersion
import edu.neu.aijiaoxue.data.entity.AudioTimeSpan
import edu.neu.aijiaoxue.data.entity.TranscriptVersion
import edu.neu.aijiaoxue.data.entity.TeachingPhaseVersion
import edu.neu.aijiaoxue.data.entity.ImageTextVersion
import edu.neu.aijiaoxue.data.entity.ManualClassroomFact
import edu.neu.aijiaoxue.data.entity.TermExclusionVersion
import edu.neu.aijiaoxue.data.entity.AnalysisJob
import edu.neu.aijiaoxue.data.entity.PublishedFactSnapshot
import edu.neu.aijiaoxue.data.entity.PublishedEvidenceSnapshot
import edu.neu.aijiaoxue.data.entity.IssueVerificationEvidence

/**
 * 保留 v1 起的历史升级路径，v6 实施证据、v7 复评、v8 教学亮点与策略采纳。
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
        LegacyAiMigrationArchive::class,
        ImprovementExecution::class, ImprovementEvidence::class,
        // Sprint 3（李奕萱）：复评关联、正式指标快照与改善核验
        MetricSnapshot::class, ReviewLink::class, IssueVerification::class,
        TeachingHighlight::class, TeachingAdoption::class,
        IssueVerificationEvidence::class,
        CourseReferenceVersion::class, AudioTimeSpan::class, TranscriptVersion::class,
        TeachingPhaseVersion::class, ImageTextVersion::class, ManualClassroomFact::class,
        TermExclusionVersion::class, AnalysisJob::class, PublishedFactSnapshot::class,
        PublishedEvidenceSnapshot::class,
    ],
    version = 10,
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
    abstract fun improvementExecutionDao(): ImprovementExecutionDao
    abstract fun metricSnapshotDao(): MetricSnapshotDao
    abstract fun reviewLinkDao(): ReviewLinkDao
    abstract fun issueVerificationDao(): IssueVerificationDao
    abstract fun reviewSourceDao(): ReviewSourceDao
    abstract fun teachingSupportDao(): TeachingSupportDao
    abstract fun governanceDao(): GovernanceDao
    abstract fun analysisSourceDao(): AnalysisSourceDao

    companion object {
        const val NAME = "aijiaoxue.db"
        val MIGRATIONS: Array<Migration> = Sprint2Migrations.ALL + Sprint3Migrations.MIGRATION_5_6 +
            Sprint3ReviewMigrations.MIGRATION_6_7 + TeachingSupportMigrations.MIGRATION_7_8 +
            RemediationV9Migration.MIGRATION_8_9 + RemediationV10Migration.MIGRATION_9_10

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
