package edu.neu.aijiaoxue.ui.analysis


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.dao.CaptureDao
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first



data class AnalysisUiState(

    val analyzing: Boolean = false,


    val results: List<AiAnalysisResult> = emptyList(),


    val evidences: List<AiEvidence> = emptyList(),


    /**
     * 最近一次AI分析结果ID
     *
     * 用于：
     * US26证据追溯
     * US27人工审核
     */
    val latestAnalysisId: Long? = null,


    val error: String? = null

)



class AnalysisViewModel(

    private val repository: AIAnalysisRepository,

    private val evidenceRepository: AiEvidenceRepository,

    private val captureDao: CaptureDao

) : ViewModel() {



    private val _uiState =
        MutableStateFlow(
            AnalysisUiState()
        )


    val uiState: StateFlow<AnalysisUiState>
        get() = _uiState.asStateFlow()





    /**
     * 开始AI课堂分析
     *
     * 输入：
     * taskId
     *
     * 自动获取：
     * Material
     * InteractionEvent
     */
    fun analyze(

        taskId: Long

    ) {


        viewModelScope.launch {


            _uiState.value =
                _uiState.value.copy(

                    analyzing = true,

                    error = null

                )



            try {


                // 获取课堂材料
                val materials =
                    captureDao
                        .getMaterials(
                            taskId
                        )



                // 获取课堂互动事件
                val events =
                    captureDao
                        .observeEvents(taskId)
                        .first()



                // AI分析并保存结果
                val results =
                    repository.analyzeAndSave(

                        taskId,

                        materials,

                        events

                    )



                // 根据AI结果生成证据
                results.forEach { result ->


                    evidenceRepository
                        .createEvidence(
                            result
                        )


                }



                // 保存最新分析结果ID
                val latestId =
                    results
                        .lastOrNull()
                        ?.id



                _uiState.value =
                    AnalysisUiState(

                        analyzing = false,

                        results = results,

                        latestAnalysisId = latestId

                    )



            } catch (e: Exception) {


                _uiState.value =
                    AnalysisUiState(

                        analyzing = false,

                        error = e.message

                    )

            }


        }


    }



}