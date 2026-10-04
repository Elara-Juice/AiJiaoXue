package edu.neu.aijiaoxue.ui.engagement

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.model.EngagementType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


data class EngagementUiState(
    val records: List<EngagementRecord> = emptyList()
)


class EngagementViewModel(
    application: Application
) : AndroidViewModel(application) {


    private val dao =
        AppDatabase
            .get(application)
            .captureDao()


    private val _uiState =
        MutableStateFlow(
            EngagementUiState()
        )


    val uiState: StateFlow<EngagementUiState>
        get() = _uiState.asStateFlow()



    private var currentTaskId = -1L



    fun loadTask(taskId: Long){

        if(currentTaskId == taskId){
            return
        }

        currentTaskId = taskId


        viewModelScope.launch {

            dao.observeEngagements(taskId)
                .collect {

                    _uiState.value =
                        EngagementUiState(
                            records = it
                        )

                }

        }

    }



    fun addRecord(
        taskId: Long,
        type: EngagementType,
        presentCount: Int?,
        engagedCount: Int?,
        engagedPercent: Int?,
        description: String?
    ){

        viewModelScope.launch {


            dao.insertEngagement(

                EngagementRecord(

                    taskId = taskId,

                    type = type,

                    presentCount = presentCount,

                    engagedCount = engagedCount,

                    engagedPercent = engagedPercent,

                    description = description,

                    recordedAt =
                        System.currentTimeMillis(),

                    offsetMs = 0

                )

            )

        }

    }


    fun deleteRecord(
        record: EngagementRecord
    ){

        viewModelScope.launch {

            dao.deleteEngagement(record)

        }

    }

}