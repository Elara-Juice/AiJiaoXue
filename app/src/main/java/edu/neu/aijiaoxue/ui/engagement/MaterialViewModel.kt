package edu.neu.aijiaoxue.ui.engagement


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.Material
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch



data class MaterialUiState(

    val materials: List<Material> = emptyList()

)



class MaterialViewModel(
    application: Application
) : AndroidViewModel(application) {


    private val dao =
        AppDatabase
            .get(application)
            .captureDao()



    private val _uiState =
        MutableStateFlow(
            MaterialUiState()
        )


    val uiState: StateFlow<MaterialUiState>
        get() = _uiState.asStateFlow()



    private var currentTaskId: Long = -1L



    fun loadTask(
        taskId: Long
    ){

        if(currentTaskId == taskId){

            return

        }


        currentTaskId = taskId



        viewModelScope.launch {


            dao.observeMaterials(taskId)
                .collect { materials ->


                    _uiState.value =
                        MaterialUiState(
                            materials = materials
                        )


                }


        }

    }



    fun deleteMaterial(
        material: Material
    ){

        viewModelScope.launch {


            dao.deleteMaterial(
                material
            )


        }

    }


}