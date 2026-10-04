package edu.neu.aijiaoxue.ui.evidence


import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository



class EvidenceViewModelFactory(

    private val database: AppDatabase

) : ViewModelProvider.Factory {



    override fun <T : ViewModel> create(
        modelClass: Class<T>
    ): T {


        if(
            modelClass.isAssignableFrom(
                EvidenceViewModel::class.java
            )
        ){


            val repository =
                AiEvidenceRepository(

                    dao =
                        database.aiEvidenceDao()

                )


            @Suppress("UNCHECKED_CAST")
            return EvidenceViewModel(
                repository
            ) as T


        }


        throw IllegalArgumentException(
            "Unknown ViewModel"
        )

    }


}