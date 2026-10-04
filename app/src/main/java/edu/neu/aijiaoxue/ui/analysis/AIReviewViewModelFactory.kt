package edu.neu.aijiaoxue.ui.analysis


import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository



class AIReviewViewModelFactory(

    private val application: Application

) : ViewModelProvider.Factory {



    override fun <T : ViewModel> create(

        modelClass: Class<T>

    ): T {



        if(
            modelClass.isAssignableFrom(
                AIReviewViewModel::class.java
            )
        ){


            val database =
                AppDatabase
                    .get(application)



            val repository =
                AiCorrectionRepository(

                    dao =
                        database.aiCorrectionDao()

                )



            val analysisDao =
                database.aiAnalysisDao()



            @Suppress("UNCHECKED_CAST")
            return AIReviewViewModel(

                repository,

                analysisDao

            ) as T


        }



        throw IllegalArgumentException(
            "Unknown ViewModel"
        )


    }


}