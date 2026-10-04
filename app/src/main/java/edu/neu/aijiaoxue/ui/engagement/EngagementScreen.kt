package edu.neu.aijiaoxue.ui.engagement


import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.model.EngagementType



class EngagementViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {


    override fun <T : ViewModel> create(
        modelClass: Class<T>
    ): T {

        if (
            modelClass.isAssignableFrom(
                EngagementViewModel::class.java
            )
        ) {

            @Suppress("UNCHECKED_CAST")
            return EngagementViewModel(application) as T

        }


        throw IllegalArgumentException(
            "Unknown ViewModel"
        )

    }

}



@Composable
fun EngagementScreen(
    taskId: Long
){

    val context = LocalContext.current


    val viewModel: EngagementViewModel =
        viewModel(
            factory = EngagementViewModelFactory(
                context.applicationContext as Application
            )
        )


    val state by viewModel.uiState.collectAsState()



    var presentCount by remember {
        mutableStateOf("")
    }


    var engagedCount by remember {
        mutableStateOf("")
    }


    var description by remember {
        mutableStateOf("")
    }



    LaunchedEffect(taskId){

        viewModel.loadTask(taskId)

    }



    Column(

        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)

    ){


        Text(
            text = "课堂参与状态记录",
            style = MaterialTheme.typography.titleMedium
        )


        Spacer(
            Modifier.height(8.dp)
        )


        Text(
            text = "当前任务ID：$taskId"
        )


        Spacer(
            Modifier.height(16.dp)
        )



        OutlinedTextField(

            value = presentCount,

            onValueChange = {

                presentCount = it

            },

            label = {
                Text("实到人数")
            },

            modifier = Modifier.fillMaxWidth()

        )



        Spacer(
            Modifier.height(8.dp)
        )



        OutlinedTextField(

            value = engagedCount,

            onValueChange = {

                engagedCount = it

            },

            label = {
                Text("参与人数")
            },

            modifier = Modifier.fillMaxWidth()

        )



        Spacer(
            Modifier.height(8.dp)
        )



        OutlinedTextField(

            value = description,

            onValueChange = {

                description = it

            },

            label = {
                Text("描述")
            },

            modifier = Modifier.fillMaxWidth()

        )



        Spacer(
            Modifier.height(12.dp)
        )



        Button(

            onClick = {


                viewModel.addRecord(

                    taskId = taskId,

                    type = EngagementType.OTHER,

                    presentCount =
                        presentCount.toIntOrNull(),

                    engagedCount =
                        engagedCount.toIntOrNull(),

                    engagedPercent = null,

                    description = description

                )


                presentCount = ""

                engagedCount = ""

                description = ""


            },

            modifier = Modifier.fillMaxWidth()

        ){

            Text(
                "保存参与记录"
            )

        }



        Spacer(
            Modifier.height(20.dp)
        )



        Text(
            "已有记录",
            style = MaterialTheme.typography.titleSmall
        )



        LazyColumn {


            items(
                state.records
            ){ record ->


                EngagementItem(record)


            }

        }



    }

}




@Composable
private fun EngagementItem(
    record: EngagementRecord
){


    Column(

        modifier = Modifier
            .padding(vertical = 8.dp)

    ){


        Text(
            text = "类型：${record.type}"
        )


        Text(
            text =
                "实到：${record.presentCount ?: "-"} 参与：${record.engagedCount ?: "-"}"
        )


        Text(
            text = record.description ?: ""
        )


    }

}
