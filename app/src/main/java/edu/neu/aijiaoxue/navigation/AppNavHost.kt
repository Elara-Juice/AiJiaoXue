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
import edu.neu.aijiaoxue.ui.capture.CaptureScreen
import edu.neu.aijiaoxue.ui.course.MyTasksScreen
import edu.neu.aijiaoxue.ui.login.LoginScreen
import edu.neu.aijiaoxue.ui.login.ProfileScreen

private val ADMIN = setOf(Role.ADMIN)
private val SUPERVISOR = setOf(Role.SUPERVISOR)
private val TEACHER = setOf(Role.TEACHER)
private val ADMIN_SUPERVISOR = setOf(Role.ADMIN, Role.SUPERVISOR)
private val ALL = Role.entries.toSet()

private val taskIdArg = listOf(navArgument(Routes.ARG_TASK_ID) { type = NavType.LongType })
private val courseIdArg = listOf(navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType })

fun NavBackStackEntry.taskId(): Long = requireNotNull(arguments).getLong(Routes.ARG_TASK_ID)
fun NavBackStackEntry.courseId(): Long = requireNotNull(arguments).getLong(Routes.ARG_COURSE_ID)

/**
 * 全部页面在这里注册。各模块完成页面后，把对应的 [PendingScreen] 换成自己的 Screen，
 * 页面需要的 ID 用 entry.taskId() / entry.courseId() 取。
 *
 * F0 业务规则 2：每个路由都声明允许的角色，越权进入时提示“无权限访问”并回到本角色首页，
 * 不依赖首页是否显示入口。数据归属（是不是本人的任务、课程）仍由各页面按 Session 用户 id 查询校验。
 */
@Composable
fun AppNavHost() {
    val ready by Session.ready.collectAsState()
    val user by Session.user.collectAsState()

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // 登录、退出、切换账号时重建导航图，返回栈随之清空（F0 验收③：退出后返回键回不到业务页面）
    key(user?.id) {
        val navController = rememberNavController()
        val current = user
        NavHost(
            navController = navController,
            startDestination = if (current == null) Routes.LOGIN else Routes.homeOf(current.role),
        ) {
            composable(Routes.LOGIN) { LoginScreen() }

            guarded(navController, Routes.PROFILE, ALL) {
                ProfileScreen(onBack = { navController.popBackStack() })
            }

            // 角色首页
            guarded(navController, Routes.HOME_ADMIN, ADMIN) { AdminHomeScreen(navController::navigate) }
            guarded(navController, Routes.HOME_SUPERVISOR, SUPERVISOR) { SupervisorHomeScreen(navController::navigate) }
            guarded(navController, Routes.HOME_TEACHER, TEACHER) { TeacherHomeScreen(navController::navigate) }

            // ui/course（王博田）
            guarded(navController, Routes.COURSE_SEARCH, SUPERVISOR) { pending(navController, "课程检索 US01") }
            // US02 前置条件：从检索结果（督导）或督导安排（管理人员）进入
            guarded(navController, Routes.COURSE_DETAIL, ADMIN_SUPERVISOR, courseIdArg) { pending(navController, "课程详情 US02") }
            guarded(navController, Routes.TASK_CREATE, SUPERVISOR, courseIdArg) { pending(navController, "创建督导任务 US04") }
            // US07（10/4 由王博田转给李奕萱）
            guarded(navController, Routes.MY_TASKS, SUPERVISOR) {
                MyTasksScreen(onBack = { navController.popBackStack() }, onNavigate = navController::navigate)
            }

            // ui/arrange（王博田）
            guarded(navController, Routes.ARRANGEMENT, ADMIN) { pending(navController, "督导安排 US05") }
            guarded(navController, Routes.TASK_PROGRESS, ADMIN) { pending(navController, "任务进度 US06") }

            // ui/capture、ui/engagement（李奕萱、孙浩然）
            guarded(navController, Routes.CAPTURE, SUPERVISOR, taskIdArg) { entry ->
                CaptureScreen(
                    onBack = { navController.popBackStack() },
                    // 需求 3.3：结束课堂采集后进入评价页；采集页留在返回栈里，返回后可继续补充拍照
                    onClassEnded = { navController.navigate(Routes.evaluation(entry.taskId())) },
                )
            }

            // ui/evaluation（于卓君）
            guarded(navController, Routes.EVALUATION, SUPERVISOR, taskIdArg) { pending(navController, "督导评价 US29/30") }
            guarded(navController, Routes.HISTORY, ADMIN_SUPERVISOR) { pending(navController, "历史记录 US32") }
            guarded(navController, Routes.HISTORY_DETAIL, ADMIN_SUPERVISOR, taskIdArg) { pending(navController, "历史详情 US32") }

            // ui/feedback（王博田）
            guarded(navController, Routes.FEEDBACK_LIST, TEACHER) { pending(navController, "督导反馈 US33") }
            guarded(navController, Routes.FEEDBACK_DETAIL, TEACHER, taskIdArg) { pending(navController, "反馈详情 US33") }

            // ui/improve（于卓君）
            guarded(navController, Routes.IMPROVE_FOCUS, TEACHER, taskIdArg) { pending(navController, "改进重点 US36") }
            guarded(navController, Routes.IMPROVE_PLAN, TEACHER, taskIdArg) { pending(navController, "改进计划 US41") }
            guarded(navController, Routes.IMPROVE_TODO, TEACHER) { pending(navController, "改进待办 US42") }
        }
    }
}

/** 注册一个需要登录且限定角色的页面。 */
private fun NavGraphBuilder.guarded(
    navController: NavHostController,
    route: String,
    roles: Set<Role>,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        val user = Session.currentUser
        if (user != null && user.role in roles) {
            content(entry)
        } else if (user != null) {
            val context = LocalContext.current
            // F0 异常处理：越权访问时提示无权限并返回首页
            LaunchedEffect(Unit) {
                Toast.makeText(context, "无权限访问", Toast.LENGTH_SHORT).show()
                val home = Routes.homeOf(user.role)
                navController.navigate(home) {
                    popUpTo(home) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        // user == null 时 AppNavHost 已重建为登录页，这里不渲染任何内容
    }
}

@Composable
private fun pending(navController: NavHostController, title: String) =
    PendingScreen(title, onBack = { navController.popBackStack() })
