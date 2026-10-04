package edu.neu.aijiaoxue.ui.capture


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.ui.engagement.EngagementScreen
import edu.neu.aijiaoxue.ui.engagement.MaterialList
import edu.neu.aijiaoxue.ui.engagement.MaterialViewModel


@Composable
fun CaptureScreen(
    taskId: Long,
    onBack: () -> Unit
){

    var showEngagement by remember {
        mutableStateOf(false)
    }
    val context = LocalContext.current


    val materialViewModel: MaterialViewModel =
        viewModel(
            factory = object : ViewModelProvider.Factory {

                override fun <T : ViewModel> create(
                    modelClass: Class<T>
                ): T {

                    @Suppress("UNCHECKED_CAST")
                    return MaterialViewModel(
                        context.applicationContext as android.app.Application
                    ) as T

                }

            }
        )


    val materialState by materialViewModel.uiState.collectAsState()

    LaunchedEffect(taskId){

        materialViewModel.loadTask(taskId)

    }
    Column(

        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)

    ){


        // 返回按钮（新增，不影响原功能）
        Button(

            onClick = {
                onBack()
            }

        ){

            Text("返回")

        }


        Spacer(
            Modifier.height(20.dp)
        )


        Text(

            text = "课堂采集",

            style = MaterialTheme.typography.headlineMedium

        )


        Spacer(
            Modifier.height(20.dp)
        )


        Text(
            text = "当前任务ID：$taskId"
        )


        Spacer(
            Modifier.height(30.dp)
        )



        Button(

            onClick = {

            },

            modifier = Modifier.fillMaxWidth()

        ){

            Text("开始录音")

        }



        Spacer(
            Modifier.height(10.dp)
        )



        Button(

            onClick = {

            },

            modifier = Modifier.fillMaxWidth()

        ){

            Text("拍摄课堂材料")

        }



        Spacer(
            Modifier.height(10.dp)
        )



        Button(

            onClick = {

            },

            modifier = Modifier.fillMaxWidth()

        ){

            Text("标注互动事件")

        }



        Spacer(
            Modifier.height(10.dp)
        )



        Button(

            onClick = {

                showEngagement = true

            },

            modifier = Modifier.fillMaxWidth()

        ){

            Text("记录参与状态")

        }



        Spacer(
            Modifier.height(20.dp)
        )



        MaterialList(

            materials = materialState.materials,

            onDelete = {

                materialViewModel.deleteMaterial(it)

            }

        )



        // 保留原来的 US13 调用逻辑
        if(showEngagement){


            Spacer(
                Modifier.height(20.dp)
            )


            EngagementScreen(
                taskId = taskId
            )

        }


    }

}