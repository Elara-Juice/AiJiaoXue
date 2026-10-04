package edu.neu.aijiaoxue.data


import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase


// DAO
import edu.neu.aijiaoxue.data.dao.UserDao
import edu.neu.aijiaoxue.data.dao.CourseDao
import edu.neu.aijiaoxue.data.dao.ArrangementDao
import edu.neu.aijiaoxue.data.dao.TaskDao
import edu.neu.aijiaoxue.data.dao.CaptureDao
import edu.neu.aijiaoxue.data.dao.EvaluationDao
import edu.neu.aijiaoxue.data.dao.ImprovementDao

// Sprint2 DAO
import edu.neu.aijiaoxue.data.dao.AiAnalysisDao
import edu.neu.aijiaoxue.data.dao.AiEvidenceDao
import edu.neu.aijiaoxue.data.dao.AiCorrectionDao



// Entity
import edu.neu.aijiaoxue.data.entity.User
import edu.neu.aijiaoxue.data.entity.Course
import edu.neu.aijiaoxue.data.entity.Arrangement
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.ImprovementPlan
import edu.neu.aijiaoxue.data.entity.ImprovementItem
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure


// Sprint2 Entity
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.entity.AiCorrection



/**
 * 数据库
 *
 * Sprint1:
 * 基础教学质量管理业务数据
 *
 * Sprint2:
 * 增加AI分析、证据追溯、人工审核数据
 *
 */
@Database(

    entities = [


        // Sprint1

        User::class,

        Course::class,

        Arrangement::class,

        SupervisionTask::class,


        // 课堂采集

        Material::class,

        InteractionEvent::class,

        EngagementRecord::class,


        // 评价

        Evaluation::class,

        EvaluationIssue::class,


        // 改进

        ImprovementPlan::class,

        ImprovementItem::class,

        ImprovementMeasure::class,



        // =====================
        // Sprint2 AI模块
        // =====================

        AiAnalysisResult::class,

        AiEvidence::class,

        AiCorrection::class


    ],


    version = 2

)
abstract class AppDatabase : RoomDatabase() {



    // =====================
    // Sprint1 DAO
    // =====================


    abstract fun userDao(): UserDao


    abstract fun courseDao(): CourseDao


    abstract fun arrangementDao(): ArrangementDao


    abstract fun taskDao(): TaskDao


    abstract fun captureDao(): CaptureDao


    abstract fun evaluationDao(): EvaluationDao


    abstract fun improvementDao(): ImprovementDao





    // =====================
    // Sprint2 AI DAO
    // =====================


    /**
     * AI分析结果
     */
    abstract fun aiAnalysisDao(): AiAnalysisDao



    /**
     * AI证据追溯
     */
    abstract fun aiEvidenceDao(): AiEvidenceDao



    /**
     * AI人工审核
     */
    abstract fun aiCorrectionDao(): AiCorrectionDao





    companion object {



        private const val NAME =
            "aijiaoxue.db"



        @Volatile
        private var INSTANCE: AppDatabase? = null




        fun get(

            context: Context

        ): AppDatabase {



            return INSTANCE
                ?: synchronized(this){


                    val instance =

                        Room.databaseBuilder(

                            context.applicationContext,


                            AppDatabase::class.java,


                            NAME

                        )


                            // Sprint期间允许破坏性迁移
                            .fallbackToDestructiveMigration(

                                dropAllTables = true

                            )


                            .build()



                    INSTANCE = instance


                    instance


                }



        }


    }


}
