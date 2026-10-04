package edu.neu.aijiaoxue.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.model.Role

import edu.neu.aijiaoxue.ui.login.LoginScreen
import edu.neu.aijiaoxue.ui.login.ProfileScreen

import edu.neu.aijiaoxue.ui.capture.CaptureScreen
import edu.neu.aijiaoxue.ui.course.MyTasksScreen


private val ADMIN = setOf(Role.ADMIN)

private val SUPERVISOR = setOf(Role.SUPERVISOR)

private val TEACHER = setOf(Role.TEACHER)

private val ADMIN_SUPERVISOR =
    setOf(Role.ADMIN, Role.SUPERVISOR)

private val ALL = Role.entries.toSet()



private val taskIdArg =
    listOf(
        navArgument(Routes.ARG_TASK_ID) {
            type = NavType.LongType
        }
    )


private val courseIdArg =
    listOf(
        navArgument(Routes.ARG_COURSE_ID) {
            type = NavType.LongType
        }
    )


fun NavBackStackEntry.taskId(): Long =
    requireNotNull(arguments)
        .getLong(Routes.ARG_TASK_ID)


fun NavBackStackEntry.courseId(): Long =
    requireNotNull(arguments)
        .getLong(Routes.ARG_COURSE_ID)



@Composable
fun AppNavHost() {

    val ready by Session.ready.collectAsState()

    val user by Session.user.collectAsState()


    if (!ready) {

        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {

            CircularProgressIndicator()

        }

        return
    }


    key(user?.id) {

        val navController =
            rememberNavController()


        val current = user


        NavHost(
            navController = navController,
            startDestination =
                if(current == null)
                    Routes.LOGIN
                else
                    Routes.homeOf(current.role)

        ) {


            // 登录

            composable(
                Routes.LOGIN
            ) {

                LoginScreen()

            }



            // 个人信息

            guarded(
                navController,
                Routes.PROFILE,
                ALL
            ){

                ProfileScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )

            }




            // 首页

            guarded(
                navController,
                Routes.HOME_ADMIN,
                ADMIN
            ){

                AdminHomeScreen(
                    navController::navigate
                )

            }


            guarded(
                navController,
                Routes.HOME_SUPERVISOR,
                SUPERVISOR
            ){

                SupervisorHomeScreen(
                    navController::navigate
                )

            }


            guarded(
                navController,
                Routes.HOME_TEACHER,
                TEACHER
            ){

                TeacherHomeScreen(
                    navController::navigate
                )

            }



            // =========================
            // 课程模块
            // =========================


            guarded(
                navController,
                Routes.COURSE_SEARCH,
                SUPERVISOR
            ){

                pending(
                    navController,
                    "课程检索 US01"
                )

            }


            guarded(
                navController,
                Routes.COURSE_DETAIL,
                ADMIN_SUPERVISOR,
                courseIdArg
            ){

                pending(
                    navController,
                    "课程详情 US02"
                )

            }



            guarded(
                navController,
                Routes.TASK_CREATE,
                SUPERVISOR,
                courseIdArg
            ){

                pending(
                    navController,
                    "创建督导任务 US04"
                )

            }



            guarded(
                navController,
                Routes.MY_TASKS,
                SUPERVISOR
            ){

                MyTasksScreen(

                    navController = navController,

                    onBack = {

                        navController.popBackStack()

                    }

                )

            }



            // =========================
            // 课堂采集模块
            // =========================


            guarded(
                navController,
                Routes.CAPTURE,
                SUPERVISOR,
                taskIdArg
            ){ entry ->


                CaptureScreen(
                    taskId = entry.taskId(),

                    onBack = {
                        navController.popBackStack()
                    }
                )

            }



            // =========================
            // 其他模块暂时保留
            // =========================


            guarded(
                navController,
                Routes.ARRANGEMENT,
                ADMIN
            ){

                pending(
                    navController,
                    "督导安排 US05"
                )

            }



            guarded(
                navController,
                Routes.TASK_PROGRESS,
                ADMIN
            ){

                pending(
                    navController,
                    "任务进度 US06"
                )

            }



            guarded(
                navController,
                Routes.EVALUATION,
                SUPERVISOR,
                taskIdArg
            ){

                pending(
                    navController,
                    "督导评价 US29/30"
                )

            }



            guarded(
                navController,
                Routes.HISTORY,
                ADMIN_SUPERVISOR
            ){

                pending(
                    navController,
                    "历史记录 US32"
                )

            }



            guarded(
                navController,
                Routes.FEEDBACK_LIST,
                TEACHER
            ){

                pending(
                    navController,
                    "督导反馈 US33"
                )

            }



            guarded(
                navController,
                Routes.IMPROVE_FOCUS,
                TEACHER,
                taskIdArg
            ){

                pending(
                    navController,
                    "改进重点 US36"
                )

            }



            guarded(
                navController,
                Routes.IMPROVE_PLAN,
                TEACHER,
                taskIdArg
            ){

                pending(
                    navController,
                    "改进计划 US41"
                )

            }



            guarded(
                navController,
                Routes.IMPROVE_TODO,
                TEACHER
            ){

                pending(
                    navController,
                    "改进待办 US42"
                )

            }


        }

    }

}



private fun NavGraphBuilder.guarded(
    navController: NavHostController,
    route: String,
    roles: Set<Role>,
    arguments: List<NamedNavArgument> = emptyList(),
    content:
    @Composable (NavBackStackEntry) -> Unit

){

    composable(
        route,
        arguments
    ){ entry ->


        val user =
            Session.currentUser


        if(
            user != null &&
            user.role in roles
        ){

            content(entry)

        }

        else if(user != null){

            val context =
                LocalContext.current


            LaunchedEffect(Unit){

                Toast.makeText(
                    context,
                    "无权限访问",
                    Toast.LENGTH_SHORT
                ).show()


                val home =
                    Routes.homeOf(user.role)


                navController.navigate(home){

                    popUpTo(home){
                        inclusive = true
                    }

                    launchSingleTop = true

                }

            }

        }

    }

}



@Composable
private fun pending(
    navController: NavHostController,
    title:String
)=
    PendingScreen(
        title,
        onBack = {
            navController.popBackStack()
        }
    )
