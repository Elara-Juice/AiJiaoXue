package edu.neu.aijiaoxue.ui.evidence


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch



data class EvidenceUiState(

    val evidences: List<AiEvidence> = emptyList(),

    val loading: Boolean = false

)



class EvidenceViewModel(

    private val repository: AiEvidenceRepository

) : ViewModel(){



    private val _uiState =
        MutableStateFlow(
            EvidenceUiState()
        )


    val uiState: StateFlow<EvidenceUiState>
        get() = _uiState.asStateFlow()



    fun loadEvidence(

        analysisId: Long

    ){


        viewModelScope.launch {


            _uiState.value =
                _uiState.value.copy(
                    loading = true
                )


            repository
                .observeEvidence(
                    analysisId
                )
                .collect { list ->


                    _uiState.value =
                        EvidenceUiState(

                            evidences = list,

                            loading = false

                        )


                }


        }

    }


}