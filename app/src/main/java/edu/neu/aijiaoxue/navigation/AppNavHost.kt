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
import edu.neu.aijiaoxue.ui.analysis.AIReviewScreen
import edu.neu.aijiaoxue.ui.analysis.AIReviewViewModel
import edu.neu.aijiaoxue.ui.analysis.AnalysisScreen
import edu.neu.aijiaoxue.ui.analysis.AnalysisViewModel
import edu.neu.aijiaoxue.ui.analysis.CloudAnalysisScreen
import edu.neu.aijiaoxue.ui.analysis.CloudAnalysisViewModel
import edu.neu.aijiaoxue.ui.input.InputWorkspaceScreen
import edu.neu.aijiaoxue.ui.input.InputWorkspaceViewModel
import edu.neu.aijiaoxue.ui.input.PublishedEvidenceScreen
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository
import edu.neu.aijiaoxue.ui.arrange.ArrangementScreen
import edu.neu.aijiaoxue.ui.arrange.TaskProgressScreen
import edu.neu.aijiaoxue.ui.capture.CaptureScreen
import edu.neu.aijiaoxue.ui.course.CourseDetailScreen
import edu.neu.aijiaoxue.ui.course.CourseSearchScreen
import edu.neu.aijiaoxue.ui.course.MyTasksScreen
import edu.neu.aijiaoxue.ui.course.TaskCreateScreen
import edu.neu.aijiaoxue.ui.feedback.FeedbackDetailScreen
import edu.neu.aijiaoxue.ui.feedback.FeedbackListScreen
import edu.neu.aijiaoxue.ui.login.LoginScreen
import edu.neu.aijiaoxue.ui.login.ProfileScreen
import edu.neu.aijiaoxue.ui.evaluation.*
import edu.neu.aijiaoxue.ui.improve.*
import edu.neu.aijiaoxue.ui.review.*
import edu.neu.aijiaoxue.data.repository.ReviewRepository
import edu.neu.aijiaoxue.ui.growth.*
import edu.neu.aijiaoxue.ui.governance.*
import edu.neu.aijiaoxue.data.repository.GovernanceRepository
import edu.neu.aijiaoxue.data.repository.AbnormalCourseRepository

private val ADMIN = setOf(Role.ADMIN)
private val SUPERVISOR = setOf(Role.SUPERVISOR)
private val TEACHER = setOf(Role.TEACHER)
private val ADMIN_SUPERVISOR = setOf(Role.ADMIN, Role.SUPERVISOR)
private val ALL = Role.entries.toSet()

private val taskIdArg = listOf(navArgument(Routes.ARG_TASK_ID) { type = NavType.LongType })
private val analysisIdArg = listOf(navArgument(Routes.ARG_ANALYSIS_ID) { type = NavType.LongType })
private val courseIdArg = listOf(navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType })
private val linkIdArg = listOf(navArgument(Routes.ARG_LINK_ID) { type = NavType.LongType })

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
            guarded(navController, Routes.COURSE_SEARCH, SUPERVISOR) {
                CourseSearchScreen(
                    onBack = { navController.popBackStack() },
                    onCourseClick = { navController.navigate(Routes.courseDetail(it)) },
                )
            }
            // US02 前置条件：从检索结果（督导）或督导安排（管理人员）进入
            guarded(navController, Routes.COURSE_DETAIL, ADMIN_SUPERVISOR, courseIdArg) { entry ->
                CourseDetailScreen(
                    courseId = entry.courseId(),
                    onBack = { navController.popBackStack() },
                    onCreateTask = { navController.navigate(Routes.taskCreate(it)) },
                    onCourseReference = { navController.navigate(Routes.courseReference(it)) },
                )
            }
            guarded(navController, Routes.TASK_CREATE, SUPERVISOR, courseIdArg) { entry ->
                // 离开成功页时把创建页移出返回栈，返回键回到课程详情，不会再回到“创建成功”
                val leave: (String) -> Unit = { route ->
                    navController.navigate(route) { popUpTo(Routes.TASK_CREATE) { inclusive = true } }
                }
                TaskCreateScreen(
                    courseId = entry.courseId(),
                    onBack = { navController.popBackStack() },
                    onStartCapture = { leave(Routes.capture(it)) },
                    onMyTasks = { leave(Routes.MY_TASKS) },
                )
            }
            // US07（10/4 由王博田转给李奕萱）
            guarded(navController, Routes.MY_TASKS, SUPERVISOR) {
                MyTasksScreen(onBack = { navController.popBackStack() }, onNavigate = navController::navigate)
            }

            // ui/arrange（王博田）
            guarded(navController, Routes.ARRANGEMENT, ADMIN) {
                ArrangementScreen(
                    onBack = { navController.popBackStack() },
                    onCourseClick = { navController.navigate(Routes.courseDetail(it)) },
                )
            }
            guarded(navController, Routes.TASK_PROGRESS, ADMIN) {
                TaskProgressScreen(onBack = { navController.popBackStack() })
            }
            guarded(navController, Routes.ABNORMAL_COURSES, ADMIN) {
                val model = moduleViewModel { db, actor -> AbnormalCourseViewModel(AbnormalCourseRepository(db), actor) }
                AbnormalCourseScreen(model, { navController.popBackStack() }, { navController.navigate(Routes.historyDetail(it)) })
            }

            // ui/capture、ui/engagement（李奕萱、孙浩然）
            guarded(navController, Routes.CAPTURE, SUPERVISOR, taskIdArg) { entry ->
                CaptureScreen(
                    onBack = { navController.popBackStack() },
                    // 需求 3.3：结束课堂采集后进入评价页；采集页留在返回栈里，返回后可继续补充拍照
                    onClassEnded = { navController.navigate(Routes.evaluation(entry.taskId())) },
                    onAnalysis = { navController.navigate(Routes.analysis(entry.taskId())) },
                )
            }

            // ui/evaluation（于卓君）
            guarded(navController, Routes.EVALUATION, SUPERVISOR, taskIdArg) { entry ->
                val model = moduleViewModel { db, actor -> EvaluationViewModel(EvaluationRepository(db), actor, entry.taskId()) }
                EvaluationScreen(model, { navController.popBackStack() }, {
                    navController.navigate(Routes.historyDetail(entry.taskId())) {
                        popUpTo(Routes.evaluation(entry.taskId())) { inclusive = true }
                    }
                }, onAnalysis = { navController.navigate(Routes.analysis(entry.taskId())) })
            }
            guarded(navController, Routes.HISTORY, ADMIN_SUPERVISOR) {
                val model = moduleViewModel { db, actor -> HistoryViewModel(EvaluationRepository(db), actor) }
                HistoryScreen(model, { navController.popBackStack() }, { navController.navigate(Routes.historyDetail(it)) })
            }
            guarded(navController, Routes.HISTORY_DETAIL, ADMIN_SUPERVISOR, taskIdArg) { entry ->
                val model = moduleViewModel { db, actor -> HistoryDetailViewModel(EvaluationRepository(db), actor, entry.taskId()) }
                HistoryDetailScreen(model, { navController.popBackStack() },
                    onEvidence = { navController.navigate(Routes.publishedEvidence(entry.taskId(), it)) },
                    onParticipation = { navController.navigate(Routes.reviewParticipation(entry.taskId())) })
            }
            guarded(navController, Routes.PUBLISHED_EVIDENCE, ADMIN_SUPERVISOR,
                taskIdArg + navArgument(Routes.ARG_EVIDENCE_ID) { type = NavType.LongType }) { entry ->
                PublishedEvidenceScreen(Session.requireUser().id, entry.taskId(), requireNotNull(entry.arguments).getLong(Routes.ARG_EVIDENCE_ID)) { navController.popBackStack() }
            }

            // ui/analysis、ui/evidence（孙浩然，Sprint 2）：采集结束后从采集页进入
            guarded(navController, Routes.ANALYSIS, SUPERVISOR, taskIdArg) { entry ->
                val context = LocalContext.current.applicationContext
                val model = moduleViewModel { db, actor ->
                    CloudAnalysisViewModel(db, context, actor, entry.taskId())
                }
                CloudAnalysisScreen(model, { navController.popBackStack() }, { navController.navigate(Routes.aiReview(it)) }, {
                    val route = Routes.evaluation(entry.taskId())
                    // Return to the existing draft VM so an old second instance cannot overwrite edits.
                    if (!navController.popBackStack(route, false)) {
                        navController.navigate(route) { launchSingleTop = true }
                    }
                }, onInputs = { navController.navigate(Routes.inputWorkspace(entry.taskId())) },
                    onParticipation = { navController.navigate(Routes.reviewParticipation(entry.taskId())) })
            }
            guarded(navController, Routes.INPUT_WORKSPACE, SUPERVISOR, taskIdArg) { entry ->
                val app = LocalContext.current.applicationContext as android.app.Application
                val model = moduleViewModel { _, actor -> InputWorkspaceViewModel(app, actor, taskId = entry.taskId()) }
                InputWorkspaceScreen(model) { navController.popBackStack() }
            }
            guarded(navController, Routes.COURSE_REFERENCE, ADMIN_SUPERVISOR, courseIdArg) { entry ->
                val app = LocalContext.current.applicationContext as android.app.Application
                val model = moduleViewModel { _, actor -> InputWorkspaceViewModel(app, actor, courseId = entry.courseId()) }
                InputWorkspaceScreen(model) { navController.popBackStack() }
            }
            // US27：AI 结果人工审核
            guarded(navController, Routes.AI_REVIEW, SUPERVISOR, analysisIdArg) { entry ->
                val analysisId = requireNotNull(entry.arguments).getLong(Routes.ARG_ANALYSIS_ID)
                val model = moduleViewModel { db, actor -> AIReviewViewModel(AiCorrectionRepository(db), actor, analysisId) }
                AIReviewScreen(model) { navController.popBackStack() }
            }

            // ui/feedback（王博田）
            guarded(navController, Routes.FEEDBACK_LIST, TEACHER) {
                FeedbackListScreen(
                    onBack = { navController.popBackStack() },
                    onFeedbackClick = { navController.navigate(Routes.feedbackDetail(it)) },
                )
            }
            guarded(navController, Routes.FEEDBACK_DETAIL, TEACHER, taskIdArg) { entry ->
                FeedbackDetailScreen(
                    taskId = entry.taskId(),
                    onBack = { navController.popBackStack() },
                    // US36 主流程 1：在反馈详情中点击“确定改进重点”
                    onImproveFocus = { navController.navigate(Routes.improveFocus(entry.taskId())) },
                )
            }

            // ui/improve（于卓君）
            guarded(navController, Routes.IMPROVE_FOCUS, TEACHER, taskIdArg) { entry ->
                val model = moduleViewModel { db, actor -> ImproveFocusViewModel(ImprovementRepository(db), actor, entry.taskId()) }
                ImproveFocusScreen(model, { navController.popBackStack() }, {
                    navController.navigate(Routes.improvePlan(entry.taskId())) {
                        popUpTo(Routes.improveFocus(entry.taskId())) { inclusive = true }
                    }
                })
            }
            guarded(navController, Routes.IMPROVE_PLAN, TEACHER, taskIdArg) { entry ->
                val model = moduleViewModel { db, actor -> ImprovePlanViewModel(ImprovementRepository(db), actor, entry.taskId()) }
                ImprovePlanScreen(model, { navController.popBackStack() }, {
                    navController.navigate(Routes.improveFocus(entry.taskId())) {
                        popUpTo(Routes.improvePlan(entry.taskId())) { inclusive = true }
                    }
                }, { navController.navigate(Routes.IMPROVE_TODO) { launchSingleTop = true } })
            }
            guarded(navController, Routes.IMPROVE_TODO, TEACHER) {
                val model = moduleViewModel { db, actor -> ImproveTodoViewModel(ImprovementRepository(db), actor) }
                ImproveTodoScreen(model, { navController.popBackStack() },
                    { navController.navigate(Routes.improvePlan(it)) }, { navController.navigate(Routes.feedbackDetail(it)) },
                    onExecution = { navController.navigate(Routes.improveExecution(it)) })
            }
            guarded(navController, Routes.IMPROVE_EXECUTION, TEACHER, taskIdArg) { entry ->
                val context = LocalContext.current.applicationContext
                val model = moduleViewModel { db, actor ->
                    val repository = ImprovementExecutionRepository(db)
                    ImproveExecutionViewModel(repository, ImprovementEvidenceStore(context, repository), actor, entry.taskId())
                }
                ImproveExecutionScreen(model, { navController.popBackStack() })
            }

            // ui/review（李奕萱，Sprint 3）：US24/US25 多源参与与异常环节、US45 指标比较、US46 改善核验
            guarded(navController, Routes.TEACHING_SUPPORT, TEACHER) {
                val model = moduleViewModel { db, actor -> TeachingSupportViewModel(TeachingSupportRepository(db), actor) }
                TeachingSupportScreen(model, { navController.popBackStack() },
                    { navController.navigate(Routes.improveFocus(it)) },
                    { navController.navigate(Routes.improvePlan(it)) },
                    { navController.navigate(Routes.feedbackDetail(it)) })
            }
            guarded(navController, Routes.GOVERNANCE, setOf(Role.ADMIN, Role.TEACHER)) {
                val model = moduleViewModel { db, actor -> GovernanceViewModel(GovernanceRepository(db), actor) }
                GovernanceScreen(model, { navController.popBackStack() },
                    { navController.navigate(Routes.governanceSource(it)) })
            }
            guarded(navController, Routes.GOVERNANCE_SOURCE, setOf(Role.ADMIN, Role.TEACHER), taskIdArg) { entry ->
                val model = moduleViewModel { db, actor -> GovernanceSourceViewModel(GovernanceRepository(db), actor, entry.taskId()) }
                GovernanceSourceScreen(model, { navController.popBackStack() },
                    { navController.navigate(Routes.governanceSource(it)) })
            }

            guarded(navController, Routes.REVIEW_LIST, ALL) {
                val model = moduleViewModel { db, actor -> ReviewLinkListViewModel(ReviewRepository(db), actor) }
                ReviewLinkListScreen(model, { navController.popBackStack() },
                    { navController.navigate(Routes.reviewDetail(it)) })
            }
            guarded(navController, Routes.REVIEW_DETAIL, ALL, linkIdArg) { entry ->
                val linkId = requireNotNull(entry.arguments).getLong(Routes.ARG_LINK_ID)
                val model = moduleViewModel { db, actor -> ReviewDetailViewModel(ReviewRepository(db), actor, linkId) }
                ReviewDetailScreen(model, { navController.popBackStack() },
                    { navController.navigate(Routes.reviewParticipation(it)) })
            }
            guarded(navController, Routes.REVIEW_PARTICIPATION, ADMIN_SUPERVISOR, taskIdArg) { entry ->
                val model = moduleViewModel { db, actor ->
                    ParticipationViewModel(ReviewRepository(db), actor, entry.taskId())
                }
                ParticipationScreen(model, { navController.popBackStack() })
            }
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
