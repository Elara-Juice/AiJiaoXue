package edu.neu.aijiaoxue.ui.analysis


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp



/**
 * Sprint2 US27
 *
 * AI结果人工审核页面
 */
@Composable
fun AIReviewScreen(

    analysisId: Long,

    viewModel: AIReviewViewModel

){



    val state by
    viewModel.uiState.collectAsState()



    LaunchedEffect(analysisId){

        viewModel.load(
            analysisId
        )

    }



    var correctedText by remember {

        mutableStateOf("")

    }



    val analysis =
        state.analysis



    LaunchedEffect(analysis){

        if(
            analysis != null &&
            correctedText.isEmpty()
        ){

            correctedText =
                analysis.content

        }

    }





    Column(

        modifier =
            Modifier
                .padding(16.dp)

    ){


        Text(
            text = "AI结果人工审核"
        )


        Spacer(
            Modifier.height(16.dp)
        )



        Text(
            text = "AI原始结论："
        )



        Text(

            text =
                analysis?.content
                    ?: "正在加载AI结果..."

        )



        Spacer(
            Modifier.height(16.dp)

        )



        OutlinedTextField(

            value = correctedText,

            onValueChange = {

                correctedText = it

            },

            label = {

                Text(
                    "修正后的结论"
                )

            }

        )



        Spacer(
            Modifier.height(16.dp)

        )



        Button(

            onClick = {


                viewModel.save(

                    analysisId = analysisId,

                    original =
                        analysis?.content
                            ?: "",

                    corrected =
                        correctedText,

                    accepted =
                        correctedText ==
                                analysis?.content

                )


            }

        ){

            Text(
                "保存审核结果"
            )

        }



        Spacer(
            Modifier.height(20.dp)

        )



        Text(
            text =
                "历史审核记录"
        )



        state.corrections.forEach {


            Text(

                text =
                    "修改后：${it.correctedContent}"

            )

        }



    }


}