package edu.neu.aijiaoxue.ui.analysis


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evidence.EvidencePanel
import edu.neu.aijiaoxue.data.entity.AiEvidence


@Composable
fun AnalysisScreen(

    taskId: Long,

    viewModel: AnalysisViewModel,

    evidences: List<AiEvidence> = emptyList(),

    onReview: (
        Long,
        String
    ) -> Unit

){


    val state by
    viewModel.uiState.collectAsState()



    Column(

        modifier = Modifier
            .padding(16.dp)

    ){


        Text(
            text = "AI课堂分析"
        )


        Spacer(
            Modifier.height(16.dp)
        )



        Button(

            onClick = {

                viewModel.analyze(
                    taskId
                )

            },
            enabled = !state.analyzing

        ) {

            Text(
                if(state.analyzing)
                    "分析中..."
                else
                    "开始AI分析"
            )

        }



        Spacer(
            Modifier.height(20.dp)
        )



        Text(
            text = "分析结果"
        )



        state.results.forEach { result ->


            Spacer(
                Modifier.height(12.dp)
            )


            Text(
                text =
                    "类型：${result.type}"
            )


            Text(
                text =
                    result.content
            )
            Button(

                onClick = {

                    onReview(

                        result.id,

                        result.content

                    )

                }

            ){

                Text(
                    "审核AI结果"
                )

            }


            Text(
                text =
                    "可信度：${result.confidence}"
            )

            Spacer(
                modifier = Modifier.height(24.dp)
            )
        }

        if (evidences.isNotEmpty()) {
            EvidencePanel(evidences = evidences)
        }



        state.error?.let {


            Text(
                text = it
            )


        }


    }


}