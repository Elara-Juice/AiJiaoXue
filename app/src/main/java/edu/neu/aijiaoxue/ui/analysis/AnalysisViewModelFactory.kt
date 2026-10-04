package edu.neu.aijiaoxue.ui.analysis


import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.MockAIAnalyzer
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository



class AnalysisViewModelFactory(

    private val application: Application

) : ViewModelProvider.Factory {



    override fun <T : ViewModel> create(

        modelClass: Class<T>

    ): T {


        if(
            modelClass.isAssignableFrom(
                AnalysisViewModel::class.java
            )
        ){


            val database =
                AppDatabase
                    .get(application)



            // AI分析数据层
            val analysisRepository =
                AIAnalysisRepository(

                    analyzer =
                        MockAIAnalyzer(),

                    dao =
                        database.aiAnalysisDao()

                )



            // AI证据数据层
            val evidenceRepository =
                AiEvidenceRepository(

                    dao =
                        database.aiEvidenceDao()

                )



            // 课堂采集数据层
            val captureDao =
                database.captureDao()



            @Suppress("UNCHECKED_CAST")
            return AnalysisViewModel(

                analysisRepository,

                evidenceRepository,

                captureDao

            ) as T


        }



        throw IllegalArgumentException(
            "Unknown ViewModel class"
        )


    }


}