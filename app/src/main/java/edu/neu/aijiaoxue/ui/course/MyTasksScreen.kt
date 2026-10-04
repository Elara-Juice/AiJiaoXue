package edu.neu.aijiaoxue.ui.course


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.neu.aijiaoxue.navigation.Routes



@Composable
fun MyTasksScreen(

    navController: NavHostController,

    onBack: () -> Unit

){


    Column {


        // 返回按钮
        Button(

            onClick = {

                onBack()

            }

        ){

            Text(
                "返回"
            )

        }



        Spacer(
            Modifier.height(20.dp)
        )



        Text(
            text = "我的督导任务"
        )



        Spacer(
            Modifier.height(20.dp)
        )



        Button(

            onClick = {


                navController.navigate(

                    Routes.capture(
                        1L
                    )

                )


            }

        ){

            Text(
                "进入课堂采集"
            )

        }



    }


}