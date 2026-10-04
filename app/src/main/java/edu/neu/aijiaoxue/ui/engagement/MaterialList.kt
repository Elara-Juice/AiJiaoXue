package edu.neu.aijiaoxue.ui.engagement


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.Material


@Composable
fun MaterialList(

    materials: List<Material>,

    onDelete: (Material) -> Unit

){


    var deleteTarget by remember {

        mutableStateOf<Material?>(null)

    }



    Column(

        modifier = Modifier
            .fillMaxWidth()

    ){


        Text(
            text = "材料列表"
        )



        materials.forEach { material ->


            Row(

                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)

            ){


                Column(

                    modifier = Modifier
                        .padding(end = 8.dp)

                ){

                    Text(
                        text = "类型：${material.type}"
                    )


                    Text(
                        text = material.filePath
                    )


                }



                Spacer(
                    modifier = Modifier.width(8.dp)
                )



                Button(

                    onClick = {

                        deleteTarget = material

                    }

                ){

                    Text(
                        "删除"
                    )

                }


            }


        }


    }



    deleteTarget?.let { material ->



        AlertDialog(


            onDismissRequest = {

                deleteTarget = null

            },


            title = {

                Text(
                    "确认删除"
                )

            },


            text = {

                Text(
                    "确定删除该教学材料吗？"
                )

            },


            confirmButton = {


                Button(

                    onClick = {

                        onDelete(material)

                        deleteTarget = null

                    }

                ){

                    Text(
                        "删除"
                    )

                }


            },


            dismissButton = {


                Button(

                    onClick = {

                        deleteTarget = null

                    }

                ){

                    Text(
                        "取消"
                    )

                }


            }


        )


    }


}