package edu.neu.aijiaoxue.ui.evidence


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.AiEvidence



/**
 * AI证据展示组件
 *
 * US26:
 * 展示AI结论对应课堂证据
 */
@Composable
fun EvidencePanel(

    evidences: List<AiEvidence>

){


    Column(

        modifier = Modifier
            .padding(8.dp)

    ){


        Text(
            text = "AI分析依据"
        )


        evidences.forEach { evidence ->


            Column(

                modifier =
                    Modifier.padding(
                        vertical = 8.dp
                    )

            ){


                Text(
                    text =
                        "证据说明：${evidence.description}"
                )


                evidence.startMs?.let {

                    Text(
                        text =
                            "开始时间：${it}ms"
                    )

                }


                evidence.endMs?.let {

                    Text(
                        text =
                            "结束时间：${it}ms"
                    )

                }


                evidence.materialId?.let {

                    Text(
                        text =
                            "关联材料ID：$it"
                    )

                }


                evidence.eventId?.let {

                    Text(
                        text =
                            "关联事件ID：$it"
                    )

                }


            }


        }


    }


}