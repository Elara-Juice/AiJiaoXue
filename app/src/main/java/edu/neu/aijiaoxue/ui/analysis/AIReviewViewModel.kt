package edu.neu.aijiaoxue.ui.analysis


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.dao.AiAnalysisDao
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch



data class AIReviewUiState(

    /**
     * 当前审核的AI分析结果
     */
    val analysis: AiAnalysisResult? = null,


    /**
     * 历史审核记录
     */
    val corrections: List<AiCorrection> = emptyList(),


    val loading: Boolean = false,


    val error: String? = null

)



class AIReviewViewModel(

    private val repository: AiCorrectionRepository,

    private val analysisDao: AiAnalysisDao

) : ViewModel() {



    private val _uiState =
        MutableStateFlow(
            AIReviewUiState()
        )


    val uiState: StateFlow<AIReviewUiState>
        get() = _uiState.asStateFlow()





    /**
     * 加载AI原始结果 + 审核历史
     */
    fun load(

        analysisId: Long

    ){

        viewModelScope.launch {


            _uiState.value =
                _uiState.value.copy(
                    loading = true
                )



            try {


                // 查询AI原始分析
                val analysis =
                    analysisDao
                        .getAnalysisById(
                            analysisId
                        )



                // 查询审核记录
                repository
                    .observeCorrections(
                        analysisId
                    )
                    .collect { corrections ->



                        _uiState.value =
                            AIReviewUiState(

                                analysis = analysis,

                                corrections = corrections,

                                loading = false

                            )


                    }



            } catch(e: Exception){


                _uiState.value =
                    AIReviewUiState(

                        loading = false,

                        error = e.message

                    )


            }


        }


    }





    /**
     * 保存人工审核结果
     */
    fun save(

        analysisId: Long,

        original: String,

        corrected: String,

        accepted: Boolean

    ){


        viewModelScope.launch {


            repository.saveCorrection(

                analysisId,

                original,

                corrected,

                accepted

            )


        }


    }



}